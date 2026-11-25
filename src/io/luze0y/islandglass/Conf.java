package io.luze0y.islandglass;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 作用域进程（SystemUI / 原子岛插件 / 设置）里的配置读取。
 *
 * 只读 LSPosed 远程偏好（模块界面写入，框架同步到各作用域进程），键名 = persist.land.* 去掉前缀；
 * 未设置或远程偏好不可用时用默认值。
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
        if (p == null) return;
        keep = (sp, k) -> {
            if (k == null) return;
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
        if (p == null) return def;
        try {
            String v = p.getString(key(prop), null);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }
}
