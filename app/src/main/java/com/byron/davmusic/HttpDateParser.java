package com.byron.davmusic;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * WebDAV 时间解析（RFC 4918 规定为 HTTP-date）。
 *
 * 单独成一个类、不依赖 Android：这是「按时间排序」的数据基础，
 * 必须能用纯 JVM 的用例表验证（各种后端返回的格式不一样）。
 */
public final class HttpDateParser {

    private static final String[] PATTERNS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",   // RFC 1123，OpenList/nginx 等
            "EEEE, dd-MMM-yy HH:mm:ss zzz",    // RFC 850（老服务器）
            "EEE MMM d HH:mm:ss yyyy",         // asctime（更老）
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",    // ISO-8601 带毫秒/偏移
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",    // 字面 Z（UTC）
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",           // 无时区，按 UTC
            "yyyy-MM-dd HH:mm:ss"
    };

    private HttpDateParser() {
    }

    /**
     * @return 毫秒时间戳；无法解析或服务器返回 1970 等占位值时返回 0（= 未知）
     */
    public static long parse(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        if (s.isEmpty()) return 0;

        for (String pattern : PATTERNS) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
                f.setLenient(false);
                // 带 "zzz"/"XXX" 的格式自带时区；其余（含字面 Z）统一按 UTC，
                // 否则会按手机本地时区解释，产生数小时偏移
                if (!pattern.endsWith("zzz") && !pattern.endsWith("XXX")) {
                    f.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
                // 必须整串消费完才算解析成功：SimpleDateFormat.parse(String)
                // 允许尾部有多余字符，会把 "2026-01-01 10:00:00garbage"
                // 也当成合法时间
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                Date d = f.parse(s, pos);
                if (d != null && pos.getIndex() == s.length() && d.getTime() > 0) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
                // 换下一个格式
            }
        }
        return 0;
    }
}
