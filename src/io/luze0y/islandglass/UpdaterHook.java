package io.luze0y.islandglass;

import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.wrap.DexMethod;

/**
 * 系统升级（com.bbk.updater，OriginOS 7 / 4.9.x）禁止检查更新。
 *
 * 1. 自动检查：所有入口最终都走 UpdateCheckStrategy.startAutoCheck(boolean, CheckTrigeType)
 *    （定时闹钟 REGULAR_ALARM / HOURLY_ALARM、开机 BOOT、联网 NETWORK_CHANGED、推送 PUSH、改时间 TIME_SET、
 *    接通电源 POWER_CONNECTED、云端配置变化 OTHER、COTA 类型变化、系统损坏检测等），开关打开时直接跳过；
 *    同时不再注册小时级检查和开机首次检查的闹钟（setHourlyCheckAlarm / setAutocheckAlarmAfterBoot）。
 *    公测 / 内测页刷新时发出的 CHECK_INSTANTLY（INSTANTLY）放行。这些类名 / 方法名未混淆。
 * 2. 联网检查（打开系统升级页、手动检查、自动下载、下载校验）：都由检查任务基类（4.9.32 混淆名 g0.a）的后台方法
 *    f(Void[]) 发请求并解析结果。该类名随版本变化，用 DexKit 按特征定位：返回 UpdateCheckResultInfo、参数 Void[]、
 *    调用 HttpUtils.getUpdateRequestParams 的唯一方法。结果按系统升级 APK（路径、大小、修改时间）缓存在其
 *    设备加密存储目录，系统升级不更新时不再重复搜索。开关打开时不联网，直接返回服务器「已是最新版本」的结果
 *    （retcode 210），并按原版对 210 的处理写入任务状态（唯一的 int 字段 = 8，唯一的 String 字段 = "result_n"）。
 *    之后原版流程照常：删除缓存的新版本信息、清除「有新版本」记录与红点，页面显示已是最新版本。
 *
 * 开关：persist.land.updater_no_autocheck  1=禁止检查更新 / 0=系统原样（默认），每次触发时读取，即时生效。
 * 关闭后 12 小时定期检查照常；小时级检查的闹钟链在下次开机或改时间后恢复。
 */
final class UpdaterHook {
    static final String PKG = "com.bbk.updater";
    private static final String TAG = "FucSuperX";
    private static final String KEY = "persist.land.updater_no_autocheck";
    private static final String STRATEGY = "com.bbk.updater.check.strategy.UpdateCheckStrategy";
    private static final String RESULT = "com.bbk.updater.bean.UpdateCheckResultInfo";
    private static final String HTTP_UTILS = "com.bbk.updater.utils.HttpUtils";
    /** 搜索条件变化时加 1，旧缓存随之失效。 */
    private static final int QUERY_VER = 1;
    private static final String CACHE = "fucsuperx_dexkit.properties";

    private static volatile boolean nativeLoaded;
    private final XposedModule module;

    UpdaterHook(XposedModule module) {
        this.module = module;
    }

    static boolean blocking() {
        return "1".equals(Conf.get(KEY, "0").trim());
    }

