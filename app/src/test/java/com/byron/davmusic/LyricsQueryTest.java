package com.byron.davmusic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 文件名 → 搜索关键词（纯 JVM）。搜错关键词会直接搜不到歌词，所以逐条钉住。 */
public class LyricsQueryTest {

    @Test
    public void stripsTrackNumberAndExtension() {
        assertEquals("爱 因为在心中", LyricsQuery.fromFileName("10 爱 因为在心中.m4a"));
        assertEquals("夜曲", LyricsQuery.fromFileName("01 - 夜曲.flac"));
        assertEquals("歌名", LyricsQuery.fromFileName("03.歌名.mp3"));
        assertEquals("歌名", LyricsQuery.fromFileName("7、歌名.m4a"));
    }

    @Test
    public void stripsChineseTrackNumber() {
        assertEquals("夜曲", LyricsQuery.fromFileName("第10首 夜曲.mp3"));
    }

    @Test
    public void keepsYearLikePrefixesIntact() {
        // 四位数字可能是歌名的一部分（2012），不能当曲目编号去掉
        assertEquals("2012 世界末日", LyricsQuery.fromFileName("2012 世界末日.mp3"));
    }

    @Test
    public void keepsNamesWithoutNumbers() {
        assertEquals("夜曲", LyricsQuery.fromFileName("夜曲.mp3"));
        assertEquals("song", LyricsQuery.fromFileName("song"));
    }

    @Test
    public void replacesUnderscoresWithSpaces() {
        assertEquals("我 的 歌", LyricsQuery.fromFileName("我_的_歌.mp3"));
    }

    @Test
    public void handlesEmptyInput() {
        assertEquals("", LyricsQuery.fromFileName(null));
        assertEquals("", LyricsQuery.fromFileName(""));
    }
}
