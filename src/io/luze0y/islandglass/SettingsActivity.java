package io.luze0y.islandglass;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * FucSuperX 模块界面，按 OriginOS 7 设置二级页（如 WLAN）的实测尺寸复刻（纯代码，无 AndroidX）。
 * 顶栏：状态栏 + 64dp，38dp 圆形返回键，标题 x=68dp；顶栏使用与 VToolbar 相同的系统玻璃材质
 * （setMaterial + setMaterialCustomized + setMaterialGradientAdd 底部渐隐），失败时回退为渐变底色。
 * 卡片：左右 20dp、圆角 14dp、行内边距 14dp、单行 54dp / 双行 78dp，按压高亮带过渡动画。
 */
public class SettingsActivity extends Activity {
    private static final String TAG = "FucSuperX";
    private static final PathInterpolator EASE_DOWN = new PathInterpolator(0.25f, 0.1f, 0.25f, 1f);
    private static final PathInterpolator EASE_UP = new PathInterpolator(0.33f, 0f, 0.67f, 1f);
    private static final PathInterpolator EASE_SWITCH = new PathInterpolator(0.2f, 0f, 0.1f, 1f);

    static final String EXTRA_PAGE = "page", PAGE_ISLAND = "island", PAGE_SYSGLASS = "sysglass", PAGE_NC = "nc";
    private boolean dark, island, sysglass, nc;
    private int cBg, cCard, cTitle, cSummary, cHeading, cDivider, cAccent, cTrackOff, cRingOff, cPress, cChevron, cCircle, cIcon;

    private FrameLayout frame;
    private LinearLayout root;
    private ScrollView scroll;
    private FrameLayout bar;
    private View backBtn;

    private void palette() {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            cBg = 0xFF000000; cCard = 0xFF1C1C1E; cTitle = 0xF2FFFFFF; cSummary = 0x80FFFFFF;
            cHeading = 0x80FFFFFF; cDivider = 0x1AFFFFFF; cAccent = 0xFF4A86FF; cTrackOff = 0x29FFFFFF;
            cRingOff = 0x4DFFFFFF; cPress = 0x1AFFFFFF; cChevron = 0x59FFFFFF; cCircle = 0xFF2C2C2E; cIcon = 0xF2FFFFFF;
        } else {
            cBg = 0xFFF3F3F5; cCard = 0xFFFFFFFF; cTitle = 0xFF000000; cSummary = 0x80000000;
            cHeading = 0x80000000; cDivider = 0x14000000; cAccent = 0xFF2A70F6; cTrackOff = 0x14000000;
            cRingOff = 0x33000000; cPress = 0x0F000000; cChevron = 0x4D000000; cCircle = 0xFFF8F8FA; cIcon = 0xFF000000;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(b);
        island = PAGE_ISLAND.equals(getIntent().getStringExtra(EXTRA_PAGE));
        sysglass = PAGE_SYSGLASS.equals(getIntent().getStringExtra(EXTRA_PAGE));
        nc = PAGE_NC.equals(getIntent().getStringExtra(EXTRA_PAGE));
        palette();
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setDecorFitsSystemWindows(false);
        w.getDecorView().setBackgroundColor(cBg);
        WindowInsetsController ic = w.getInsetsController();
        if (ic != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ic.setSystemBarsAppearance(dark ? 0 : light, light);
        }
        // 等 LSPosed 服务绑定后再构建（读到远程偏好里的值）；800ms 内未绑定则回退 root 模式
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        Prop.listen(() -> h.post(this::onPrefsBound));
        h.postDelayed(this::buildOnce, 800);
    }

    private boolean built, builtRemote;

    /** 800ms 内没等到 LSPosed 服务时页面先用系统属性构建，值可能是旧的；服务绑定后按真实设置重建一次。 */
    private void onPrefsBound() {
        if (isFinishing() || isDestroyed()) return;
        if (!built) {
            buildOnce();
            return;
        }
        if (builtRemote || !Prop.remote()) return;
        builtRemote = true;
        int y = scroll == null ? 0 : scroll.getScrollY();
        Log.i(TAG, "module UI rebuilt with remote prefs");
        build();
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void buildOnce() {
        if (built || isFinishing() || isDestroyed()) return;
        built = true;
        builtRemote = Prop.remote();
        Log.i(TAG, "module UI mode=" + (Prop.remote() ? "remote-prefs" : "root-fallback"));
        build();
    }

    @Override
    protected void onDestroy() {
        Prop.listen(null);
        if (installRx != null) {
            try {
                unregisterReceiver(installRx);
            } catch (Throwable ignored) {
            }
            installRx = null;
        }
        super.onDestroy();
    }

    private void build() {
        frame = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        buildBar();
        frame.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            lp.height = s.top + dp(64);
            bar.setLayoutParams(lp);
            bar.setPadding(0, s.top, 0, 0);
            root.setPadding(0, s.top + dp(64) + dp(12), 0, s.bottom + dp(24));
            return WindowInsets.CONSUMED;
        });
        setContentView(frame);

