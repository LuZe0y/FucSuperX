package io.luze0y.islandglass;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import dalvik.system.BaseDexClassLoader;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * 原子岛（SystemUI 插件 com.vivo.systemuiplugin 内的 DynamicCapsuleContainer）全程使用系统玻璃材质 409~412，跟随深浅色。
 *
 * 原子岛运行在 com.vivo.systemuiplugin:island 独立进程，插件 ClassLoader 就绪后安装以下 Hook：
 * 1. View.setMaterialCustomized（10 参）-> 对原子岛容器改为 View.setMaterial(style, ct)。
 * 2. ContainerAnimatorCommon.updateBlurAppearance(false) -> 原子岛可见时强制为 true，收起的小胶囊也走材质。
 * 3. ContainerAnimatorCommon.mPaint 换成 GlassPaint：材质生效时黑色底色/渐变改为透明。
 * 4. drawBounds 后：清除音乐卡片 #background_show 黑底、隐藏两层光效、充电 PAG 去黑底（AGSL unblack）、
 *    逐帧同步材质区域（应用收回原子岛的插值动画不走弹簧回调）、补救卡住的材质。
 *
 * 开关（LSPosed 远程偏好，模块界面修改即时生效）：
 *   persist.land.island_glass_on     原子岛总开关：1=启用（默认），0=全部 Hook 直通、原子岛恢复系统原版（切换时原子岛进程自动重启）
 *   persist.land.island_glass        strong|medium，默认 strong。浅色 409/410，深色 411/412
 *   persist.land.island_glass_trans  1=应用过渡动画期间也保持玻璃（默认），0=原版行为
 *   persist.land.island_glass_sync   1=逐帧同步材质区域（默认），0=关闭
 *   persist.land.island_glass_ct     材质 contentType 0~2，默认 2（3 为缓存背景，展开 / 收起会露出拉伸的旧画面，已移除，旧配置 3 按 2 处理）
 *   persist.land.island_glass_debug  1=输出 [diag] 诊断日志，默认 0
 */
public class ModuleEntry extends XposedModule {
    private static final String TAG = "IslandGlass";
    private static final String SYSUI = "com.android.systemui";
    private static final String PLUGIN_APK = "SystemUIPlugin";
    private static final String PLUGIN_PKG = "com.vivo.systemuiplugin";
    private static final String CONTAINER = "com.vivo.island.view.DynamicCapsuleContainer";
    private static final String ANIMATOR = "com.vivo.island.animation.ContainerAnimatorCommon";
    private static final String PROP_GLASS = "persist.land.island_glass";
    private static final String PROP_TRANS = "persist.land.island_glass_trans";
    private static final String PROP_SYNC = "persist.land.island_glass_sync";
    private static final String PROP_ON = "persist.land.island_glass_on";
    /** 原子岛总开关。关闭时所有原子岛 Hook 直接走原版（GlassPaint 同样读取）。 */
    static volatile boolean islandOn = readOn();
    private static boolean DEBUG = "1".equals(sysProp("persist.land.island_glass_debug", "0").trim()); // onModuleLoaded 后按远程偏好刷新

    private String processName = "";
    private Method setMaterial;
    private volatile boolean configHooked;
    private final Map<View, Integer> applied = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<ClassLoader> hookedLoaders = Collections.newSetFromMap(new WeakHashMap<>());

    // ContainerAnimatorCommon 反射缓存
    private Field fBlurVisible, fPaint, fMainCapsule;
    private Method mSysNeedBlur, mGetDisplayRect, mIsAppTransition;
    private SystemGlass systemGlass;

    static {
        Log.e(TAG, "class loaded");
    }

    public ModuleEntry() {
        Log.e(TAG, "constructed");
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        Log.e(TAG, "onModuleLoaded process=" + processName + " api=" + safeApi());
        Conf.init(this, processName); // 远程偏好：模块界面无 root 修改配置
        DEBUG = "1".equals(sysProp("persist.land.island_glass_debug", "0").trim());
        islandOn = readOn(); // 远程偏好就绪后重新读取
        Conf.subscribe(this::onConfChanged); // 配置实时生效
        if (SystemGlass.wants(processName)) {
            try {
                (systemGlass = new SystemGlass(this)).install(); // 系统液态玻璃调节（不含原子岛进程）
            } catch (Throwable t) {
                error("system glass install failed", t);
            }
        }
    }

    @Override
    public void onPackageReady(io.github.libxposed.api.XposedModuleInterface.PackageReadyParam param) {
        Log.e(TAG, "onPackageReady pkg=" + param.getPackageName() + " first=" + param.isFirstPackage());
        if (PLUGIN_PKG.equals(param.getPackageName()) && param.isFirstPackage()) {
            onPluginLoader(param.getClassLoader()); // 插件独立进程：直接用应用 ClassLoader 安装原子岛 Hook
        }
        if (SYSUI.equals(param.getPackageName()) && SYSUI.equals(processName) && param.isFirstPackage()
                && systemGlass != null) {
            try {
                systemGlass.installSysui(param.getClassLoader()); // 下拉面板背景模糊
            } catch (Throwable t) {
                error("system glass bg blur install failed", t);
            }
            try {
                new NcLand(this).install(param.getClassLoader()); // 横屏通知中心分栏
            } catch (Throwable t) {
                error("nc land install failed", t);
            }
        }
        if (UpdaterHook.PKG.equals(param.getPackageName()) && param.isFirstPackage()) {
            try {
                new UpdaterHook(this).install(param.getClassLoader(), processName); // 系统升级：禁止自动检查更新
            } catch (Throwable t) {
                error("updater hook install failed", t);
            }
        }
        if (SettingsHook.PKG.equals(param.getPackageName()) && param.isFirstPackage()) {
            try {
                new SettingsHook(this).install(param.getClassLoader()); // 设置首页顶部 FucSuperX 入口
            } catch (Throwable t) {
                error("settings entry install failed", t);
            }
        }
    }

    private String safeApi() {
        try { return String.valueOf(getApiVersion()); } catch (Throwable t) { return "?" + t; }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        Log.e(TAG, "onPackageLoaded pkg=" + param.getPackageName() + " first=" + param.isFirstPackage());
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        boolean sysui = SYSUI.equals(pkg) && SYSUI.equals(processName);
        boolean plugin = PLUGIN_PKG.equals(pkg); // 原子岛实际运行在 com.vivo.systemuiplugin:island 独立进程
        if (!sysui && !plugin) return;
        try {
            install();
            info("installed in " + processName + " (pkg=" + pkg + ")");
        } catch (Throwable t) {
            error("install failed, module inactive", t);
        }
    }

