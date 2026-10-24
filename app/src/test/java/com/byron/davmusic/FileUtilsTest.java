package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 扩展名判定与格式化（纯 JVM）。
 *
 * 这些方法决定「列表里显示什么」与「播放器认不认这个文件」，
 * 判定宽了会把封面/歌词当歌播，窄了会让 .opus/.ape 消失。
 */
public class FileUtilsTest {

    @Test
    public void recognizesAllSupportedAudioExtensions() {
        String[] names = {"a.mp3", "a.m4a", "a.flac", "a.wav", "a.wma",
                "a.aac", "a.ogg", "a.ape", "a.opus"};
        for (String n : names) {
            assertTrue(n + " 应被识别为音频", FileUtils.isAudioFile(n));
        }
    }

    @Test
    public void audioDetectionIsCaseInsensitive() {
        assertTrue(FileUtils.isAudioFile("SONG.MP3"));
        assertTrue(FileUtils.isAudioFile("Song.FlAc"));
    }

    @Test
    public void nonAudioFilesAreRejected() {
        assertFalse(FileUtils.isAudioFile("cover.jpg"));
        assertFalse(FileUtils.isAudioFile("lyrics.lrc"));
        assertFalse(FileUtils.isAudioFile("notes.txt"));
        assertFalse(FileUtils.isAudioFile(null));
        assertFalse(FileUtils.isAudioFile(""));
    }

    @Test
    public void lyricsDetectionCoversLrcAndTtml() {
        assertTrue(FileUtils.isLyricsFile("a.lrc"));
        assertTrue(FileUtils.isLyricsFile("a.TTML"));
        assertFalse(FileUtils.isLyricsFile("a.txt"));
    }

    @Test
    public void imageDetection() {
        assertTrue(FileUtils.isImageFile("cover.JPG"));
        assertTrue(FileUtils.isImageFile("cover.webp"));
        assertFalse(FileUtils.isImageFile("song.mp3"));
    }

    @Test
    public void extensionHelpers() {
        assertEquals("mp3", FileUtils.getFileExtension("a.b.MP3"));
        assertEquals("", FileUtils.getFileExtension("noext"));
        assertEquals("", FileUtils.getFileExtension(".hidden"));   // 点开头的隐藏文件没有扩展名
        assertEquals("a.b", FileUtils.getFileNameWithoutExtension("a.b.mp3"));
        assertEquals("noext", FileUtils.getFileNameWithoutExtension("noext"));
    }

    @Test
    public void formatsFileSize() {
        assertEquals("0 B", FileUtils.formatFileSize(0));
        assertEquals("0 B", FileUtils.formatFileSize(-1));
        assertEquals("1 KB", FileUtils.formatFileSize(1024));
        assertEquals("1.5 KB", FileUtils.formatFileSize(1536));
        assertEquals("1 MB", FileUtils.formatFileSize(1024L * 1024));
    }

    @Test
    public void formatsDuration() {
        assertEquals("--:--", FileUtils.formatDuration(0));
        assertEquals("--:--", FileUtils.formatDuration(-5));
        assertEquals("00:01", FileUtils.formatDuration(1000));
        assertEquals("01:01", FileUtils.formatDuration(61000));
        // 超过一小时不折成小时，继续按分钟累计（与播放器显示一致）
        assertEquals("61:01", FileUtils.formatDuration(3661000));
    }

    @Test
    public void pathHelpers() {
        assertEquals("b", FileUtils.getFileNameFromPath("/a/b/"));
        assertEquals("b", FileUtils.getFileNameFromPath("/a/b"));
        assertEquals("/a", FileUtils.getParentPath("/a/b"));
        assertEquals("", FileUtils.getParentPath("/a"));
        assertEquals("", FileUtils.getFileNameFromPath(""));
    }
}
