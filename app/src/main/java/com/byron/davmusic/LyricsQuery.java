package com.byron.davmusic;

/**
 * 从文件名推导歌词搜索关键词（纯逻辑，可纯 JVM 测）。
 *
 * 文件名往往带曲目编号和扩展名（`10 爱 因为在心中.m4a`、`01 - 夜曲.flac`），
 * 直接拿整串去搜会把「10」当成歌名的一部分。这里只做保守的规范化，
 * 不做「歌手 - 歌名」的拆分：拆错比不拆更糟，用户在搜索框里能自己改。
 */
public final class LyricsQuery {

    private LyricsQuery() {
    }

    public static String fromFileName(String displayName) {
        if (displayName == null) return "";
        String s = displayName.trim();
        if (s.isEmpty()) return "";

        // 去扩展名（只认最后一个点之后、长度 1-5 的扩展名）
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            String ext = s.substring(dot + 1);
            if (!ext.isEmpty() && ext.length() <= 5 && ext.matches("[A-Za-z0-9]+")) {
                s = s.substring(0, dot);
            }
        }

        s = s.replace('_', ' ').trim();

        // 去掉「第10首 / 第 3 集」这类中文编号
        s = s.replaceFirst("^第\\s*\\d{1,3}\\s*[首集话章]\\s*", "");

        // 去掉开头的曲目编号：`10 爱…`、`01 - 夜曲`、`03.歌名`
        // 只认 1-3 位数字，避免把 `2012 世界末日` 这种以年份开头的歌名误伤
        s = s.replaceFirst("^\\d{1,3}\\s*[-._、 ]\\s*", "");

        return s.trim();
    }
}
