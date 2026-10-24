package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

/**
 * PROPFIND 解析的用例表（纯 JVM，DOM 解析器来自 JDK）。
 *
 * 这段逻辑历史上出过两次真事故，都在这张表里：
 *   · v1.41 中文目录显示成 %E5%8A%A8%E7%94%BB —— urlDecode 实际没解码
 *   · v1.42 之前 getlastmodified 被丢弃、时间被伪造成当前时刻 —— 按时间排序刷新即乱
 * 另外两条是 WebDAV 客户端的经典坑：同名目录自身混进列表、'+' 被当空格。
 */
public class WebDAVResponseParserTest {

    private static final String BASE = "/dav/";
    private static final long TIME = Instant.parse("2026-01-01T10:00:00Z").toEpochMilli();

    private static String entry(String href, String propInner) {
        return "<D:response><D:href>" + href + "</D:href><D:propstat><D:prop>"
                + propInner
                + "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>";
    }

    private static String multistatus(String... entries) {
        StringBuilder sb = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">");
        for (String e : entries) sb.append(e);
        return sb.append("</D:multistatus>").toString();
    }

    private static List<WebDAVFile> parse(String xml, String requestedPath) throws Exception {
        return WebDAVResponseParser.parse(xml, requestedPath, BASE, null);
    }

    @Test
    public void dropsTheDirectoryItself() throws Exception {
        // Depth:1 的首条是目录自身；不剔除的话界面会出现同名文件夹
        String xml = multistatus(
                entry("/dav/cmcc/music/", "<D:resourcetype><D:collection/></D:resourcetype>"),
                entry("/dav/cmcc/music/a.mp3", ""));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertEquals(1, files.size());
        assertEquals("a.mp3", files.get(0).getDisplayName());
    }

    @Test
    public void decodesPercentEncodedChineseNames() throws Exception {
        // v1.41 的回归：href 里的 %XX 必须真的解出来
        String xml = multistatus(
                entry("/dav/cmcc/music/%E5%8A%A8%E7%94%BB/",
                        "<D:resourcetype><D:collection/></D:resourcetype>"));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertEquals(1, files.size());
        assertEquals("动画", files.get(0).getDisplayName());
        assertEquals("cmcc/music/动画", files.get(0).getRelativePath());
    }

    @Test
    public void keepsPlusAsLiteralCharacter() throws Exception {
        // form-urlencoded 语义会把 '+' 解成空格，导致这类文件名请求 404
        String xml = multistatus(
                entry("/dav/cmcc/music/nobodyknows+-Hero%27s+Come+Back!!.m4a", ""));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertEquals("nobodyknows+-Hero's+Come+Back!!.m4a", files.get(0).getDisplayName());
    }

    @Test
    public void detectsFoldersByResourceTypeOrTrailingSlash() throws Exception {
        String xml = multistatus(
                entry("/dav/cmcc/music/by-type", "<D:resourcetype><D:collection/></D:resourcetype>"),
                entry("/dav/cmcc/music/by-slash/", "<D:resourcetype/>"),
                entry("/dav/cmcc/music/song.mp3",
                        "<D:resourcetype/><D:getcontentlength>1024</D:getcontentlength>"));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertTrue(files.get(0).isCollection());
        assertTrue(files.get(1).isCollection());
        assertFalse(files.get(2).isCollection());
    }

    @Test
    public void readsStandardProperties() throws Exception {
        String xml = multistatus(
                entry("/dav/cmcc/music/a.mp3",
                        "<D:getcontentlength>5242880</D:getcontentlength>"
                                + "<D:getcontenttype>audio/mpeg</D:getcontenttype>"
                                + "<D:getetag>\"abc123\"</D:getetag>"
                                + "<D:getlastmodified>Thu, 01 Jan 2026 10:00:00 GMT</D:getlastmodified>"));

        WebDAVFile f = parse(xml, "/cmcc/music").get(0);

        assertEquals(5242880L, f.getContentLength());
        assertEquals("audio/mpeg", f.getContentType());
        assertEquals("\"abc123\"", f.getEtag());
    }

    @Test
    public void usesServerTimestampInsteadOfNow() throws Exception {
        // 这条是 v1.42 的核心回归：时间必须来自 getlastmodified
        String xml = multistatus(
                entry("/dav/cmcc/music/a.mp3",
                        "<D:getlastmodified>Thu, 01 Jan 2026 10:00:00 GMT</D:getlastmodified>"));

        WebDAVFile f = parse(xml, "/cmcc/music").get(0);

        assertEquals(TIME, f.getLastModified());
    }

    @Test
    public void missingOrBadTimestampBecomesUnknown() throws Exception {
        String xml = multistatus(
                entry("/dav/cmcc/music/a.mp3", ""),
                entry("/dav/cmcc/music/b.mp3",
                        "<D:getlastmodified>not a date</D:getlastmodified>"));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertEquals(0, files.get(0).getLastModified());
        assertEquals(0, files.get(1).getLastModified());
    }

    @Test
    public void skipsSystemMetadataFiles() throws Exception {
        String xml = multistatus(
                entry("/dav/cmcc/music/._a.mp3", ""),
                entry("/dav/cmcc/music/.DS_Store", ""),
                entry("/dav/cmcc/music/Thumbs.db", ""),
                entry("/dav/cmcc/music/real.mp3", ""));

        List<WebDAVFile> files = parse(xml, "/cmcc/music");

        assertEquals(1, files.size());
        assertEquals("real.mp3", files.get(0).getDisplayName());
    }

    @Test
    public void acceptsAbsoluteHrefWithHost() throws Exception {
        // 部分服务器返回完整 URL；剥前缀前要先取出 path
        String xml = multistatus(
                entry("https://cloud.example.com/dav/cmcc/music/a.mp3",
                        "<D:getcontentlength>10</D:getcontentlength>"));

        WebDAVFile f = parse(xml, "/cmcc/music").get(0);

        assertEquals("a.mp3", f.getDisplayName());
        assertEquals("cmcc/music/a.mp3", f.getRelativePath());
    }

    @Test
    public void skipsEntriesWithoutHref() throws Exception {
        String xml = multistatus(
                entry("", "<D:getcontentlength>1</D:getcontentlength>"),
                entry("/dav/cmcc/music/a.mp3", ""));

        assertEquals(1, parse(xml, "/cmcc/music").size());
    }

    @Test
    public void stampsOwnerServerOnEveryEntry() throws Exception {
        ServerProfile p = new ServerProfile("id-1", "我的云盘", "https://cloud.example.com/dav/", "u", "p");
        String xml = multistatus(entry("/dav/cmcc/music/a.mp3", ""));

        List<WebDAVFile> files =
                WebDAVResponseParser.parse(xml, "/cmcc/music", BASE, p);

        assertEquals("id-1", files.get(0).getServerId());
        assertEquals("我的云盘", files.get(0).getServerName());
        assertEquals("id-1:cmcc/music/a.mp3", files.get(0).getCacheKey());
    }

    @Test
    public void handlesRootRequest() throws Exception {
        // 请求 "/" 时目录自身的 relPath 是空串
        String xml = multistatus(
                entry("/dav/", "<D:resourcetype><D:collection/></D:resourcetype>"),
                entry("/dav/cmcc/", "<D:resourcetype><D:collection/></D:resourcetype>"));

        List<WebDAVFile> files = parse(xml, "/");

        assertEquals(1, files.size());
        assertEquals("cmcc", files.get(0).getDisplayName());
    }

    @Test
    public void getBasePathExtractsUrlPath() {
        assertEquals("/dav/", WebDAVResponseParser.getBasePath("https://h/dav/"));
        // 不带尾斜杠时原样返回；stripBasePath 自己会容忍两种写法
        assertEquals("/dav", WebDAVResponseParser.getBasePath("https://h/dav"));
        assertEquals("/", WebDAVResponseParser.getBasePath("https://h"));
        assertEquals("/", WebDAVResponseParser.getBasePath("not a url"));
    }
}