    // 409~412 的模糊半径 / 折射参数由 BlurMaterial.resolveGlassEffectBlurGeometryByStyle 填进 BlurGeometry。
    // setMaterialBlurRadius 对 style>=0 的材质无效（要求 style==-1），所以直接改解析结果。只在原子岛进程生效。
    private void installGeometryHook() {
        try {
            String proc = android.app.Application.getProcessName();
            if (proc == null || !proc.endsWith(":island")) return;
            Class<?> bm = Class.forName("android.view.blur.BlurMaterial");
            Method m = null;
            for (Method x : bm.getDeclaredMethods()) {
                if (x.getName().equals("resolveGlassEffectBlurGeometryByStyle") && x.getParameterCount() == 2) { m = x; break; }
            }
            if (m == null) { info("geometry resolver not found"); return; }
            hook(m).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                Object r = chain.proceed();
                Object[] a = chain.getArgs().toArray();
                Object g = a[0];
                int style = (Integer) a[1];
                if (!islandOn || g == null || style < 409 || style > 412) return r;
                try {
                    float blur = Math.max(0f, Float.parseFloat(sysProp("persist.land.island_glass_blur", "0").trim()));
                    float ri = Float.parseFloat(sysProp("persist.land.island_glass_refr", "-1").trim());
                    float rr = Float.parseFloat(sysProp("persist.land.island_glass_refr_r", "-1").trim());
                    Class<?> gc = g.getClass();
                    if (blur >= 0) gc.getField("mBlurRadius").setFloat(g, blur);
                    if (ri >= 0) gc.getField("mRefractionIntensity").setFloat(g, ri);
                    if (rr >= 0) gc.getField("mRefractionRange").setFloat(g, rr);
                    if (DEBUG) diag("geometry style=" + style + " blur=" + gc.getField("mBlurRadius").getFloat(g)
                            + " refr=" + gc.getField("mRefractionIntensity").getFloat(g)
                            + " range=" + gc.getField("mRefractionRange").getFloat(g));
                } catch (Throwable t) {
                    warn("geometry override failed", t);
                }
                return r;
            });
            info("geometry hook installed");
        } catch (Throwable t) {
            warn("geometry hook failed", t);
        }
    }

    private void install() throws Throwable {
        installGeometryHook();
        setMaterial = View.class.getMethod("setMaterial", int.class, int.class);
        Method customized = View.class.getMethod("setMaterialCustomized",
                float.class, float.class, int.class, float.class, int.class,
                int.class, float.class, int.class, float.class, int.class);

        final Class<?> ret = customized.getReturnType();
        info("View.setMaterialCustomized return=" + ret + ", setMaterial return=" + setMaterial.getReturnType());
        hook(customized).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
            Object self = chain.getThisObject();
            if (!islandOn || IN_RECIPE.get() || !(self instanceof View) || !isContainer(self.getClass())) return chain.proceed();
            View v = (View) self;
            ensureConfigHook(v.getClass());
            int style = styleFor(v.getResources().getConfiguration());
            info("intercept setMaterialCustomized(tint=" + Integer.toHexString((Integer) chain.getArg(2)) + ") -> setMaterial " + style);
            if (applyStyle(v, style)) return ret == boolean.class ? Boolean.TRUE : null;
            return chain.proceed(); // 不支持该 style 时回退原版自定义模糊
        });

        // 下拉控制中心等场景胶囊会缩成摄像头处的小圆点：原版此时是黑色（与挖孔融为一体），
        // 玻璃会在截图/录屏里露出一个小玻璃球。圆点状态下把材质区域置空，GlassPaint 同时恢复黑色。
        try {
            Method region = View.class.getMethod("setMaterialRegion", RectF.class, float.class);
            hook(region).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                Object self = chain.getThisObject();
                if (!islandOn || !(self instanceof View) || !isContainer(self.getClass())) return chain.proceed();
                Object a0 = chain.getArg(0);
                if (a0 instanceof RectF && isDot((RectF) a0)) {
                    if (DEBUG) diag("region dot -> empty " + ((RectF) a0).toShortString());
                    return chain.proceed(new Object[]{new RectF(), 0f});
                }
                return chain.proceed();
            });
            info("View.setMaterialRegion hooked");
        } catch (Throwable t) {
            warn("setMaterialRegion hook failed (camera dot may show glass in screenshots)", t);
        }

        // 插件 ClassLoader 创建后安装原子岛相关 Hook。
        // vivo 插件不一定走 PluginInstance 的 PathClassLoader(3 参)，也可能经 LoadedApk/ApplicationLoaders
        // 创建（libLockProcess.so 已映射即为此路径），因此 Hook BaseDexClassLoader 的全部构造器。
        int n = 0;
        for (Constructor<?> k : BaseDexClassLoader.class.getDeclaredConstructors()) {
            Class<?>[] ps = k.getParameterTypes();
            if (ps.length == 0 || ps[0] != String.class) continue;
            try {
                hook(k).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                    Object r = chain.proceed();
                    Object dex = chain.getArg(0);
                    if (dex instanceof String && isPluginDex((String) dex)) {
                        info("plugin loader created: " + dex);
                        onPluginLoader((ClassLoader) chain.getThisObject());
                    }
                    return r;
                });
                n++;
            } catch (Throwable t) {
                warn("hook " + k + " failed", t);
            }
        }
        info("BaseDexClassLoader ctor hooks: " + n);
    }

    /** 内置版在 /system/priv-app/SystemUIPlugin/，应用商店更新后在 /data/app/…/com.vivo.systemuiplugin-…/，两种都要认。 */
    private static boolean isPluginDex(String dex) {
        return dex.contains(PLUGIN_APK) || dex.contains(PLUGIN_PKG + "-") || dex.contains("/" + PLUGIN_PKG + "/");
    }

    private synchronized void onPluginLoader(ClassLoader cl) {
        if (!hookedLoaders.add(cl)) return;
        if (SYSUI.equals(processName)) {
            try {
                new NcHeader(this).install(cl); // 通知中心时间 / 日期大号居中
            } catch (Throwable t) {
                error("nc header install failed", t);
            }
        }
        try {
            Class<?> cac = Class.forName(ANIMATOR, false, cl);
            fBlurVisible = findField(cac, "mBlurVisible");
            fPaint = findField(cac, "mPaint");
            fMainCapsule = findField(cac, "mMainCapsule");
            fContainer = findField(cac, "mContainer");
            mSysNeedBlur = findMethod(cac, "isSystemNeedEnableBlur");
            if (fBlurVisible == null || fPaint == null || fMainCapsule == null) {
                warn("ContainerAnimatorCommon fields missing, capsule glass disabled", null);
                return;
            }

            for (Constructor<?> k : cac.getDeclaredConstructors()) {
                hook(k).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        wrapPaint(chain.getThisObject());
                    } catch (Throwable t) {
                        warn("wrap paint failed", t);
                    }
                    return r;
                });
            }

            Method uba = cac.getDeclaredMethod("updateBlurAppearance", boolean.class);
            uba.setAccessible(true);
            mUpdateBlur = uba;
            fSmallLight = findField(cac, "smallEffectLightView");
            fBigLight = findField(cac, "bigEffectLightView");
            fStroke = findField(cac, "mStrokePaint");
            hook(uba).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                Object anim = chain.getThisObject();
                Object want = chain.getArg(0);
                boolean force = islandOn && Boolean.FALSE.equals(want) && shouldForce(anim);
                if (DEBUG) diag("updateBlurAppearance(" + want + ") force=" + force + " why=" + forceWhy);
                return force ? chain.proceed(new Object[]{Boolean.TRUE}) : chain.proceed();
            });

            installFxHook();

            Method db = cac.getDeclaredMethod("drawBounds", android.graphics.Canvas.class);
            hook(db).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                if (!islandOn) return chain.proceed();
                Object anim = chain.getThisObject();
                updateDot(anim); // 先于原版绘制判定，让本帧 GlassPaint 就画对颜色
                updateStroke(anim);
                syncRegion(anim);           // 材质区域跟随胶囊：放在绘制前，与本帧胶囊形状同帧提交，避免玻璃落后一帧
                Object r = chain.proceed();
                fixOpaqueBackgrounds(anim); // 音乐卡片 #background_show 黑底
                fixEffectLights(anim);      // 材质可见时隐藏两层光效
                fixLumaKey(anim);           // 充电 PAG 去黑底
                reviveStuckBlur(anim);
                updateTextInvert(anim);     // 小胶囊文字随背景亮度反色
                return r;
            });

            try {
                Class<?> cfg = Class.forName("com.vivo.island.animation.IslandAnimConfigsCommon", false, cl);
                info("flags SUPPORT_REAL_TIME_BLUR=" + cfg.getField("SUPPORT_REAL_TIME_BLUR").get(null)
                        + " DEBUG_ENABLE_ISLAND_BLUR=" + cfg.getField("DEBUG_ENABLE_ISLAND_BLUR").get(null));
            } catch (Throwable t) {
                warn("read island flags failed", t);
            }
            info("plugin hooks installed (" + cl + ")");
        } catch (Throwable t) {
            warn("plugin hook failed, capsule glass disabled", t);
        }
    }

    // ===== 小胶囊文字随背景亮度反色 =====
    // 用 SurfaceFlinger 的 CompositionSamplingListener 采样胶囊背后（本窗口以下图层）的中位亮度，
    // 背景偏亮时给胶囊内的 TextView 挂一层反色 ColorMatrix（硬件层 paint），偏暗时还原。
    // 作用于小胶囊与展开岛（玻璃可见、非圆点）；音乐展开岛额外反色播放控制按钮（iv_menu_item）和进度条，
    // 专辑封面等其他图片不处理。
    //   persist.land.island_glass_invert     1 开 / 0 关，默认 1
    //   persist.land.island_glass_invert_hi  亮度高于此值切成反色，默认 0.6
    //   persist.land.island_glass_invert_lo  亮度低于此值恢复，默认 0.5（两值之间保持不变，防抖）
    private static final String PROP_INVERT = "persist.land.island_glass_invert";
    private static final float SMALL_MAX_H = 140f;
    private android.view.CompositionSamplingListener sampler;
    private View samplerHost;
    private final android.graphics.Rect sampleRect = new android.graphics.Rect();
    private long lastSampleReg, lastTextScan;
    private volatile boolean lightBg;
    private boolean invertBroken;
    private float invHi = 0.6f, invLo = 0.5f;
    private android.graphics.Paint invertPaint;
    private final java.util.WeakHashMap<View, Integer> inverted = new java.util.WeakHashMap<>();

    private void updateTextInvert(Object anim) {
        if (invertBroken) return;
        try {
            View c = fContainer == null ? null : (View) fContainer.get(anim);
            if (c == null) return;
            boolean on = !"0".equals(sysProp(PROP_INVERT, "1").trim());
            RectF r = null;
            if (on && fBlurVisible.getBoolean(anim) && !GlassPaint.dot) {
                Object cap = fMainCapsule.get(anim);
                if (cap != null) {
                    if (mGetDisplayRect == null) {
                        mGetDisplayRect = cap.getClass().getMethod("getDisplayRect");
                        mIsAppTransition = cap.getClass().getMethod("isAppTransitionRunning");
                    }
                    r = (RectF) mGetDisplayRect.invoke(cap);
                }
            }
            if (r == null || r.isEmpty()) {
                stopSampler();
                applyInvert(c, false);
                return;
            }
            ensureSampler(c, r);
            applyInvert(c, lightBg);
        } catch (Throwable t) {
            invertBroken = true;
            warn("text invert disabled", t);
        }
    }

    private void ensureSampler(View c, RectF r) throws Throwable {
        int[] loc = new int[2];
        c.getLocationOnScreen(loc);
        android.graphics.Rect want = new android.graphics.Rect(
                loc[0] + Math.round(r.left), loc[1] + Math.round(r.top),
                loc[0] + Math.round(r.right), loc[1] + Math.round(r.bottom));
        if (sampler != null && samplerHost == c
                && Math.abs(want.left - sampleRect.left) <= 8 && Math.abs(want.right - sampleRect.right) <= 8
                && Math.abs(want.top - sampleRect.top) <= 8 && Math.abs(want.bottom - sampleRect.bottom) <= 8) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (samplerHost != null && now - lastSampleReg < 200) return;
        Object root = c.getRootSurfaceControl();
        if (root == null || c.getDisplay() == null) return;
        android.view.SurfaceControl sc = (android.view.SurfaceControl) root.getClass().getMethod("getSurfaceControl").invoke(root);
        if (sc == null || !sc.isValid()) return;
        try {
            invHi = Float.parseFloat(sysProp("persist.land.island_glass_invert_hi", "0.6").trim());
            invLo = Float.parseFloat(sysProp("persist.land.island_glass_invert_lo", "0.5").trim());
        } catch (Throwable ignored) {
        }
        if (sampler == null) {
            sampler = new android.view.CompositionSamplingListener(c.getContext().getMainExecutor()) {
                @Override
                public void onSampleCollected(float luma) {
                    onLuma(luma);
                }
            };
        } else if (samplerHost != null) {
            android.view.CompositionSamplingListener.unregister(sampler);
        }
        android.view.CompositionSamplingListener.register(sampler, c.getDisplay().getDisplayId(), sc, want);
        sampleRect.set(want);
        samplerHost = c;
        lastSampleReg = now;
        if (DEBUG) diag("sampler -> " + want.toShortString());
    }

    private void onLuma(float l) {
        boolean nl = lightBg ? l > invLo : l > invHi;
        if (DEBUG) diag("luma=" + l + " light=" + nl);
        if (nl == lightBg) return;
        lightBg = nl;
        View h = samplerHost;
        if (h != null) h.postInvalidateOnAnimation();
    }

    private void stopSampler() {
        if (sampler != null && samplerHost != null) {
            try {
                android.view.CompositionSamplingListener.unregister(sampler);
            } catch (Throwable ignored) {
            }
        }
        samplerHost = null;
        sampleRect.setEmpty();
        lightBg = false;
    }

    private void applyInvert(View c, boolean on) {
        if (!on) {
            if (inverted.isEmpty()) return;
            for (Map.Entry<View, Integer> e : inverted.entrySet()) {
                if (e.getKey() != null) e.getKey().setLayerType(e.getValue(), null);
            }
            inverted.clear();
            if (DEBUG) diag("text invert off");
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTextScan < 250 && !inverted.isEmpty()) return;
        lastTextScan = now;
        if (invertPaint == null) {
            invertPaint = new android.graphics.Paint();
            invertPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0}));
        }
        int before = inverted.size();
        scanText(c, 0);
        if (DEBUG && inverted.size() != before) diag("text invert on, views=" + inverted.size());
    }

    private final java.util.Map<Integer, String> idNames = new java.util.HashMap<>();

    private String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        String n = idNames.get(id);
        if (n == null) {
            try {
                n = v.getResources().getResourceEntryName(id);
            } catch (Throwable t) {
                n = "";
            }
            idNames.put(id, n);
        }
        return n;
    }

    /** 进度条：RemoteViews 里可能是自定义类，只看无障碍类名。 */
    private static boolean isProgress(View v) {
        if (v instanceof android.widget.ProgressBar) return true;
        CharSequence cn = v.getAccessibilityClassName();
        return cn != null && (cn.toString().endsWith("SeekBar") || cn.toString().endsWith("ProgressBar"));
    }

    private void scanText(View v, int d) {
        if (d > 16 || v.getVisibility() != View.VISIBLE) return;
        String name = idName(v);
        if ("menu_media".equals(name) || "album_view".equals(name)) return; // 应用图标、专辑封面保持原色
        if (v instanceof android.widget.TextView || isProgress(v)
                || (v instanceof android.widget.ImageView && "iv_menu_item".equals(name))) {
            if (!inverted.containsKey(v)) {
                inverted.put(v, v.getLayerType());
                v.setLayerType(View.LAYER_TYPE_HARDWARE, invertPaint);
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scanText(g.getChildAt(i), d + 1);
        }
    }

    private Method mGetDisplayRadius, mSetRegion;
    private final RectF lastRegion = new RectF();
    private float lastRadius = -1f;

    /**
     * 应用收回原子岛（单胶囊）时，胶囊形状由 CapsuleInterpolatorAnimator 逐帧 setProp 驱动，
     * 不走弹簧回调，原版不会每帧 setMaterialRegion -> 玻璃区域停在旧值，表现为"透明胶囊先出现、玻璃后到"。
     * 这里在每次绘制时比对实际显示区域，变化了就直接同步给材质。persist.land.island_glass_sync=0 可关闭。
     */
    private void syncRegion(Object anim) {
        try {
            if (!fBlurVisible.getBoolean(anim)) { lastRadius = -1f; return; }
            if (!"1".equals(sysProp(PROP_SYNC, "1").trim())) return;
            Object cap = fMainCapsule.get(anim);
            Object c = fContainer == null ? null : fContainer.get(anim);
            if (cap == null || c == null) return;
            if (mGetDisplayRect == null) {
                mGetDisplayRect = cap.getClass().getMethod("getDisplayRect");
                mIsAppTransition = cap.getClass().getMethod("isAppTransitionRunning");
            }
            if (mGetDisplayRadius == null) mGetDisplayRadius = cap.getClass().getMethod("getDisplayRadius");
            if (mSetRegion == null) mSetRegion = c.getClass().getMethod("setMaterialRegion", RectF.class, float.class);
            RectF r = (RectF) mGetDisplayRect.invoke(cap);
            float rad = (Float) mGetDisplayRadius.invoke(cap);
            if (r == null || r.width() < 2f || r.height() < 2f) return;
            if (r.equals(lastRegion) && rad == lastRadius) return;
            lastRegion.set(r);
            lastRadius = rad;
            mSetRegion.invoke(c, new RectF(r), rad);
            if (DEBUG) diag("syncRegion " + r.toShortString() + " r=" + rad
                    + " trans=" + (mIsAppTransition == null ? "?" : mIsAppTransition.invoke(cap)));
        } catch (Throwable t) {
            Log.w(TAG, "syncRegion failed", t);
        }
    }

    private void wrapPaint(Object anim) throws IllegalAccessException {
        Object p = fPaint.get(anim);
        if (p instanceof GlassPaint || !(p instanceof Paint)) return;
        fPaint.set(anim, new GlassPaint((Paint) p, anim, fBlurVisible));
    }

    // 原版在胶囊外沿画一圈 #26FFFFFF 描边（为黑底设计）。叠在玻璃上时这圈白线不随背景变化，深色下上下直边尤其明显。
    // 玻璃可见时改用 persist.land.island_glass_stroke 指定的颜色（默认 0=不画，可设如 0x14FFFFFF），其余时候还原。
    private static final int STROKE_ORIG = 0x26FFFFFF;
    private Field fStroke;

    private void updateStroke(Object anim) {
        try {
            if (fStroke == null) return;
            Object p = fStroke.get(anim);
            if (!(p instanceof Paint)) return;
            boolean glass = fBlurVisible.getBoolean(anim) && !GlassPaint.dot;
            int want = glass ? strokeColor() : STROKE_ORIG;
            if (((Paint) p).getColor() != want) {
                ((Paint) p).setColor(want);
                if (DEBUG) diag("stroke -> #" + Integer.toHexString(want));
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateStroke failed", t);
        }
    }

    private static int strokeColor() {
        try {
            return (int) Long.decode(sysProp("persist.land.island_glass_stroke", "0").trim()).longValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 胶囊缩成摄像头挖孔处的小圆点（近似正圆且比收起的小胶囊矮）。收起的小胶囊高 112，圆点约 75x75。 */
    private static boolean isDot(RectF r) {
        if (r == null || r.isEmpty()) return false;
        return r.height() < 104f && r.width() <= r.height() * 1.2f;
    }

    private void updateDot(Object anim) {
        try {
            Object cap = fMainCapsule.get(anim);
            if (cap == null) return;
            if (mGetDisplayRect == null) {
                mGetDisplayRect = cap.getClass().getMethod("getDisplayRect");
                mIsAppTransition = cap.getClass().getMethod("isAppTransitionRunning");
            }
            boolean d = isDot((RectF) mGetDisplayRect.invoke(cap));
            if (d != GlassPaint.dot) {
                GlassPaint.dot = d;
                Object p = fPaint.get(anim);
                if (p instanceof Paint && !d && fBlurVisible.getBoolean(anim)) ((Paint) p).setColor(0); // 离开圆点立即恢复透明
                if (DEBUG) diag("dot=" + d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateDot failed", t);
        }
    }

    /** 原子岛可见、不在应用过渡动画、系统允许实时模糊时，强制启用材质（含收起的小胶囊）。 */
    private boolean shouldForce(Object anim) {
        try {
            Object cap = fMainCapsule.get(anim);
            if (cap == null) return false;
            if (mGetDisplayRect == null) {
                mGetDisplayRect = cap.getClass().getMethod("getDisplayRect");
                mIsAppTransition = cap.getClass().getMethod("isAppTransitionRunning");
            }
            RectF r = (RectF) mGetDisplayRect.invoke(cap);
            if (r == null || r.width() < 2f || r.height() < 2f) { forceWhy = "rect=" + r; return false; }
            // 原版在"应用收回到原子岛"的弹簧动画期间（约 1s，onSpringEnded 才清标志）不开材质，表现为首次出现延迟。
            // 默认忽略该标志、动画期间也保持玻璃；persist.land.island_glass_trans=0 可恢复原版行为。
            if (!"1".equals(sysProp(PROP_TRANS, "1").trim()) && Boolean.TRUE.equals(mIsAppTransition.invoke(cap))) {
                forceWhy = "appTransition";
                return false;
            }
            boolean ok = mSysNeedBlur == null || Boolean.TRUE.equals(mSysNeedBlur.invoke(anim));
            forceWhy = ok ? "ok" : "sysNoBlur";
            return ok;
        } catch (Throwable t) {
            forceWhy = "err " + t;
            return false;
        }
    }

    /** 深浅色切换时，若原子岛正处于材质态则重走一次原版流程，让新 style 生效。 */
    private void ensureConfigHook(Class<?> cls) {
        if (configHooked) return;
        synchronized (this) {
            if (configHooked) return;
            configHooked = true;
            try {
                Class<?> c = cls;
                while (c != null && !CONTAINER.equals(c.getName())) c = c.getSuperclass();
                if (c == null) return;
                Method m = c.getDeclaredMethod("onConfigurationChanged", Configuration.class);
                hook(m).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        onConfig((View) chain.getThisObject(), (Configuration) chain.getArg(0));
                    } catch (Throwable t) {
                        warn("onConfig failed", t);
                    }
                    return r;
                });
                info("config hook installed");
            } catch (Throwable t) {
                warn("config hook failed (dark-mode switch needs island re-expand)", t);
            }
        }
    }

    private void onConfig(View v, Configuration cfg) throws Throwable {
        Integer cur = applied.get(v);
        if (cur == null) return;
        int want = styleFor(cfg);
        boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Boolean wasNight;
        synchronized (folderNight) {
            wasNight = folderNight.get(v);
        }
        // 桌面文件夹档深浅色都是 style 0，只比 style 会漏掉切换：额外比较上次套用时的深浅色
        boolean folderFlip = want == STYLE_FOLDER && wasNight != null && wasNight != night;
        if (cur == want && !folderFlip) return;

        Object anim = readField(v, "mContainerAnimator");
        Field visible = anim == null ? null : findField(anim.getClass(), "mBlurVisible");
        if (visible == null) {
            applyStyle(v, want);
            return;
        }
        if (!visible.getBoolean(anim)) {
            applied.remove(v);
            return;
        }
        visible.setBoolean(anim, false);
        anim.getClass().getMethod("updateBlurAppearance", boolean.class).invoke(anim, true);
        info("ui mode changed, reapply " + cur + " -> " + want);
    }

    /** 模块界面改了配置：对当前处于玻璃态的原子岛重走一次原版材质流程，新值立即生效。 */
    private void onConfChanged(String key) {
        if ("island_glass_debug".equals(key)) {
            DEBUG = "1".equals(sysProp("persist.land.island_glass_debug", "0").trim());
            return;
        }
        if ("island_glass_on".equals(key)) {
            boolean v = readOn();
            if (v == islandOn) return;
            islandOn = v;
            info("island master switch -> " + v);
            if (processName != null && processName.endsWith(":island")) {
                // 已改动的视图状态（材质、描边、光效、反色、去黑效果）逐项还原代价大且易漏，
                // 直接退出原子岛进程，由系统重新拉起，按新开关得到干净的原版 / 模块状态
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            return;
        }
        if (!key.startsWith("island_glass")) return;
        View[] views;
        synchronized (applied) {
            views = applied.keySet().toArray(new View[0]);
        }
        info("config changed: " + key + ", live reapply x" + views.length);
        for (View v : views) {
            if (v == null) continue;
            v.post(() -> {
                try {
                    reapply(v);
                } catch (Throwable t) {
                    warn("live reapply failed", t);
                }
            });
        }
    }

    private void reapply(View v) throws Throwable {
        if (!applied.containsKey(v)) return;
        Object anim = readField(v, "mContainerAnimator");
        Field visible = anim == null ? null : findField(anim.getClass(), "mBlurVisible");
        if (visible == null) {
            applyStyle(v, styleFor(v.getResources().getConfiguration()));
            return;
        }
        if (!visible.getBoolean(anim)) return; // 未显示：下次出现时自然读到新值
        visible.setBoolean(anim, false);
        anim.getClass().getMethod("updateBlurAppearance", boolean.class).invoke(anim, true);
    }

    private boolean applyStyle(View v, int style) {
        try {
            Object ok = setMaterial.invoke(v, style, contentType());
            if (Boolean.TRUE.equals(ok)) {
                applyFps(v);
                if (style == STYLE_FOLDER) applyFolderRecipe(v);
                else tuneEdgeLight(v);
                Integer prev = applied.put(v, style);
                if (prev == null || prev != style) info("setMaterial " + style);
                return true;
            }
            warn("setMaterial(" + style + ") returned " + ok + ", fallback", null);
        } catch (Throwable t) {
            warn("setMaterial(" + style + ") failed, fallback", t);
        }
        applied.remove(v);
        return false;
    }

    // 系统玻璃自带一圈内描边高光（409/411：白色、强度 0.6、宽 4、饱和 0.6，光源在正上方，所以上下直边最亮）。
    // 胶囊很扁，上下直边很长，高光显得又硬又白。这里在 setMaterial 之后重设：
    //   persist.land.island_glass_light     强度，默认 0.2（填 -1 保持系统默认）
    //   persist.land.island_glass_light_sat 饱和度（越大越带背景色），默认 0.8
    //   persist.land.island_glass_light_w   宽度，默认 2
    private Method mInnerLight;

    // 模糊度 / 折射。409/411 系统默认：blurRadius 0.008（glassLevel1_5 机型 0.058），
    // 折射强度 120，折射范围 32（离边缘多少 px 内发生折射）。填 -1 保持系统默认。
    //   persist.land.island_glass_blur     模糊半径（0~1，越大越糊）
    //   persist.land.island_glass_refr     折射强度（像素位移量，0 关闭）
    //   persist.land.island_glass_refr_r   折射范围（px）
    private Method mBlurRadius, mGlassCustom;

    private void tuneBlurRefraction(View v) {
        try {
            float blur = Math.max(0f, Float.parseFloat(sysProp("persist.land.island_glass_blur", "0").trim()));
            if (blur >= 0) {
                if (mBlurRadius == null) mBlurRadius = View.class.getMethod("setMaterialBlurRadius", float.class);
                mBlurRadius.invoke(v, blur);
            }
            float ri = Float.parseFloat(sysProp("persist.land.island_glass_refr", "-1").trim());
            float rr = Float.parseFloat(sysProp("persist.land.island_glass_refr_r", "-1").trim());
            if (ri >= 0 || rr >= 0) {
                if (ri < 0) ri = 120f;
                if (rr < 0) rr = 32f;
                if (mGlassCustom == null) mGlassCustom = View.class.getMethod("setGlassEffectCustomized", float.class, float.class);
                mGlassCustom.invoke(v, ri, rr);
            }
            if (DEBUG) diag("blur=" + blur + " refr=" + ri + " range=" + rr);
        } catch (Throwable t) {
            warn("tune blur/refraction failed", t);
        }
    }

    private void tuneEdgeLight(View v) {
        tuneBlurRefraction(v);
        try {
            float intensity = Float.parseFloat(sysProp("persist.land.island_glass_light", "0.2").trim());
            if (intensity < 0) return;
            float sat = Float.parseFloat(sysProp("persist.land.island_glass_light_sat", "0.8").trim());
            int w = Integer.parseInt(sysProp("persist.land.island_glass_light_w", "2").trim());
            if (mInnerLight == null) {
                mInnerLight = View.class.getMethod("setGlassEffectInnerStrokeLight",
                        int.class, float.class, float.class, float.class, float.class, int.class, float.class);
            }
            mInnerLight.invoke(v, -1, intensity, 0f, -1f, -1f, w, sat);
            if (DEBUG) diag("edge light i=" + intensity + " sat=" + sat + " w=" + w);
        } catch (Throwable t) {
            warn("tune edge light failed", t);
        }
    }

    // 「桌面文件夹同款」：照搬 BBKLauncher2 强玻璃档大文件夹图标（OriginFolderIconBlurView，设备实测调用）：
    //   setMaterial(0, ct) → FPS 120 → setGlassEffectCustomized(140, 60)
    //   → InnerStrokeLight(-1, 0.45, 0, 1, 1, 4, 0.4)
    //   → setMaterialCustomized(0.02, 20, #E6E6E6 0.1 f0, #262626 0.1 f7, #FFFFFF 0 f0, 1.0, 0)   浅色
    //     深色（桌面 m.c() 分支）：(0.02, 40, #000000 0.35 f2, #BFBFBF 0.08 f0, #FFFFFF 0 f0, 1.0, 0)
    //   → TimelineEnable(true) + TimelineLight(0x19b3c6ff, 0, -5.955, 11.91)
    // 模糊 / 折射仍可用 island_glass_blur / refr / refr_r 覆盖（-1 = 文件夹原值）；边缘高光固定用文件夹参数。
    static final int STYLE_FOLDER = 0;
    private Method mCustom13, mTimelineEnable, mTimelineLight;

    // 防重入：若系统 13 参 setMaterialCustomized 内部转调 10 参版本，不能再被我们的拦截改写
    private static final ThreadLocal<Boolean> IN_RECIPE = ThreadLocal.withInitial(() -> false);

    private final java.util.Map<View, Boolean> folderNight = new java.util.WeakHashMap<>();

    private void applyFolderRecipe(View v) {
        boolean night = (v.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        synchronized (folderNight) {
            folderNight.put(v, night);
        }
        Class<?> f = float.class, i = int.class;
        IN_RECIPE.set(true);
        try {
            float ri = Float.parseFloat(sysProp("persist.land.island_glass_refr", "-1").trim());
            float rr = Float.parseFloat(sysProp("persist.land.island_glass_refr_r", "-1").trim());
            tryInvoke(v, "setGlassEffectCustomized", new Class<?>[]{f, f}, ri >= 0 ? ri : 140f, rr >= 0 ? rr : 60f);
            tryInvoke(v, "setGlassEffectInnerStrokeLight", new Class<?>[]{i, f, f, f, f, i, f},
                    -1, 0.45f, 0f, 1f, 1f, 4, 0.4f);
            float blur = Math.max(0f, Float.parseFloat(sysProp("persist.land.island_glass_blur", "0").trim()));
            if (mCustom13 == null) {
                for (Method m : View.class.getMethods()) {
                    if (m.getName().equals("setMaterialCustomized") && m.getParameterCount() == 13) { mCustom13 = m; break; }
                }
            }
            if (mCustom13 == null) throw new NoSuchMethodException("setMaterialCustomized/13");
            if (night) {
                mCustom13.invoke(v, blur, 40f, 0x000000, 0.35f, 2, 0xBFBFBF, 0.08f, 0, 0xFFFFFF, 0f, 0, 1f, 0);
            } else {
                mCustom13.invoke(v, blur, 20f, 0xE6E6E6, 0.1f, 0, 0x262626, 0.1f, 7, 0xFFFFFF, 0f, 0, 1f, 0);
            }
            if (mTimelineEnable == null) mTimelineEnable = View.class.getMethod("setGlassEffectTimelineEnable", boolean.class);
            if (mTimelineLight == null) mTimelineLight = View.class.getMethod("setGlassEffectTimelineLight", i, f, f, f);
            mTimelineEnable.invoke(v, true);
            mTimelineLight.invoke(v, 0x19b3c6ff, 0f, -5.955f, 11.91f);
            if (DEBUG) diag("folder recipe night=" + night + " blur=" + blur + " refr=" + ri + "/" + rr);
        } catch (Throwable t) {
            warn("folder recipe failed", t);
        } finally {
            IN_RECIPE.set(false);
        }
    }

    // 材质背景的采样刷新率。409~412 不设时走系统默认（可能低于屏幕刷新率），胶囊形状按屏幕帧率动、
    // 玻璃背景按较低帧率刷新，二者不同步就会出现撕裂感；高刷设置不同的机器表现不一样。
    //   persist.land.island_glass_fps   0=跟随当前屏幕模式（默认），或固定 60/90/120/144
    private void applyFps(View v) {
        int fps = 0;
        try {
            fps = Integer.parseInt(sysProp("persist.land.island_glass_fps", "0").trim());
        } catch (Throwable ignored) {
        }
        if (fps <= 0) {
            android.view.Display d = v.getDisplay();
            fps = d == null ? 120 : Math.round(d.getMode().getRefreshRate());
        }
        fps = Math.max(30, Math.min(144, fps));
        tryInvoke(v, "setMaterialFPS", new Class<?>[]{int.class}, fps);
        if (DEBUG) diag("material fps=" + fps);
    }

    private void tryInvoke(View v, String name, Class<?>[] types, Object... args) {
        try {
            View.class.getMethod(name, types).invoke(v, args);
        } catch (Throwable t) {
            warn(name + " failed", t);
        }
    }

    private static int styleFor(Configuration cfg) {
        boolean night = (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        String mode = sysProp(PROP_GLASS, "strong");
        if ("folder".equalsIgnoreCase(mode)) return STYLE_FOLDER;
        boolean strong = !"medium".equalsIgnoreCase(mode);
        return night ? (strong ? 411 : 412) : (strong ? 409 : 410);
    }

    private static boolean isContainer(Class<?> c) {
        for (; c != null && c != View.class; c = c.getSuperclass()) {
            if (CONTAINER.equals(c.getName())) return true;
        }
        return false;
    }

    private static boolean readOn() {
        return !"0".equals(sysProp(PROP_ON, "1").trim());
    }

    private static String sysProp(String key, String def) {
        return Conf.get(key, def); // LSPosed 远程偏好
    }

    private static Field findField(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... types) {
        for (; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Object readField(Object o, String name) throws IllegalAccessException {
        Field f = findField(o.getClass(), name);
        return f == null ? null : f.get(o);
    }

    private Field fContainer;

    // 音乐展开卡片 IslandMusicWidget 内有一个纯黑的 #background_show 盖在材质上：材质可见时清掉其背景，材质关闭时恢复
    private final java.util.WeakHashMap<View, android.graphics.drawable.Drawable> hiddenBg = new java.util.WeakHashMap<>();
    private long lastBgScan;

    private void fixOpaqueBackgrounds(Object anim) {
        try {
            if (fContainer == null) return;
            Object c = fContainer.get(anim);
            if (!(c instanceof View)) return;
            boolean blur = fBlurVisible.getBoolean(anim);
            if (!blur) {
                if (hiddenBg.isEmpty()) return;
                for (java.util.Map.Entry<View, android.graphics.drawable.Drawable> e : hiddenBg.entrySet()) {
                    if (e.getKey() != null) e.getKey().setBackground(e.getValue());
                }
                hiddenBg.clear();
                diag("restore opaque backgrounds");
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastBgScan < 250) return;
            lastBgScan = now;
            scanBg((View) c, 0);
        } catch (Throwable t) {
            Log.w(TAG, "fixOpaqueBackgrounds failed", t);
        }
    }

    private void scanBg(View v, int d) {
        if (d > 14) return;
        if (v.getId() != View.NO_ID && !hiddenBg.containsKey(v)) {
            android.graphics.drawable.Drawable bg = v.getBackground();
            if (bg instanceof android.graphics.drawable.ColorDrawable
                    && Color.alpha(((android.graphics.drawable.ColorDrawable) bg).getColor()) > 0
                    && "background_show".equals(entryName(v))) {
                hiddenBg.put(v, bg);
                v.setBackground(null);
                info("cleared opaque background_show (" + Integer.toHexString(((android.graphics.drawable.ColorDrawable) bg).getColor()) + ")");
            }
        }
        if (v instanceof android.view.ViewGroup && v.getVisibility() != View.GONE) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) scanBg(g.getChildAt(i), d + 1);
        }
    }

    // 计时器/充电等带"光效"的胶囊：两层全宽光效 View 盖在材质上显示成黑色。材质可见时把它们隐藏（alpha=0），材质关闭时还原
    private Field fSmallLight, fBigLight;
    private Method mUpdateBlur;
    private final java.util.WeakHashMap<View, Float> hiddenLight = new java.util.WeakHashMap<>();

    private void fixEffectLights(Object anim) {
        try {
            boolean blur = fBlurVisible.getBoolean(anim);
            for (Field f : new Field[]{fSmallLight, fBigLight}) {
                if (f == null) continue;
                Object o = f.get(anim);
                if (!(o instanceof View)) continue;
                View v = (View) o;
                if (blur) {
                    if (!hiddenLight.containsKey(v)) {
                        hiddenLight.put(v, v.getAlpha());
                        diag("hide effect light " + entryName(v));
                    }
                    if (v.getAlpha() != 0f) v.setAlpha(0f);
                } else {
                    Float a = hiddenLight.remove(v);
                    if (a != null) {
                        v.setAlpha(a);
                        diag("restore effect light " + entryName(v));
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixEffectLights failed", t);
        }
    }

    // 小胶囊静止后原版不再调用 updateBlurAppearance；若上一次恰好在过渡期被关掉材质，就会一直停在黑/透明，需点一下才恢复。
    // 绘制时发现材质关着但满足强制条件，就补调一次（post 到下一帧，限流 300ms）。
    private long lastRevive;

    private void reviveStuckBlur(final Object anim) {
        try {
            if (mUpdateBlur == null || fContainer == null || fBlurVisible.getBoolean(anim)) return;
            if (!shouldForce(anim)) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastRevive < 300) return;
            lastRevive = now;
            Object c = fContainer.get(anim);
            if (!(c instanceof View)) return;
            ((View) c).post(() -> {
                try {
                    if (!fBlurVisible.getBoolean(anim) && shouldForce(anim)) {
                        mUpdateBlur.invoke(anim, true);
                        ((View) c).invalidate();
                        diag("revived stuck blur");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "revive blur failed", t);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "reviveStuckBlur failed", t);
        }
    }

    private volatile String forceWhy = "";

    // ---- "从黑底中还原透明度"（unpremultiply-from-black）----
    // 这些素材是按"画在纯黑胶囊上"设计的：可见颜色 = 预乘 rgb。
    // 输出预乘 (rgb, max(r,g,b))：叠在黑色上与原图逐像素完全一致，叠在玻璃上则只剩光本身，没有黑底、也不会变暗。
    private static final String UNBLACK_AGSL =
            "uniform shader content;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  half m = max(max(c.r, c.g), c.b);\n"
            + "  if (m < 0.004) return half4(0.0);\n"
            + "  return half4(c.rgb, m);\n"
            + "}\n";
    private android.graphics.RenderEffect unblackFx;
    private final java.util.WeakHashMap<View, android.graphics.RenderEffect> theirFx = new java.util.WeakHashMap<>();
    private boolean selfFx;

    private android.graphics.RenderEffect unblack() {
        if (unblackFx == null) {
            unblackFx = android.graphics.RenderEffect.createRuntimeShaderEffect(
                    new android.graphics.RuntimeShader(UNBLACK_AGSL), "content");
        }
        return unblackFx;
    }

    private android.graphics.RenderEffect composed(View v) {
        android.graphics.RenderEffect t = theirFx.get(v);
        return t == null ? unblack() : android.graphics.RenderEffect.createChainEffect(unblack(), t);
    }

    // 记录原版（光效 SDK 等）自己设置的 RenderEffect，我们的效果与之串联而不是覆盖
    private void installFxHook() {
        try {
            Method m = View.class.getMethod("setRenderEffect", android.graphics.RenderEffect.class);
            hook(m).setExceptionMode(ExceptionMode.DEFAULT).intercept(chain -> {
                if (selfFx || !islandOn) return chain.proceed();
                View v = (View) chain.getThisObject();
                android.graphics.RenderEffect e = (android.graphics.RenderEffect) chain.getArg(0);
                if (e == null) theirFx.remove(v); else theirFx.put(v, e);
                if (keyed.containsKey(v)) return chain.proceed(new Object[]{composed(v)});
                return chain.proceed();
            });
            info("View.setRenderEffect hooked");
        } catch (Throwable t) {
            warn("setRenderEffect hook failed", t);
        }
    }
    private final java.util.WeakHashMap<View, Integer> keyed = new java.util.WeakHashMap<>();
    private final java.util.WeakHashMap<View, Boolean> pagViews = new java.util.WeakHashMap<>();
    private long lastPagScan;

    private void fixLumaKey(Object anim) {
        try {
            boolean blur = fBlurVisible.getBoolean(anim);
            // 充电 PAG（TextureView）：材质可见时去黑（光效 View 已回退为 alpha=0 隐藏，不在这里处理）
            Object c = fContainer == null ? null : fContainer.get(anim);
            long now = android.os.SystemClock.uptimeMillis();
            if (c instanceof View && now - lastPagScan > 250) {
                lastPagScan = now;
                findPag((View) c, 0);
            }
            for (View v : new java.util.ArrayList<>(pagViews.keySet())) {
                if (v != null) setKey(v, blur);
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixLumaKey failed", t);
        }
    }

    private void findPag(View v, int d) {
        if (d > 14) return;
        if (v instanceof android.view.TextureView && v.getClass().getName().endsWith("PAGView")) pagViews.put(v, Boolean.TRUE);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findPag(g.getChildAt(i), d + 1);
        }
    }

    private void setKey(View v, boolean on) {
        boolean has = keyed.containsKey(v);
        if (on == has) return;
        selfFx = true;
        try {
            if (on) {
                keyed.put(v, 0);
                v.setRenderEffect(composed(v));
            } else {
                keyed.remove(v);
                v.setRenderEffect(theirFx.get(v));
            }
        } catch (Throwable t) {
            keyed.remove(v);
            Log.w(TAG, "unblack failed on " + entryName(v), t);
        } finally {
            selfFx = false;
        }
        v.invalidate();
        diag((on ? "unblack on " : "unblack off ") + v.getClass().getSimpleName() + " " + entryName(v));
    }

    // 材质 contentType：0=纯 SurfaceFlinger 窗口模糊（首次出现要等几帧，期间透明）；
    // 4=窗口模糊 + 应用内 no-delay 背景模糊兜底（vivo 用来消除首帧延迟）。默认 4，可用 prop 切回 0。
    private static int contentType() {
        try {
            int ct = Integer.parseInt(sysProp("persist.land.island_glass_ct", "2").trim());
            return ct >= 0 && ct <= 2 ? ct : 2;
        } catch (Throwable t) {
            return 2;
        }
    }

    private static String entryName(View v) {
        try {
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return null;
        }
    }

    // 诊断日志：persist.land.island_glass_debug=1 时启用，去重 + 限流（每秒最多 20 条），只写 logcat
    private String lastDiag;
    private long diagWindow;
    private int diagCount;

    private synchronized void diag(String s) {
        if (!DEBUG) return;
        if (s.equals(lastDiag)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - diagWindow > 1000) { diagWindow = now; diagCount = 0; }
        if (++diagCount > 20) return;
        lastDiag = s;
        Log.i(TAG, "[diag] " + s);
    }

    // 同时写 logcat 与 LSPosed 模块日志
    private void info(String s) {
        Log.i(TAG, s);
        try { log(Log.INFO, TAG, s); } catch (Throwable ignored) { }
    }

    private void warn(String s, Throwable t) {
        Log.w(TAG, s, t);
        try { log(Log.WARN, TAG, s, t); } catch (Throwable ignored) { }
    }

    private void error(String s, Throwable t) {
        Log.e(TAG, s, t);
        try { log(Log.ERROR, TAG, s, t); } catch (Throwable ignored) { }
    }
}
