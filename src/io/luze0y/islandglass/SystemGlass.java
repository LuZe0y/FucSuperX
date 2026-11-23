package io.luze0y.islandglass;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 系统液态玻璃调节：作用于 SystemUI / 插件主进程内除原子岛以外的全部材质
 * （控制中心、通知中心、通知、锁屏通知等）。原子岛跑在 :island 独立进程，不安装本 Hook。
 *
 * 1. BlurMaterial.resolveGlassEffectBlurGeometryByStyle(geometry, style)：液态玻璃预设 401~434
 *    解析完成后按倍数缩放模糊 / 饱和 / 染色层 / 折射 / 边缘高光。只缩放本次解析写入的字段，
 *    同一几何被重复解析时不会叠加。
 * 2. VivoViewImpl.setMaterialCustomized(10 参 / 14 参)：所有自定义材质（含 Bundle、13 参入口）的汇合点。
 *    View 当前 style 为 401~434 用液态玻璃倍数，style 0（控制中心等三层材质）用「样式 0」倍数，其他预设不动。
 * 3. getGlassEffectParamsByStyle 内部解析不缩放：OriginUI 取这份参数后会走 setMaterialCustomized(Bundle)，
 *    由 2 统一缩放一次，避免双重生效。
 *
 * 键（远程偏好 / persist.land.*），倍数 1 = 系统原值：
 *   sysglass             1 开 / 0 关（关闭即完全不干预）
 *   sysglass_tint        染色层不透明度（材质强度）     sysglass_blur     模糊半径
 *   sysglass_sat         饱和度                         sysglass_alpha    最终不透明度
 *   sysglass_refr        折射强度                       sysglass_refr_r   折射范围
 *   sysglass_light       边缘高光强度                   sysglass_light_w  边缘高光宽度
 *   sysglass_zero_tint   样式 0 染色层不透明度          sysglass_zero_blur 样式 0 模糊半径
 */
final class SystemGlass {
    private static final String TAG = "FucSuperX";
    private static final int GLASS_MIN = 401, GLASS_MAX = 434;
    // 与 factor() 下标一一对应
    private static final String[] GEO = {"mBlurRadius", "mSaturation", "mMaskOpacity_1", "mMaskOpacity_2",
            "mMaskOpacity_3", "mFinalOpacity", "mRefractionIntensity", "mRefractionRange", "mLight1Intensity",
            "mLight1StrokeWidth"};
    // VivoViewImpl 中各 materialPositionType 对应的几何字段（contentType 0 走窗口模糊缓存）
    private static final String[] POS_GEO = {"mBlurGeometry", "mForegroundBlurGeometry", "mBackgroundBlurGeometry",
            "mAfterBackgroundBlurGeometry", "mAfterForegroundBlurGeometry"};
    private static final ThreadLocal<Boolean> IN_PARAMS = ThreadLocal.withInitial(() -> false);

    private final XposedModule module;
    private volatile boolean on = true;
    private volatile float tint = 1, blur = 1, sat = 1, alpha = 1, refr = 1, refrR = 1, light = 1, lightW = 1;
    private volatile float zTint = 1, zBlur = 1;

    private Field[] geo;
    private Field fStyle, fSfGeo, fContentType;
    private final Field[] posGeo = new Field[POS_GEO.length];

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
            if (!on || IN_PARAMS.get() || g == null || !(s instanceof Integer) || glassIdentity()) return chain.proceed();
            int style = (Integer) s;
            if (style < GLASS_MIN || style > GLASS_MAX) return chain.proceed();
            float[] before = snapshot(g);
            Object r = chain.proceed();
            scale(g, before);
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

        int n = hookCustomized();
        Log.i(TAG, "system glass hooks installed (customized x" + n + ")");
    }

    private int hookCustomized() throws Throwable {
        Class<?> vi = Class.forName("android.view.VivoViewImpl");
        fSfGeo = findField(vi, "mSFBlurGeometryCache");
        fContentType = findField(vi, "mMaterialContentType");
        for (int i = 0; i < POS_GEO.length; i++) posGeo[i] = findField(vi, POS_GEO[i]);

        int count = 0;
        for (Method x : vi.getDeclaredMethods()) {
            if (!x.getName().equals("setMaterialCustomized")) continue;
            final int n = x.getParameterCount();
            if ((n != 10 && n != 14) || x.getParameterTypes()[0] != float.class) continue;
            final int fin = n == 10 ? 8 : 11;
            final int[] ops = n == 10 ? new int[]{3, 6} : new int[]{3, 6, 9};
            module.hook(x).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!on) return chain.proceed();
                int pos = 0;
                if (n == 14 && chain.getArg(13) instanceof Integer) pos = (Integer) chain.getArg(13);
                int style = styleOf(chain.getThisObject(), pos);
                float kt, kb;
                if (style >= GLASS_MIN && style <= GLASS_MAX) {
                    kt = tint;
                    kb = blur;
                } else if (style == 0) {
                    kt = zTint;
                    kb = zBlur;
                } else {
                    return chain.proceed();
                }
                if (kt == 1f && kb == 1f && sat == 1f && alpha == 1f) return chain.proceed();
                Object[] a = chain.getArgs().toArray();
                a[0] = (Float) a[0] * kb;
                a[1] = (Float) a[1] * sat;
                for (int i : ops) a[i] = Math.min(1f, (Float) a[i] * kt);
                a[fin] = Math.min(1f, (Float) a[fin] * alpha);
                return chain.proceed(a);
            });
            count++;
        }
        return count;
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

    private float[] snapshot(Object g) {
        float[] v = new float[geo.length];
        for (int i = 0; i < geo.length; i++) {
            try {
                v[i] = geo[i] == null ? Float.NaN : geo[i].getFloat(g);
            } catch (Throwable t) {
                v[i] = Float.NaN;
            }
        }
        return v;
    }

    /** 只缩放解析器本次写过（值有变化）的字段：未写的字段保持原样，重复解析不会叠乘。 */
    private void scale(Object g, float[] before) {
        for (int i = 0; i < geo.length; i++) {
            Field f = geo[i];
            if (f == null) continue;
            try {
                float now = f.getFloat(g);
                if (Float.compare(now, before[i]) == 0) continue;
                float k = factor(i);
                if (k == 1f) continue;
                float v = now * k;
                if ((i >= 2 && i <= 5) || i == 8) v = Math.min(1f, v); // 不透明度 / 高光强度上限 1
                f.setFloat(g, v);
            } catch (Throwable ignored) {
            }
        }
    }

    private float factor(int i) {
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

    private boolean glassIdentity() {
        return tint == 1f && blur == 1f && sat == 1f && alpha == 1f && refr == 1f && refrR == 1f
                && light == 1f && lightW == 1f;
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
