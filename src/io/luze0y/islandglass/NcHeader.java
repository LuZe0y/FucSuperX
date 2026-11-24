package io.luze0y.islandglass;

import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 通知中心顶部时间 / 日期改成 iOS 式大号居中：只改字号和位置，不改内容与行为。
 * 日期居中放在上面一行（右侧按钮保持在这一行），时间放大后居中放在下面。
 * 只作用于通知中心（父级为 VivoSimpleHeaderContainer 的 VivoQSHeader），控制中心不动；横屏不动。
 *   nc_bigclock      1 开（默认）/ 0 关
 *   nc_clock_scale   时间字号倍数 1~3，默认 2
 *   nc_clock_offset  时间 / 日期整体下移距离 0~200dp，默认 42（约为原顶部距离的一倍）
 */
final class NcHeader {
    private static final String TAG = "FucSuperX";
    private static final String HEADER = "com.vivo.systemuiplugin.systemui.statusbar.header.VivoQSHeader";
    private static final String CONTAINER = "com.vivo.systemuiplugin.systemui.VivoSimpleHeaderContainer";
    private static final String PKG = "com.vivo.systemuiplugin";
    private static final int ID_DATE = 0x770b06b5, ID_ICONS = 0x770b0331; // right_date / icon_container 兜底

    private final XposedModule module;
    private volatile boolean on = true;
    private volatile float scale = 2f;
    private volatile float offsetDp = 42f;
    private Class<?> headerClass;
    private Field fClock;
    private Method mSetMeasured;
    private int idDate, idIcons;
    private final Map<View, Boolean> headers = new WeakHashMap<>();      // header -> 是否通知中心（null = 未判定）
    private final Map<TextView, float[]> sizes = new WeakHashMap<>();    // 时间 -> {系统字号, 上次写入}
    private final Map<TextView, Integer> gravity = new WeakHashMap<>();  // 日期 -> 原 gravity
    private final Handler main = new Handler(Looper.getMainLooper());

