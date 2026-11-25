package io.luze0y.islandglass;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 作用域进程（SystemUI / 原子岛插件 / 设置）里的配置读取。
 *
 * 只读 LSPosed 远程偏好（模块界面写入，框架同步到各作用域进程），键名 = persist.land.* 去掉前缀；
 * 未设置或远程偏好不可用时用默认值。
 *
 * 「抄作业」：copy_<分组>=1 时，该分组可分享的键（见 Pack）优先取参数包里的 pk_<键>，包里没有再取用户自己的值。
 * 同一分组 120ms 内的多次变化（导入参数包、恢复默认参数包等一次改很多键）节流：首个立即通知，
 * 其余合并为一次代表键通知（各模块按键名前缀过滤订阅，收到任一键都会整体重新读取）。
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
            if (k == null || k.startsWith("pack_")) return;
            String key = k, sec;
            if (k.startsWith("copy_")) {
                sec = k.substring(5);
                key = Pack.trigger(sec);
                if (key == null) return;
            } else {
                if (k.startsWith(Pack.PK)) key = k.substring(Pack.PK.length());
                sec = Pack.section(key);
                if (sec == null) {
                    if (!k.startsWith(Pack.PK)) dispatch(k);
                    return;
                }
            }
            throttle(sec, key);
        };
        try {
            p.registerOnSharedPreferenceChangeListener(keep);
        } catch (Throwable t) {
            Log.w(TAG, "prefs listener: " + t);
        }
    }

    private static void dispatch(String k) {
        for (java.util.function.Consumer<String> c : SUBS) {
            try {
                c.accept(k);
            } catch (Throwable t) {
                Log.w(TAG, "conf subscriber: " + t);
            }
        }
    }

    private static final long WINDOW = 120;
    private static final java.util.Map<String, Long> LAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile android.os.Handler main;

    /** 按分组节流：窗口外的变化立即通知；窗口内的合并为窗口结束时一次代表键通知（在主线程）。 */
    private static void throttle(String sec, String key) {
        long now = android.os.SystemClock.uptimeMillis();
        Long last = LAST.get(sec);
        if (last == null || now - last >= WINDOW) {
            LAST.put(sec, now);
            dispatch(key);
            return;
        }
        if (!PENDING.add(sec)) return;
        android.os.Handler h = main;
        if (h == null) main = h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> {
            PENDING.remove(sec);
            LAST.put(sec, android.os.SystemClock.uptimeMillis());
            dispatch(Pack.trigger(sec));
        }, Math.max(1, last + WINDOW - now));
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
            String k = key(prop), sec = Pack.section(k);
            if (sec != null && "1".equals(p.getString("copy_" + sec, "0"))) {
                String v = p.getString(Pack.PK + k, null);
                if (v != null && !v.isEmpty()) return v;
            }
            String v = p.getString(k, null);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }
}
