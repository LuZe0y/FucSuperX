package io.luze0y.islandglass;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 作用域进程（SystemUI / 原子岛插件 / 设置）里的配置读取。
 *
 * 优先读 LSPosed 远程偏好（模块界面无 root 写入，框架同步到各作用域进程）；
 * 键名 = persist.land.* 去掉前缀。未设置时回退 SystemProperties（兼容旧的 persist.land.*），再回退默认值。
 *
 * 另外监听 cmd_restart_island / cmd_restart_settings：模块界面写入时间戳，对应进程自行退出，
 * 由系统重新拉起，替代原先的 su pkill / am force-stop。
 */
final class Conf {
    static final String GROUP = "config";
    private static final String TAG = "FucSuperX";
    private static final String PREFIX = "persist.land.";

    private static volatile SharedPreferences prefs;
    @SuppressWarnings("FieldCanBeLocal")
    private static SharedPreferences.OnSharedPreferenceChangeListener keep; // 监听器是弱引用，需要强持有

    private Conf() {
    }

    static void init(XposedInterface x, String process) {
        SharedPreferences p;
        try {
            p = x.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            Log.w(TAG, "remote prefs unavailable in " + process + ": " + t);
            return;
        }
        prefs = p;
        if (p == null || process == null) return;
        final String cmd = process.endsWith(":island") ? "cmd_restart_island"
                : process.startsWith("com.android.settings") ? "cmd_restart_settings"
                : "com.android.systemui".equals(process) ? "cmd_restart_systemui" : null;
        keep = (sp, k) -> {
            if (k == null) return;
            if (k.equals(cmd)) {
                Log.i(TAG, "restart requested by module UI, exiting " + process);
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }
            for (java.util.function.Consumer<String> c : SUBS) {
                try {
                    c.accept(k);
                } catch (Throwable t) {
                    Log.w(TAG, "conf subscriber: " + t);
                }
            }
        };
        try {
            p.registerOnSharedPreferenceChangeListener(keep);
        } catch (Throwable t) {
            Log.w(TAG, "prefs listener: " + t);
        }
    }

    private static final java.util.List<java.util.function.Consumer<String>> SUBS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 订阅配置变更（参数为去掉 persist.land. 前缀的键名），在 Binder 线程回调。 */
    static void subscribe(java.util.function.Consumer<String> c) {
        SUBS.add(c);
    }

    static String key(String prop) {
        return prop.startsWith(PREFIX) ? prop.substring(PREFIX.length()) : prop;
    }

    static String get(String prop, String def) {
        SharedPreferences p = prefs;
        if (p != null) {
            try {
                String v = p.getString(key(prop), null);
                if (v != null && !v.isEmpty()) return v;
            } catch (Throwable ignored) {
            }
        }
        try {
            String v = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class).invoke(null, prop, def);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }
}
