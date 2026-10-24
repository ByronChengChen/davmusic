package com.byron.davmusic;

import android.content.Context;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * 列表与播放列表共用的排序规则（唯一排序入口）。
 *
 * 为什么单独成一个类：目录列表由 FileListAdapter 排，播放队列由 MusicPlayer 排，
 * 两边必须用【同一个比较器】，否则「界面顺序」和「下一首的顺序」会不一致 ——
 * 这种不一致表现为点下一首跳到了界面上不相邻的歌，极难排查。
 *
 * 两种模式：
 *   · MODE_TIME（默认）：按服务器返回的最后修改时间，最新在前
 *   · MODE_NAME：按名称
 *
 * 文件夹始终置顶（便于导航），但两种模式下文件夹【内部】也按对应规则排序。
 */
public final class TrackSorter {

    /** 按修改时间，最新在前。默认值 */
    public static final int MODE_TIME = 0;
    /** 按名称 */
    public static final int MODE_NAME = 1;

    private static final String PREF_NAME = "player_prefs";
    private static final String PREF_KEY_SORT_MODE = "file_sort_mode";

    /**
     * 中文排序器。
     *
     * 不用 String.compareTo / compareToIgnoreCase：那是 UTF-16 码点序，
     * 中文按 Unicode 排出来对用户就是乱序，中英混排更明显。
     * PRIMARY 强度忽略大小写与重音，"ABC"、"abc"、"Abc" 视为同名。
     *
     * 只在主线程（列表渲染 / 排序菜单）使用，因此静态共享一个是安全的。
     */
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    static {
        COLLATOR.setStrength(Collator.PRIMARY);
        COLLATOR.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
    }

    private TrackSorter() {
    }

    public static int getMode(Context context) {
        try {
            int mode = context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getInt(PREF_KEY_SORT_MODE, MODE_TIME);
            return (mode == MODE_TIME || mode == MODE_NAME) ? mode : MODE_TIME;
        } catch (Exception e) {
            return MODE_TIME;
        }
    }

    public static void setMode(Context context, int mode) {
        if (mode != MODE_TIME && mode != MODE_NAME) return;
        try {
            context.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_KEY_SORT_MODE, mode)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** 取某个模式的比较器（每次都返回新实例，内部共用 Collator） */
    public static Comparator<WebDAVFile> comparator(final int mode) {
        return (a, b) -> {
            if (a == b) return 0;
            if (a == null) return 1;
            if (b == null) return -1;

            // 1) 文件夹置顶：导航优先，两种模式一致
            boolean ac = a.isCollection();
            boolean bc = b.isCollection();
            if (ac != bc) return ac ? -1 : 1;

            // 2) 时间模式先比时间，最新在前
            if (mode == MODE_TIME) {
                long ta = a.getLastModified();
                long tb = b.getLastModified();
                boolean ka = ta > 0;
                boolean kb = tb > 0;
                if (ka && kb) {
                    if (ta != tb) return ta > tb ? -1 : 1;
                } else if (ka != kb) {
                    // 时间未知（服务器没返回 / 返回 1970）的条目沉到底部，
                    // 而不是按 0 排到最前面冒充「最旧」
                    return ka ? -1 : 1;
                }
            }

            // 3) 名称：自然序 + 中文 Collator
            int c = compareNames(a.getDisplayName(), b.getDisplayName());
            if (c != 0) return c;

            // 4) 兜底：同一时间且同名（不同目录/不同服务器）时用路径定序，
            //    保证排序结果确定 —— 否则 PROPFIND 返回顺序一变，
            //    列表就会在两次刷新之间「自己乱跳」
            String pa = a.getRelativePath() == null ? "" : a.getRelativePath();
            String pb = b.getRelativePath() == null ? "" : b.getRelativePath();
            c = pa.compareTo(pb);
            if (c != 0) return c;

            String sa = a.getServerId() == null ? "" : a.getServerId();
            String sb = b.getServerId() == null ? "" : b.getServerId();
            return sa.compareTo(sb);
        };
    }

    /**
     * 名称比较：数字按数值比（第2首 排在 第10首 前），其余按中文 Collator。
     *
     * 不能用「整串 Collator 比较」代替：那样 "Track 10" 会排在 "Track 2" 前面，
     * 专辑里连续编号的曲目看起来就是乱的。
     */
    public static int compareNames(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i);
                String nb = b.substring(sj, j);

                String ta = stripLeadingZeros(na);
                String tb = stripLeadingZeros(nb);
                int c = ta.length() != tb.length()
                        ? Integer.compare(ta.length(), tb.length())
                        : ta.compareTo(tb);
                if (c != 0) return c;

                // 数值相同（"01" vs "1"）：视为同一段，继续比后面的文本。
                // 不能按位数决定先后 —— 补零只是排版习惯，
                // "01 - a" 与 "1 - b" 应当由 "- a" / "- b" 决定顺序。
            } else {
                int si = i, sj = j;
                while (i < a.length() && !Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && !Character.isDigit(b.charAt(j))) j++;
                int c = COLLATOR.compare(a.substring(si, i), b.substring(sj, j));
                if (c != 0) return c;
            }
        }
        return Integer.compare(a.length(), b.length());
    }

    private static String stripLeadingZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
