package io.luze0y.islandglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

/**
 * 状态栏液态玻璃胶囊（SystemUI 主进程，仅桌面 / 应用内的顶部状态栏 PhoneStatusBarView）。
 *
 * 隐藏原生的网速、移动信号、Wi-Fi 与电池图标（可选隐藏左侧时钟），在 system_icons 末尾放一个自绘内容视图（Cap）：
 *   左侧：上排「网速  电池温度」，下排大号时间；
 *   右侧圆环：外圈为电量（缺口在下方），顶部压在圆环上的电量数字，充电时数字旁显示闪电（快充为双闪电）；
 *            环内显示当前使用的网络：Wi-Fi 时为按信号点亮的 Wi-Fi 图标，数据网络时为 4G / 5G / 5GA 等；
 *            底部断开处为移动信号点，每张卡一排（双卡两排）。
 * 数字和闪电压在圆环上时只在字形周围抠出一圈空隙，圆弧长度不随充电状态或数字位数变化。
 * 玻璃背景（Bg）是 system_icons 同级、垫在最底层的另一个视图，每帧按实际位置覆盖「图标容器里仍可见的其他图标
 * （蓝牙、闹钟、定位、副 Wi-Fi 等）+ 自绘内容」，图标出现 / 消失 / 位移动画时胶囊随之伸缩。
 * 布局参考 HyperDuo（GPL-3.0，github.com/yixing233/HyperDuo）的胶囊样式，按 OriginOS 结构重写。
 *
 * 胶囊高度可以超过 system_icons：两个视图都按设定高度布局、关闭 system_icons 的子视图裁剪，
 * 只受状态栏本身高度限制（以 system_icons 的竖直中心计算可容纳的最大高度）。
 *
 * 数据全部来自公开接口：时间（TIME_TICK）、电池（BATTERY_CHANGED，含温度；vivo 扩展字段 engine 为 1 / 3 时为快充）、
 * 网速（TrafficStats 每秒采样，熄屏不采样）、默认网络（ConnectivityManager 默认网络回调）、Wi-Fi 信号（RSSI_CHANGED）、
 * 各卡信号与网络制式（TelephonyCallback：信号强度 / 服务状态 / 显示信息）。
 * 深浅色取电池视图收到的 onDarkChanged，按胶囊自身位置计算图标颜色。
 *
 * 原生图标的隐藏：网速 / 移动信号 / Wi-Fi 加入 StatusIconContainer 的忽略槽位（每次测量前补回，系统清空也不丢）；
 * 电池设为 GONE（每帧检查，被系统改回立即再隐藏）；时钟拦截 Clock.setVisibility。关闭后全部还原。
 *
 * 玻璃材质完全独立：先 setMaterial 打开系统玻璃管线（折射 / 边缘高光需要 style ≥ 0），再用本模块参数重写观感，
 * 不受「系统液态玻璃」页倍数影响（SystemGlass 通过 owns() 跳过）。材质不可用时画一层圆角底兜底。
 *
 * 键（远程偏好 / persist.land.*）：
 *   sb_capsule 总开关         sb_capsule_bg 玻璃背景
 *   sb_capsule_clock_hide 隐藏左侧时钟（开：时间显示在胶囊里；关：左侧保留系统时钟，胶囊不显示时间）
 *     sb_capsule_speed 显示网速   sb_capsule_temp 显示电池温度
 *   sb_capsule_color 电量着色（充电绿 / 省电黄 / 低电红）           sb_capsule_text 文字大小倍数
 *   sb_capsule_h 玻璃长胶囊大小（高度）dp，中心对齐原子岛（不影响内容）   sb_capsule_ring 圆环高度 dp（最大 35）
 *   sb_capsule_style 圆环样式：1 圆环（底部信号点）/ 2 三层环 / 3 双侧点
 *   sb_capsule_tone auto / dark / light   sb_capsule_tint 底色浓度   sb_capsule_blur 模糊半径   sb_capsule_sat 饱和度
 *   sb_capsule_alpha 整体不透明度   sb_capsule_refr / _refr_r 折射强度 / 范围   sb_capsule_light / _light_w 边缘高光强度 / 宽度
 */
final class SbCapsule {
    private static final String TAG = "FucSuperX";
    private static final String SYSUI = "com.android.systemui";
    private static final String PSBV = "com.android.systemui.statusbar.phone.PhoneStatusBarView";
    private static final String SIC = "com.android.systemui.statusbar.phone.StatusIconContainer";
    private static final String BATTERY = "com.vivo.systemui.battery.VivoBatteryMeterView";
    private static final String CLOCK = "com.android.systemui.statusbar.policy.Clock";
    private static final String DARK = "com.android.systemui.plugins.DarkIconDispatcher";
    /** 由胶囊接管、从图标容器隐藏的槽位。其余图标（含副 Wi-Fi）保持原生显示并被胶囊包住。 */
    private static final String[] HIDE = {"network_speed", "mobile", "wifi", "airplane"};
    private static final String ACTION_DDS = "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED";
    private static final int CT = 2;
    private static final int TONE_DARK = 0x1C1C20, TONE_LIGHT = 0xF4F4F7;
    private static final int GREEN = 0xFF34C759, AMBER = 0xFFFFB800, RED = 0xFFFF3B30;
    private static final int NET_NONE = 0, NET_WIFI = 1, NET_CELL = 2;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final boolean DEBUG = false;

    private final XposedModule module;
    private volatile boolean on = true, glassOn = false, hideClock = false, showSpeed = true, showTemp = true, colorful = false;
    private volatile String tone = "auto";
    private volatile int style = 1; // 圆环样式：1 圆环 / 2 三层环 / 3 双侧点
    /** 内容（文字与圆环）区域高度 dp：固定，文字字号按它计算，与玻璃高度无关。 */
    private static final float CONTENT_DP = 40f;
    private volatile float hDp = 32, ringDp = 32, textK = 1f;
    private volatile float tint = 0f, blur = 0f, sat = 20f, alpha = 1f, refr = 300f, refrR = 24f,
            light = 0.5f, lightW = 2f;

    // 状态栏图标颜色（onDarkChanged）：内容用 fg，dark > 0.5 为深色图标（背景偏亮）
    private int fg = Color.WHITE;
    private float dark;

    private Field fIgnored, fIsland;
    private Method mIconVisible, mSlot;
    private Method mGetTint, mInAreas;
    private WeakReference<View> wPsbv = new WeakReference<>(null), wBattery = new WeakReference<>(null),
            wClock = new WeakReference<>(null);
    private WeakReference<ViewGroup> wSys = new WeakReference<>(null), wIcons = new WeakReference<>(null);
    private WeakReference<Cap> wCap = new WeakReference<>(null);
    private WeakReference<Bg> wBg = new WeakReference<>(null);
    private final Set<String> added = new HashSet<>(); // 本模块加进忽略槽位的
    private boolean hidBattery, selfSet;
    private volatile int clockWant = View.VISIBLE; // 系统最近一次要求的时钟可见性
    private final int[] locA = new int[2], locB = new int[2];

    private static Method mSetMaterial, mClear, mRegion, mAlpha, mFps, mLight, mGlass, mCustom13;

    /** 原子岛（小胶囊 / 圆点）竖直中心的屏幕 y，未知时为 NaN。由 ModuleEntry 在原子岛绘制时上报。 */
    private static volatile float islandCy = Float.NaN;
    private static volatile WeakReference<View> sBg = new WeakReference<>(null);

    static void reportIsland(float cy) {
        if (!(cy > 0) || Math.abs(cy - islandCy) < 0.5f) return;
        islandCy = cy;
        View b = sBg.get();
        if (b != null) b.post(b::invalidate); // 让状态栏重新走一次对齐
    }

    SbCapsule(XposedModule module) {
        this.module = module;
    }

    /** 该 View 是否是本模块的胶囊（SystemGlass 据此跳过，不套用系统液态玻璃倍数）。 */
    static boolean owns(Object v) {
        return v instanceof Bg || v instanceof Cap;
    }