        if (island) buildIsland();
        else if (sysglass) buildSysGlass();
        else if (nc) buildNc();
        else buildMain();
    }

    /** 首页：各功能入口 + 通用设置。原子岛相关全部收进「原子岛」二级页。 */
    private void buildMain() {
        heading("功能", false);
        Card c = card();
        c.link("原子岛", "玻璃材质、深浅色反相、高级参数", () ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_PAGE, PAGE_ISLAND)));
        c.link("系统液态玻璃", "控制中心、通知、锁屏通知等，不含原子岛", () ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_PAGE, PAGE_SYSGLASS)));
        c.link("通知中心", "顶部时间、日期大号居中", () ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_PAGE, PAGE_NC)));

        heading("设置", true);
        c = card();
        c.toggle("设置首页入口", "在设置顶部显示 FucSuperX", "persist.land.settings_entry", "1");
        if (!Prop.remote()) { // root 回退模式下修改不会推送到作用域进程，仍需手动重启
            c.action("重启设置", "修改设置入口后执行", "cmd_restart_settings", "am force-stop com.android.settings");
        }
        buildAbout();
        footer(Prop.remote()
                ? "修改即时生效，无需 root。"
                : "未连接 LSPosed 服务（模块未启用或框架过旧），已回退 root 模式：配置写入 persist.land.*。");
    }

    private void buildIsland() {
        heading("总开关", false);
        Card c = card();
        c.toggle("调节原子岛", "关闭后原子岛完全恢复系统原版，切换时原子岛会自动重启", "persist.land.island_glass_on", "1");

        heading("玻璃材质", true);
        c = card();
        c.segmented("材质强度", null, "persist.land.island_glass", "strong",
                new String[]{"strong", "medium", "folder"}, new String[]{"强", "中", "T0"});
        c.toggle("过渡动画保持玻璃", "应用打开/收回原子岛时也使用材质", "persist.land.island_glass_trans", "1");
        c.toggle("逐帧同步材质区域", "修正插值动画期间材质错位", "persist.land.island_glass_sync", "1");
        c.toggle("深浅色反相", "背景偏亮时反色文字、音乐按钮与进度条", "persist.land.island_glass_invert", "1");

        heading("高级参数", true);
        c = card();
        c.segmented("材质 contentType", null, "persist.land.island_glass_ct", "2",
                new String[]{"0", "1", "2"}, new String[]{"0", "1", "2"});
        c.segmented("材质刷新率", "动画有撕裂感时可尝试固定为屏幕刷新率", "persist.land.island_glass_fps", "0",
                new String[]{"0", "60", "90", "120", "144"}, new String[]{"自动", "60", "90", "120", "144"});
        c.slider("模糊半径", "0 为不模糊", "persist.land.island_glass_blur", "0", 0f, 0.3f, 0.005f, false, 3);
        c.slider("折射强度", "拖到最左端为材质默认值", "persist.land.island_glass_refr", "-1", 0f, 300f, 5f, true, 0);
        c.slider("折射范围", "拖到最左端为材质默认值", "persist.land.island_glass_refr_r", "-1", 0f, 120f, 1f, true, 0);
        c.toggle("诊断日志", "输出 [diag] 日志", "persist.land.island_glass_debug", "0");

        if (!Prop.remote()) {
            heading("生效", true);
            c = card();
            c.action("重启原子岛进程", "修改原子岛相关选项后执行", "cmd_restart_island",
                    "pkill -f com.vivo.systemuiplugin:island || killall com.vivo.systemuiplugin:island");
        }
        footer(Prop.remote()
                ? "点按滑块右侧的数值可恢复默认。修改即时生效，无需 root。"
                : "未连接 LSPosed 服务，已回退 root 模式：修改后需重启原子岛进程。");
    }

    /** 系统液态玻璃：SystemUI 内除原子岛外的全部材质。全部为倍数，1.00 = 系统原值。 */
    private void buildSysGlass() {
        heading("总开关", false);
        Card c = card();
        c.toggle("调节系统液态玻璃", "关闭后完全使用系统原版参数", "persist.land.sysglass", "1");
        c.toggle("排除其他系统弹窗", "指纹验证弹窗、小窗 / 分屏菜单、原子工作台入口等不在通知栏 / 控制中心 / 锁屏里的材质保持系统原样",
                "persist.land.sysglass_excl_other", "1");

        heading("液态玻璃材质", true);
        c = card();
        c.slider("材质强度", "染色层不透明度倍数，越小越通透", "persist.land.sysglass_tint", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("模糊半径", "倍数", "persist.land.sysglass_blur", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("饱和度", "倍数", "persist.land.sysglass_sat", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("整体不透明度", "倍数，1.00 为系统原值", "persist.land.sysglass_alpha", "1", 0f, 1f, 0.05f, false, 2);

        heading("样式 0（控制中心等三层材质）", true);
        c = card();
        c.slider("材质强度", "染色层不透明度倍数", "persist.land.sysglass_zero_tint", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("模糊半径", "倍数", "persist.land.sysglass_zero_blur", "1", 0f, 3f, 0.05f, false, 2);

        heading("背景", true);
        c = card();
        c.slider("背景模糊", "下拉通知 / 控制中心时背后全屏模糊的倍数（最小 0.05，为 0 会导致 SystemUI 崩溃）", "persist.land.sysglass_bg_blur", "1", 0.05f, 5f, 0.05f, false, 2);

        heading("高级参数", true);
        c = card();
        c.slider("折射强度", "倍数", "persist.land.sysglass_refr", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("折射范围", "倍数", "persist.land.sysglass_refr_r", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("边缘高光强度", "倍数", "persist.land.sysglass_light", "1", 0f, 3f, 0.05f, false, 2);
        c.slider("边缘高光宽度", "倍数", "persist.land.sysglass_light_w", "1", 0f, 3f, 0.05f, false, 2);

        if (!Prop.remote()) { // root 回退模式：配置不会推送到 SystemUI，仍需重启
            heading("生效", true);
            c = card();
            c.action("重启 SystemUI", "root 模式下修改后需重启才会套用", "cmd_restart_systemui",
                    "kill -9 $(pidof com.android.systemui)");
        }
        footer(Prop.remote()
                ? "所有数值为相对系统原值的倍数，点按滑块右侧数值恢复 1.00。修改即时生效，无需 root。原子岛不受本页影响。"
                : "未连接 LSPosed 服务，已回退 root 模式：修改后需重启 SystemUI。原子岛不受本页影响。");
    }

    /** 通知中心：顶部时间 / 日期的大小与位置，控制中心不受影响。 */
    private void buildNc() {
        heading("时间与日期", false);
        Card c = card();
        c.toggle("大号居中", "日期在上、时间放大在下，均水平居中", "persist.land.nc_bigclock", "1");
        c.slider("时间字号", "相对系统原字号的倍数", "persist.land.nc_clock_scale", "2", 1f, 3f, 0.05f, false, 2);
        c.slider("下移距离", "整体向下移动，单位 dp（竖屏）", "persist.land.nc_clock_offset", "42", 0f, 200f, 1f, false, 0);

        heading("字体", true);
        c = card();
        c.fontCards("时间字体", "跟随锁屏：时间和日期都使用锁屏当前的字体", "persist.land.nc_clock_font", "sys",
                new String[]{"sys", "lock", "vivoSansClockVF", "vivoSansClock-Bold", "vivoSansClock-Light",
                        "vivoSansClockHAVF-OS7", "vivoSansClockHASerifVF-OS7", "vivoSansClockHASquareVF",
                        "vivoSansClockRoundedVF", "vivoSansClockSerifVF", "vivoSansClockSlabVF",
                        "vivoSansClockStencilVF", "vivoSansClockInlineVF", "vivoSansClockSilkVF",
                        "vivoSansCondVF", "vivoSansCompVF"},
                new String[]{"系统默认", "跟随锁屏", "时钟 常规", "时钟 粗体", "时钟 细体",
                        "HA", "HA 衬线", "HA 方形", "圆体", "衬线", "粗衬线",
                        "镂空", "内线", "丝线", "窄体", "紧凑"});
        c.slider("字重", "仅对自选字体生效，点按数值恢复字体默认", "persist.land.nc_clock_wght", "-1", 100f, 900f, 50f, true, 0);

        heading("横屏", true);
        c = card();
        c.toggle("横屏分栏", "时间日期在左半屏，通知在右半屏", "persist.land.nc_land_split", "1");

        if (!Prop.remote()) { // root 回退模式：配置不会推送到 SystemUI，仍需重启
            heading("生效", true);
            c = card();
            c.action("重启 SystemUI", "root 模式下修改后需重启才会套用", "cmd_restart_systemui",
                    "kill -9 $(pidof com.android.systemui)");
        }
        footer(Prop.remote()
                ? "只改通知中心的字号和位置，控制中心不受影响。点按滑块右侧数值恢复默认。修改即时生效。"
                : "未连接 LSPosed 服务，已回退 root 模式：修改后需重启 SystemUI。");
    }

    private void footer(String s) {
        TextView foot = text(s, 12, cHeading, false);
        foot.setLineSpacing(dp(2), 1f);
        foot.setPadding(dp(24), dp(12), dp(24), 0);
        root.addView(foot);
    }

    // ---------------- 顶栏 ----------------

    private void buildBar() {
        bar = new FrameLayout(this);
        frame.addView(bar, new FrameLayout.LayoutParams(-1, dp(88), Gravity.TOP));

        // 返回键：38dp 圆形，左 20dp，垂直居中
        backBtn = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                p.setColor(cIcon);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                float cx = getWidth() / 2f - dp(1.5f), cy = getHeight() / 2f, s = dp(5.5f);
                c.drawLine(cx + s * 0.55f, cy - s, cx - s * 0.45f, cy, p);
                c.drawLine(cx - s * 0.45f, cy, cx + s * 0.55f, cy + s, p);
            }
        };
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(cCircle);
        backBtn.setBackground(circle);
        backBtn.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        backBtn.setElevation(dp(dark ? 0 : 3));
        backBtn.setOutlineAmbientShadowColor(0x40000000);
        backBtn.setOutlineSpotShadowColor(0x33000000);
        backBtn.setContentDescription("返回");
        pressScale(backBtn);
        backBtn.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START | Gravity.CENTER_VERTICAL);
        bl.leftMargin = dp(20);
        bar.addView(backBtn, bl);

        TextView title = text(island ? "原子岛" : sysglass ? "系统液态玻璃" : nc ? "通知中心" : "FucSuperX", 18, cTitle, true);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        tl.leftMargin = dp(68);
        tl.topMargin = dp(2); // 与系统 OriginUI 顶栏标题基线对齐
        bar.addView(title, tl);

        bar.post(this::applyBarMaterial);
        backBtn.post(() -> {
            if (applyButtonGlass(backBtn)) {
                backBtn.setBackground(null); // 玻璃成功：去掉实色底（与 OriginUI 一致）
                backBtn.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline o) {
                        o.setOval(0, 0, v.getWidth(), v.getHeight());
                    }
                });
            }
        });
    }

    /**
     * 复刻 OriginUI 顶栏圆形按钮（VToolbarMenuGroupView）的玻璃参数（设置 WLAN 页实测）：
     * setMaterial(409 浅 / 411 深, 1) → 圆角 = 半径 → alpha 1 → FPS 60 → 内描边高光 (-1, 0.6, 0, 1, 1, 3, 0.64)。
     */
    private boolean applyButtonGlass(View v) {
        Class<?> f = float.class, i = int.class;
        try {
            Object r = View.class.getMethod("setMaterial", i, i).invoke(v, dark ? 411 : 409, 1);
            if (!Boolean.TRUE.equals(r)) {
                Log.w(TAG, "button glass: setMaterial returned " + r);
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, "button glass unavailable: " + t);
            return false;
        }
        float rad = dp(19);
        tryCall(v, "setMaterialCornerRadius", new Class<?>[]{f, f, f, f}, rad, rad, rad, rad);
        tryCall(v, "setMaterialAlpha", new Class<?>[]{f}, 1f);
        tryCall(v, "setMaterialFPS", new Class<?>[]{i, boolean.class}, 60, true);
        tryCall(v, "setGlassEffectInnerStrokeLight", new Class<?>[]{i, f, f, f, f, i, f}, -1, 0.6f, 0f, 1f, 1f, 3, 0.64f);
        Log.i(TAG, "button glass on (" + (dark ? 411 : 409) + ")");
        return true;
    }

    private static void tryCall(View v, String name, Class<?>[] types, Object... args) {
        try {
            View.class.getMethod(name, types).invoke(v, args);
        } catch (Throwable t) {
            Log.w(TAG, name + ": " + t);
        }
    }

    /** 复刻 VToolbar（AIOS）：setMaterial(0,1) → setMaterialCustomized → setMaterialGradientAdd（底部渐隐）。 */
    private void applyBarMaterial() {
        boolean ok = false;
        try {
            Method sm = View.class.getMethod("setMaterial", int.class, int.class);
            Object r = sm.invoke(bar, 0, 1);
            ok = !(r instanceof Boolean) || (Boolean) r;
            if (ok) ok = customize(bar);
            if (!ok) { // 回退：普通预设材质 type 2（浅 202 / 深 252）
                r = sm.invoke(bar, dark ? 252 : 202, 1);
                ok = !(r instanceof Boolean) || (Boolean) r;
            }
            if (ok) {
                Bundle g = new Bundle();
                String[] k = {"X0", "X1", "AlphaX0", "AlphaX1", "X2", "X3", "AlphaX2", "AlphaX3",
                        "Y0", "Y1", "AlphaY0", "AlphaY1", "Y2", "Y3", "AlphaY2", "AlphaY3"};
                float[] v = {0, 0, 0, 0, 0, 0, 0, 0, 0.74f, 0.9f, 1.0f, 0.6f, 0.9f, 1.0f, 0.6f, 0.02f};
                for (int i = 0; i < k.length; i++) g.putFloat(k[i].startsWith("Alpha") ? "gradientAdd" + k[i] : "gradientAdd" + k[i], v[i]);
                try {
                    View.class.getMethod("setMaterialGradientAdd", Bundle.class).invoke(bar, g);
                } catch (Throwable t) {
                    Log.w(TAG, "bar gradient: " + t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "bar material unavailable: " + t);
            ok = false;
        }
        Log.i(TAG, "bar material " + (ok ? "on" : "fallback"));
        if (!ok) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{withAlpha(cBg, 0xF5), withAlpha(cBg, 0xE6), withAlpha(cBg, 0x00)});
            bar.setBackground(g);
        }
    }

    private static boolean customize(View v) {
        Class<?> f = float.class, i = int.class;
        try { // spaceFeatureVersion > 2
            Method m = View.class.getMethod("setMaterialCustomized", f, f, i, f, i, i, f, i, i, f, i, f, i);
            m.invoke(v, 0.05f, 0.4f, 0, 0f, 0, 0, 0f, 0, 0, 0f, 0, 1.0f, 0);
            return true;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            Log.w(TAG, "customized13: " + t);
            return false;
        }
        try {
            Method m = View.class.getMethod("setMaterialCustomized", f, f, i, f, i, i, f, i, f, i);
            m.invoke(v, 0.05f, 0.4f, 0, 0f, 0, 0, 0f, 0, 1.0f, 0);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "customized10: " + t);
            return false;
        }
    }

    // ---------------- 组件 ----------------

    private void heading(String s, boolean gapAbove) {
        TextView t = text(s, 15, cHeading, false);
        t.setPadding(dp(24), gapAbove ? dp(20) : dp(4), dp(24), dp(9));
        root.addView(t);
    }

    private Card card() {
        Card c = new Card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.leftMargin = lp.rightMargin = dp(20);
        root.addView(c, lp);
        return c;
    }

    /** 行：按下 200ms 淡入高亮、松开 350ms 淡出（OriginUI vclickdrawable 节奏）。 */
    private final class PressRow extends LinearLayout {
        private float a;
        private ValueAnimator an;
        private final Paint p = new Paint();

        PressRow(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        @Override
        public void setPressed(boolean pressed) {
            boolean was = isPressed();
            super.setPressed(pressed);
            if (was == pressed) return;
            if (an != null) an.cancel();
            an = ValueAnimator.ofFloat(a, pressed ? 1f : 0f).setDuration(pressed ? 200 : 350);
            an.setInterpolator(pressed ? EASE_DOWN : EASE_UP);
            an.addUpdateListener(x -> {
                a = (float) x.getAnimatedValue();
                invalidate();
            });
            an.start();
        }

        @Override
        public void draw(Canvas c) {
            super.draw(c);
            if (a <= 0f) return;
            p.setColor(cPress);
            p.setAlpha(Math.round(Color.alpha(cPress) * a));
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }

    private final class Card extends LinearLayout {
        Card(Context ctx) {
            super(ctx);
            setOrientation(VERTICAL);
            GradientDrawable g = new GradientDrawable();
            g.setColor(cCard);
            g.setCornerRadius(dp(14));
            setBackground(g);
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            setClipToOutline(true);
        }

        private void divider() {
            if (getChildCount() == 0) return;
            View d = new View(getContext());
            d.setBackgroundColor(cDivider);
            LayoutParams lp = new LayoutParams(-1, Math.max(1, dp(0.5f)));
            lp.leftMargin = dp(14);
            addView(d, lp);
        }

        /** 无按压态的块：标题（+ 摘要）+ 右侧可选控件，下方由调用方追加内容。 */
        private LinearLayout block(String title, String summary, View right) {
            divider();
            LinearLayout box = new LinearLayout(getContext());
            box.setOrientation(VERTICAL);
            box.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout top = new LinearLayout(getContext());
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            texts.addView(text(title, 17, cTitle, false));
            if (summary != null) {
                TextView s = text(summary, 13, cSummary, false);
                s.setPadding(0, dp(4), 0, 0);
                texts.addView(s);
            }
            top.addView(texts, new LayoutParams(0, -2, 1f));
            if (right != null) {
                LayoutParams rl = new LayoutParams(-2, -2);
                rl.leftMargin = dp(12);
                top.addView(right, rl);
            }
            box.addView(top, new LayoutParams(-1, -2));
            addView(box, new LayoutParams(-1, -2));
            return box;
        }

        private void row(String title, String summary, View widget, View.OnClickListener click) {
            divider();
            PressRow r = new PressRow(getContext());
            r.setOrientation(HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(summary == null ? 54 : 78));
            r.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout texts = new LinearLayout(getContext());
            texts.setOrientation(VERTICAL);
            texts.addView(text(title, 17, cTitle, false));
            if (summary != null) {
                TextView s = text(summary, 13, cSummary, false);
                s.setPadding(0, dp(4), 0, 0);
                texts.addView(s);
            }
            r.addView(texts, new LayoutParams(0, -2, 1f));
            if (widget != null) {
                LayoutParams wl = new LayoutParams(-2, -2);
                wl.leftMargin = dp(12);
                r.addView(widget, wl);
            }
            r.setOnClickListener(click);
            addView(r, new LayoutParams(-1, -2));
        }

        void toggle(String title, String summary, String key, String def) {
            Switch sw = new Switch(getContext());
            sw.setChecked(!"0".equals(Prop.get(key, def).trim()), false);
            row(title, summary, sw, v -> {
                boolean on = !sw.checked;
                sw.setChecked(on, true); // 先动画，写入失败再弹回
                if (!Prop.set(key, on ? "1" : "0")) {
                    sw.setChecked(!on, true);
                    toast("写入失败：需要 root");
                }
            });
        }

        /** 分段滑动选择（参考 pd2425re「应用主题」）：点按或拖动色块切换，选中即写入。 */
        void segmented(String title, String summary, String key, String def, String[] vals, String[] labels) {
            LinearLayout box = block(title, summary, null);
            int i = indexOf(vals, Prop.get(key, def).trim());
            if (i < 0) i = Math.max(0, indexOf(vals, def)); // 已移除的旧值（如 contentType 3）显示为默认项
            Segmented s = new Segmented(getContext(), labels, i);
            s.onPick = which -> {
                if (!Prop.set(key, vals[which])) {
                    toast("写入失败：需要 root");
                    s.select(Math.max(0, indexOf(vals, Prop.get(key, def).trim())), true);
                }
            };
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            box.addView(s, lp);
        }

        /**
         * 滑块：[min, max] 按 step 取值；allowDefault 时最左端额外一格表示「默认」（写入 -1）。
         * 拖动中节流写入（远程偏好模式），松手写入最终值；点按右侧数值恢复 def。
         */
        void slider(String title, String summary, String key, String def, float min, float max, float step,
                    boolean allowDefault, int decimals) {
            TextView val = valueText();
            LinearLayout box = block(title, summary, val);
            Slider s = new Slider(getContext());
            final int n = Math.round((max - min) / step), off = allowDefault ? 1 : 0;
            s.max = n + off;
            java.util.function.IntFunction<String> raw = idx -> {
                if (allowDefault && idx == 0) return "-1";
                float v = min + (idx - off) * step;
                return decimals == 0 ? String.valueOf(Math.round(v))
                        : String.format(java.util.Locale.US, "%." + decimals + "f", v);
            };
            java.util.function.ToIntFunction<String> toIdx = str -> {
                float v;
                try {
                    v = Float.parseFloat(str.trim());
                } catch (Throwable t) {
                    v = Float.parseFloat(def);
                }
                if (allowDefault && v < 0) return 0;
                return off + Math.max(0, Math.min(n, Math.round((v - min) / step)));
            };
            java.util.function.IntFunction<String> label = idx -> allowDefault && idx == 0 ? "默认" : raw.apply(idx);
            final String[] last = {null};
            final long[] lastAt = {0};
            s.setIndex(toIdx.applyAsInt(Prop.get(key, def)));
            last[0] = raw.apply(s.index);
            val.setText(label.apply(s.index));
            s.onChange = (idx, fin) -> {
                val.setText(label.apply(idx));
                String v = raw.apply(idx);
                long now = android.os.SystemClock.uptimeMillis();
                if (v.equals(last[0]) || (!fin && (!Prop.remote() || now - lastAt[0] < 100))) return;
                last[0] = v;
                lastAt[0] = now;
                if (!Prop.set(key, v) && fin) toast("写入失败：需要 root");
            };
            val.setOnClickListener(v -> {
                s.setIndex(toIdx.applyAsInt(def));
                s.onChange.accept(s.index, true);
            });
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            box.addView(s, lp);
        }

        /** 单选：右侧显示当前项，点按弹出列表。 */
        void choice(String title, String summary, String key, String def, String[] vals, String[] labels) {
            TextView val = valueText();
            int i = indexOf(vals, Prop.get(key, def).trim());
            if (i < 0) i = Math.max(0, indexOf(vals, def));
            final int[] sel = {i};
            val.setText(labels[i]);
            row(title, summary, withChevron(val), v -> new android.app.AlertDialog.Builder(getContext(),
                    dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                            : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(title)
                    .setSingleChoiceItems(labels, sel[0], (dlg, which) -> {
                        sel[0] = which;
                        val.setText(labels[which]);
                        if (!Prop.set(key, vals[which])) toast("写入失败：需要 root");
                        dlg.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show());
        }

        /** 字体卡片：横向滑动，每张用对应字体显示「09:41」，点按立即生效。 */
        void fontCards(String title, String summary, String key, String def, String[] vals, String[] labels) {
            LinearLayout box = block(title, summary, null);
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(getContext());
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout strip = new LinearLayout(getContext());
            strip.setOrientation(HORIZONTAL);
            hs.addView(strip);
            int sel = indexOf(vals, Prop.get(key, def).trim());
            if (sel < 0) sel = Math.max(0, indexOf(vals, def));
            final int[] cur = {sel};
            final View[] cards = new View[vals.length];
            for (int i = 0; i < vals.length; i++) {
                LinearLayout cd = new LinearLayout(getContext());
                cd.setOrientation(VERTICAL);
                cd.setGravity(Gravity.CENTER);
                cd.setPadding(dp(10), dp(8), dp(10), dp(8));
                cd.setMinimumWidth(dp(96));
                // 按数字实际轮廓居中绘制：HA 系列字体自带行高远大于数字，用 TextView 会把数字顶出卡片、名字被挤掉
                GlyphPreview pv = new GlyphPreview(getContext(), "09:41", previewFace(vals[i]), cTitle);
                cd.addView(pv, new LayoutParams(-2, dp(32)));
                TextView lb = text(labels[i], 12, cSummary, false);
                lb.setSingleLine(true);
                LayoutParams ll = new LayoutParams(-2, -2);
                ll.topMargin = dp(6);
                cd.addView(lb, ll);
                final int idx = i;
                cd.setOnClickListener(v -> {
                    if (cur[0] == idx) return;
                    if (!Prop.set(key, vals[idx])) {
                        toast("写入失败：需要 root");
                        return;
                    }
                    cur[0] = idx;
                    for (int j = 0; j < cards.length; j++) paintFontCard(cards[j], j == idx);
                });
                cards[i] = cd;
                paintFontCard(cd, i == sel);
                LayoutParams lp = new LayoutParams(-2, dp(84));
                if (i > 0) lp.leftMargin = dp(10);
                strip.addView(cd, lp);
            }
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            box.addView(hs, lp);
            hs.post(() -> hs.scrollTo(Math.max(0, cards[cur[0]].getLeft() - dp(40)), 0));
        }

        /** 跳转行：标题 + 摘要 + 右箭头。 */
        void link(String title, String summary, Runnable go) {
            row(title, summary, withChevron(null), v -> go.run());
        }

        void action(String title, String summary, String cmdKey, String legacyCmd) {
            row(title, summary, withChevron(null), v -> toast(Prop.restart(cmdKey, legacyCmd)
                    ? "已执行" : "执行失败：LSPosed 服务不可用且没有 root"));
        }
    }

    /** 只按字形轮廓排版的预览文字，任何字体都在固定高度内居中。 */
    private final class GlyphPreview extends View {
        private final String s;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Rect b = new android.graphics.Rect();

        GlyphPreview(Context c, String s, Typeface tf, int color) {
            super(c);
            this.s = s;
            p.setColor(color);
            p.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 26,
                    getResources().getDisplayMetrics()));
            if (tf != null) p.setTypeface(tf);
            p.getTextBounds(s, 0, s.length(), b);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int want = (int) Math.ceil(Math.max(p.measureText(s), b.width())) + dp(2);
            setMeasuredDimension(resolveSize(want, w), resolveSize(b.height() + dp(2), h));
        }

        @Override
        protected void onDraw(Canvas c) {
            float x = (getWidth() - p.measureText(s)) / 2f;
            float y = getHeight() / 2f - (b.top + b.bottom) / 2f;
            c.drawText(s, x, y, p);
        }
    }

    private void paintFontCard(View v, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? withAlpha(cAccent, 0x1F) : cCircle);
        g.setCornerRadius(dp(12));
        g.setStroke(on ? dp(2) : Math.max(1, dp(0.5f)), on ? cAccent : cDivider);
        v.setBackground(g);
    }

    private static final java.util.Map<String, Typeface> PREVIEW = new java.util.HashMap<>();

    /** 预览用字体，直接读 /system/fonts；系统默认 / 跟随锁屏返回 null（用默认字体）。 */
    private static Typeface previewFace(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+") || "sys".equals(name) || "lock".equals(name)) return null;
        if (PREVIEW.containsKey(name)) return PREVIEW.get(name);
        Typeface tf = null;
        java.io.File f = new java.io.File("/system/fonts/" + (name.endsWith(".ttf") ? name : name + ".ttf"));
        try {
            if (f.isFile()) tf = new Typeface.Builder(f).build();
        } catch (Throwable ignored) {
        }
        PREVIEW.put(name, tf);
        return tf;
    }

    private TextView valueText() {
        return text("", 15, cSummary, false);
    }

    private View withChevron(View value) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (value != null) l.addView(value);
        View chev = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas c) {
                p.setColor(cChevron);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.5f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                float w = getWidth(), h = getHeight(), s = dp(3.5f);
                float cx = w / 2f + s / 2f, cy = h / 2f;
                c.drawLine(cx - s, cy - s * 1.8f, cx, cy, p);
                c.drawLine(cx, cy, cx - s, cy + s * 1.8f, p);
            }
        };
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(24));
        lp.leftMargin = dp(6);
        l.addView(chev, lp);
        return l;
    }

    private void pressScale(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                x.animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).setInterpolator(EASE_DOWN).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                x.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(EASE_UP).start();
            }
            return false;
        });
    }

    /** OS7 开关：细轨道 + 白色圆钮（开：强调色描边环 / 关：灰色描边环），带过渡动画。 */
    private final class Switch extends View {
        boolean checked;
        float pos;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private ValueAnimator anim;

        Switch(Context c) {
            super(c);
            setClickable(false);
            setFocusable(false);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // 圆钮阴影
        }

        void setChecked(boolean on, boolean animate) {
            checked = on;
            if (anim != null) anim.cancel();
            if (!animate) {
                pos = on ? 1 : 0;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(pos, on ? 1 : 0).setDuration(300);
            anim.setInterpolator(EASE_SWITCH);
            anim.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(46), dp(30));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float knob = dp(24), th = dp(16), pad = dp(2);
            float cy = h / 2f;
            // 轨道
            r.set(pad + dp(2), cy - th / 2f, w - pad - dp(2), cy + th / 2f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(cTrackOff, withAlpha(cAccent, 0x4D), pos));
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            // 圆钮
            float x = pad + knob / 2f + (w - pad * 2 - knob) * pos;
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(dp(2f), 0, dp(0.5f), 0x33000000);
            c.drawCircle(x, cy, knob / 2f, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            float ring = dp(2.5f);
            p.setStrokeWidth(ring);
            p.setColor(blend(cRingOff, cAccent, pos));
            c.drawCircle(x, cy, knob / 2f - ring / 2f, p);
        }
    }

    /** 分段选择：灰色圆角轨道 + 强调色圆角色块，色块可拖动，松手吸附到最近一段，切换带动画。 */
    private final class Segmented extends View {
        final String[] labels;
        int sel;
        float pos;
        java.util.function.IntConsumer onPick;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int slop;
        private ValueAnimator an;
        private float downX, grab;
        private boolean dragging;

        Segmented(Context c, String[] labels, int sel) {
            super(c);
            this.labels = labels;
            this.sel = sel;
            this.pos = sel;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()));
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(52));
        }

        private float inset() {
            return dp(5);
        }

        private float segW() {
            return (getWidth() - inset() * 2) / labels.length;
        }

        void select(int i, boolean animate) {
            sel = i;
            if (an != null) an.cancel();
            if (!animate) {
                pos = i;
                invalidate();
                return;
            }
            an = ValueAnimator.ofFloat(pos, i).setDuration(320);
            an.setInterpolator(EASE_SWITCH);
            an.addUpdateListener(a -> {
                pos = (float) a.getAnimatedValue();
                invalidate();
            });
            an.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), in = inset(), sw = segW();
            p.setStyle(Paint.Style.FILL);
            r.set(0, 0, w, h);
            p.setColor(cTrackOff);
            c.drawRoundRect(r, dp(18), dp(18), p);
            float l = in + sw * pos;
            r.set(l, in, l + sw, h - in);
            p.setColor(cAccent);
            c.drawRoundRect(r, dp(14), dp(14), p);
            Paint.FontMetrics fm = p.getFontMetrics();
            float base = h / 2f - (fm.ascent + fm.descent) / 2f;
            for (int i = 0; i < labels.length; i++) {
                float t = Math.max(0f, 1f - Math.abs(pos - i));
                p.setColor(blend(cTitle, 0xFFFFFFFF, t));
                p.setFakeBoldText(t > 0.5f);
                c.drawText(labels[i], in + sw * (i + 0.5f), base, p);
            }
            p.setFakeBoldText(false);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), in = inset(), sw = segW();
            int n = labels.length;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = x;
                    dragging = false;
                    float pl = in + sw * pos;
                    grab = (x >= pl && x <= pl + sw) ? x - pl : -1;
                    if (grab >= 0) {
                        if (an != null) an.cancel();
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (grab >= 0 && (dragging || Math.abs(x - downX) > slop)) {
                        dragging = true;
                        pos = Math.max(0f, Math.min(n - 1, (x - grab - in) / sw));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    int i = dragging ? Math.round(pos) : Math.max(0, Math.min(n - 1, (int) ((x - in) / sw)));
                    dragging = false;
                    boolean changed = i != sel;
                    select(i, true);
                    if (changed) {
                        playSoundEffect(android.view.SoundEffectConstants.CLICK);
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        if (onPick != null) onPick.accept(i);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    select(sel, true);
                    return true;
            }
            return false;
        }
    }

    /** 滑块：圆角轨道 + 强调色进度 + 与开关同款圆钮；水平拖动时接管触摸，竖向滑动交给列表。 */
    private final class Slider extends View {
        int max = 100, index;
        java.util.function.BiConsumer<Integer, Boolean> onChange;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int slop;
        private float downX, downY;
        private boolean dragging;

        Slider(Context c) {
            super(c);
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
            setLayerType(LAYER_TYPE_SOFTWARE, null); // 圆钮阴影
        }

        void setIndex(int i) {
            index = Math.max(0, Math.min(max, i));
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(36));
        }

        private float left() {
            return dp(12);
        }

        private float right() {
            return getWidth() - dp(12);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), cy = h / 2f, th = dp(6), l = left(), rt = right();
            float x = l + (rt - l) * (max == 0 ? 0 : index / (float) max);
            p.setStyle(Paint.Style.FILL);
            r.set(l, cy - th / 2f, rt, cy + th / 2f);
            p.setColor(cTrackOff);
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            r.set(l, cy - th / 2f, Math.max(l + th, x), cy + th / 2f);
            p.setColor(cAccent);
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            float knob = dp(22);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(dp(2f), 0, dp(0.5f), 0x33000000);
            c.drawCircle(x, cy, knob / 2f, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            float ring = dp(2.5f);
            p.setStrokeWidth(ring);
            p.setColor(cAccent);
            c.drawCircle(x, cy, knob / 2f - ring / 2f, p);
        }

        private void update(float x, boolean fin) {
            float l = left(), rt = right();
            int i = Math.round(Math.max(0f, Math.min(1f, (x - l) / (rt - l))) * max);
            boolean changed = i != index;
            index = i;
            if (changed) {
                invalidate();
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            if ((changed || fin) && onChange != null) onChange.accept(i, fin);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        float dx = Math.abs(x - downX), dy = Math.abs(y - downY);
                        if (dx > slop && dx > dy) {
                            dragging = true;
                            getParent().requestDisallowInterceptTouchEvent(true);
                        } else {
                            return true; // 竖向滑动由 ScrollView 拦截
                        }
                    }
                    update(x, false);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging || Math.abs(x - downX) <= slop) update(x, true);
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && onChange != null) onChange.accept(index, true);
                    dragging = false;
                    return true;
            }
            return false;
        }
    }

    // ---------------- 工具 ----------------

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------- 关于 / 检查更新 ----------------
    static final String REPO = "LuZe0y/FucSuperX";
    static final String COOLAPK_UID = "912067";
    /** 国内加速镜像，按顺序竞速，最后直连 GitHub。 */
    private static final String[] MIRRORS = {"https://gh-proxy.org/", "https://v4.gh-proxy.org/", "https://cdn.gh-proxy.org/", ""};
    private static final String ACTION_INSTALL = "io.luze0y.islandglass.INSTALL_STATUS";
    private volatile boolean checking;
    private android.content.BroadcastReceiver installRx;

    private void buildAbout() {
        heading("关于", true);
        Card c = card();
        TextView author = valueText();
        author.setText("陈破孩");
        c.row("作者", null, withChevron(author), v -> openCoolapk());
        c.link("酷安主页", "coolapk.com/u/" + COOLAPK_UID, this::openCoolapk);
        c.link("GitHub 仓库", "github.com/" + REPO, () -> openBrowser("https://github.com/" + REPO));
        TextView ver = valueText();
        ver.setText(curVersion());
        c.row("检查更新", "通过国内加速镜像下载 GitHub Release", withChevron(ver), v -> checkUpdate(ver));
    }

    private int dialogTheme() {
        return dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    /** 优先用酷安 App 打开主页，没装酷安时用浏览器。 */
    private void openCoolapk() {
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("coolmarket://u/" + COOLAPK_UID))
                .setPackage("com.coolapk.market");
        try {
            startActivity(i);
        } catch (Throwable t) {
            openBrowser("https://www.coolapk.com/u/" + COOLAPK_UID);
        }
    }

    /** 用浏览器打开：优先默认浏览器，其次任一已安装浏览器；直接指定包名，不弹「打开方式」、不被其他 App 截走。 */
    private void openBrowser(String url) {
        Intent view = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        String pkg = browserPkg();
        if (pkg != null) view.setPackage(pkg);
        try {
            startActivity(view);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (Throwable t2) {
                toast("没有可用的浏览器");
            }
        }
    }

    private String browserPkg() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        java.util.List<android.content.pm.ResolveInfo> all = pm.queryIntentActivities(probe, 0);
        java.util.Set<String> browsers = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(
                Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0)) {
            browsers.add(r.activityInfo.packageName);
        }
        android.content.pm.ResolveInfo def = pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && browsers.contains(def.activityInfo.packageName)) {
            return def.activityInfo.packageName;
        }
        def = pm.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && browsers.contains(def.activityInfo.packageName)) {
            return def.activityInfo.packageName;
        }
        for (android.content.pm.ResolveInfo r : all) {
            if (browsers.contains(r.activityInfo.packageName)) return r.activityInfo.packageName;
        }
        return null;
    }

    private String curVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 比较 "v3.2" / "3.10" 这类版本号。 */
    static int cmpVer(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static String httpGet(String url, int timeout) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "FucSuperX");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    /** 所有节点同时请求最新 Release，用最先返回的；返回 {release, 下载节点顺序（最快的在前）}。 */
    private static Object[] fetchLatest() throws Exception {
        String api = "https://api.github.com/repos/" + REPO + "/releases/latest";
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(MIRRORS.length);
        java.util.concurrent.ExecutorCompletionService<Object[]> cs = new java.util.concurrent.ExecutorCompletionService<>(ex);
        for (String b : MIRRORS) {
            cs.submit(() -> {
                org.json.JSONObject o = new org.json.JSONObject(httpGet(b + api, 8000));
                if (!o.has("tag_name")) throw new java.io.IOException("返回内容无效");
                return new Object[]{b, o};
            });
        }
        Throwable last = null;
        try {
            for (int i = 0; i < MIRRORS.length; i++) {
                java.util.concurrent.Future<Object[]> f = cs.poll(12, java.util.concurrent.TimeUnit.SECONDS);
                if (f == null) break;
                try {
                    Object[] r = f.get();
                    java.util.List<String> order = new java.util.ArrayList<>();
                    order.add((String) r[0]);
                    for (String b : MIRRORS) if (!b.equals(r[0])) order.add(b);
                    return new Object[]{r[1], order};
                } catch (java.util.concurrent.ExecutionException e) {
                    last = e.getCause();
                }
            }
        } finally {
            ex.shutdownNow();
        }
        throw new java.io.IOException(last == null ? "连接超时" : String.valueOf(last.getMessage()));
    }

    private void checkUpdate(TextView ver) {
        if (checking) return;
        checking = true;
        ver.setText("检查中…");
        new Thread(() -> {
            Object[] r = null;
            String err = null;
            try {
                r = fetchLatest();
            } catch (Throwable t) {
                err = t.getMessage();
                Log.w(TAG, "update check: " + t);
            }
            final Object[] res = r;
            final String e = err;
            runOnUiThread(() -> {
                checking = false;
                ver.setText(curVersion());
                if (isFinishing() || isDestroyed()) return;
                if (res == null) toast("检查更新失败：" + e);
                else {
                    @SuppressWarnings("unchecked")
                    java.util.List<String> order = (java.util.List<String>) res[1];
                    onRelease((org.json.JSONObject) res[0], order);
                }
            });
        }, "fsx-update").start();
    }

    private void onRelease(org.json.JSONObject rel, java.util.List<String> bases) {
        String tag = rel.optString("tag_name"), cur = curVersion();
        if (cmpVer(tag, cur) <= 0) {
            toast("已是最新版本 " + cur);
            return;
        }
        String apk = null;
        long size = -1;
        org.json.JSONArray as = rel.optJSONArray("assets");
        for (int i = 0; as != null && i < as.length(); i++) {
            org.json.JSONObject a = as.optJSONObject(i);
            if (a != null && a.optString("name").endsWith(".apk")) {
                apk = a.optString("browser_download_url");
                size = a.optLong("size", -1);
                break;
            }
        }
        String notes = rel.optString("body", "").trim();
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        String page = rel.optString("html_url", "https://github.com/" + REPO + "/releases/latest");
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this, dialogTheme())
                .setTitle("发现新版本 " + tag)
                .setMessage("当前版本 " + cur + (notes.isEmpty() ? "" : "\n\n" + notes))
                .setNegativeButton("取消", null)
                .setNeutralButton("浏览器打开", (d, w) -> openBrowser(page));
        if (apk != null && !apk.isEmpty()) {
            final String url = apk;
            final long sz = size;
            b.setPositiveButton("下载并安装", (d, w) -> download(url, sz, tag, bases));
        }
        b.show();
    }

    private void download(String url, long size, String tag, java.util.List<String> bases) {
        final boolean[] cancel = {false};
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this, dialogTheme())
                .setTitle("下载 " + tag)
                .setMessage("连接中…")
                .setCancelable(false)
                .setNegativeButton("取消", (d, w) -> cancel[0] = true)
                .create();
        dlg.show();
        java.io.File f = new java.io.File(getCacheDir(), "update.apk");
        new Thread(() -> {
            String err = "没有可用节点";
            for (String b : bases) {
                if (cancel[0]) break;
                String host = b.isEmpty() ? "github.com" : android.net.Uri.parse(b).getHost();
                try {
                    fetchTo(b + url, f, size, host, dlg, cancel);
                    runOnUiThread(() -> {
                        dlg.dismiss();
                        if (!isFinishing() && !isDestroyed()) install(f);
                    });
                    return;
                } catch (Throwable t) {
                    err = host + "：" + t.getMessage();
                    Log.w(TAG, "update download via " + host + ": " + t);
                }
            }
            final String e = err;
            runOnUiThread(() -> {
                dlg.dismiss();
                if (!cancel[0]) toast("下载失败，" + e);
            });
        }, "fsx-download").start();
    }

    private void fetchTo(String url, java.io.File f, long size, String host, android.app.AlertDialog dlg, boolean[] cancel)
            throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FucSuperX");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            if (total <= 0) total = size;
            long got = 0, lastUi = 0;
            try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel[0]) throw new java.io.IOException("已取消");
                    out.write(buf, 0, n);
                    got += n;
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUi > 150) {
                        lastUi = now;
                        String msg = "节点 " + host + "\n" + (total > 0 ? (got * 100 / total) + "%" : (got / 1024) + " KB");
                        runOnUiThread(() -> dlg.setMessage(msg));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        if (size > 0 && f.length() != size) throw new java.io.IOException("文件不完整");
        android.content.pm.PackageInfo pi = getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
        if (pi == null || !getPackageName().equals(pi.packageName)) throw new java.io.IOException("安装包无效");
    }

    /** 用系统安装器安装（PackageInstaller 会话，弹出系统确认页）。 */
    private void install(java.io.File f) {
        try {
            android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(getPackageName());
            sp.setSize(f.length());
            int id = pi.createSession(sp);
            try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
                try (java.io.OutputStream o = s.openWrite("base.apk", 0, f.length());
                     java.io.InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    s.fsync(o);
                }
                registerInstallRx();
                Intent cb = new Intent(ACTION_INSTALL).setPackage(getPackageName());
                android.app.PendingIntent pin = android.app.PendingIntent.getBroadcast(this, id, cb,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
                s.commit(pin.getIntentSender());
            }
        } catch (Throwable t) {
            Log.w(TAG, "update install: " + t);
            toast("安装失败：" + t.getMessage());
        }
    }

    private void registerInstallRx() {
        if (installRx != null) return;
        installRx = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS,
                        android.content.pm.PackageInstaller.STATUS_FAILURE);
                if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(confirm);
                    }
                } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                    toast("更新完成");
                } else if (st == android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT
                        || st == android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    toast("安装失败：签名与当前版本不一致，需先卸载");
                } else if (st != android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) {
                    toast("安装失败：" + i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
            }
        };
        android.content.IntentFilter flt = new android.content.IntentFilter(ACTION_INSTALL);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(installRx, flt, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(installRx, flt);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static int indexOf(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equalsIgnoreCase(v)) return i;
        return -1;
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int blend(int a, int b, float t) {
        int aa = Color.alpha(a), ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a);
        int ba = Color.alpha(b), br = Color.red(b), bg = Color.green(b), bb = Color.blue(b);
        return Color.argb(Math.round(aa + (ba - aa) * t), Math.round(ar + (br - ar) * t),
                Math.round(ag + (bg - ag) * t), Math.round(ab + (bb - ab) * t));
    }

    /**
     * 配置读写。优先走 LSPosed 远程偏好（无需 root，框架同步给作用域进程，键名去掉 persist.land.）；
     * 服务不可用时回退旧方案：读 SystemProperties，写 su setprop。
     */
    static final class Prop {
        private static final String PREFIX = "persist.land.";
        private static volatile android.content.SharedPreferences prefs;
        private static volatile Runnable onBound;
        private static boolean listening;

        static synchronized void listen(Runnable cb) {
            onBound = cb;
            if (listening) {
                if (prefs != null && cb != null) cb.run();
                return;
            }
            listening = true;
            io.github.libxposed.service.XposedServiceHelper.registerListener(
                    new io.github.libxposed.service.XposedServiceHelper.OnServiceListener() {
                        @Override
                        public void onServiceBind(io.github.libxposed.service.XposedService s) {
                            try {
                                prefs = s.getRemotePreferences("config");
                                Log.i(TAG, "remote prefs bound, framework=" + s.getFrameworkName() + " api=" + s.getApiVersion());
                            } catch (Throwable t) {
                                Log.w(TAG, "remote prefs: " + t);
                            }
                            Runnable r = onBound;
                            if (r != null) r.run();
                        }

                        @Override
                        public void onServiceDied(io.github.libxposed.service.XposedService s) {
                            prefs = null;
                        }
                    });
        }

        static boolean remote() {
            return prefs != null;
        }

        private static String rk(String key) {
            return key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        }

        static String get(String key, String def) {
            android.content.SharedPreferences p = prefs;
            if (p != null) {
                try {
                    String v = p.getString(rk(key), null);
                    if (v != null && !v.isEmpty()) return v;
                } catch (Throwable ignored) {
                }
            }
            return sys(key, def);
        }

        /** 让作用域进程自行退出重启（远程偏好写时间戳）；无服务时回退 su 命令。 */
        static boolean restart(String cmdKey, String legacyCmd) {
            android.content.SharedPreferences p = prefs;
            if (p != null) {
                try {
                    p.edit().putString(cmdKey, String.valueOf(System.currentTimeMillis())).apply();
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "restart via prefs: " + t);
                }
            }
            return su(legacyCmd);
        }

        private static String sys(String key, String def) {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                String v = (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
                return v == null || v.isEmpty() ? def : v;
            } catch (Throwable t) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String v = r.readLine();
                        return v == null || v.isEmpty() ? def : v.trim();
                    }
                } catch (Throwable t2) {
                    return def;
                }
            }
        }

        static boolean set(String key, String value) {
            if (!key.matches("persist\\.land\\.[a-z_]+") || !value.matches("[A-Za-z0-9._-]{1,32}")) return false;
            android.content.SharedPreferences p = prefs;
            if (p != null) {
                try {
                    p.edit().putString(rk(key), value).apply();
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "prefs write: " + t);
                }
            }
            return su("setprop " + key + " " + value);
        }

        static boolean su(String cmd) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                return p.waitFor() == 0;
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
