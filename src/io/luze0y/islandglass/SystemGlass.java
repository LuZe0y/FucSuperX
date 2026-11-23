package io.luze0y.islandglass;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 系统液态玻璃调节：作用于 SystemUI / 插件主进程内除原子岛以外的全部材质
 * （控制中心、通知中心、通知、锁屏通知等）。原子岛跑在 :island 独立进程，不安装本 Hook。
 *
 * 1. BlurMaterial.resolveGlassEffectBlurGeometryByStyle(geometry, style)：液态玻璃预设 401~434
 *    解析后按倍数缩放模糊 / 饱和 / 染色层 / 折射 / 边缘高光。每个几何对象记住「原值」和「上次写出值」：
 *    解析器没重写的字段从原值重新计算，所以重复解析不会叠乘，倍数改回 1 也能恢复原样。
 * 2. VivoViewImpl.setMaterialCustomized(10 参 / 14 参)：所有自定义材质的汇合点（重载互相转调时只处理最外层）。
 *    style 401~434 用液态玻璃倍数，style 0（控制中心等三层材质）用「样式 0」倍数，其他预设不动。
 *    取不到当前 style 时用该位置最近一次 setMaterialForce 的 style 兜底。
 * 3. getGlassEffectParamsByStyle 内部解析不缩放：OriginUI 取这份参数后会走 setMaterialCustomized(Bundle)，
 *    由 2 统一缩放一次，避免双重生效。
 * 4. 实时生效：记录每个材质最近一次 setMaterialForce / setMaterialCustomized 的原始参数，
 *    配置变更后在 View 线程重放一遍，已显示的界面立即套用新倍数，无需重启 SystemUI。
 *
 * 键（远程偏好 / persist.land.*），倍数 1 = 系统原值：
 *   sysglass             1 开 / 0 关（关闭即完全不干预）
 *   sysglass_tint        染色层不透明度（材质强度）     sysglass_blur     模糊半径
 *   sysglass_sat         饱和度                         sysglass_alpha    最终不透明度
 *   sysglass_refr        折射强度                       sysglass_refr_r   折射范围
 *   sysglass_light       边缘高光强度                   sysglass_light_w  边缘高光宽度
 *   sysglass_zero_tint   样式 0 染色层不透明度          sysglass_zero_blur 样式 0 模糊半径
 *   sysglass_bg_blur     下拉通知 / 控制中心背后的全屏背景模糊（VivoDynamicBlurBg.setBlurRadius，0~1 比例，结果上限 1）
 */
final class SystemGlass {
    private static final String TAG = "FucSuperX";
    private static final int GLASS_MIN = 401, GLASS_MAX = 434;
    private static final int MAX_POS = 8;
    // 与 factor() 下标一一对应
    private static final String[] GEO = {"mBlurRadius", "mSaturation", "mMaskOpacity_1", "mMaskOpacity_2",
            "mMaskOpacity_3", "mFinalOpacity", "mRefractionIntensity", "mRefractionRange", "mLight1Intensity",
            "mLight1StrokeWidth"};
    // VivoViewImpl 中各 materialPositionType 对应的几何字段（contentType 0 走窗口模糊缓存）
    private static final String[] POS_GEO = {"mBlurGeometry", "mForegroundBlurGeometry", "mBackgroundBlurGeometry",
            "mAfterBackgroundBlurGeometry", "mAfterForegroundBlurGeometry"};
    private static final ThreadLocal<Boolean> IN_PARAMS = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> C_DEPTH = ThreadLocal.withInitial(() -> 0);

    /** 每个 VivoViewImpl 各位置最近一次的原始调用（缩放前）。 */
    private static final class Rec {
        final int[] style = new int[MAX_POS];
        final Object[][] force = new Object[MAX_POS][];
        final Method[] cm = new Method[MAX_POS];
        final Object[][] cargs = new Object[MAX_POS][];

        Rec() {
            Arrays.fill(style, -1);
        }
    }

    /** 几何对象各字段的原值与上次写出值。 */
    private static final class GeoState {
        final float[] orig, out;

        GeoState(int n) {
            orig = new float[n];
            out = new float[n];
        }
    }

    private final XposedModule module;
    private volatile boolean on = true;
    private volatile float tint = 1, blur = 1, sat = 1, alpha = 1, refr = 1, refrR = 1, light = 1, lightW = 1;
    private volatile float zTint = 1, zBlur = 1;

    private Field[] geo;
    private Field fStyle, fSfGeo, fContentType, fView;
    private final Field[] posGeo = new Field[POS_GEO.length];
    private Method mForce;

