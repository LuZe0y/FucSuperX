package io.luze0y.islandglass;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 通知中心上滑时的顶部渐进模糊（SystemUI 一侧）。
 * 原版在通知列表上滑（滚动量 > 0）时显示顶栏里的 top_blur_view（渐进模糊，高 120 / 160dp），
 * 同时给通知列表设同样高度的材质渐变（setMaterialGradientHeight）。大号时间把顶栏撑高后，
 * 时间下半截不在这段模糊里，上滑的通知就和时间叠在一起。
 * 插件一侧（NcHeader）把 top_blur_view 拉长到时间下沿以下，并通过 setBottom 报告它的下沿；
 * 这里让通知列表的材质渐变与划走通知时的模糊刷新范围跟着延长。bottom ≤ 0 时完全按原版。
 * 渐入：原版滚动量一离开 0 就把模糊整块显示出来（setOwnScrollY 里切 VISIBLE / GONE），这里按滚动量
 * 把模糊视图的透明度从 0 拉到系统值（面板动画 setStatckAnimAlpha 写入的值），上滑 nc_top_blur_fade（dp）
 * 后完全出现，下滑回顶部时同样逐渐消失；为 0 时按原版立即出现。
 */
final class NcTopBlur {
    private static final String TAG = "FucSuperX";
    private static final String NSSL = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";

    /** top_blur_view 下沿（窗口内布局坐标，不含动画位移）；≤ 0 = 未延长。 */
    private static volatile int bottom = -1;
    private static volatile WeakReference<View> stack = new WeakReference<>(null);
    private static Method mRefresh, mGrad;
    private static Field fOn;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String CTRL = "com.vivo.blur.VivoHeaderTopBlurViewController";
    private volatile float fadeDp = 60f;
    private float baseAlpha = 1f; // 系统给模糊视图的透明度（面板展开 / 收起动画）
    private Object ctrl;
    private Field fBlurView;
    private Method mDepGet, mScrollY;
    private Class<?> ctrlClass;

    private final XposedModule module;

