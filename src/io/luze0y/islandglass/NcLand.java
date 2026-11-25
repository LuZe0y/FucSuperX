package io.luze0y.islandglass;

import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 横屏通知中心分栏：原版把顶部时间日期（qs_frame）和通知列表（notification_stack_scroller）
 * 都放在屏幕中间同一列。横屏时改为一侧半屏放时间日期、另一侧半屏放通知（默认时间日期在左），通知从顶部开始排列。
 * 清除全部按钮移到时间下方；左右两栏与屏幕边缘的留白对称（按挖孔较宽的一侧）。
 * 只改这些控件的位置和宽度，不改内容与行为；竖屏不动。
 *   nc_land_split   1 开（默认）/ 0 关
 *   nc_land_side    left = 时间日期在左、通知在右（默认）/ right = 时间日期在右、通知在左
 */
final class NcLand {
    private static final String TAG = "FucSuperX";
    private static final String SYSUI = "com.android.systemui";
    private final XposedModule module;
    private volatile boolean on = true, clockRight;
    private Class<?> container;
    private int idQs, idNs, idClear;
    private static final int ID_CLOCK = 0x770b030d; // 插件 header_clock
    private final Map<View, Boolean> containers = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    NcLand(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader cl) throws Throwable {
        load();
        Conf.subscribe(k -> {
            if (k != null && k.startsWith("nc_")) {
                load();
                main.post(this::refresh);
            }
        });
        container = cl.loadClass("com.android.systemui.shade.NotificationsQuickSettingsContainer");
        // 容器没有覆写 onLayout，挂在父类 ConstraintLayout 上并按实例过滤
        Method ol = container.getSuperclass().getDeclaredMethod("onLayout",
                boolean.class, int.class, int.class, int.class, int.class);
        module.hook(ol).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            Object self = chain.getThisObject();
            if (container.isInstance(self)) {
                synchronized (containers) {
                    containers.put((View) self, Boolean.TRUE);
                }
                if (active((View) self)) split((ViewGroup) self);
            }
            return r;
        });
        // 清除按钮在 notification_panel 下、与容器同级：在面板布局后按时间位置重新摆放
        final Class<?> panel = cl.loadClass("com.android.systemui.shade.NotificationPanelView");
        Method pl = null;
        for (Class<?> k = panel; k != null && pl == null; k = k.getSuperclass()) {
            try {
                pl = k.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (pl != null) {
            module.hook(pl).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object r = chain.proceed();
                Object self = chain.getThisObject();
                if (panel.isInstance(self) && active((View) self)) placeClear((ViewGroup) self);
                return r;
            });
        }
        Class<?> nssl = cl.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        Method tp = nssl.getDeclaredMethod("updateTopPadding", float.class, boolean.class);
        module.hook(tp).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            View v = (View) chain.getThisObject();
            if (!active(v)) return chain.proceed();
            return chain.proceed(new Object[]{(float) topPadding(v), chain.getArg(1)});
        });
        Log.i(TAG, "nc land hooks installed, on=" + on);
    }

    static boolean landscape(View v) {
        return v.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean active(View v) {
        return on && landscape(v);
    }

    private View find(ViewGroup p, String name, boolean qs) {
        int id = qs ? idQs : idNs;
        if (id == 0) {
            id = p.getResources().getIdentifier(name, "id", SYSUI);
            if (qs) idQs = id;
            else idNs = id;
        }
        return id == 0 ? null : p.findViewById(id);
    }

    /** 时间日期与通知各占半屏（左右由 nc_land_side 决定），避开挖孔。 */
    private void split(ViewGroup p) {
        int W = p.getWidth(), H = p.getHeight();
        if (W <= H) return;
        View qs = find(p, "qs_frame", true), ns = find(p, "notification_stack_scroller", false);
        if (qs == null || ns == null) return;
        int safeL = 0, safeR = 0;
        WindowInsets in = p.getRootWindowInsets();
        DisplayCutout dc = in == null ? null : in.getDisplayCutout();
        if (dc != null) {
            safeL = dc.getSafeInsetLeft();
            safeR = dc.getSafeInsetRight();
        }
        int m = Math.max(safeL, safeR), mid = W / 2; // 两侧留白对称
        if (clockRight) {
            place(ns, m, mid - m);
            place(qs, mid, W - m - mid);
        } else {
            place(qs, m, mid - m);
            place(ns, mid, W - m - mid);
        }
    }

    /** 清除全部按钮：时间正下方，水平居中。 */
    private void placeClear(ViewGroup p) {
        if (p.getWidth() <= p.getHeight()) return;
        if (idClear == 0) idClear = p.getResources().getIdentifier("vivo_notification_clear_all", "id", SYSUI);
        View clear = idClear == 0 ? null : p.findViewById(idClear);
        View clock = p.findViewById(ID_CLOCK);
        if (clear == null || clock == null || clock.getWidth() == 0) return;
        int[] o = offsetIn(clock, p);
        if (o == null) return;
        int w = clear.getMeasuredWidth() > 0 ? clear.getMeasuredWidth() : clear.getWidth();
        int h = clear.getMeasuredHeight() > 0 ? clear.getMeasuredHeight() : clear.getHeight();
        int l = o[0] + clock.getWidth() / 2 - w / 2;
        Integer vb;
        synchronized (NcHeader.VISUAL_BOTTOM) {
            vb = NcHeader.VISUAL_BOTTOM.get(clock);
        }
        int t = o[1] + (vb == null ? clock.getHeight() : vb) + Math.round(12 * p.getResources().getDisplayMetrics().density);
        clear.layout(l, t, l + w, t + h);
    }

    /** v 在祖先 anc 中的布局位置（不含动画位移）。 */
    static int[] offsetIn(View v, View anc) {
        int x = 0, y = 0;
        View c = v;
        while (c != null && c != anc) {
            x += c.getLeft();
            y += c.getTop();
            Object pp = c.getParent();
            c = pp instanceof View ? (View) pp : null;
        }
        return c == anc ? new int[]{x, y} : null;
    }

    private static void place(View v, int left, int w) {
        if (w <= 0) return;
        int top = v.getTop(), h = v.getHeight();
        if (v.getMeasuredWidth() != w || v.getMeasuredHeight() != h)
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(left, top, left + w, top + h);
    }

    /** 通知不再排在时间日期下方：从状态栏下方开始。 */
    private static int topPadding(View v) {
        int id = v.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int sb = id == 0 ? 0 : v.getResources().getDimensionPixelSize(id);
        return sb + Math.round(8 * v.getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        List<View> cs;
        synchronized (containers) {
            cs = new ArrayList<>(containers.keySet());
        }
        for (View c : cs) c.requestLayout();
    }

    private void load() {
        on = !"0".equals(Conf.get("persist.land.nc_land_split", "1").trim());
        clockRight = "right".equals(Conf.get("persist.land.nc_land_side", "left").trim());
    }
}
