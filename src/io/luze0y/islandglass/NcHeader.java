package io.luze0y.islandglass;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 通知中心顶部时间 / 日期改成 iOS 式大号居中：只改字号和位置，不改内容与行为。
 * 日期居中放在上面一行（右侧按钮保持在这一行），时间放大后居中放在下面。
 * 只作用于通知中心（父级为 VivoSimpleHeaderContainer 的 VivoQSHeader），控制中心不动；横屏不动。
 *   nc_bigclock      1 开（默认）/ 0 关
 *   nc_clock_scale   时间字号倍数 1~3，默认 2
 *   nc_land_split    横屏分栏时，时间日期在左半屏垂直居中（与 NcLand 共用开关）
 *   nc_clock_font    sys=系统默认 | lock=跟随锁屏（时间、日期都取锁屏当前字体）| /system/fonts 下的字体文件名
 *   nc_clock_wght    自选字体的字重 100~900，-1 = 字体默认
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
    private volatile boolean landSplit = true;
    private volatile String font = "sys";
    private volatile int wght = -1;
    private int idHour, idLockDate;
    private boolean lockMissLogged;
    private final Map<TextView, Object[]> faces = new WeakHashMap<>(); // {系统字体, 系统可变轴, 上次写入字体, 上次写入轴}
    private final Map<String, Typeface> fileFaces = new HashMap<>();
    private final Map<TextView, Object[]> metrics = new WeakHashMap<>(); // {includeFontPadding, fallbackLineSpacing, extra, mult}
    /** 紧凑模式下时间数字的可见下沿（相对时间控件顶部），供横屏清除按钮定位。 */
    static final Map<View, Integer> VISUAL_BOTTOM = new WeakHashMap<>();
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
        // 旋转屏幕时系统不一定重设时间字号：这里按当前方向重算（横屏恢复系统字号），否则放大的字会被裁掉
        Method occ = headerClass.getDeclaredMethod("onConfigurationChanged", Configuration.class);
        module.hook(occ).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            View h = (View) chain.getThisObject();
            track(h);
            applyStyle(h);
            h.requestLayout();
            return r;
        });
        // 收起 / 展开时系统用一个「假时钟」（状态栏字体）按时间控件的大小和位置做过渡动画，
        // 同时把时间颜色过渡成状态栏颜色。自选字体时假时钟仍是系统字体，过渡中途会换成另一种字形。
        // 动画量尺寸前（getViewWidth）把假时钟换成同样的字体和行高，让它以自选字体缩到状态栏，
        // 只在最后变成状态栏小时钟时换回系统字体；选回系统默认时还原。
        Field fBar = headerClass.getDeclaredField("mBarClock");
        fBar.setAccessible(true);
        // 收起后半段由 SystemUI 自己把假时钟移到状态栏时钟上并交叉淡变（runStatusBarClockTranslationYAnimation），
        // 状态栏时钟换成同一种字体后这一段就是无缝的，这里不再另做淡变（另做会和系统的位移冲突，结束时闪出大号时间）。
        Method gvw = headerClass.getDeclaredMethod("getViewWidth", View.class);
        module.hook(gvw).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object a = chain.getArg(0);
            View h = (View) chain.getThisObject();
            TextView c = clock(h);
            if (a instanceof TextView && a != c && c != null) syncFake((TextView) a, c, h);
            return chain.proceed();
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
            if (active(h)) {
                relayout(h);
                if ("lock".equals(font)) h.post(() -> applyStyle(h)); // 锁屏换字体后跟上（无变化时不做任何事）
            }
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
        boolean portrait = h.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        return on && (portrait || landSplit) && isNc(h);
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
        String f = font;
        if (c != null) {
            if (!act || "sys".equals(f)) applyFont(c, null, null);
            else if ("lock".equals(f)) {
                TextView lh = lockView(h, "hour", true);
                if (lh != null) applyFont(c, lh.getTypeface(), lh.getFontVariationSettings());
            } else applyFont(c, fileFace(f), null);
            compact(c, act && !"sys".equals(f));
            syncBar(h, c, act && !"sys".equals(f) && metrics.containsKey(c));
        }
        View d = child(h, "right_date", ID_DATE, true);
        if (d instanceof TextView) {
            TextView dt = (TextView) d;
            if (act && "lock".equals(f)) {
                TextView ld = lockView(h, "date", false);
                if (ld != null) applyFont(dt, ld.getTypeface(), ld.getFontVariationSettings());
            } else applyFont(dt, null, null);
        }
        if (d instanceof TextView) {
            TextView t = (TextView) d;
            if (!gravity.containsKey(t)) gravity.put(t, t.getGravity());
            int g = act ? Gravity.CENTER_HORIZONTAL : gravity.get(t);
            if (t.getGravity() != g) t.setGravity(g);
        }
    }

    /** tf 为 null 时恢复系统字体；系统自己改过字体（与上次写入不同）时以系统新值为准。 */
    private void applyFont(TextView t, Typeface tf, String var) {
        Typeface cur = t.getTypeface();
        String curVar = t.getFontVariationSettings();
        Object[] st = faces.get(t);
        if (st == null || cur != st[2] || !Objects.equals(curVar, st[3])) {
            st = new Object[]{cur, curVar, cur, curVar};
            faces.put(t, st);
        }
        Typeface wantTf = tf == null ? (Typeface) st[0] : tf;
        String wantVar = tf == null ? (String) st[1] : var;
        if (cur != wantTf) t.setTypeface(wantTf);
        if (!Objects.equals(t.getFontVariationSettings(), wantVar)) {
            try {
                t.setFontVariationSettings(wantVar);
            } catch (Throwable ignored) {
            }
        }
        st[2] = t.getTypeface();
        st[3] = t.getFontVariationSettings();
    }

    private Method depGet, sbGet;
    private Class<?> transferIf;
    private boolean sbMissLogged;

    /** 状态栏真时钟（SystemUI 通过 IPluginInOutAnimateTransfer 提供给插件）。 */
    private TextView barClock(View h) {
        try {
            if (sbGet == null) {
                ClassLoader cl = h.getClass().getClassLoader();
                Class<?> dep = Class.forName("com.android.systemui.plugins.PluginDependency", false, cl);
                transferIf = Class.forName("com.android.systemui.plugins.vivo.IPluginInOutAnimateTransfer", false, cl);
                Method g = dep.getDeclaredMethod("get", Class.class);
                g.setAccessible(true);
                depGet = g;
                sbGet = transferIf.getMethod("getStatusBarClockView");
            }
            Object o = depGet.invoke(null, transferIf);
            Object v = o == null ? null : sbGet.invoke(o);
            if (v instanceof TextView) return (TextView) v;
        } catch (Throwable t) {
            if (!sbMissLogged) Log.w(TAG, "nc header: status bar clock: " + t);
            sbMissLogged = true;
            return null;
        }
        if (!sbMissLogged) Log.i(TAG, "nc header: status bar clock not a TextView");
        sbMissLogged = true;
        return null;
    }

    /** 状态栏时钟跟顶栏时间用同一种字体；选回系统默认时还原。 */
    private void syncBar(View h, TextView c, boolean custom) {
        TextView sb = barClock(h);
        if (sb == null) return;
        if (custom) {
            if (!barShift.containsKey(sb)) {
                float base = glyphCenter(sb);
                if (Float.isNaN(base)) return; // 还没排版，下次再来
                barShift.put(sb, new float[]{sb.getPaddingTop(), sb.getPaddingBottom(), sb.getTop() + base});
                if (barHooked.put(sb, Boolean.TRUE) == null) {
                    // 换字体后控件高度会变、父布局会重新居中它，要等布局完成再对齐
                    sb.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(() -> alignBar((TextView) v)));
                }
            }
            applyFont(sb, c.getTypeface(), c.getFontVariationSettings());
            compact(sb, false);
            sb.post(() -> alignBar(sb));
        } else {
            applyFont(sb, null, null);
            compact(sb, false);
            float[] st = barShift.remove(sb);
            if (st != null) sb.setPadding(sb.getPaddingLeft(), (int) st[0], sb.getPaddingRight(), (int) st[1]);
        }
    }

    /** {原 paddingTop, 原 paddingBottom, 系统字体下数字中心（父布局坐标，原 padding）} */
    private final Map<TextView, float[]> barShift = new WeakHashMap<>();
    private final Map<TextView, Boolean> barHooked = new WeakHashMap<>();

    /** 换字体后数字中心会上下偏（HA 系列偏高），用上下 padding 等量反向挪回，控件总高度不变。 */
    private void alignBar(TextView sb) {
        float[] st = barShift.get(sb);
        if (st == null) return;
        float now = glyphCenter(sb);
        if (Float.isNaN(now)) return;
        now += sb.getTop(); // 与记录的原位置一样按父布局坐标比较
        int curShift = sb.getPaddingTop() - (int) st[0];
        int d = Math.round(st[2] - (now - curShift));
        if (d == curShift) return;
        Log.i(TAG, "nc header: status bar clock re-center " + curShift + " -> " + d);
        sb.setPadding(sb.getPaddingLeft(), (int) st[0] + d, sb.getPaddingRight(), (int) st[1] - d);
    }

    /** 数字（0~9 和冒号）可见部分的垂直中心，相对控件顶部，含 gravity 偏移。 */
    private static float glyphCenter(TextView t) {
        android.text.Layout l = t.getLayout();
        if (l == null || t.getHeight() == 0) return Float.NaN;
        Rect b = new Rect();
        t.getPaint().getTextBounds("0123456789:", 0, 11, b);
        if (b.height() <= 0) return Float.NaN;
        return t.getTotalPaddingTop() + l.getLineBaseline(0) + (b.top + b.bottom) / 2f;
    }

    private static float glyphCenterOnScreen(TextView t) {
        float c = glyphCenter(t);
        if (Float.isNaN(c)) return c;
        int[] o = new int[2];
        t.getLocationOnScreen(o);
        return o[1] + c * t.getScaleY();
    }

    private void syncFake(TextView fake, TextView c, View h) {
        boolean custom = active(h) && !"sys".equals(font) && metrics.containsKey(c);
        if (custom) applyFont(fake, c.getTypeface(), c.getFontVariationSettings());
        else applyFont(fake, null, null);
        compact(fake, custom);
        syncBar(h, c, custom);
        fake.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        fake.layout(fake.getLeft(), fake.getTop(), fake.getLeft() + fake.getMeasuredWidth(),
                fake.getTop() + fake.getMeasuredHeight());
    }

    /**
     * 紧凑模式（自选字体）下数字的可见上下沿，相对时间控件顶部；系统字体或未排版时返回 null。
     * 用「0123456789:」的边界，分钟变化时高度不跳动。
     */
    private int[] glyphs(TextView t) {
        if (!metrics.containsKey(t) || t.getLayout() == null) return null;
        Rect b = new Rect();
        String s = "0123456789:";
        t.getPaint().getTextBounds(s, 0, s.length(), b);
        if (b.height() <= 0) return null;
        int base = t.getPaddingTop() + t.getLayout().getLineBaseline(0);
        return new int[]{base + b.top, base + b.bottom};
    }

    private static int gap(View h) {
        return Math.round(6 * h.getResources().getDisplayMetrics().density);
    }

    /**
     * HA 系列（锁屏时钟用的也是）字体的行高远大于数字本身，换上后时间一行会变得很高。
     * 自选字体时去掉字体内边距和备用字体行距，行高固定为字号的 1.12 倍；恢复系统字体时还原。
     */
    private void compact(TextView t, boolean on) {
        Object[] m = metrics.get(t);
        if (!on) {
            if (m == null) return;
            metrics.remove(t);
            t.setIncludeFontPadding((Boolean) m[0]);
            t.setFallbackLineSpacing((Boolean) m[1]);
            t.setLineSpacing((Float) m[2], (Float) m[3]);
            return;
        }
        if (m == null) {
            metrics.put(t, new Object[]{t.getIncludeFontPadding(), t.isFallbackLineSpacing(),
                    t.getLineSpacingExtra(), t.getLineSpacingMultiplier()});
        }
        if (t.getIncludeFontPadding()) t.setIncludeFontPadding(false);
        if (t.isFallbackLineSpacing()) t.setFallbackLineSpacing(false);
        int lh = Math.round(t.getTextSize() * 1.12f);
        if (t.getLineHeight() != lh) t.setLineHeight(lh);
    }

    /** 锁屏时钟（插件 hour / date），与通知中心在同一窗口内。 */
    private TextView lockView(View h, String name, boolean hour) {
        int id = hour ? idHour : idLockDate;
        if (id == 0) {
            id = h.getResources().getIdentifier(name, "id", PKG);
            if (hour) idHour = id;
            else idLockDate = id;
        }
        View v = id == 0 ? null : h.getRootView().findViewById(id);
        if (!(v instanceof TextView)) {
            if (!lockMissLogged) {
                lockMissLogged = true;
                Log.i(TAG, "nc header: lockscreen " + name + " view not found");
            }
            return null;
        }
        return (TextView) v;
    }

    private Typeface fileFace(String name) {
        if (!name.matches("[A-Za-z0-9_.-]+")) return null;
        String key = name + "@" + wght;
        synchronized (fileFaces) {
            if (fileFaces.containsKey(key)) return fileFaces.get(key);
        }
        Typeface tf = null;
        File f = new File("/system/fonts/" + (name.endsWith(".ttf") ? name : name + ".ttf"));
        if (f.isFile()) {
            try {
                Typeface.Builder b = new Typeface.Builder(f);
                if (wght > 0) b.setFontVariationSettings("'wght' " + wght);
                tf = b.build();
            } catch (Throwable t) {
                Log.w(TAG, "nc header font load failed: " + f, t);
            }
        }
        synchronized (fileFaces) {
            fileFaces.put(key, tf);
        }
        return tf;
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
        int[] g = glyphs((TextView) c);
        int content = d.getMeasuredHeight() + (g == null ? c.getMeasuredHeight() : gap(h) + g[1] - g[0] + gap(h));
        int need = h.getPaddingTop() + topOffset(h, content) + content + h.getPaddingBottom();
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
        int dw = d.getMeasuredWidth(), dh = d.getMeasuredHeight();
        int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
        int[] g = glyphs((TextView) c);
        int vis = g == null ? ch : gap(h) + g[1] - g[0] + gap(h); // 时间实际占用的高度
        int W = h.getWidth(), top = h.getPaddingTop() + topOffset(h, dh + vis);
        int dx = (W - dw) / 2;
        d.layout(dx, top, dx + dw, top + dh);
        int cx = (W - cw) / 2, cy = top + dh;
        if (g != null) cy += gap(h) - g[0]; // 让数字可见部分紧贴日期下方，控件上方的空白与日期重叠（日期在上层，点按不受影响）
        c.layout(cx, cy, cx + cw, cy + ch);
        synchronized (VISUAL_BOTTOM) {
            if (g != null) VISUAL_BOTTOM.put(c, g[1]);
            else VISUAL_BOTTOM.remove(c);
        }
        if (ic != null && ic.getVisibility() != View.GONE) {
            int iw = ic.getWidth(), ih = ic.getHeight(), il, it;
            if (h.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                il = ic.getLeft(); // 竖屏：与日期同一行，横向位置不变
                it = top + Math.max(0, (dh - ih) / 2);
            } else {
                il = -firstVisibleLeft(ic); // 横屏：左半屏左上角（状态栏下方）
                it = landIconTop(h);
            }
            ic.layout(il, it, il + iw, it + ih);
        }
        h.setPivotX(cx + cw / 2f); // 系统展开动画以时间为中心缩放
        h.setPivotY(g == null ? cy + ch / 2f : cy + (g[0] + g[1]) / 2f);
    }

    private static int firstVisibleLeft(View v) {
        if (!(v instanceof android.view.ViewGroup)) return 0;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) min = Math.min(min, c.getLeft());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int landIconTop(View h) {
        int id = h.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int sb = id == 0 ? 0 : h.getResources().getDimensionPixelSize(id);
        int y = 0;
        for (View v = h; v != null; ) {
            y += v.getTop();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return Math.max(0, sb + Math.round(8 * h.getResources().getDisplayMetrics().density) - y);
    }

    /** 竖屏：下移距离；横屏分栏：在左半屏垂直居中。按布局位置计算（不含动画位移），测量与布局一致。 */
    private int topOffset(View h, int content) {
        if (h.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) return offset(h);
        int y = 0;
        View v = h;
        while (v != null) {
            y += v.getTop();
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        int screenH = h.getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, (screenH - content) / 2 - y - h.getPaddingTop());
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
        landSplit = !"0".equals(Conf.get("persist.land.nc_land_split", "1").trim());
        font = Conf.get("persist.land.nc_clock_font", "sys").trim();
        try {
            wght = Math.round(Float.parseFloat(Conf.get("persist.land.nc_clock_wght", "-1").trim()));
        } catch (Throwable t) {
            wght = -1;
        }
        if (wght > 0) wght = Math.max(100, Math.min(900, wght));
    }
}