    NcTopBlur(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader cl) throws Throwable {
        Class<?> nssl = cl.loadClass(NSSL);
        fOn = nssl.getDeclaredField("mIsSetMaterialGradientHeight");
        fOn.setAccessible(true);
        mGrad = View.class.getMethod("setMaterialGradientHeight", float.class, float.class);
        Method refresh = nssl.getDeclaredMethod("setMaterialGradientHeight", boolean.class);
        refresh.setAccessible(true);
        mRefresh = refresh;
        // 原版按资源里的固定高度设渐变；之后按延长后的模糊下沿重设
        module.hook(refresh).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View v = (View) chain.getThisObject();
            if (stack.get() != v) stack = new WeakReference<>(v);
            int h = height(v);
            if (h > 0 && fOn.getBoolean(v)) mGrad.invoke(v, 0f, (float) h);
            return r;
        });
        // 划走通知时，位于模糊区内的才刷新模糊：范围同样延长
        Method region = null;
        for (Method m : nssl.getDeclaredMethods()) {
            if (m.getName().equals("isInTopBlurRegion") && m.getParameterCount() == 1) region = m;
        }
        if (region != null) {
            module.hook(region).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object r = chain.proceed();
                if (!Boolean.FALSE.equals(r)) return r;
                View v = (View) chain.getThisObject();
                int h = height(v);
                Object a = chain.getArg(0);
                if (h <= 0 || !(a instanceof View) || !fOn.getBoolean(v) || !((View) a).isShown()) return r;
                float y = 0;
                for (View c = (View) a; c != null && c != v; ) {
                    y += c.getTop() + c.getTranslationY();
                    ViewParent p = c.getParent();
                    c = p instanceof View ? (View) p : null;
                }
                return y < h;
            });
        }
        installFade(cl, nssl);
        Log.i(TAG, "nc top blur hooks installed, region=" + (region != null) + " fade=" + fadeDp);
    }

    private void installFade(ClassLoader cl, Class<?> nssl) throws Throwable {
        load();
        Conf.subscribe(k -> {
            if (k != null && k.startsWith("nc_top_blur")) {
                load();
                MAIN.post(this::applyFade);
            }
        });
        ctrlClass = cl.loadClass(CTRL);
        fBlurView = ctrlClass.getDeclaredField("mBlurView");
        fBlurView.setAccessible(true);
        Class<?> dep = cl.loadClass("com.android.systemui.Dependency");
        Field fs = dep.getDeclaredField("sDependency");
        fs.setAccessible(true);
        Method gi = dep.getDeclaredMethod("getDependencyInner", Object.class);
        gi.setAccessible(true);
        mDepGet = gi;
        ctrl = new Object[]{fs}; // 延迟取实例：安装时 Dependency 可能还没初始化
        mScrollY = nssl.getMethod("getOwnScrollY");
        // 滚动量变化：系统在这里切换模糊视图的可见性，之后按滚动量设透明度
        Method so = nssl.getDeclaredMethod("setOwnScrollY", int.class, boolean.class);
        module.hook(so).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View v = (View) chain.getThisObject();
            if (stack.get() != v) stack = new WeakReference<>(v);
            applyFade();
            return r;
        });
        // 面板展开 / 收起动画会直接给模糊视图设透明度：记下系统值，再乘上渐入进度
        try {
            Class<?> anim = cl.loadClass("com.vivo.systemui.animation.VivoStackScrollAnimationController");
            Method sa = anim.getDeclaredMethod("setStatckAnimAlpha", float.class);
            module.hook(sa).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object r = chain.proceed();
                baseAlpha = (Float) chain.getArg(0);
                applyFade();
                return r;
            });
        } catch (Throwable t) {
            Log.w(TAG, "nc top blur: stack anim alpha hook failed: " + t);
        }
    }

    private View blurView() {
        try {
            Object c = ctrl;
            if (c instanceof Object[]) {
                Object d = ((Field) ((Object[]) c)[0]).get(null);
                if (d == null) return null;
                c = mDepGet.invoke(d, ctrlClass);
                if (c == null) return null;
                ctrl = c;
            }
            Object v = fBlurView.get(c);
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 透明度 = 系统值 × 渐入进度（滚动量 / 渐入距离）。只在模糊可见时设置，GONE 时交给系统。 */
    private void applyFade() {
        View s = stack.get(), b = blurView();
        if (s == null || b == null || b.getVisibility() != View.VISIBLE) return;
        float p = 1f;
        float ramp = fadeDp * s.getResources().getDisplayMetrics().density;
        if (ramp > 0) {
            try {
                p = Math.max(0f, Math.min(1f, (Integer) mScrollY.invoke(s) / ramp));
            } catch (Throwable ignored) {
            }
        }
        float want = baseAlpha * p;
        if (Float.compare(b.getAlpha(), want) != 0) b.setAlpha(want);
    }

    private void load() {
        try {
            float v = Float.parseFloat(Conf.get("persist.land.nc_top_blur_fade", "60").trim());
            fadeDp = Float.isNaN(v) ? 60f : Math.max(0f, Math.min(200f, v));
        } catch (Throwable t) {
            fadeDp = 60f;
        }
    }

    /** 插件一侧报告 top_blur_view 下沿；变化时让通知列表重设材质渐变。 */
    static void setBottom(int y) {
        if (y <= 0) y = -1;
        if (y == bottom) return;
        bottom = y;
        Log.i(TAG, "nc top blur bottom -> " + y);
        MAIN.post(() -> {
            View s = stack.get();
            if (s == null || mRefresh == null) return;
            try {
                mRefresh.invoke(s, true);
            } catch (Throwable t) {
                Log.w(TAG, "nc top blur refresh failed", t);
            }
        });
    }

    /** 渐变高度（通知列表自身坐标）；未延长时返回 -1。 */
    private static int height(View s) {
        int b = bottom;
        return b <= 0 ? -1 : b - rootY(s);
    }

    /** 在窗口内的布局位置（不含动画位移）。 */
    static int rootY(View v) {
        int y = 0;
        while (v != null) {
            y += v.getTop();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return y;
    }
}