    NcHeader(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader cl) throws Throwable {
        load();
        Conf.subscribe(k -> {
            if (k != null && k.startsWith("nc_")) {
                load();
                main.post(this::refreshAll);
            }
        });
        headerClass = cl.loadClass(HEADER);
        fClock = headerClass.getDeclaredField("mHeaderClock");
        fClock.setAccessible(true);
        mSetMeasured = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
        mSetMeasured.setAccessible(true);

        Method ucr = headerClass.getDeclaredMethod("updateClockResource", boolean.class);
        module.hook(ucr).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View h = (View) chain.getThisObject();
            track(h);
            applyStyle(h);
            return r;
        });
        Method att = headerClass.getDeclaredMethod("onAttachedToWindow");
        module.hook(att).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View h = (View) chain.getThisObject();
            track(h);
            applyStyle(h);
            h.requestLayout();
            return r;
        });
        // VivoQSHeader 没有覆写 onMeasure，挂在父类 ConstraintLayout 上并按实例过滤
        Method om = headerClass.getSuperclass().getDeclaredMethod("onMeasure", int.class, int.class);
        module.hook(om).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            Object self = chain.getThisObject();
            if (headerClass.isInstance(self) && active((View) self)) remeasure((View) self, (Integer) chain.getArg(0));
            return r;
        });
        Method ol = headerClass.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
        module.hook(ol).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View h = (View) chain.getThisObject();
            if (active(h)) relayout(h);
            return r;
        });
        Log.i(TAG, "nc header hooks installed, on=" + on + " scale=" + scale);
    }

    private void track(View h) {
        synchronized (headers) {
            if (!headers.containsKey(h)) headers.put(h, null);
        }
    }

    private boolean isNc(View h) {
        Boolean c;
        synchronized (headers) {
            c = headers.get(h);
        }
        if (c != null) return c;
        ViewParent p = h.getParent();
        if (p == null) return false; // 尚未挂到父级，下次再判定
        boolean nc = false;
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (CONTAINER.equals(p.getClass().getName())) {
                nc = true;
                break;
            }
        }
        synchronized (headers) {
            headers.put(h, nc);
        }
        Log.i(TAG, "nc header " + Integer.toHexString(System.identityHashCode(h)) + " notification=" + nc);
        return nc;
    }

    private boolean active(View h) {
        return on && h.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && isNc(h);
    }

    private TextView clock(View h) {
        try {
            Object c = fClock.get(h);
            return c instanceof TextView ? (TextView) c : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private View child(View h, String name, int fallback, boolean date) {
        int id = date ? idDate : idIcons;
        if (id == 0) {
            id = h.getResources().getIdentifier(name, "id", PKG);
            if (id == 0) id = fallback;
            if (date) idDate = id;
            else idIcons = id;
        }
        return h.findViewById(id);
    }

    /** 时间字号 = 系统字号 × 倍数；日期文字居中。关闭或不适用时恢复系统值。 */
    private void applyStyle(View h) {
        boolean act = active(h);
        TextView c = clock(h);
        if (c != null) {
            float now = c.getTextSize();
            float[] st = sizes.get(c);
            float base = st != null && Float.compare(now, st[1]) == 0 ? st[0] : now;
            float want = act ? base * scale : base;
            if (st == null) sizes.put(c, st = new float[2]);
            st[0] = base;
            st[1] = want;
            if (Float.compare(want, now) != 0) c.setTextSize(TypedValue.COMPLEX_UNIT_PX, want);
        }
        View d = child(h, "right_date", ID_DATE, true);
        if (d instanceof TextView) {
            TextView t = (TextView) d;
            if (!gravity.containsKey(t)) gravity.put(t, t.getGravity());
            int g = act ? Gravity.CENTER_HORIZONTAL : gravity.get(t);
            if (t.getGravity() != g) t.setGravity(g);
        }
    }

    /** 高度改为「日期一行 + 大号时间」。 */
    private void remeasure(View h, int wSpec) {
        View c = clock(h), d = child(h, "right_date", ID_DATE, true);
        if (c == null || d == null) return;
        int w = View.MeasureSpec.getSize(wSpec);
        if (w <= 0) w = h.getMeasuredWidth();
        int avail = Math.max(0, w - h.getPaddingLeft() - h.getPaddingRight());
        int at = View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST);
        int un = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        c.measure(at, un);
        d.measure(at, un);
        int need = h.getPaddingTop() + offset(h) + d.getMeasuredHeight() + c.getMeasuredHeight() + h.getPaddingBottom();
        if (need <= h.getMeasuredHeight()) return;
        try {
            mSetMeasured.invoke(h, h.getMeasuredWidthAndState(), need);
        } catch (Throwable t) {
            Log.w(TAG, "nc header measure failed", t);
        }
    }

    /** 日期居中在上，时间居中在下，右侧按钮与日期同一行。 */
    private void relayout(View h) {
        View c = clock(h), d = child(h, "right_date", ID_DATE, true), ic = child(h, "icon_container", ID_ICONS, false);
        if (c == null || d == null) return;
        int W = h.getWidth(), top = h.getPaddingTop() + offset(h);
        int dw = d.getMeasuredWidth(), dh = d.getMeasuredHeight();
        int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
        int dx = (W - dw) / 2;
        d.layout(dx, top, dx + dw, top + dh);
        int cx = (W - cw) / 2, cy = top + dh;
        c.layout(cx, cy, cx + cw, cy + ch);
        if (ic != null && ic.getVisibility() != View.GONE) {
            int iw = ic.getWidth(), ih = ic.getHeight(), il = ic.getLeft(), it = top + Math.max(0, (dh - ih) / 2);
            ic.layout(il, it, il + iw, it + ih);
        }
        h.setPivotX(cx + cw / 2f); // 系统展开动画以时间为中心缩放
        h.setPivotY(cy + ch / 2f);
    }

    private int offset(View h) {
        return Math.round(offsetDp * h.getResources().getDisplayMetrics().density);
    }

    private void refreshAll() {
        List<View> hs;
        synchronized (headers) {
            hs = new ArrayList<>(headers.keySet());
        }
        for (View h : hs) {
            try {
                applyStyle(h);
                h.requestLayout();
            } catch (Throwable t) {
                Log.w(TAG, "nc header refresh failed", t);
            }
        }
    }

    private void load() {
        on = !"0".equals(Conf.get("persist.land.nc_bigclock", "1").trim());
        try {
            float v = Float.parseFloat(Conf.get("persist.land.nc_clock_scale", "2").trim());
            scale = Float.isNaN(v) ? 2f : Math.max(1f, Math.min(3f, v));
        } catch (Throwable t) {
            scale = 2f;
        }
        try {
            float v = Float.parseFloat(Conf.get("persist.land.nc_clock_offset", "42").trim());
            offsetDp = Float.isNaN(v) ? 42f : Math.max(0f, Math.min(200f, v));
        } catch (Throwable t) {
            offsetDp = 42f;
        }
    }
}
