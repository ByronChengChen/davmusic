package com.byron.davmusic;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析（纯逻辑，不依赖 Android）。
 *
 * 为什么单独成类：歌词同步的全部难点都在这里 —— 编码、时间标签、offset、
 * 一行多标签、排序。这些错了不会崩，只会「歌词慢半拍」或「中文全是乱码」，
 * 所以必须在纯 JVM 上钉死（`LrcParserTest`）。
 */
public final class LrcParser {

    /** 一行歌词：时间点 + 文本（文本可为空，表示间奏） */
    public static final class Line {
        public final long timeMs;
        public final String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return timeMs + "ms " + text;
        }
    }

    /** [mm:ss]、[mm:ss.xx]、[mm:ss.xxx]，少数播放器用 [mm:ss:xx] */
    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    private static final Pattern OFFSET_TAG =
            Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {
    }

    /**
     * 把 LRC 文本解析成按时间升序的行。
     *
     * @return 永不为 null；没有任何时间标签时返回空列表（调用方据此显示「暂无歌词」）
     */
    public static List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        String[] rawLines = text.split("\r\n|\r|\n");

        // 先扫 offset：它作用于整份歌词，必须在解析各行之前知道
        int offset = 0;
        for (String raw : rawLines) {
            Matcher om = OFFSET_TAG.matcher(raw);
            if (om.find()) {
                try {
                    offset = Integer.parseInt(om.group(1));
                } catch (NumberFormatException ignored) {
                }
                break;
            }
        }

        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                long ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    ms = Long.parseLong(frac);
                    // 两位是百分之一秒，三位是毫秒，一位是十分之一秒
                    if (frac.length() == 1) ms *= 100;
                    else if (frac.length() == 2) ms *= 10;
                }
                times.add(min * 60000L + sec * 1000L + ms);
                lastEnd = m.end();
            }
            if (times.isEmpty()) continue;   // [ti:] / [ar:] 等元信息行

            String content = line.substring(lastEnd).trim();
            for (long t : times) {
                // LRC 规范：offset 为正表示歌词提前显示
                out.add(new Line(Math.max(0L, t - offset), content));
            }
        }

        out.sort(Comparator.comparingLong(l -> l.timeMs));
        return out;
    }

    /**
     * 与歌曲同目录、同名、扩展名换成 .lrc 的路径。
     *
     * v1 只支持这种命名（用户已确认）：`a/b/歌.mp3` → `a/b/歌.lrc`。
     * 命名不一致的情况（`歌.mp3.lrc`、`歌手 - 歌名.lrc`）一律当「没有歌词」，
     * 不去猜、也不列目录。
     *
     * @return 路径异常时返回 null
     */
    public static String siblingLrcPath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        int slash = relativePath.lastIndexOf('/');
        String dir = slash >= 0 ? relativePath.substring(0, slash + 1) : "";
        String name = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        if (name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isEmpty()) return null;
        return dir + base + ".lrc";
    }

    /**
     * 把字节解码成文本。
     *
     * 顺序：BOM(UTF-8/UTF-16) → 严格 UTF-8 → GB18030 → 宽松 UTF-8。
     * 直接按 UTF-8 读 GBK 歌词，中文会变成「锟斤拷」；而 GB18030 几乎不会
     * 解码失败，所以它必须放在严格 UTF-8 之后，否则所有 UTF-8 文件都会被它抢走。
     */
    public static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) return "";

        int off = 0;
        if (raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            off = 3;
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            return new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        }

        String utf8 = decodeStrict(raw, off, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;

        String gb = decodeStrict(raw, off, Charset.forName("GB18030"));
        if (gb != null) return gb;

        // 两种都失败：按 UTF-8 宽松解，至少保证有内容可显示
        return new String(raw, off, raw.length - off, StandardCharsets.UTF_8);
    }

    private static String decodeStrict(byte[] raw, int off, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, off, raw.length - off))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
