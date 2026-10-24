package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

/**
 * HTTP-date 解析的用例表。
 *
 * 这张表来自真实后端返回过的格式：OpenList/nginx 用 RFC 1123，老服务器用
 * RFC 850 / asctime，部分云盘返回 ISO-8601。解析错一位，按时间排序就会
 * 静默错乱（不会再报错），所以每条都必须钉住。
 */
public class HttpDateParserTest {

    /** 2026-01-01T10:00:00Z */
    private static final long T = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli();

    @Test
    public void parsesRfc1123() {
        assertEquals(T, HttpDateParser.parse("Thu, 01 Jan 2026 10:00:00 GMT"));
    }

    @Test
    public void parsesRfc850() {
        assertEquals(T, HttpDateParser.parse("Thursday, 01-Jan-26 10:00:00 GMT"));
    }

    @Test
    public void parsesAsctime() {
        assertEquals(T, HttpDateParser.parse("Thu Jan 1 10:00:00 2026"));
    }

    @Test
    public void parsesIsoVariantsToSameInstant() {
        // 字面 Z、带毫秒、带偏移，都必须是同一时刻
        assertEquals(T, HttpDateParser.parse("2026-01-01T10:00:00Z"));
        assertEquals(T, HttpDateParser.parse("2026-01-01T10:00:00.000Z"));
        assertEquals(T, HttpDateParser.parse("2026-01-01T18:00:00+08:00"));
        assertEquals(T, HttpDateParser.parse("2026-01-01T10:00:00+00:00"));
        assertEquals(T, HttpDateParser.parse("2026-01-01T10:00:00.000Z"));
    }

    @Test
    public void parsesIsoWithoutZoneAsUtc() {
        // 无时区按 UTC 解释；若按手机本地时区解释会产生数小时偏移
        assertEquals(T, HttpDateParser.parse("2026-01-01T10:00:00"));
    }

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals(T, HttpDateParser.parse("  Thu, 01 Jan 2026 10:00:00 GMT  "));
    }

    @Test
    public void unknownInputsReturnZero() {
        assertEquals(0, HttpDateParser.parse(null));
        assertEquals(0, HttpDateParser.parse(""));
        assertEquals(0, HttpDateParser.parse("   "));
        assertEquals(0, HttpDateParser.parse("not a date"));
        // 1970 是服务器「无时间」的占位值，必须当作未知，不能当成合法时间
        assertEquals(0, HttpDateParser.parse("Thu, 01 Jan 1970 00:00:00 GMT"));
        assertEquals(0, HttpDateParser.parse("1970-01-01T00:00:00Z"));
    }

    @Test
    public void trailingGarbageIsRejected() {
        // SimpleDateFormat.parse(String) 会忽略尾部内容；实现要求整串消费完
        assertEquals(0, HttpDateParser.parse("2026-01-01 10:00:00garbage"));
        assertEquals(0, HttpDateParser.parse("Thu, 01 Jan 2026 10:00:00 GMT extra"));
    }

    @Test
    public void dateOnlyIsRejected() {
        // WebDAV 的 getlastmodified 一定有时刻；只有日期说明格式不是我们认识的
        assertEquals(0, HttpDateParser.parse("2026-01-01"));
    }

    @Test
    public void parsesRealOpenListSample() {
        // 真机上抓到的样本（GMT 时区）
        long v = HttpDateParser.parse("Wed, 08 Oct 2025 03:14:07 GMT");
        assertTrue(v > 0);
        assertEquals(Instant.parse("2025-10-08T03:14:07Z").toEpochMilli(), v);
    }
}
