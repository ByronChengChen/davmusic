package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * LRC 解析与编码的用例表（纯 JVM）。
 *
 * 这些规则错了不会崩：中文变乱码、歌词慢半拍、间奏后整段错位。
 * 所以每条行为都钉住，而不是只跑一个「能解析」的冒烟用例。
 */
public class LrcParserTest {

    @Test
    public void parsesSimpleLrc() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:01.00]第一句\n[00:05.50]第二句\n");

        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).timeMs);
        assertEquals("第一句", lines.get(0).text);
        assertEquals(5500L, lines.get(1).timeMs);
        assertEquals("第二句", lines.get(1).text);
    }

    @Test
    public void supportsMillisecondsWithOneTwoOrThreeDigits() {
        // .5 = 500ms，.50 = 500ms，.500 = 500ms
        assertEquals(1500L, LrcParser.parse("[00:01.5]a").get(0).timeMs);
        assertEquals(1500L, LrcParser.parse("[00:01.50]a").get(0).timeMs);
        assertEquals(1500L, LrcParser.parse("[00:01.500]a").get(0).timeMs);
    }

    @Test
    public void supportsColonFractionFormat() {
        // 少数生成器用 [mm:ss:xx]
        assertEquals(1230L, LrcParser.parse("[00:01:23]a").get(0).timeMs);
    }

    @Test
    public void oneLineCanCarrySeveralTimeTags() {
        // 副歌重复段落常见：[00:10.00][01:20.00]同一句
        List<LrcParser.Line> lines = LrcParser.parse("[00:10.00][01:20.00]副歌");

        assertEquals(2, lines.size());
        assertEquals(10000L, lines.get(0).timeMs);
        assertEquals(80000L, lines.get(1).timeMs);
        assertEquals("副歌", lines.get(0).text);
        assertEquals("副歌", lines.get(1).text);
    }

    @Test
    public void offsetMovesLyricsEarlier() {
        // LRC 规范：[offset:+500] 表示歌词提前 500ms 显示
        List<LrcParser.Line> lines = LrcParser.parse("[offset:500]\n[00:10.00]一句");

        assertEquals(9500L, lines.get(0).timeMs);
    }

    @Test
    public void negativeOffsetMovesLyricsLater() {
        List<LrcParser.Line> lines = LrcParser.parse("[offset:-200]\n[00:10.00]一句");

        assertEquals(10200L, lines.get(0).timeMs);
    }

    @Test
    public void offsetCannotProduceNegativeTime() {
        List<LrcParser.Line> lines = LrcParser.parse("[offset:5000]\n[00:01.00]一句");

        assertEquals(0L, lines.get(0).timeMs);
    }

    @Test
    public void metadataTagsAreIgnored() {
        String lrc = "[ti:歌名]\n[ar:歌手]\n[al:专辑]\n[by:某人]\n[00:01.00]正文";
        List<LrcParser.Line> lines = LrcParser.parse(lrc);

        assertEquals(1, lines.size());
        assertEquals("正文", lines.get(0).text);
    }

    @Test
    public void outputIsSortedByTime() {
        // 文件里的行顺序不可信（合并歌词、手工编辑都会乱）
        List<LrcParser.Line> lines = LrcParser.parse(
                "[00:20.00]后\n[00:05.00]先\n[00:10.00]中");

        assertEquals("先", lines.get(0).text);
        assertEquals("中", lines.get(1).text);
        assertEquals("后", lines.get(2).text);
    }

    @Test
    public void emptyLyricLinesAreKeptForInstrumentalGaps() {
        List<LrcParser.Line> lines = LrcParser.parse("[00:01.00]一句\n[00:08.00]");

        assertEquals(2, lines.size());
        assertEquals("", lines.get(1).text);
    }

    @Test
    public void invalidAndEmptyLinesAreSkipped() {
        List<LrcParser.Line> lines = LrcParser.parse(
                "这不是歌词\n\n[xx:yy]坏标签\n[00:03.00]唯一一行");

        assertEquals(1, lines.size());
        assertEquals("唯一一行", lines.get(0).text);
    }

    @Test
    public void emptyInputYieldsEmptyList() {
        assertTrue(LrcParser.parse(null).isEmpty());
        assertTrue(LrcParser.parse("").isEmpty());
    }

    // ---------------- 编码 ----------------

    @Test
    public void decodesUtf8() {
        byte[] raw = "[00:01.00]你好世界".getBytes(StandardCharsets.UTF_8);
        assertEquals("[00:01.00]你好世界", LrcParser.decode(raw));
    }

    @Test
    public void stripsUtf8Bom() {
        byte[] body = "[00:01.00]你好".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[body.length + 3];
        raw[0] = (byte) 0xEF;
        raw[1] = (byte) 0xBB;
        raw[2] = (byte) 0xBF;
        System.arraycopy(body, 0, raw, 3, body.length);

        assertEquals("[00:01.00]你好", LrcParser.decode(raw));
        // BOM 若没去掉，第一行的标签会解析不出来
        assertEquals(1, LrcParser.parse(LrcParser.decode(raw)).size());
    }

    @Test
    public void decodesGbkLyricsAsChineseNotReplacementChars() {
        // 中文环境大量老 LRC 是 GBK；按 UTF-8 强解会变乱码
        Charset gbk = Charset.forName("GB18030");
        byte[] raw = "[00:01.00]后来".getBytes(gbk);

        String text = LrcParser.decode(raw);
        assertEquals("[00:01.00]后来", text);
        List<LrcParser.Line> lines = LrcParser.parse(text);
        assertEquals("后来", lines.get(0).text);
    }

    @Test
    public void decodesUtf16LittleEndianWithBom() {
        byte[] body = "[00:01.00]好".getBytes(StandardCharsets.UTF_16LE);
        byte[] raw = new byte[body.length + 2];
        raw[0] = (byte) 0xFF;
        raw[1] = (byte) 0xFE;
        System.arraycopy(body, 0, raw, 2, body.length);

        assertEquals("[00:01.00]好", LrcParser.decode(raw));
    }

    // ---------------- 歌词路径规则 ----------------

    @Test
    public void siblingLrcPathKeepsDirectoryAndReplacesExtension() {
        assertEquals("cmcc/music/欧美/夜曲.lrc",
                LrcParser.siblingLrcPath("cmcc/music/欧美/夜曲.mp3"));
        assertEquals("夜曲.lrc", LrcParser.siblingLrcPath("夜曲.m4a"));
    }

    @Test
    public void siblingLrcPathHandlesNamesWithDotsAndNoExtension() {
        assertEquals("a/歌.name.lrc", LrcParser.siblingLrcPath("a/歌.name.flac"));
        assertEquals("a/歌.lrc", LrcParser.siblingLrcPath("a/歌"));
    }

    @Test
    public void siblingLrcPathRejectsBadInput() {
        assertNull(LrcParser.siblingLrcPath(null));
        assertNull(LrcParser.siblingLrcPath(""));
        assertNull(LrcParser.siblingLrcPath("dir/"));
    }
}
