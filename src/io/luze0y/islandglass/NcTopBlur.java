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
        Log.i(TAG, "nc top blur hooks installed, region=" + (region != null));
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
