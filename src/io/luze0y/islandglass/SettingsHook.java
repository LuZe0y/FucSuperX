package io.luze0y.islandglass;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * vivo 设置（OriginOS 7）首页顶部注入 FucSuperX 入口。
 *
 * 首页 RecyclerView 适配器 h0 的数据来自 com.vivo.settings.dashboard.a：构造时 b() 把
 * DashboardCategory / Tile 平铺成 List<a$b(obj, layoutRes, hash)>（字段 a）。
 * 1. Hook a(a$a) 构造：在列表下标 0 插入 a$b(我们的 Tile, R.layout.vivo_dashboard_tile, hash)。
 *    位置 0 时 h0$e.a()/b() 都返回分类类型 p，卡片样式自动为单项圆角卡片，不影响后续条目。
 *    不改 DashboardCategory，m0 分类器与 Tile.equals 不受影响。
 * 2. Hook h0$h.e(Tile, ...)：原版绑定后覆盖图标/摘要/点击（点击打开模块界面）。
 *
 * 开关：persist.land.settings_entry  0=不显示入口（默认 1，重启设置生效）
 */
final class SettingsHook {
    static final String PKG = "com.android.settings";
    private static final String TAG = "FucSuperX";
    private static final String TITLE = "FucSuperX";
    private static final String SUMMARY = "模块设置";
    private static final String MODULE_PKG = "io.luze0y.islandglass";
    private static final String MODULE_ACT = "io.luze0y.islandglass.SettingsActivity";
    private static final int TILE_ID = -0x46535558; // "FSUX"，避开 -7050 / m0.b 等特殊 id

    private final XposedModule module;
    private Class<?> tileCls;
    private Constructor<?> itemCtor;
    private Field fItems, fItemObj, fVList, fItemView;
    private Method mCardStyle;
    private int layoutTile;
    private Object tile; // 单例：只靠引用比较识别
    private Field fAdapterList;
    private volatile java.lang.ref.WeakReference<Object> adapterRef; // 首页适配器 h0，用于实时增删入口