    void install(ClassLoader cl) throws Throwable {
        load();
        Conf.subscribe(k -> {
            if (k != null && k.startsWith("sb_capsule")) { // 参数包开关切换时以代表键 sb_capsule_tint 通知
                load();
                MAIN.post(this::refresh);
            }
        });
        mSetMaterial = View.class.getMethod("setMaterial", int.class, int.class);
        mRegion = View.class.getMethod("setMaterialRegion", RectF.class, float.class);
        mClear = optMethod("clearMaterial");
        mAlpha = optMethod("setMaterialAlpha", float.class);
        mFps = optMethod("setMaterialFPS", int.class);
        mGlass = optMethod("setGlassEffectCustomized", float.class, float.class);
        mLight = optMethod("setGlassEffectInnerStrokeLight",
                int.class, float.class, float.class, float.class, float.class, int.class, float.class);
        for (Method m : View.class.getMethods()) {
            if (m.getName().equals("setMaterialCustomized") && m.getParameterCount() == 13) {
                mCustom13 = m;
                break;
            }
        }
        try {
            for (Method m : cl.loadClass(DARK).getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("getTint") && p.length == 3 && p[1] == View.class && p[2] == int.class) mGetTint = m;
                else if (m.getName().equals("isInAreas") && p.length == 2 && p[1] == View.class) mInAreas = m;
            }
        } catch (Throwable t) {
            Log.w(TAG, "sb capsule: DarkIconDispatcher helpers unavailable", t);
        }

        Class<?> sic = cl.loadClass(SIC);
        fIgnored = findField(sic, "mIgnoredSlots");
        if (fIgnored == null) throw new NoSuchFieldException("StatusIconContainer.mIgnoredSlots");
        fIsland = findField(sic, "mAtomIslandWidth"); // BlockableLinearLayout：原子岛当前宽度（以挖孔为中心）
        try {
            Class<?> disp = cl.loadClass("com.android.systemui.statusbar.StatusIconDisplayable");
            mIconVisible = disp.getMethod("isIconVisible");
            mSlot = disp.getMethod("getSlot");
        } catch (Throwable t) {
            Log.w(TAG, "sb capsule: icon helpers unavailable", t);
        }