    private final Map<Object, Rec> recs = new WeakHashMap<>();
    private final Map<Object, GeoState> geoStates = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable replayAll = this::replayAll;

    // 下拉面板背景模糊：VivoDynamicBlurBg 实例 -> 最近一次系统传入的原始值
    private volatile float bgBlur = 1;
    private final Map<Object, Float> bgLast = new WeakHashMap<>();
    private volatile Method mBgSet;

    SystemGlass(XposedModule module) {
        this.module = module;
    }

    /** SystemUI 及插件的非原子岛进程。 */
    static boolean wants(String process) {
        if (process == null || process.endsWith(":island")) return false;
        return process.startsWith("com.android.systemui") || process.startsWith("com.vivo.systemuiplugin");
    }

    void install() throws Throwable {
        load();
        Conf.subscribe(k -> {
            if (k != null && k.startsWith("sysglass")) {
                load();
                Log.i(TAG, "system glass config changed: " + k);
                main.removeCallbacks(replayAll); // 连续拖动滑块时合并为一次重放
                main.postDelayed(replayAll, 80);
            }
        });

        Class<?> bm = Class.forName("android.view.blur.BlurMaterial");
        Method resolve = null, params = null;
        for (Method x : bm.getDeclaredMethods()) {
            if (!Modifier.isStatic(x.getModifiers())) continue;
            if (x.getName().equals("resolveGlassEffectBlurGeometryByStyle") && x.getParameterCount() == 2) resolve = x;
            else if (x.getName().equals("getGlassEffectParamsByStyle") && x.getParameterCount() == 1) params = x;
        }
        if (resolve == null) throw new NoSuchMethodException("resolveGlassEffectBlurGeometryByStyle");

        Class<?> gc = resolve.getParameterTypes()[0];
        geo = new Field[GEO.length];
        for (int i = 0; i < GEO.length; i++) {
            try {
                Field f = gc.getField(GEO[i]);
                if (f.getType() == float.class) geo[i] = f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        try {
            fStyle = gc.getField("mStyle");
        } catch (NoSuchFieldException ignored) {
        }

        module.hook(resolve).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object g = chain.getArg(0);
            Object s = chain.getArg(1);
            if (IN_PARAMS.get() || g == null || !(s instanceof Integer)) return chain.proceed();
            int style = (Integer) s;
            if (style < GLASS_MIN || style > GLASS_MAX) return chain.proceed();
            Object r = chain.proceed();
            scale(g);
            return r;
        });

        if (params != null) {
            module.hook(params).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                boolean prev = IN_PARAMS.get();
                IN_PARAMS.set(true);
                try {
                    return chain.proceed();
                } finally {
                    IN_PARAMS.set(prev);
                }
            });
        }

        Class<?> vi = Class.forName("android.view.VivoViewImpl");
        fSfGeo = findField(vi, "mSFBlurGeometryCache");
        fContentType = findField(vi, "mMaterialContentType");
        fView = findField(vi, "mView");
        for (int i = 0; i < POS_GEO.length; i++) posGeo[i] = findField(vi, POS_GEO[i]);
        int n = hookCustomized(vi);
        hookForce(vi);
        Log.i(TAG, "system glass hooks installed (customized x" + n + ", live reapply)");
    }

    /** SystemUI 主进程 ClassLoader 就绪后安装：下拉面板背景模糊倍数。 */
    void installSysui(ClassLoader cl) throws Throwable {
        Class<?> c = cl.loadClass("com.vivo.systemui.statusbar.panel.background.VivoDynamicBlurBg");
        Method m = c.getDeclaredMethod("setBlurRadius", float.class);
        m.setAccessible(true);
        module.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object a = chain.getArg(0);
            if (!(a instanceof Float)) return chain.proceed();
            float raw = (Float) a;
            synchronized (bgLast) {
                bgLast.put(chain.getThisObject(), raw);
            }
            float k = on ? bgBlur : 1f;
            if (k == 1f || raw <= 0f) return chain.proceed();
            return chain.proceed(new Object[]{Math.min(1f, raw * k)});
        });
        mBgSet = m;
        Log.i(TAG, "system glass bg blur hook installed");
    }

    private void replayBg() {
        Method m = mBgSet;
        if (m == null) return;
        List<Object> objs = new ArrayList<>();
        List<Float> vals = new ArrayList<>();
        synchronized (bgLast) {
            for (Map.Entry<Object, Float> e : bgLast.entrySet()) {
                objs.add(e.getKey());
                vals.add(e.getValue());
            }
        }
        for (int i = 0; i < objs.size(); i++) {
            try {
                m.invoke(objs.get(i), vals.get(i)); // 经过上面的 Hook，按新倍数重新设置
            } catch (Throwable t) {
                Log.w(TAG, "system glass bg blur reapply failed", t);
            }
        }
    }

    private int hookCustomized(Class<?> vi) {
        int count = 0;
        for (Method x : vi.getDeclaredMethods()) {
            if (!x.getName().equals("setMaterialCustomized")) continue;
            final int n = x.getParameterCount();
            if ((n != 10 && n != 14) || x.getParameterTypes()[0] != float.class) continue;
            final int fin = n == 10 ? 8 : 11;
            final int[] ops = n == 10 ? new int[]{3, 6} : new int[]{3, 6, 9};
            final Method mx = x;
            mx.setAccessible(true);
            module.hook(x).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                int depth = C_DEPTH.get();
                if (depth > 0) return chain.proceed(); // 外层已处理，重载内部转调不再缩放
                C_DEPTH.set(1);
                try {
                    int pos = 0;
                    if (n == 14 && chain.getArg(13) instanceof Integer) pos = (Integer) chain.getArg(13);
                    Object self = chain.getThisObject();
                    int style = styleOf(self, pos);
                    if (style < 0) style = lastStyle(self, pos);
                    boolean glass = style >= GLASS_MIN && style <= GLASS_MAX;
                    if (!glass && style != 0) return chain.proceed();
                    recordCustom(self, pos, mx, chain.getArgs().toArray());
                    if (!on) return chain.proceed();
                    float kt = glass ? tint : zTint, kb = glass ? blur : zBlur;
                    if (kt == 1f && kb == 1f && sat == 1f && alpha == 1f) return chain.proceed();
                    Object[] a = chain.getArgs().toArray();
                    a[0] = (Float) a[0] * kb;
                    a[1] = (Float) a[1] * sat;
                    for (int i : ops) a[i] = Math.min(1f, (Float) a[i] * kt);
                    a[fin] = Math.min(1f, (Float) a[fin] * alpha);
                    return chain.proceed(a);
                } finally {
                    C_DEPTH.set(depth);
                }
            });
            count++;
        }
        return count;
    }

    /** setMaterial 的汇合点：只记录（用于 style 兜底和实时重放），不改参数。 */
    private void hookForce(Class<?> vi) throws Throwable {
        Method force = vi.getDeclaredMethod("setMaterialForce", int.class, int.class, int.class);
        force.setAccessible(true);
        mForce = force;
        module.hook(force).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object[] args = chain.getArgs().toArray();
            Object r = chain.proceed();
            if (args[0] instanceof Integer && args[2] instanceof Integer) {
                int style = (Integer) args[0], pos = (Integer) args[2];
                Object self = chain.getThisObject();
                if (Boolean.TRUE.equals(r) && (style == 0 || (style >= GLASS_MIN && style <= GLASS_MAX)))
                    recordForce(self, pos, args, style);
                else
                    clearRec(self, pos);
            }
            return r;
        });
    }

    private int styleOf(Object impl, int pos) {
        try {
            int ct = -1;
            Object arr = fContentType == null ? null : fContentType.get(impl);
            if (arr instanceof int[] && pos >= 0 && pos < ((int[]) arr).length) ct = ((int[]) arr)[pos];
            Field f;
            if (ct == 0) f = fSfGeo;
            else f = pos >= 0 && pos < posGeo.length ? posGeo[pos] : null;
            Object g = f == null ? null : f.get(impl);
            return g == null || fStyle == null ? -1 : fStyle.getInt(g);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 按「原值 × 当前倍数」写回；解析器没重写的字段（仍是上次写出值）从记住的原值重新计算。 */
    private void scale(Object g) {
        synchronized (geoStates) {
            GeoState st = geoStates.get(g);
            boolean fresh = st == null;
            if (fresh) st = new GeoState(geo.length);
            for (int i = 0; i < geo.length; i++) {
                Field f = geo[i];
                if (f == null) continue;
                try {
                    float now = f.getFloat(g);
                    float orig = !fresh && Float.compare(now, st.out[i]) == 0 ? st.orig[i] : now;
                    float v = orig * factor(i);
                    if ((i >= 2 && i <= 5) || i == 8) v = Math.min(1f, v); // 不透明度 / 高光强度上限 1
                    if (Float.compare(v, now) != 0) f.setFloat(g, v);
                    st.orig[i] = orig;
                    st.out[i] = v;
                } catch (Throwable ignored) {
                }
            }
            if (fresh) geoStates.put(g, st);
        }
    }

    private float factor(int i) {
        if (!on) return 1f;
        switch (i) {
            case 0: return blur;
            case 1: return sat;
            case 2: case 3: case 4: return tint;
            case 5: return alpha;
            case 6: return refr;
            case 7: return refrR;
            case 8: return light;
            case 9: return lightW;
            default: return 1f;
        }
    }

    // ---------------- 记录与实时重放 ----------------

    private Rec rec(Object impl) {
        Rec r = recs.get(impl);
        if (r == null) recs.put(impl, r = new Rec());
        return r;
    }

    private void recordForce(Object impl, int pos, Object[] args, int style) {
        if (pos < 0 || pos >= MAX_POS) return;
        synchronized (recs) {
            Rec r = rec(impl);
            r.style[pos] = style;
            r.force[pos] = args;
            r.cm[pos] = null; // 新预设：之前的自定义参数作废
            r.cargs[pos] = null;
        }
    }

    private void recordCustom(Object impl, int pos, Method m, Object[] args) {
        if (pos < 0 || pos >= MAX_POS) return;
        synchronized (recs) {
            Rec r = rec(impl);
            r.cm[pos] = m;
            r.cargs[pos] = args;
        }
    }

    private void clearRec(Object impl, int pos) {
        if (pos < 0 || pos >= MAX_POS) return;
        synchronized (recs) {
            Rec r = recs.get(impl);
            if (r == null) return;
            r.style[pos] = -1;
            r.force[pos] = null;
            r.cm[pos] = null;
            r.cargs[pos] = null;
        }
    }

    private int lastStyle(Object impl, int pos) {
        if (pos < 0 || pos >= MAX_POS) return -1;
        synchronized (recs) {
            Rec r = recs.get(impl);
            return r == null ? -1 : r.style[pos];
        }
    }

    /** 对所有已记录的材质重走一遍原始调用：经过上面的 Hook 后自然套用新倍数。 */
    private void replayAll() {
        List<Object> impls = new ArrayList<>();
        List<Rec> snaps = new ArrayList<>();
        synchronized (recs) {
            for (Map.Entry<Object, Rec> e : recs.entrySet()) {
                Rec src = e.getValue(), cp = new Rec();
                boolean any = false;
                for (int p = 0; p < MAX_POS; p++) {
                    cp.style[p] = src.style[p];
                    cp.force[p] = src.force[p];
                    cp.cm[p] = src.cm[p];
                    cp.cargs[p] = src.cargs[p];
                    any |= src.force[p] != null || src.cargs[p] != null;
                }
                if (any) {
                    impls.add(e.getKey());
                    snaps.add(cp);
                }
            }
        }
        int n = 0;
        for (int i = 0; i < impls.size(); i++) {
            Object impl = impls.get(i);
            Rec r = snaps.get(i);
            Object vv;
            try {
                vv = fView == null ? null : fView.get(impl);
            } catch (Throwable t) {
                vv = null;
            }
            if (!(vv instanceof View)) continue;
            View v = (View) vv;
            v.post(() -> replay(impl, v, r));
            n++;
        }
        replayBg();
        Log.i(TAG, "system glass live reapply x" + n + ", bg x" + bgLast.size());
    }

    private void replay(Object impl, View v, Rec r) {
        for (int p = 0; p < MAX_POS; p++) {
            try {
                if (r.force[p] != null && mForce != null) mForce.invoke(impl, r.force[p]);
                if (r.cargs[p] != null && r.cm[p] != null) r.cm[p].invoke(impl, r.cargs[p]);
            } catch (Throwable t) {
                Log.w(TAG, "system glass reapply failed", t);
            }
        }
        v.invalidate();
    }

    private void load() {
        on = !"0".equals(Conf.get("persist.land.sysglass", "1").trim());
        tint = num("sysglass_tint", 3f);
        blur = num("sysglass_blur", 3f);
        sat = num("sysglass_sat", 3f);
        alpha = num("sysglass_alpha", 1f);
        refr = num("sysglass_refr", 3f);
        refrR = num("sysglass_refr_r", 3f);
        light = num("sysglass_light", 3f);
        lightW = num("sysglass_light_w", 3f);
        zTint = num("sysglass_zero_tint", 3f);
        zBlur = num("sysglass_zero_blur", 3f);
        bgBlur = num("sysglass_bg_blur", 5f);
    }

    private static float num(String key, float max) {
        try {
            float v = Float.parseFloat(Conf.get("persist.land." + key, "1").trim());
            return Float.isNaN(v) ? 1f : Math.max(0f, Math.min(max, v));
        } catch (Throwable t) {
            return 1f;
        }
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
}
