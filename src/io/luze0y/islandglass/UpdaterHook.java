package io.luze0y.islandglass;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 系统升级（com.bbk.updater，OriginOS 7 / 4.9.x）禁止自动检查更新。
 *
 * 自动检查的所有入口最终都走 UpdateCheckStrategy.startAutoCheck(boolean, CheckTrigeType)：
 *   定时闹钟 new.com.bbk.updater.action.AUTO_CHECK（12 小时，REGULAR_ALARM）、AUTO_CHECK_HOURLY（HOURLY_ALARM）、
 *   开机 AUTO_CHECK_BOOT_FIRST（BOOT）、联网 onNetworkChanged（NETWORK_CHANGED）、推送（PUSH）、改时间（TIME_SET）、
 *   接通电源（POWER_CONNECTED）、云端配置变化（OTHER）、COTA 类型变化、系统损坏检测等。
 * 设置里点「检查更新」走 UpdatePresenter.checkUpdate（MANUL）/ 公测页（PUBLIC_MANUAL），不经过 startAutoCheck，不受影响；
 * 公测 / 内测页刷新时发出的 CHECK_INSTANTLY（INSTANTLY）也放行。
 * 同时不再注册小时级检查和开机首次检查的闹钟（setHourlyCheckAlarm / setAutocheckAlarmAfterBoot），减少唤醒。
 *
 * 另外所有联网检查（含打开系统升级页、手动检查）直接按「已是最新版本」处理，不发请求（见 installNewest），
 * 缓存的新版本信息、红点与「有新版本」记录随之被原版流程清除。
 *
 * 开关：persist.land.updater_no_autocheck  1=禁止自动检查 / 0=系统原样（默认），每次触发时读取，即时生效。
 * 关闭后 12 小时定期检查照常；小时级检查的闹钟链在下次开机或改时间后恢复。
 */
final class UpdaterHook {
    static final String PKG = "com.bbk.updater";
    private static final String TAG = "FucSuperX";
    private static final String KEY = "persist.land.updater_no_autocheck";
    private static final String STRATEGY = "com.bbk.updater.check.strategy.UpdateCheckStrategy";

    private final XposedModule module;

    UpdaterHook(XposedModule module) {
        this.module = module;
    }

    static boolean blocking() {
        return "1".equals(Conf.get(KEY, "0").trim());
    }

    void install(ClassLoader cl, String process) throws Throwable {
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
        n += installNewest(cl);
        Log.i(TAG, "updater hooks installed x" + n + " in " + process);
    }

    /**
     * 所有联网检查（打开页面、手动检查、自动下载、下载校验）都由检查任务基类 g0.a 的后台方法
     * f(Void[]) 发请求并解析结果。开关打开时不联网，直接返回服务器「已是最新版本」的结果（retcode 210），
     * 并按原版对 210 的处理写入状态（d=8、e="result_n"）。之后原版流程照常：删除缓存的更新信息、
     * 清除「有新版本」记录和红点，页面显示已是最新版本。
     */
    private int installNewest(ClassLoader cl) {
        try {
            Class<?> resCls = cl.loadClass("com.bbk.updater.bean.UpdateCheckResultInfo");
            Class<?> task = cl.loadClass("g0.a"); // BaseCheckUpdateTask（混淆名，按结构校验）
            if (task.getSuperclass() != android.os.AsyncTask.class) throw new IllegalStateException("g0.a not a task");
            Method bg = task.getDeclaredMethod("f", Void[].class);
            if (bg.getReturnType() != resCls) throw new IllegalStateException("g0.a.f signature");
            java.lang.reflect.Field fState = null, fReason = null;
            for (java.lang.reflect.Field f : task.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == int.class) {
                    if (fState != null) throw new IllegalStateException("g0.a int fields");
                    fState = f;
                } else if (f.getType() == String.class) {
                    if (fReason != null) throw new IllegalStateException("g0.a String fields");
                    fReason = f;
                }
            }
            if (fState == null || fReason == null) throw new IllegalStateException("g0.a fields");
            fState.setAccessible(true);
            fReason.setAccessible(true);
            Method setRet = resCls.getMethod("setRetcode", int.class);
            final java.lang.reflect.Field state = fState, reason = fReason;
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
}