        Method fin = cl.loadClass(PSBV).getDeclaredMethod("onFinishInflate");
        module.hook(fin).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            try {
                attach((View) chain.getThisObject());
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule attach failed", t);
            }
            return r;
        });

        // 测量前补回忽略槽位：系统 addIgnoredSlots$1 会先清空集合
        Method measure = sic.getDeclaredMethod("onMeasure", int.class, int.class);
        module.hook(measure).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object v = chain.getThisObject();
            if (v != wIcons.get()) return chain.proceed();
            syncIgnored((ViewGroup) v, false);
            return chain.proceed();
        });

        Method clockVis = cl.loadClass(CLOCK).getDeclaredMethod("setVisibility", int.class);
        module.hook(clockVis).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (chain.getThisObject() != wClock.get() || !(chain.getArg(0) instanceof Integer)) return chain.proceed();
            int want = (Integer) chain.getArg(0);
            if (!selfSet) clockWant = want;
            if (on && hideClock && want != View.GONE) return chain.proceed(new Object[]{View.GONE});
            return chain.proceed();
        });

        Method dk = cl.loadClass(BATTERY).getDeclaredMethod("onDarkChanged", ArrayList.class, float.class, int.class);
        module.hook(dk).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object r = chain.proceed();
            Cap cap = wCap.get();
            if (cap != null && chain.getThisObject() == wBattery.get()
                    && chain.getArg(1) instanceof Float && chain.getArg(2) instanceof Integer) {
                Object areas = chain.getArg(0);
                float f = (Float) chain.getArg(1);
                int c = (Integer) chain.getArg(2);
                try {
                    if (mGetTint != null) c = (Integer) mGetTint.invoke(null, areas, cap, c);
                    if (mInAreas != null && !Boolean.TRUE.equals(mInAreas.invoke(null, areas, cap))) f = 0f;
                } catch (Throwable ignored) {
                }
                setDark(c, f);
            }
            return r;
        });
        Log.i(TAG, "sb capsule hooks installed, on=" + on + " custom13=" + (mCustom13 != null)
                + " tint=" + (mGetTint != null) + "/" + (mInAreas != null));
    }

    private void load() {
        on = flag("sb_capsule", true);
        glassOn = flag("sb_capsule_bg", false);
        hideClock = flag("sb_capsule_clock_hide", false);
        showSpeed = flag("sb_capsule_speed", true);
        showTemp = flag("sb_capsule_temp", true);
        colorful = flag("sb_capsule_color", false);
        String t = Conf.get("persist.land.sb_capsule_tone", "auto").trim();
        tone = "dark".equals(t) || "light".equals(t) ? t : "auto";
        String sty = Conf.get("persist.land.sb_capsule_style", "1").trim();
        style = "2".equals(sty) ? 2 : "3".equals(sty) ? 3 : 1;
        hDp = num("sb_capsule_h", 32f, 20f, 48f);
        ringDp = num("sb_capsule_ring", 32f, 16f, 35f);
        textK = num("sb_capsule_text", 1f, 0.6f, 1.4f);
        tint = num("sb_capsule_tint", 0f, 0f, 1f);
        blur = num("sb_capsule_blur", 0f, 0f, 0.3f);
        sat = num("sb_capsule_sat", 20f, 0f, 60f);
        alpha = num("sb_capsule_alpha", 1f, 0f, 1f);
        refr = num("sb_capsule_refr", 300f, 0f, 300f);
        refrR = num("sb_capsule_refr_r", 24f, 0f, 120f);
        light = num("sb_capsule_light", 0.5f, 0f, 1f);
        lightW = num("sb_capsule_light_w", 2f, 0f, 8f);
    }

    private static boolean flag(String key, boolean def) {
        return !"0".equals(Conf.get("persist.land." + key, def ? "1" : "0").trim());
    }

    private static float num(String key, float def, float min, float max) {
        try {
            float v = Float.parseFloat(Conf.get("persist.land." + key, String.valueOf(def)).trim());
            return Float.isNaN(v) ? def : Math.max(min, Math.min(max, v));
        } catch (Throwable t) {
            return def;
        }
    }

    private boolean lightTone() {
        String t = tone;
        if ("light".equals(t)) return true;
        if ("dark".equals(t)) return false;
        return dark > 0.5f;
    }

    private void setDark(int color, float d) {
        boolean flip = (dark > 0.5f) != (d > 0.5f);
        fg = color;
        dark = d;
        Cap cap = wCap.get();
        if (cap != null) cap.invalidate();
        Bg bg = wBg.get();
        if (bg != null && flip && "auto".equals(tone)) bg.applyMaterial(true);
    }

    // ---------------- 挂载与原生图标 ----------------

    private void attach(View psbv) {
        Resources res = psbv.getResources();
        int idSys = res.getIdentifier("system_icons", "id", SYSUI);
        int idIcons = res.getIdentifier("statusIcons", "id", SYSUI);
        int idBattery = res.getIdentifier("battery", "id", SYSUI);
        int idClock = res.getIdentifier("clock", "id", SYSUI);
        View sys = idSys == 0 ? null : psbv.findViewById(idSys);
        View icons = idIcons == 0 ? null : psbv.findViewById(idIcons);
        View bat = idBattery == 0 ? null : psbv.findViewById(idBattery);
        ViewParent parent = sys == null ? null : sys.getParent();
        if (!(sys instanceof LinearLayout) || !(icons instanceof ViewGroup) || bat == null
                || !(parent instanceof RelativeLayout) || !(psbv instanceof android.widget.FrameLayout)) {
            Log.w(TAG, "sb capsule: status bar layout not recognized, sys=" + sys + " icons=" + icons
                    + " battery=" + bat + " parent=" + parent);
            return;
        }
        LinearLayout ll = (LinearLayout) sys;
        ll.setClipChildren(false); // 胶囊可以比 system_icons 高
        ll.setClipToPadding(false);
        ((RelativeLayout) parent).setClipChildren(false);

        Context ctx = psbv.getContext();
        Cap cap = new Cap(ctx);
        ll.addView(cap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(psbv, CONTENT_DP)));
        // 玻璃挂在 PhoneStatusBarView（FrameLayout）最底层、横向铺满：RelativeLayout 会把子视图高度限制在自身高度，
        // 玻璃就比内容矮。区域按窗口坐标每帧计算，竖直方向与内容同一个中心
        Bg bg = new Bg(ctx);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(psbv, hDp), android.view.Gravity.TOP);
        ((ViewGroup) psbv).addView(bg, 0, lp);

        wPsbv = new WeakReference<>(psbv);
        wSys = new WeakReference<>(ll);
        wIcons = new WeakReference<>((ViewGroup) icons);
        wBattery = new WeakReference<>(bat);
        wClock = new WeakReference<>(idClock == 0 ? null : psbv.findViewById(idClock));
        wCap = new WeakReference<>(cap);
        wBg = new WeakReference<>(bg);
        sBg = new WeakReference<>(bg);
        refresh();
        Log.i(TAG, "sb capsule attached");
    }

    /** 配置变化或挂载后：可见性与间距、原生图标隐藏 / 还原、材质与重绘。主线程。 */
    private void refresh() {
        Cap cap = wCap.get();
        Bg bg = wBg.get();
        ViewGroup icons = wIcons.get();
        if (cap == null || bg == null || icons == null) return;
        cap.setVisibility(on ? View.VISIBLE : View.GONE);
        bg.setVisibility(on ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams p = cap.getLayoutParams();
        if (p instanceof LinearLayout.LayoutParams) {
            int m = 0; // 与其他图标紧挨，间距由内容自身的左留白提供
            if (((LinearLayout.LayoutParams) p).getMarginStart() != m) {
                ((LinearLayout.LayoutParams) p).setMarginStart(m);
                cap.setLayoutParams(p);
            }
        }
        syncHeight();
        syncBattery();
        View clock = wClock.get();
        if (clock != null) {
            selfSet = true;
            try {
                clock.setVisibility(on && hideClock ? View.GONE : clockWant);
            } finally {
                selfSet = false;
            }
        }
        syncIgnored(icons, true);
        cap.reload();
        bg.applyMaterial(true);
    }

    /** 玻璃长胶囊高度（px）：设定值，最多到状态栏高度减上下各 1dp。 */
    private int capPx() {
        return fitPx(hDp);
    }

    /** 内容（文字与圆环）高度（px）：固定 CONTENT_DP，最多到状态栏高度减上下各 1dp。 */
    private int contentPx() {
        return fitPx(CONTENT_DP);
    }

    private int fitPx(float dp) {
        View sys = wSys.get(), ps = wPsbv.get();
        if (sys == null) return 0;
        int want = px(sys, dp);
        if (ps == null || ps.getHeight() == 0) return want;
        return Math.max(px(sys, 16), Math.min(want, ps.getHeight() - 2 * px(sys, 1)));
    }

    /**
     * 竖直位置（中心对齐原子岛，还没见过原子岛时对齐图标行）：胶囊中心尽量与图标行中心一致；图标行偏下（顶部留给挖孔）时胶囊放不下，
     * 就整体上移到正好贴着状态栏下沿。内容与玻璃用同一个目标中心，每帧校正。
     */
    private void alignAll() {
        Cap cap = wCap.get();
        Bg bg = wBg.get();
        View sys = wSys.get(), ps = wPsbv.get();
        if (cap == null || bg == null || sys == null || ps == null || !sys.isAttachedToWindow()) return;
        if (cap.getHeight() <= 0 || bg.getHeight() <= 0) return;
        sys.getLocationOnScreen(locA);
        ps.getLocationOnScreen(locB);
        float isl = islandCy;
        float c = Float.isNaN(isl) ? locA[1] + sys.getHeight() / 2f : isl;
        // 玻璃：中心对齐原子岛，放不下时收进状态栏（上下各留 1dp）
        int m = px(sys, 1), h = bg.getHeight();
        float lo = locB[1] + m + h / 2f, hi = locB[1] + ps.getHeight() - m - h / 2f;
        alignY(bg, lo > hi ? locB[1] + ps.getHeight() / 2f : Math.max(lo, Math.min(hi, c)));
        // 内容：同一个中心，但整体收进状态栏，否则圆环底部的信号点会被窗口裁掉
        int hc = cap.getHeight();
        float clo = locB[1] + hc / 2f, chi = locB[1] + ps.getHeight() - hc / 2f;
        alignY(cap, clo > chi ? locB[1] + ps.getHeight() / 2f : Math.max(clo, Math.min(chi, c)));
    }

    private void alignY(View v, float target) {
        if (!(v.getParent() instanceof View)) return;
        ((View) v.getParent()).getLocationOnScreen(locA);
        float ty = target - (locA[1] + v.getTop() + v.getHeight() / 2f);
        if (Math.abs(v.getTranslationY() - ty) > 0.5f) v.setTranslationY(ty);
    }

    /** 两个视图的布局高度跟随 capPx，玻璃用负外边距抵消状态栏左右内边距以铺满全宽；有变化时返回 true（本帧跳过绘制）。 */
    private boolean syncHeight() {
        int h = capPx();
        if (h <= 0) return false;
        boolean changed = false;
        Bg bg = wBg.get();
        View ps = wPsbv.get();
        if (bg != null && ps != null && bg.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mp = (ViewGroup.MarginLayoutParams) bg.getLayoutParams();
            int l = -ps.getPaddingLeft(), r = -ps.getPaddingRight();
            if (mp.leftMargin != l || mp.rightMargin != r) {
                mp.leftMargin = l;
                mp.rightMargin = r;
                bg.setLayoutParams(mp);
                changed = true;
            }
        }
        View[] vs = {wCap.get(), wBg.get()};
        int[] hs = {contentPx(), h}; // 内容高度固定，玻璃高度可调
        for (int i = 0; i < vs.length; i++) {
            View v = vs[i];
            if (v == null) continue;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != hs[i]) {
                lp.height = hs[i];
                v.setLayoutParams(lp);
                changed = true;
            }
        }
        return changed;
    }

    private void syncBattery() {
        View bat = wBattery.get();
        if (bat == null) return;
        if (on) {
            if (bat.getVisibility() != View.GONE) {
                bat.setVisibility(View.GONE);
                hidBattery = true;
            }
        } else if (hidBattery) {
            bat.setVisibility(View.VISIBLE);
            hidBattery = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void syncIgnored(ViewGroup icons, boolean relayout) {
        Set<String> set;
        try {
            set = (Set<String>) fIgnored.get(icons);
        } catch (Throwable t) {
            return;
        }
        if (set == null) return;
        boolean changed = false;
        if (on) {
            for (String s : HIDE) {
                if (set.add(s)) {
                    added.add(s);
                    changed = true;
                }
            }
        } else if (!added.isEmpty()) {
            for (String s : added) changed |= set.remove(s);
            added.clear();
        }
        if (changed && relayout) icons.requestLayout();
    }

    private static int px(View v, float dp) {
        return Math.round(dp * v.getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int c, float a) {
        return (Math.round(Color.alpha(c) * a) << 24) | (c & 0xFFFFFF);
    }

    // ---------------- 玻璃背景 ----------------

    /** 垫在整个状态栏下面的玻璃长胶囊（横向铺满屏幕，左右各留 6dp，竖直中心对齐原子岛）。 */
    private final class Bg extends View {
        private final RectF region = new RectF();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean material, applied;
        private float lastAlpha = -1f;
        private final ViewTreeObserver.OnPreDrawListener pre = () -> {
            if (!on) return true;
            syncBattery();
            if (syncHeight()) return false;
            alignAll();
            update();
            return true;
        };

        Bg(Context c) {
            super(c);
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(pre);
            applied = false;
            post(() -> applyMaterial(true));
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(pre);
            applied = false;
            material = false;
            super.onDetachedFromWindow();
        }

        /** 打开系统玻璃管线后用本模块的参数重写全部观感。 */
        void applyMaterial(boolean force) {
            if (!on || !glassOn || !isAttachedToWindow()) {
                if (applied && mClear != null) {
                    try {
                        mClear.invoke(this);
                    } catch (Throwable ignored) {
                    }
                }
                applied = false;
                material = false;
                return;
            }
            if (applied && !force) return;
            boolean lt = lightTone();
            try {
                material = Boolean.TRUE.equals(mSetMaterial.invoke(this, lt ? 409 : 411, CT));
            } catch (Throwable t) {
                material = false;
                Log.w(TAG, "sb capsule setMaterial failed", t);
            }
            applied = true;
            if (material) {
                Display d = getDisplay();
                tryInvoke(mFps, d == null ? 120 : Math.max(30, Math.min(144, Math.round(d.getMode().getRefreshRate()))));
                tryInvoke(mGlass, refr, refrR);
                tryInvoke(mLight, -1, light, 0f, -1f, -1f, Math.round(lightW), 0.8f);
                tryInvoke(mCustom13, blur, sat, lt ? TONE_LIGHT : TONE_DARK, tint, 0,
                        0x000000, 0f, 0, 0xFFFFFF, 0f, 0, alpha, 0); // 染色层 1 为底色，层 2 / 3 关闭
                pushRegion();
                lastAlpha = -1f;
            }
            Log.i(TAG, "sb capsule material light=" + lt + " ok=" + material);
            invalidate();
        }

        private void tryInvoke(Method m, Object... args) {
            if (m == null) return;
            try {
                m.invoke(this, args);
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule " + m.getName() + " failed", t);
            }
        }

        private void pushRegion() {
            if (!material || region.isEmpty()) return;
            tryInvoke(mRegion, new RectF(region), region.height() / 2f);
        }

        /** 自身、system_icons 与自绘内容的累计透明度：下拉面板、原子岛等让状态栏图标淡出时胶囊跟着淡出。 */
        private float effAlpha(ViewGroup sys, Cap cap) {
            float a = 1f;
            for (View v = cap; v != null; ) {
                a *= v.getAlpha();
                if (v.getVisibility() != VISIBLE) return 0f;
                ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
            return a;
        }

        private final int[] wa = new int[2], wb = new int[2];

        /** 视图左端相对本视图的 x（窗口坐标差，含所有位移动画）。 */
        private float xOf(View v) {
            v.getLocationInWindow(wb);
            return wb[0] - wa[0];
        }

        /**
         * 每帧：玻璃区域为整个视图宽度（左右各留 6dp）的长胶囊。
         * 原子岛展开到胶囊内容左端时，把内容整体向右推（超出屏幕的部分被裁掉），像被原子岛挤出去；不缩放文字。
         */
        void update() {
            ViewGroup sys = wSys.get();
            Cap cap = wCap.get();
            if (sys == null || cap == null || cap.visW <= 0) return;
            getLocationInWindow(wa);
            float capL0 = xOf(cap) - cap.getTranslationX(); // 布局位置，不含本模块的右移
            float isl = islandRight();
            float push = isl == -Float.MAX_VALUE ? 0f : Math.max(0f, isl - capL0 - cap.contentLeft());
            if (Math.abs(cap.getTranslationX() - push) > 0.5f) cap.setTranslationX(push);
            float m = px(this, 6);
            RectF n = new RectF(m, 0, Math.max(m, getWidth() - m), getHeight());
            float a = effAlpha(sys, cap);
            if (!n.equals(region) || Math.abs(a - lastAlpha) > 0.004f) diag(n, a, sys, cap);
            if (!n.equals(region)) {
                region.set(n);
                pushRegion();
                invalidate();
            }
            if (material) {
                if (getAlpha() != 1f) setAlpha(1f);
                if (Math.abs(a - lastAlpha) > 0.004f) {
                    lastAlpha = a;
                    tryInvoke(mAlpha, a);
                }
            } else if (Math.abs(getAlpha() - a) > 0.004f) {
                setAlpha(a);
            }
        }

        /** 原子岛右沿（本视图坐标，含 2dp 间距）；没有原子岛时为负无穷。原子岛以屏幕水平中心（挖孔）为中心。 */
        private float islandRight() {
            ViewGroup icons = wIcons.get();
            if (fIsland == null || icons == null) return -Float.MAX_VALUE;
            float w;
            try {
                w = ((Number) fIsland.get(icons)).floatValue();
            } catch (Throwable t) {
                return -Float.MAX_VALUE;
            }
            if (!(w > 0)) return -Float.MAX_VALUE;
            getLocationOnScreen(locB);
            return getResources().getDisplayMetrics().widthPixels / 2f + w / 2f + px(this, 2) - locB[0];
        }

        private long diagT;
        private int diagN;

        private void diag(RectF n, float a, ViewGroup sys, Cap cap) {
            long t = SystemClock.uptimeMillis();
            if (t - diagT > 1000) {
                diagT = t;
                diagN = 0;
            }
            if (++diagN > 4) return;
            if (!DEBUG) return;
            int[] o = new int[2];
            getLocationOnScreen(o);
            StringBuilder ib = new StringBuilder();
            ViewGroup ic = wIcons.get();
            if (ic != null) {
                ib.append(" icons=").append(ic.getLeft()).append('/').append(ic.getWidth()).append(" vis=").append(ic.getVisibility());
                for (int i = 0; i < ic.getChildCount(); i++) {
                    View ch = ic.getChildAt(i);
                    String sl = "?";
                    boolean iv = false;
                    try {
                        sl = String.valueOf(mSlot.invoke(ch));
                        iv = Boolean.TRUE.equals(mIconVisible.invoke(ch));
                    } catch (Throwable ignored) {
                    }
                    if (!iv && ch.getVisibility() != VISIBLE) continue;
                    ib.append(" [").append(sl).append(" iv=").append(iv).append(" v=").append(ch.getVisibility())
                            .append(" x=").append(Math.round(ch.getX())).append(" w=").append(ch.getWidth())
                            .append(" mw=").append(ch.getMeasuredWidth()).append(" a=").append(ch.getAlpha()).append(']');
                }
            }
            Log.i(TAG, "sb capsule bg" + ib + " region=" + n.toShortString() + " a=" + a + " mat=" + material
                    + " bg=" + o[0] + "," + o[1] + " " + getWidth() + "x" + getHeight() + " vis=" + getVisibility()
                    + " sys=" + sys.getLeft() + "/" + sys.getWidth() + "x" + sys.getHeight() + " a=" + sys.getAlpha()
                    + " cap=" + cap.getLeft() + "/" + cap.getWidth() + "x" + cap.getHeight() + " tx=" + cap.getTranslationX());
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!on || !glassOn || material || region.isEmpty()) return;
            int a = Math.round(255 * Math.min(1f, tint * alpha + 0.15f)); // 材质不可用时的兜底底色
            fill.setColor((a << 24) | (lightTone() ? TONE_LIGHT : TONE_DARK));
            float rad = region.height() / 2f;
            c.drawRoundRect(region, rad, rad, fill);
        }
    }

    // ---------------- 自绘内容 ----------------

    private final class Cap extends View {
        private final Paint pTime = new Paint(Paint.ANTI_ALIAS_FLAG), pSmall = new Paint(Paint.ANTI_ALIAS_FLAG),
                pNum = new Paint(Paint.ANTI_ALIAS_FLAG), pType = new Paint(Paint.ANTI_ALIAS_FLAG),
                pStroke = new Paint(Paint.ANTI_ALIAS_FLAG), pFill = new Paint(Paint.ANTI_ALIAS_FLAG),
                pClear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds = new Rect();
        private final RectF oval = new RectF();
        private final Path path = new Path();

        // 显示的数据
        private String time = "", speed = "0 B/s", temp = "";
        private int level = -1;
        private boolean charging, quick, powerSave, airplane;
        private int net = NET_NONE, wifiArcs = 3, dds = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        private final List<Sim> sims = new ArrayList<>();
        // 所有已连接的 Wi-Fi 网络（双 WLAN 时为两个）：网络 → 信号格数；副 Wi-Fi 为默认网络以外的那个
        private final java.util.Map<Network, Integer> wifiNets = new java.util.HashMap<>();
        private Network defNet;
        private int wifi2 = -1; // 副 Wi-Fi 信号格数，没有副 Wi-Fi 时为 -1

        // 几何（本视图坐标）。左侧文字区自然宽度 Lw（含内边距与到圆环的间距），圆环区宽度 Rw（不缩放）
        private float H, padL, leftW, gapL, sw, r, ringPadL, ringPadR, Lw, Rw, capTime, capSmall, capNum;
        float visW;            // 实际宽度 = Rw + Lw（始终按自然宽度，不缩放）

        /** 第一个可见内容（文字，没有文字时为圆环）左端 x（本视图坐标）：原子岛挤压时贴着它推。 */
        float contentLeft() {
            return Lw > 0 ? padL : ringPadL;
        }

        /** 圆环中心 x（本视图坐标）。 */
        float ringCx() {
            return visW - ringPadR - sw / 2f - r;
        }

        // 网速采样
        private long lastBytes = -1, lastT;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                sampleSpeed();
                if (isAttachedToWindow()) postDelayed(this, 1000);
            }
        };

        private final BroadcastReceiver rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (a == null) return;
                switch (a) {
                    case Intent.ACTION_BATTERY_CHANGED:
                        onBattery(i);
                        break;
                    case Intent.ACTION_TIME_TICK:
                    case Intent.ACTION_TIME_CHANGED:
                    case Intent.ACTION_TIMEZONE_CHANGED:
                        updateTime();
                        break;
                    case WifiManager.RSSI_CHANGED_ACTION:
                        onRssi(i.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Integer.MIN_VALUE));
                        break;
                    case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                        PowerManager pm = c.getSystemService(PowerManager.class);
                        powerSave = pm != null && pm.isPowerSaveMode();
                        invalidate();
                        break;
                    case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                        readAirplane();
                        break;
                    case ACTION_DDS:
                        dds = SubscriptionManager.getDefaultDataSubscriptionId();
                        invalidate();
                        break;
                    default:
                        break;
                }
            }
        };

        private final ConnectivityManager.NetworkCallback netCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                defNet = n;
                onCaps(nc);
                updateWifi2();
            }

            @Override
            public void onLost(Network n) {
                defNet = null;
                onCaps(null);
                updateWifi2();
            }
        };

        /** 监听所有 Wi-Fi 网络（含双 WLAN 的第二个），用来画副 Wi-Fi。 */
        private final ConnectivityManager.NetworkCallback wifiCb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                Integer old = wifiNets.get(n);
                int a = old == null ? 3 : old;
                if (nc.getTransportInfo() instanceof WifiInfo) {
                    int rssi = ((WifiInfo) nc.getTransportInfo()).getRssi();
                    if (rssi != Integer.MIN_VALUE && rssi > -127) a = arcsFor(rssi);
                }
                wifiNets.put(n, a);
                updateWifi2();
            }

            @Override
            public void onLost(Network n) {
                wifiNets.remove(n);
                updateWifi2();
            }
        };

        private void updateWifi2() {
            int w = -1;
            if (wifiNets.size() >= 2) {
                for (java.util.Map.Entry<Network, Integer> en : wifiNets.entrySet()) {
                    if (!en.getKey().equals(defNet)) {
                        w = en.getValue();
                        break;
                    }
                }
            }
            if (w != wifi2) {
                wifi2 = w;
                if (style != 1) invalidate();
            }
        }

        private final SubscriptionManager.OnSubscriptionsChangedListener subL =
                new SubscriptionManager.OnSubscriptionsChangedListener() {
                    @Override
                    public void onSubscriptionsChanged() {
                        rebuildSims();
                    }
                };

        /** 一张卡：信号等级、是否有服务、网络制式。回调在主线程。 */
        private final class Sim extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener {
            final int subId;
            final TelephonyManager tm;
            int lv;
            boolean inService = true;
            String type = "";

            Sim(int subId, TelephonyManager tm) {
                this.subId = subId;
                this.tm = tm;
            }

            @Override
            public void onSignalStrengthsChanged(SignalStrength s) {
                lv = Math.max(0, Math.min(4, s.getLevel()));
                invalidate();
            }

            @Override
            public void onServiceStateChanged(ServiceState ss) {
                inService = ss.getState() == ServiceState.STATE_IN_SERVICE;
                invalidate();
            }

            @Override
            public void onDisplayInfoChanged(TelephonyDisplayInfo d) {
                type = typeName(d);
                invalidate();
            }
        }

        Cap(Context c) {
            super(c);
            setWillNotDraw(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setClickable(false);
            setFocusable(false);
            Typeface base = Typeface.create("sans-serif", Typeface.NORMAL);
            pTime.setTypeface(Typeface.create(base, 500, false));
            pSmall.setTypeface(Typeface.create(base, 600, false));
            pNum.setTypeface(Typeface.create(base, 700, false));
            pType.setTypeface(Typeface.create(base, 700, false));
            for (Paint p : new Paint[]{pTime, pSmall, pNum}) p.setFontFeatureSettings("tnum");
            pTime.setTextAlign(Paint.Align.CENTER);
            pNum.setTextAlign(Paint.Align.LEFT);
            pType.setTextAlign(Paint.Align.CENTER);
            pStroke.setStyle(Paint.Style.STROKE);
            pStroke.setStrokeCap(Paint.Cap.ROUND);
            pFill.setStyle(Paint.Style.FILL);
            pClear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            pClear.setStyle(Paint.Style.FILL_AND_STROKE);
            pClear.setStrokeJoin(Paint.Join.ROUND);
        }

        // ---------- 生命周期与数据源 ----------

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Context c = getContext();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(WifiManager.RSSI_CHANGED_ACTION);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            f.addAction(ACTION_DDS);
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            readAirplane();
            try {
                Intent sticky = Build.VERSION.SDK_INT >= 33
                        ? c.registerReceiver(rx, f, null, MAIN, Context.RECEIVER_EXPORTED)
                        : c.registerReceiver(rx, f, null, MAIN);
                if (sticky != null) onBattery(sticky);
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule receiver failed", t);
            }
            try {
                PowerManager pm = c.getSystemService(PowerManager.class);
                powerSave = pm != null && pm.isPowerSaveMode();
            } catch (Throwable ignored) {
            }
            try {
                c.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(netCb, MAIN);
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule network callback failed", t);
            }
            registerWifi(c);
            try {
                c.getSystemService(SubscriptionManager.class).addOnSubscriptionsChangedListener(c.getMainExecutor(), subL);
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule subscription listener failed", t);
            }
            dds = SubscriptionManager.getDefaultDataSubscriptionId();
            rebuildSims();
            updateTime();
            lastBytes = -1;
            removeCallbacks(tick);
            post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            Context c = getContext();
            try {
                c.unregisterReceiver(rx);
            } catch (Throwable ignored) {
            }
            try {
                c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb);
            } catch (Throwable ignored) {
            }
            try {
                c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiCb);
            } catch (Throwable ignored) {
            }
            wifiNets.clear();
            wifi2 = -1;
            try {
                c.getSystemService(SubscriptionManager.class).removeOnSubscriptionsChangedListener(subL);
            } catch (Throwable ignored) {
            }
            for (Sim s : sims) unregister(s);
            sims.clear();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onConfigurationChanged(Configuration cfg) {
            super.onConfigurationChanged(cfg);
            updateTime();
            requestLayout();
        }

        /** 双 WLAN 的第二个网络可能没有默认能力（如受限），先去掉默认能力再按 Wi-Fi 监听，失败则用默认请求。 */
        private void registerWifi(Context c) {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            try {
                android.net.NetworkRequest req = new android.net.NetworkRequest.Builder().clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
                cm.registerNetworkCallback(req, wifiCb, MAIN);
                return;
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule wifi listen (all) failed, fallback", t);
            }
            try {
                cm.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb, MAIN);
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule wifi listen failed", t);
            }
        }

        private void readAirplane() {
            boolean a;
            try {
                a = android.provider.Settings.Global.getInt(getContext().getContentResolver(),
                        android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
            } catch (Throwable t) {
                a = false;
            }
            if (a != airplane) {
                airplane = a;
                invalidate();
            }
        }

        void reload() {
            lastBytes = -1;
            updateTime();
            requestLayout();
            invalidate();
        }

        private void onBattery(Intent i) {
            int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            int engine = i.getIntExtra("engine", 0); // vivo：1 / 3 为快充（与锁屏 BatteryStatus.isQuickCharging 一致）
            level = lv < 0 || sc <= 0 ? -1 : Math.round(lv * 100f / sc);
            charging = plugged && (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
            quick = charging && (engine == 1 || engine == 3);
            temp = t == Integer.MIN_VALUE ? "" : String.format(Locale.US, "%.1f°C", t / 10f);
            invalidate();
        }

        private void updateTime() {
            String s = android.text.format.DateFormat.format(
                    android.text.format.DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm",
                    System.currentTimeMillis()).toString();
            if (!s.equals(time)) {
                time = s;
                invalidate();
            }
        }

        private void onCaps(NetworkCapabilities nc) {
            int n = NET_NONE;
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    n = NET_WIFI;
                    int rssi = Integer.MIN_VALUE;
                    if (nc.getTransportInfo() instanceof WifiInfo) rssi = ((WifiInfo) nc.getTransportInfo()).getRssi();
                    if (rssi != Integer.MIN_VALUE && rssi > -127) wifiArcs = arcsFor(rssi);
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    n = NET_CELL;
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    n = NET_WIFI;
                    wifiArcs = 3;
                }
            }
            if (n != net) {
                net = n;
                dds = SubscriptionManager.getDefaultDataSubscriptionId();
            }
            invalidate();
        }

        private void onRssi(int rssi) {
            if (rssi == Integer.MIN_VALUE || rssi <= -127) return;
            int a = arcsFor(rssi);
            if (a != wifiArcs) {
                wifiArcs = a;
                if (net == NET_WIFI) invalidate();
            }
        }

        private int arcsFor(int rssi) {
            try {
                WifiManager wm = getContext().getSystemService(WifiManager.class);
                int max = wm.getMaxSignalLevel();
                int lv = wm.calculateSignalLevel(rssi);
                return max <= 0 ? 3 : Math.max(1, Math.min(3, Math.round(lv * 3f / max)));
            } catch (Throwable t) {
                return rssi >= -60 ? 3 : rssi >= -75 ? 2 : 1;
            }
        }

        private void rebuildSims() {
            List<Integer> ids = new ArrayList<>();
            try {
                List<SubscriptionInfo> l = getContext().getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
                if (l != null) {
                    List<SubscriptionInfo> s = new ArrayList<>(l);
                    s.sort((a, b) -> Integer.compare(a.getSimSlotIndex(), b.getSimSlotIndex()));
                    for (SubscriptionInfo si : s) if (ids.size() < 2) ids.add(si.getSubscriptionId());
                }
            } catch (Throwable t) {
                Log.w(TAG, "sb capsule subscriptions failed", t);
            }
            boolean same = ids.size() == sims.size();
            for (int i = 0; same && i < ids.size(); i++) same = ids.get(i) == sims.get(i).subId;
            dds = SubscriptionManager.getDefaultDataSubscriptionId();
            if (same) return;
            for (Sim s : sims) unregister(s);
            sims.clear();
            TelephonyManager base = getContext().getSystemService(TelephonyManager.class);
            for (int id : ids) {
                try {
                    TelephonyManager tm = base.createForSubscriptionId(id);
                    Sim s = new Sim(id, tm);
                    tm.registerTelephonyCallback(getContext().getMainExecutor(), s);
                    sims.add(s);
                } catch (Throwable t) {
                    Log.w(TAG, "sb capsule telephony callback failed for sub " + id, t);
                }
            }
            Log.i(TAG, "sb capsule sims=" + ids + " dds=" + dds);
            invalidate();
        }

        private void unregister(Sim s) {
            try {
                s.tm.unregisterTelephonyCallback(s);
            } catch (Throwable ignored) {
            }
        }

        private void sampleSpeed() {
            if (!on || !showSpeed) return;
            Display d = getDisplay();
            if (d == null || d.getState() != Display.STATE_ON || !isShown()) {
                lastBytes = -1;
                return;
            }
            long b = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes();
            long t = SystemClock.elapsedRealtime();
            if (lastBytes >= 0 && b >= lastBytes && t > lastT) {
                String s = fmtSpeed((b - lastBytes) * 1000.0 / (t - lastT));
                if (!s.equals(speed)) {
                    speed = s;
                    invalidate();
                }
            }
            lastBytes = b;
            lastT = t;
        }

        // ---------- 几何 ----------

        private float cap(Paint p) {
            p.getTextBounds("8", 0, 1, bounds);
            return bounds.height();
        }

        private boolean showTime() {
            return hideClock; // 左侧保留系统时钟时胶囊里不再显示时间
        }

        private boolean topRow() {
            return showSpeed || showTemp;
        }

        private float rowGap() {
            return H * 0.1f;
        }

        /** 左侧各行的总高度。 */
        private float stackH() {
            if (showTime()) return (topRow() ? capSmall + rowGap() : 0) + capTime;
            if (showSpeed && showTemp) return 2 * capSmall + rowGap();
            return topRow() ? capSmall : 0;
        }

        /**
         * 按内容高度（固定，与玻璃高度无关）计算字号与各区宽度；圆环按独立的圆环高度。
         * 宽度只取决于预留的最宽字形，数据变化不会改变胶囊宽度。
         */
        private void geom(int h) {
            H = h;
            float k = textK;
            boolean tm = showTime(), both = showSpeed && showTemp;
            pTime.setTextSize(H * (topRow() ? 0.5f : 0.6f) * k);
            pSmall.setTextSize(H * (tm ? 0.23f : both ? 0.27f : 0.32f) * k);
            // 圆环（含顶部电量数字、线宽、信号点）只随圆环高度缩放
            float D = Math.min(px(this, ringDp), H);
            pNum.setTextSize(D * 0.245f);
            capTime = cap(pTime);
            capSmall = cap(pSmall);
            capNum = cap(pNum);
            float maxStack = H * (tm ? (topRow() ? 0.74f : 0.62f) : both ? 0.7f : 0.42f), stack = stackH();
            if (stack > maxStack) {
                float f = maxStack / stack;
                pTime.setTextSize(pTime.getTextSize() * f);
                pSmall.setTextSize(pSmall.getTextSize() * f);
                capTime = cap(pTime);
                capSmall = cap(pSmall);
            }
            if (capNum > D * 0.22f) {
                pNum.setTextSize(pNum.getTextSize() * D * 0.22f / capNum);
                capNum = cap(pNum);
            }
            float timeW = tm ? pTime.measureText("88:88") : 0;
            float speedW = showSpeed ? pSmall.measureText("888 KB/s") : 0;
            float tempW = showTemp ? pSmall.measureText("88.8°C") : 0;
            float topW = tm ? speedW + tempW + (both ? H * 0.12f : 0) : Math.max(speedW, tempW);
            leftW = Math.max(timeW, topW);
            boolean empty = leftW <= 0;
            // 左侧其他图标 / 原子岛、文字、圆环之间几乎紧挨
            padL = empty ? 0 : H * 0.1f;
            gapL = empty ? 0 : H * 0.06f;
            sw = D * 0.068f;
            // 数字压在圆环顶上：数字上沿不超出内容区
            r = Math.min(D / 2f - sw / 2f, H / 2f - capNum * 0.56f);
            ringPadR = H * 0.12f; // 圆环右边的留白
            ringPadL = empty ? H * 0.1f : 0;
            Rw = ringPadL + 2 * r + sw + ringPadR;
            Lw = empty ? 0 : padL + leftW + gapL;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int h = MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED ? px(this, CONTENT_DP) : MeasureSpec.getSize(hSpec);
            if (!on) {
                setMeasuredDimension(0, h);
                return;
            }
            geom(h);
            // 始终按自然宽度：其他图标（蓝牙、闹钟等）放不下时由系统图标容器自己收起，胶囊内容不缩放
            visW = Rw + Lw;
            if (DEBUG) Log.i(TAG, "sb capsule measure Lw=" + Lw + " Rw=" + Rw);
            setMeasuredDimension((int) Math.ceil(visW), h);
        }

        // ---------- 绘制 ----------

        @Override
        protected void onDraw(Canvas c) {
            if (!on || H <= 0) return;
            int sc = c.saveLayer(0, 0, getWidth(), getHeight(), null);
            if (Lw > 0) drawLeft(c);
            drawRing(c, withAlpha(fg, 0.3f));
            c.restoreToCount(sc);
        }

        /** 左侧文字区（本地坐标：0 ~ Lw）。 */
        private void drawLeft(Canvas c) {
            pTime.setColor(fg);
            pSmall.setColor(fg);
            // 全部靠右对齐，紧贴圆环
            float x1 = padL + leftW;
            float y = (H - stackH()) / 2f;
            pSmall.setTextAlign(Paint.Align.RIGHT);
            pTime.setTextAlign(Paint.Align.RIGHT);
            if (!showTime()) { // 只有网速 / 温度：上下两行（或一行）
                if (showSpeed) {
                    y += capSmall;
                    c.drawText(fitted(speed, leftW), x1, y, pSmall);
                    pSmall.setTextSize(pSmallSize);
                    y += rowGap();
                }
                if (showTemp) c.drawText(temp, x1, y + capSmall, pSmall);
                return;
            }
            if (topRow()) {
                float base = y + capSmall;
                if (showSpeed && showTemp) {
                    // 温度贴右；网速紧挨在温度左边，字符串比预留的更宽（如 8.88 MB/s）时只压缩网速
                    float tw = pSmall.measureText(temp), g = H * 0.12f;
                    c.drawText(temp, x1, base, pSmall);
                    c.drawText(fitted(speed, leftW - tw - g), x1 - tw - g, base, pSmall);
                    pSmall.setTextSize(pSmallSize);
                } else {
                    c.drawText(showSpeed ? fitted(speed, leftW) : temp, x1, base, pSmall);
                    pSmall.setTextSize(pSmallSize);
                }
                y = base + rowGap();
            }
            c.drawText(time, x1, y + capTime, pTime);
        }

        private float pSmallSize;

        /** 文字超出 room 时临时缩小 pSmall 字号（调用方画完后恢复为 pSmallSize）。 */
        private String fitted(String s, float room) {
            pSmallSize = pSmall.getTextSize();
            float w = pSmall.measureText(s);
            if (w > room && room > 0) pSmall.setTextSize(pSmallSize * room / w);
            return s;
        }

        private int litColor() {
            if (!colorful) return fg;
            if (charging) return GREEN;
            if (powerSave) return AMBER;
            if (level >= 0 && level <= 20) return RED;
            return fg;
        }

        /** 圆环样式：1 圆环（底部信号点）/ 2 三层环（电量环内两圈信号环）/ 3 双侧点（信号点分列两侧）。 */
        private void drawRing(Canvas c, int track) {
            if (style == 2) drawRing2(c, track);
            else if (style == 3) drawRing3(c, track);
            else drawRing1(c, track);
        }

        /** 当前数据卡的网络制式（4G / 5G 等），没有时为空串。 */
        private String cellType() {
            String t = "";
            for (Sim m : sims) if (m.subId == dds) t = m.type;
            if (t.isEmpty() && !sims.isEmpty()) t = sims.get(0).type;
            return t;
        }

        /** 只有连着 Wi-Fi 时才显示副 Wi-Fi。 */
        private boolean dualWifi() {
            return net == NET_WIFI && wifi2 >= 0;
        }

        private void fillPlane(Canvas c, float x, float y, float w, float h) {
            planePath(x, y, w, h);
            pFill.setColor(fg);
            c.drawPath(path, pFill);
        }

        /**
         * 样式 2：第一层电量环（15° 起顺时针经底部到 230°），里面第二 / 三层为卡 1 / 卡 2 的信号环（84°~219°，每环 4 段，
         * 自下而上 1→4 格）；主图标在圆心右上，副 Wi-Fi 压在右上开口。尺寸见 docs/design/ring_style2_t4。
         */
        private void drawRing2(Canvas c, int track) {
            float cx = ringCx(), cy = H / 2f, D = 2f * r + sw;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            pStroke.setStrokeWidth(sw);
            float s = 15f, e = 230f;
            arc(c, s, e, track);
            if (level > 0) arc(c, s, s + (e - s) * Math.min(1f, level / 100f), litColor());
            drawNumber(c, cx, cy - r);

            float iy = cy - D * 0.026f;
            if (net == NET_WIFI) {
                wifiAt(c, cx + D * 0.152f, iy, D * 0.44f, 0.18f, wifiArcs, track);
            } else if (net == NET_CELL) {
                String t = cellType();
                if (!t.isEmpty()) typeAt(c, t, cx + D * 0.126f, iy, D * 0.429f, 0.92f, D * 0.5f);
            } else if (airplane) {
                float w = D * 0.535f, h = D * 0.48f;
                fillPlane(c, cx + D * 0.029f - w / 2f, cy - D * 0.032f - h / 2f, w, h);
            } // Wi-Fi 与移动数据都没有连接：留空
            if (dualWifi()) wifiAt(c, cx + D * 0.35f, cy - D * 0.33f, D * 0.15f, 0.18f, wifi2, track);

            if (airplane) { // 飞行模式不画信号环；连着 Wi-Fi 时小飞机放在左下
                if (net == NET_WIFI) {
                    float ps = D * 0.24f;
                    fillPlane(c, cx - D * 0.165f - ps / 2f, cy + D * 0.171f - ps / 2f, ps, ps);
                }
                return;
            }
            float ssw = sw * 0.85f, gap = sw * 0.35f;
            float r1 = r - sw / 2f - gap - ssw / 2f, r2 = r1 - ssw - gap;
            pStroke.setStrokeWidth(ssw);
            for (int i = 0; i < Math.min(2, sims.size()); i++) {
                Sim m = sims.get(i);
                float rr = i == 0 ? r1 : r2;
                int lv = m.inService ? m.lv : 0;
                oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
                float g = (float) Math.toDegrees(ssw * 1.6f / rr); // 段间可见空隙 0.6 × 线宽（圆头各伸出半个线宽）
                float seg = (219f - 84f - 3f * g) / 4f;
                for (int k = 0; k < 4; k++) {
                    float a0 = 84f + k * (seg + g);
                    arc(c, a0, a0 + seg, k < lv ? fg : track);
                }
            }
            pStroke.setStrokeWidth(sw);
        }

        /**
         * 样式 3：电量圆环只在顶部为电量数字留缺口；两组信号点排成左右对称的括号形（左卡 1、右卡 2）。
         * 双 Wi-Fi 时两个图标整体居中；飞行模式 + Wi-Fi 对角对称；飞行模式 + 双 Wi-Fi 三角排列。尺寸见 docs/design/ring_style3_t4。
         */
        private void drawRing3(Canvas c, int track) {
            float cx = ringCx(), cy = H / 2f, D = 2f * r + sw;
            float g = deg((numGroupW() / 2f + sw * 1.2f) / r);
            float s = 270f + g, e = 630f - g;
            oval.set(cx - r, cy - r, cx + r, cy + r);
            pStroke.setStrokeWidth(sw);
            arc(c, s, e, track);
            if (level > 0) arc(c, s, s + (e - s) * Math.min(1f, level / 100f), litColor());
            drawNumber(c, cx, cy - r);

            boolean two = dualWifi();
            float a3 = D * 0.3125f;
            if (net == NET_WIFI) {
                float ox = 0f, oy = 0f, ms = a3;
                if (airplane && two) {
                    oy = -0.095f;
                    ms = D * 0.27f;
                } else if (airplane) {
                    ox = 0.108f;
                    oy = -0.108f;
                } else if (two) {
                    ox = 0.025f;
                    oy = -0.018f;
                }
                wifiAt(c, cx + ox * D, cy + oy * D, ms, 0.2f, wifiArcs, track);
                if (two) {
                    if (airplane) wifiAt(c, cx + D * 0.15f, cy + D * 0.13f, D * 0.165f, 0.2f, wifi2, track);
                    else wifiAt(c, cx - D * 0.163f, cy + D * 0.095f, D * 0.1234f, 0.2f, wifi2, track);
                }
                if (airplane) {
                    float ps = (two ? 0.21f : 0.25f) * D;
                    float px = cx + (two ? -0.15f : -0.108f) * D, py = cy + (two ? 0.13f : 0.108f) * D;
                    fillPlane(c, px - ps / 2f, py - ps / 2f, ps, ps);
                }
            } else if (net == NET_CELL) {
                String t = cellType();
                if (!t.isEmpty()) typeAt(c, t, cx, cy, D * 0.282f, 1f, D * 0.5f);
            } else if (airplane) {
                float ps = a3 * 1.15f;
                fillPlane(c, cx - ps / 2f, cy - ps / 2f, ps, ps);
            } // Wi-Fi 与移动数据都没有连接：留空
            if (airplane) return; // 飞行模式不画信号点

            float dot = sw * 1.31f, da = D * 0.456f, de = D * 0.1125f;
            float[] ang = {34.5f, 11.5f, -11.5f, -34.5f}; // 自下而上 1→4 格
            for (int i = 0; i < Math.min(2, sims.size()); i++) {
                Sim m = sims.get(i);
                int lv = m.inService ? m.lv : 0;
                float ccx = cx + (i == 0 ? de : -de); // 左组弧心在右、右组弧心在左，呈括号形
                for (int k = 0; k < 4; k++) {
                    double t = Math.toRadians(i == 0 ? 180f - ang[k] : ang[k]);
                    pFill.setColor(k < lv ? fg : track);
                    c.drawCircle(ccx + da * (float) Math.cos(t), cy + da * (float) Math.sin(t), dot / 2f, pFill);
                }
            }
        }

        /** Wi-Fi 图标：扇形 + 两道弧，中心 (ix, iy)，size 为最外弧半径，线宽 size × wsK。 */
        private void wifiAt(Canvas c, float ix, float iy, float size, float wsK, int arcs, int track) {
            float ws = size * wsK, oy = iy + size * 0.5f;
            float a1 = size * 0.34f, a2 = size * 0.66f - ws / 2f, a3c = size - ws / 2f;
            path.reset();
            path.moveTo(ix, oy);
            oval.set(ix - a1, oy - a1, ix + a1, oy + a1);
            path.arcTo(oval, 225f, 90f);
            path.close();
            pFill.setColor(arcs >= 1 ? fg : track);
            c.drawPath(path, pFill);
            pStroke.setStrokeWidth(ws);
            oval.set(ix - a2, oy - a2, ix + a2, oy + a2);
            arc(c, 225f, 315f, arcs >= 2 ? fg : track);
            oval.set(ix - a3c, oy - a3c, ix + a3c, oy + a3c);
            arc(c, 225f, 315f, arcs >= 3 ? fg : track);
            pStroke.setStrokeWidth(sw);
        }

        /** 网络制式文字，以 (x, y) 为中心；宽度超过 maxW 时缩小字号。 */
        private void typeAt(Canvas c, String t, float x, float y, float size, float scaleX, float maxW) {
            pType.setTextSize(size);
            pType.setTextScaleX(scaleX);
            float w = pType.measureText(t);
            if (w > maxW && w > 0) pType.setTextSize(size * maxW / w);
            pType.getTextBounds(t, 0, t.length(), bounds);
            pType.setColor(fg);
            c.drawText(t, x, y + bounds.height() / 2f, pType);
            pType.setTextScaleX(1f);
        }

        /** 电量数字 + 充电闪电，字形竖直中心在 numCy；先在字形周围抠出空隙（压在圆环上时环线断开）。 */
        private void drawNumber(Canvas c, float cx, float numCy) {
            String num = level < 0 ? "" : String.valueOf(level);
            if (!num.isEmpty()) {
                float numW = pNum.measureText(num);
                float boltH = capNum * 1.2f, boltW = boltH * 0.6f, boltGap = capNum * 0.16f;
                float boltsW = !charging ? 0 : quick ? boltW * 1.55f : boltW;
                float groupW = numW + (charging ? boltGap + boltsW : 0);
                float x = cx - groupW / 2f, base = numCy + capNum / 2f;
                float halo = sw * 1.5f;
                float bx = x + numW + boltGap, by = numCy - boltH / 2f, bx2 = bx + boltW * 0.55f;
                pClear.setStrokeWidth(halo * 2f);
                pClear.setTextSize(pNum.getTextSize());
                pClear.setTypeface(pNum.getTypeface());
                pClear.setTextAlign(Paint.Align.LEFT);
                c.drawText(num, x, base, pClear);
                if (charging) {
                    boltPath(bx, by, boltW, boltH);
                    c.drawPath(path, pClear);
                    if (quick) {
                        boltPath(bx2, by, boltW, boltH);
                        c.drawPath(path, pClear);
                    }
                }
                pNum.setColor(fg);
                c.drawText(num, x, base, pNum);
                if (charging) {
                    pFill.setColor(colorful ? GREEN : fg);
                    boltPath(bx, by, boltW, boltH);
                    c.drawPath(path, pFill);
                    if (quick) {
                        boltPath(bx2, by, boltW, boltH);
                        c.drawPath(path, pFill);
                    }
                }
            }
        }

        /** 电量数字 + 充电闪电的总宽度（没有电量时为 0）。 */
        private float numGroupW() {
            if (level < 0) return 0f;
            float numW = pNum.measureText(String.valueOf(level));
            float boltH = capNum * 1.2f, boltW = boltH * 0.6f, boltGap = capNum * 0.16f;
            float boltsW = !charging ? 0 : quick ? boltW * 1.55f : boltW;
            return numW + (charging ? boltGap + boltsW : 0);
        }

        /** 样式 1：整圈电量（底部留缺口放信号点），顶部电量数字压在环上，环内为当前网络。 */
        private void drawRing1(Canvas c, int track) {
            float cx = ringCx(), cy = H / 2f;
            int lit = litColor();

            // 底部：信号点，每张卡一排，压在圆环底部的缺口里
            int rows = airplane ? 0 : sims.size();
            float dot = sw * 1.0f, step = dot * 1.8f, rowD = dot * 1.05f;
            // 信号点上移：最下面一排的下沿与圆环外沿齐平，不再伸出圆环
            float dotY = cy + r - sw * (rows == 2 ? 1.05f : 0.5f);
            // 飞行模式：连着 Wi-Fi 时飞机放在信号点位置、环内照常画 Wi-Fi；没有 Wi-Fi 时飞机放进环中心
            float rin = r - sw * 1.3f;
            boolean planeBottom = airplane && net == NET_WIFI;
            float plane = planeBottom ? sw * 4.4f : rin * 1.45f;
            float gb = planeBottom ? deg((plane * 0.5f + sw * 0.9f) / r)
                    : rows == 0 ? 20f : deg((1.5f * step + dot / 2f + sw * 1.3f) / r);

            // 外圈：整圈（除底部缺口）表示电量
            float s = 90f + gb, e = 450f - gb, total = e - s;
            float litEnd = level <= 0 ? s : s + total * Math.min(1f, level / 100f);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            pStroke.setStrokeWidth(sw);
            arc(c, s, e, track);
            arc(c, s, litEnd, lit);

            drawNumber(c, cx, cy - r);

            // 环内：当前网络
            float ri = r - sw * 1.3f;
            if (net == NET_CELL) {
                String t = "";
                for (Sim m : sims) if (m.subId == dds) t = m.type;
                if (t.isEmpty() && !sims.isEmpty()) t = sims.get(0).type;
                if (!t.isEmpty()) drawType(c, t, cx, cy, ri);
            } else if (net == NET_WIFI) {
                drawWifi(c, cx, cy, ri, wifiArcs, track);
            } else if (airplane) {
                pFill.setColor(fg);
                planePath(cx - plane / 2f, cy - plane / 2f, plane);
                c.drawPath(path, pFill);
            } // Wi-Fi 与移动数据都没有连接：环内留空

            if (planeBottom) { // 下沿与圆环外沿齐平
                pFill.setColor(fg);
                planePath(cx - plane / 2f, cy + r + sw / 2f - plane, plane);
                c.drawPath(path, pFill);
            }
            for (int row = 0; row < rows; row++) {
                Sim m = sims.get(row);
                float y = dotY + (rows == 2 ? (row == 0 ? -rowD : rowD) : 0f);
                int on = m.inService ? m.lv : 0;
                for (int i = 0; i < 4; i++) {
                    pFill.setColor(i < on ? fg : track);
                    c.drawCircle(cx + (i - 1.5f) * step, y, dot / 2f, pFill);
                }
            }
        }

        /** 机头朝上的飞机（边长 s 的正方形内）。 */
        private void planePath(float x, float y, float s) {
            planePath(x, y, s, s);
        }

        /** 机头朝上的飞机（宽 w、高 h 的矩形内）。 */
        private void planePath(float x, float y, float w, float h) {
            float[] p = {0.50f, 0.00f, 0.57f, 0.07f, 0.57f, 0.37f, 0.98f, 0.60f, 0.98f, 0.71f, 0.57f, 0.57f,
                    0.57f, 0.80f, 0.71f, 0.90f, 0.71f, 0.98f, 0.50f, 0.92f, 0.29f, 0.98f, 0.29f, 0.90f,
                    0.43f, 0.80f, 0.43f, 0.57f, 0.02f, 0.71f, 0.02f, 0.60f, 0.43f, 0.37f, 0.43f, 0.07f};
            path.reset();
            path.moveTo(x + p[0] * w, y + p[1] * h);
            for (int i = 2; i < p.length; i += 2) path.lineTo(x + p[i] * w, y + p[i + 1] * h);
            path.close();
        }

        private float deg(float sin) {
            return (float) Math.toDegrees(Math.asin(Math.max(0f, Math.min(1f, sin))));
        }

        private void arc(Canvas c, float from, float to, int color) {
            if (to - from < 0.5f) return;
            pStroke.setColor(color);
            c.drawArc(oval, from, to - from, false, pStroke);
        }

        private void boltPath(float x, float y, float w, float h) {
            float[] pts = {0.62f, 0f, 0.05f, 0.58f, 0.44f, 0.58f, 0.36f, 1f, 0.95f, 0.40f, 0.56f, 0.40f};
            path.reset();
            path.moveTo(x + pts[0] * w, y + pts[1] * h);
            for (int i = 2; i < pts.length; i += 2) path.lineTo(x + pts[i] * w, y + pts[i + 1] * h);
            path.close();
        }

        /** Wi-Fi：扇形 + 两道弧，按信号点亮；arcs = 0 时全部暗色（无网络）。 */
        private void drawWifi(Canvas c, float cx, float cy, float ri, int arcs, int track) {
            wifiAt(c, cx, cy, ri * 1.3f / 1.414f, 0.2f, arcs, track);
        }

        private void drawType(Canvas c, String t, float cx, float cy, float ri) {
            pType.setTextSize(100f);
            float w100 = pType.measureText(t);
            pType.setTextSize(Math.min(ri * 0.95f, 100f * ri * 1.55f / Math.max(1f, w100)));
            pType.getTextBounds(t, 0, t.length(), bounds);
            pType.setColor(fg);
            c.drawText(t, cx, cy + bounds.height() / 2f, pType);
        }
    }

    // ---------------- 工具 ----------------

    private static String fmtSpeed(double bps) {
        if (bps < 1000) return Math.round(bps) + " B/s";
        double v = bps / 1024;
        String unit = "KB/s";
        if (v >= 1000) {
            v /= 1024;
            unit = "MB/s";
        }
        if (v >= 1000) {
            v /= 1024;
            unit = "GB/s";
        }
        String f = v < 10 ? "%.2f" : v < 100 ? "%.1f" : "%.0f";
        return String.format(Locale.US, f, v) + " " + unit;
    }

    /** 状态栏网络制式：5G-A（NR_ADVANCED）、5G（NR / NSA）、4G、3G、2G。 */
    private static String typeName(TelephonyDisplayInfo d) {
        switch (d.getOverrideNetworkType()) {
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED:
                return "5GA";
            case TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA:
            case 4: // OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE（已弃用）
                return "5G";
            default:
                break;
        }
        switch (d.getNetworkType()) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "2G";
            default:
                return "";
        }
    }

    private static Method optMethod(String name, Class<?>... types) {
        try {
            return View.class.getMethod(name, types);
        } catch (Throwable t) {
            return null;
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
