package io.luze0y.islandglass;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「抄作业」参数包：原子岛 / 系统液态玻璃 / 通知中心三组可分享的参数（不含总开关、诊断日志、设置入口）。
 *
 * 分享码是明文：「咕嘎」后面跟着与默认值不同的参数，每项写成 编号=值，用空格分隔，例如
 *   咕嘎 i7=0.05 s2=0.80 n2=2.40
 * 编号 = 分组字母（i 原子岛 / s 系统液态玻璃 / n 通知中心）+ 该参数在分组里的序号（从 1 开始，见 KEYS）。
 * 没写出来的参数就是默认值，所以全部默认时分享码只有「咕嘎」两个字。
 * 一个参数包总是包含三个分组的全部参数。KEYS 的顺序就是编号：新增参数只能追加在所在分组末尾，
 * 旧分享码里没有的新参数按默认值处理；不认识的编号（来自更新的版本）忽略并计数。
 *
 * 使用中的参数包存在远程偏好 pk_<键>；copy_<分组>=1 时 Conf.get / 模块界面对该分组的键优先取参数包里的值，
 * 模块界面的修改也写进参数包，用户自己的设置不动。
 */
final class Pack {
    static final String PK = "pk_", MAGIC = "咕嘎";
    static final String[] SECTIONS = {"island", "sysglass", "nc"};
    static final String[] NAMES = {"原子岛", "系统液态玻璃", "通知中心"};
    private static final char[] LETTERS = {'i', 's', 'n'};
    /** 分组的参数包状态变化时通知各模块用的代表键（各模块按键名前缀过滤订阅）。 */
    private static final String[] TRIGGER = {"island_glass", "sysglass_tint", "nc_clock_scale"};

    /** 各分组可分享的键与默认值（与模块界面一致）。顺序即分享码编号，只能在末尾追加。 */
    static final String[][][] KEYS = {
            {
                    {"island_glass", "strong"}, {"island_glass_trans", "1"}, {"island_glass_sync", "1"},
                    {"island_glass_invert", "1"}, {"island_glass_ct", "2"}, {"island_glass_fps", "0"},
                    {"island_glass_blur", "0"}, {"island_glass_refr", "-1"}, {"island_glass_refr_r", "-1"},
            },
            {
                    {"sysglass_excl_other", "1"}, {"sysglass_tint", "1"}, {"sysglass_blur", "1"},
                    {"sysglass_sat", "1"}, {"sysglass_alpha", "1"}, {"sysglass_zero_tint", "1"},
                    {"sysglass_zero_blur", "1"}, {"sysglass_bg_blur", "1"}, {"sysglass_refr", "1"},
                    {"sysglass_refr_r", "1"}, {"sysglass_light", "1"}, {"sysglass_light_w", "1"},
            },
            {
                    {"nc_bigclock", "1"}, {"nc_clock_scale", "2"}, {"nc_clock_offset", "42"},
                    {"nc_clock_font", "sys"}, {"nc_clock_wght", "-1"}, {"nc_land_split", "1"},
            },
    };

    private static final Pattern VAL = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final Pattern ITEM = Pattern.compile("([A-Za-z])(\\d{1,3})\\s*[=＝]\\s*([A-Za-z0-9._-]{1,32})");
    private static final Map<String, String> SEC = new HashMap<>();

    static {
        for (int i = 0; i < KEYS.length; i++) for (String[] kd : KEYS[i]) SEC.put(kd[0], SECTIONS[i]);
    }

    private Pack() {
    }

    /** 键（去掉 persist.land. 前缀）所属分组；不可分享的键返回 null。 */
    static String section(String key) {
        return SEC.get(key);
    }

    static String trigger(String sec) {
        for (int i = 0; i < SECTIONS.length; i++) if (SECTIONS[i].equals(sec)) return TRIGGER[i];
        return null;
    }

    static boolean validValue(String v) {
        return v != null && VAL.matcher(v).matches();
    }

    /** 两个值是否等价（数值按大小比较，1 与 1.00 相同）。 */
    private static boolean same(String a, String b) {
        if (a.equals(b)) return true;
        try {
            return Double.parseDouble(a) == Double.parseDouble(b);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** values：键 -> 值。只写出与默认值不同的参数；缺失或不合法的按默认值处理。 */
    static String encode(Map<String, String> values) {
        StringBuilder b = new StringBuilder(MAGIC);
        for (int i = 0; i < KEYS.length; i++) {
            for (int j = 0; j < KEYS[i].length; j++) {
                String v = values.get(KEYS[i][j][0]);
                if (!validValue(v) || same(v, KEYS[i][j][1])) continue;
                b.append(' ').append(LETTERS[i]).append(j + 1).append('=').append(v);
            }
        }
        return b.toString();
    }

    /** 文本里是否带着分享码。 */
    static boolean looksLike(String text) {
        return text != null && text.contains(MAGIC);
    }

    static final class Parsed {
        final Map<String, String> values = new LinkedHashMap<>();
        final int[] counts = new int[SECTIONS.length];
        /** 与默认值不同的参数个数。 */
        int changed;
        int skipped;

        List<String> sections() {
            List<String> r = new ArrayList<>();
            for (int i = 0; i < SECTIONS.length; i++) if (counts[i] > 0) r.add(SECTIONS[i]);
            return r;
        }
    }

    /** 各分组项数的简述，例如「原子岛 9 · 通知中心 6」。 */
    static String summary(int[] counts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SECTIONS.length; i++) {
            if (counts[i] <= 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(NAMES[i]).append(' ').append(counts[i]);
        }
        return b.length() == 0 ? "空" : b.toString();
    }

    /**
     * 解析分享码：取「咕嘎」之后的全部 编号=值（允许前后有其他文字、换行或逗号分隔），
     * 其余参数按默认值。不认识的编号、不合法的值计入 skipped。
     */
    static Parsed parse(String text) {
        int at = text == null ? -1 : text.indexOf(MAGIC);
        if (at < 0) throw new IllegalArgumentException("没有找到以「" + MAGIC + "」开头的分享码");
        Parsed p = new Parsed();
        for (int i = 0; i < KEYS.length; i++) {
            for (String[] kd : KEYS[i]) p.values.put(kd[0], kd[1]);
            p.counts[i] = KEYS[i].length;
        }
        Matcher m = ITEM.matcher(text.substring(at + MAGIC.length()));
        while (m.find()) {
            int sec = new String(LETTERS).indexOf(Character.toLowerCase(m.group(1).charAt(0)));
            int idx = Integer.parseInt(m.group(2)) - 1;
            if (sec < 0 || idx < 0 || idx >= KEYS[sec].length) { // 来自更新的版本
                p.skipped++;
                continue;
            }
            String[] kd = KEYS[sec][idx];
            if (!same(m.group(3), kd[1])) p.changed++;
            p.values.put(kd[0], m.group(3));
        }
        return p;
    }
}