    SettingsHook(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader cl) throws Throwable {
        Class<?> aCls = cl.loadClass("com.vivo.settings.dashboard.a");
        Class<?> aaCls = cl.loadClass("com.vivo.settings.dashboard.a$a");
        Class<?> bCls = cl.loadClass("com.vivo.settings.dashboard.a$b");
        Class<?> h0 = cl.loadClass("com.vivo.settings.dashboard.h0");
        Class<?> eCls = cl.loadClass("com.vivo.settings.dashboard.h0$e");
        Class<?> hCls = cl.loadClass("com.vivo.settings.dashboard.h0$h");
        tileCls = cl.loadClass("com.vivo.settings.settingslib.drawer.Tile");

        itemCtor = bCls.getDeclaredConstructor(Object.class, int.class, int.class);
        itemCtor.setAccessible(true);
        fItems = acc(aCls.getDeclaredField("a"));
        fItemObj = acc(bCls.getDeclaredField("a"));
        fVList = acc(eCls.getDeclaredField("h"));
        fItemView = findItemView(eCls);
        for (Method x : eCls.getDeclaredMethods()) {
            if (x.getName().equals("c") && x.getParameterCount() == 2 && x.getParameterTypes()[1] == tileCls) {
                mCardStyle = acc(x);
            }
        }
        layoutTile = acc(h0.getDeclaredField("l")).getInt(null);

        // a(a$a) 构造很短，会被 ART 内联进 a$a.c()，直接 Hook 不触发。
        // 改为 Hook 适配器 h0.B(List, List)（synchronized，不会被内联）：原版建好列表后再插入入口。
        fAdapterList = acc(h0.getDeclaredField("i"));
        Method setCats = h0.getDeclaredMethod("B", List.class, List.class);
        module.hook(setCats).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            adapterRef = new java.lang.ref.WeakReference<>(chain.getThisObject());
            try {
                Object list = fAdapterList.get(chain.getThisObject());
                if (list != null && inject(list)) {
                    Log.i(TAG, "entry injected");
                    call(chain.getThisObject(), "x", "FucSuperX entry"); // 再通知一次，确保首帧前刷新
                }
            } catch (Throwable t) {
                Log.e(TAG, "inject failed", t);
            }
            return r;
        });

        Method bind = null;
        for (Method x : hCls.getDeclaredMethods()) {
            if (x.getName().equals("e") && x.getParameterCount() == 6 && x.getParameterTypes()[0] == tileCls) bind = x;
        }
        if (bind == null) throw new NoSuchMethodException("h0$h.e(Tile,...)");
        module.hook(bind).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object t = chain.getArg(0);
            if (t == null || t != tile) return chain.proceed();
            boolean nativeOk = true;
            try {
                chain.proceed();
            } catch (Throwable e) {
                nativeOk = false;
                Log.w(TAG, "native bind failed, using fallback", e);
            }
            try {
                decorate(chain.getThisObject(), nativeOk);
            } catch (Throwable e) {
                Log.e(TAG, "decorate failed", e);
            }
            return null;
        });
        Conf.subscribe(k -> {
            if ("settings_entry".equals(k)) liveToggle();
        });
        Log.i(TAG, "settings entry hooks installed");
    }

    /** 模块界面切换「设置首页入口」：直接在首页列表里增删入口并刷新，无需重启设置。 */
    private void liveToggle() {
        java.lang.ref.WeakReference<Object> ref = adapterRef;
        Object ad = ref == null ? null : ref.get();
        if (ad == null) return; // 首页还没创建：下次创建时按新值处理
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                Object list = fAdapterList.get(ad);
                if (list == null) return;
                boolean on = !"0".equals(sysProp("persist.land.settings_entry", "1").trim());
                boolean changed;
                synchronized (ad) {
                    changed = on ? inject(list) : remove(list);
                }
                if (changed) {
                    call(ad, "notifyDataSetChanged");
                    Log.i(TAG, "entry live " + (on ? "shown" : "hidden"));
                }
            } catch (Throwable t) {
                Log.e(TAG, "live toggle failed", t);
            }
        });
    }

    private boolean remove(Object list) throws Throwable {
        if (tile == null) return false;
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) fItems.get(list);
        if (items == null) return false;
        boolean changed = false;
        for (java.util.Iterator<Object> it = items.iterator(); it.hasNext(); ) {
            if (fItemObj.get(it.next()) == tile) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    static Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(MODULE_PKG, MODULE_ACT));
    }

    private Object tile() throws Throwable {
        if (tile != null) return tile;
        Object t = tileCls.getDeclaredConstructor().newInstance();
        set(t, "a", TITLE);
        set(t, "e", SUMMARY);
        set(t, "n", TILE_ID);
        set(t, "r", true);
        set(t, "i", launchIntent());
        try {
            Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            EntryIcon d = new EntryIcon();
            d.setBounds(0, 0, 96, 96);
            d.draw(new Canvas(bm));
            set(t, "g", Icon.createWithBitmap(bm));
        } catch (Throwable e) {
            Log.w(TAG, "tile icon", e);
        }
        tile = t;
        return t;
    }

    private boolean inject(Object list) throws Throwable {
        if ("0".equals(sysProp("persist.land.settings_entry", "1").trim())) return false;
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) fItems.get(list);
        if (items == null) return false;
        Object t = tile();
        for (Object it : items) {
            if (fItemObj.get(it) == t) return false;
        }
        items.add(0, itemCtor.newInstance(t, layoutTile, Objects.hash(TITLE)));
        return true;
    }

    private void decorate(Object holder, boolean nativeOk) throws Throwable {
        View item = (View) fItemView.get(holder);
        if (item == null) return;
        Object vl = fVList.get(holder);
        if (vl != null) {
            if (!nativeOk) {
                call(vl, "setTitle", TITLE);
                if (mCardStyle != null) mCardStyle.invoke(holder, vl, tile);
            }
            call(vl, "setPriorityWidthStates", 1);
            call(vl, "setShowSummary", true);
            call(vl, "setSummary", SUMMARY);
            EntryIcon icon = new EntryIcon();
            if (!call(vl, "setIcon", icon)) {
                Object iv = call0(vl, "getIconView");
                if (iv instanceof ImageView) ((ImageView) iv).setImageDrawable(icon);
            }
            call(vl, "setContentDescription", TITLE + ", " + SUMMARY);
        }
        item.setEnabled(true);
        item.setClickable(true);
        item.setOnClickListener(v -> {
            try {
                v.getContext().startActivity(launchIntent());
            } catch (Throwable e) {
                Log.e(TAG, "launch module activity failed", e);
            }
        });
    }

    // ---------------- 反射工具 ----------------

    private void set(Object o, String name, Object v) throws Throwable {
        acc(tileCls.getDeclaredField(name)).set(o, v);
    }

    private static <T extends java.lang.reflect.AccessibleObject> T acc(T x) {
        x.setAccessible(true);
        return x;
    }

    private static Field findItemView(Class<?> c) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return acc(k.getDeclaredField("itemView"));
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException("itemView");
    }

    private static boolean call(Object o, String name, Object... args) {
        try {
            invoke(o, name, args);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, name + " failed: " + t);
            return false;
        }
    }

    private static Object call0(Object o, String name) {
        try {
            return invoke(o, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invoke(Object o, String name, Object... args) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] p = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length && ok; i++) ok = fits(p[i], args[i]);
                if (!ok) continue;
                m.setAccessible(true);
                return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(o.getClass().getName() + "." + name);
    }

    private static boolean fits(Class<?> p, Object a) {
        if (a == null) return !p.isPrimitive();
        if (p == int.class) return a instanceof Integer;
        if (p == boolean.class) return a instanceof Boolean;
        if (p == float.class) return a instanceof Float;
        if (p == long.class) return a instanceof Long;
        return p.isInstance(a);
    }

    static String sysProp(String key, String def) {
        return Conf.get(key, def); // LSPosed 远程偏好
    }

    /** 入口图标：圆角方形蓝色渐变底 + 白色 F，忽略系统着色，随 bounds 自适应。 */
    static final class EntryIcon extends Drawable {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        EntryIcon() {
            fg.setColor(0xFFFFFFFF);
            fg.setTextAlign(Paint.Align.CENTER);
            fg.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        @Override
        protected void onBoundsChange(Rect b) {
            bg.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    0xFF6E8BFF, 0xFF3A4FE0, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            float l = b.exactCenterX() - s / 2f, t = b.exactCenterY() - s / 2f;
            rect.set(l, t, l + s, t + s);
            float r = s * 0.3f;
            c.drawRoundRect(rect, r, r, bg);
            fg.setTextSize(s * 0.58f);
            Paint.FontMetrics fm = fg.getFontMetrics();
            c.drawText("F", rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, fg);
        }

        @Override
        public void setAlpha(int a) {
            bg.setAlpha(a);
            fg.setAlpha(a);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return 96;
        }

        @Override
        public int getIntrinsicHeight() {
            return 96;
        }
    }
}