    void install(ClassLoader cl, ApplicationInfo ai, String process) throws Throwable {
        Class<?> s = cl.loadClass(STRATEGY);
        int n = 0;
        for (Method m : s.getDeclaredMethods()) {
            String name = m.getName();
            if ("startAutoCheck".equals(name) && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == boolean.class) {
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object type = chain.getArg(1);
                    String t = type instanceof Enum ? ((Enum<?>) type).name() : String.valueOf(type);
                    if (!blocking() || "INSTANTLY".equals(t)) return chain.proceed();
                    Log.i(TAG, "updater: auto check blocked, trigger=" + t);
                    return null;
                });
                n++;
            } else if (("setHourlyCheckAlarm".equals(name) || "setAutocheckAlarmAfterBoot".equals(name))
                    && m.getParameterCount() == 0 && m.getReturnType() == void.class) {
                module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (!blocking()) return chain.proceed();
                    Log.i(TAG, "updater: alarm skipped, " + name);
                    return null;
                });
                n++;
            }
        }
        if (n == 0) throw new NoSuchMethodException(STRATEGY + ".startAutoCheck");
        n += installNewest(cl, ai);
        Log.i(TAG, "updater hooks installed x" + n + " in " + process);
    }

    /** 联网检查一律按「已是最新版本」处理。定位或校验失败时不安装（系统升级保持原样），返回安装数。 */
    private int installNewest(ClassLoader cl, ApplicationInfo ai) {
        try {
            Class<?> resCls = cl.loadClass(RESULT);
            Method bg = findCheckTask(cl, ai);
            Class<?> task = bg.getDeclaringClass();
            if (task.getSuperclass() != android.os.AsyncTask.class || bg.getReturnType() != resCls
                    || Modifier.isStatic(bg.getModifiers())) {
                throw new IllegalStateException("unexpected check task " + bg);
            }
            Field fState = null, fReason = null;
            for (Field f : task.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == int.class) {
                    if (fState != null) throw new IllegalStateException(task.getName() + " has several int fields");
                    fState = f;
                } else if (f.getType() == String.class) {
                    if (fReason != null) throw new IllegalStateException(task.getName() + " has several String fields");
                    fReason = f;
                }
            }
            if (fState == null || fReason == null) throw new IllegalStateException(task.getName() + " state fields");
            fState.setAccessible(true);
            fReason.setAccessible(true);
            Method setRet = resCls.getMethod("setRetcode", int.class);
            final Field state = fState, reason = fReason;
            module.hook(bg).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!blocking()) return chain.proceed();
                Object r = resCls.getDeclaredConstructor().newInstance();
                setRet.invoke(r, 210); // CHECK_RESULT_CODE_ALREAD_NEWEST
                Object self = chain.getThisObject();
                state.setInt(self, 8);
                reason.set(self, "result_n");
                Log.i(TAG, "updater: check request skipped, report newest");
                return r;
            });
            return 1;
        } catch (Throwable t) {
            Log.w(TAG, "updater newest hook failed: " + t);
            return 0;
        }
    }

    /** 用 DexKit 定位检查任务的后台方法；命中缓存时不加载原生库、不搜索。 */
    private Method findCheckTask(ClassLoader cl, ApplicationInfo ai) throws Throwable {
        String apk = ai.sourceDir;
        File f = new File(apk);
        String key = QUERY_VER + "|" + apk + "|" + f.length() + "|" + f.lastModified();
        String dir = ai.deviceProtectedDataDir != null ? ai.deviceProtectedDataDir : ai.dataDir;
        File cache = new File(dir, CACHE);
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(cache)) {
            p.load(in);
        } catch (Throwable ignored) {
        }
        String hit = key.equals(p.getProperty("key")) ? p.getProperty("check_task") : null;
        if (hit != null) {
            try {
                return DexMethod.deserialize(hit).getMethodInstance(cl);
            } catch (Throwable t) {
                Log.w(TAG, "dexkit cache stale: " + t);
            }
        }

        loadNative();
        long t0 = SystemClock.uptimeMillis();
        MethodData md;
        try (DexKitBridge b = DexKitBridge.create(apk)) {
            MethodDataList r = b.findMethod(FindMethod.create().matcher(new MethodMatcher()
                    .returnType(RESULT)
                    .paramTypes(Void[].class)
                    .addInvoke(new MethodMatcher().declaredClass(HTTP_UTILS).name("getUpdateRequestParams"))));
            if (r.size() != 1) throw new IllegalStateException("check task matches: " + r.size());
            md = r.get(0);
        }
        Method m = md.getMethodInstance(cl);
        Log.i(TAG, "dexkit: check task " + md.getClassName() + "." + md.getMethodName()
                + " (" + (SystemClock.uptimeMillis() - t0) + " ms)");

        p.clear();
        p.setProperty("key", key);
        p.setProperty("check_task", md.toDexMethod().serialize());
        File tmp = new File(dir, CACHE + "." + android.os.Process.myPid());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "FucSuperX DexKit cache");
        } catch (Throwable t) {
            Log.w(TAG, "dexkit cache write: " + t);
        }
        if (!tmp.renameTo(cache)) tmp.delete();
        return m;
    }

    /** libdexkit.so 随模块 APK 安装到 nativeLibraryDir；模块类加载器找不到时按绝对路径加载。 */
    private void loadNative() {
        if (nativeLoaded) return;
        synchronized (UpdaterHook.class) {
            if (nativeLoaded) return;
            try {
                System.loadLibrary("dexkit");
            } catch (UnsatisfiedLinkError e) {
                System.load(module.getModuleApplicationInfo().nativeLibraryDir + "/libdexkit.so");
            }
            nativeLoaded = true;
        }
    }
}
