package com.byron.davmusic;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * PROPFIND 响应解析（从 WebDAVClient 抽出的纯逻辑）。
 *
 * 为什么单独成一个类：这段解析过去内联在 WebDAVClient 里，只能靠「真机 + 云端构建」
 * 验证，而它恰好是历史上出错最多的地方（href 前缀、中文 URL 编码、目录自身重复、
 * 时间被伪造成 now）。抽出来后不依赖 Context / OkHttp，可以在纯 JVM 上跑用例表，
 * 把「v1.41 中文目录显示成 %E5%8A%A8%E7%94%BB」「v1.42 前时间全是 now」这类
 * 回归挡在 CI 里。
 *
 * 关于 href 的处理（关键，曾导致路径错乱、404、同名文件夹重复）：
 *   - 服务器返回的 href 形如 "/dav/cmcc/music/"，是【以 URL 的 path 部分为基准】
 *     的绝对路径，不含 scheme/host。因此不能用 baseUrl（含 https://host）去剥前缀。
 *   - 正确做法：取出 baseUrl 的 path（如 "/dav/"），剥离它，再按 "/" 逐段拆分。
 *   - href 中的中文等非 ASCII 字符是 URL 编码的（%E5%9B%BD%E8%AF%AD），
 *     必须 URL 解码后再作显示名与路径，否则会乱码、且拼接出的请求 URL 打不开。
 *   - PROPFIND Depth:1 的响应【首条通常是目录自身】，必须剔除，否则界面会
 *     出现与当前目录同名的文件夹。判断方式：与当前请求路径比较，而不是
 *     判断相对路径是否为空（请求 /cmcc/ 时自身的 relPath 恰为 "cmcc"，非空）。
 */
public final class WebDAVResponseParser {

    private static final String DAV = "DAV:";

    private WebDAVResponseParser() {
    }

    /**
     * 解析一份 PROPFIND 响应。
     *
     * @param xml           响应体
     * @param requestedPath 本次请求的路径（如 "/cmcc" 或 "/"），用于识别并剔除目录自身
     * @param basePath      baseUrl 的 path 部分（如 "/dav/"），用于从 href 中剥离前缀
     * @param owner         条目归属的服务器；可为 null
     */
    public static List<WebDAVFile> parse(String xml, String requestedPath,
                                         String basePath, ServerProfile owner) throws Exception {
        List<WebDAVFile> files = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // 当前请求目录的规范化相对路径（用于剔除"目录自身"那一条）
        String selfRel = normalizeRelPath(requestedPath, basePath);

        NodeList responses = doc.getElementsByTagNameNS(DAV, "response");

        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);

            String href = getElementText(response, DAV, "href");
            if (href == null || href.isEmpty()) continue;

            // 1) 剥离 baseUrl 的 path 前缀，得到相对路径（如 "cmcc/music/苏慧伦"）
            String relPath = stripBasePath(href, basePath);

            // 2) URL 解码（处理中文、空格等）
            relPath = urlDecode(relPath);

            // 3) 规范化：去首尾斜杠
            relPath = trimSlashes(relPath);

            // 4) 剔除目录自身（与当前请求路径一致的那一条）
            if (relPath.equals(selfRel)) continue;

            // 5) 跳过系统元数据文件
            if (isSystemFile(relPath)) continue;

            WebDAVFile file = new WebDAVFile();
            file.setHref(href);
            // 显示名 = 相对路径的最后一段
            file.setDisplayName(lastSegment(relPath));
            // 保存规范化后的相对路径，供快照与后续拼接使用
            file.setRelativePath(relPath);
            // 打上服务器归属：条目自带来源，后续取流/缓存/播放队列都靠它
            file.setOwner(owner);

            // 获取属性
            Element propstat = getChildElement(response, DAV, "propstat");
            if (propstat != null) {
                Element prop = getChildElement(propstat, DAV, "prop");
                if (prop != null) {
                    String contentLength = getElementText(prop, DAV, "getcontentlength");
                    if (contentLength != null && !contentLength.isEmpty()) {
                        try {
                            file.setContentLength(Long.parseLong(contentLength.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    String lastModified = getElementText(prop, DAV, "getlastmodified");
                    if (lastModified != null) {
                        // 【关键】必须用服务器返回的真实时间。
                        // 旧实现读了这一段文本却把它丢掉，直接
                        // setLastModified(System.currentTimeMillis())，
                        // 于是「文件时间」变成了「这次列目录的时刻」：
                        // 每刷新一次全目录的时间都变，任何按时间排序都会
                        // 在两次刷新之间自己乱跳。详见 HttpDateParser。
                        file.setLastModified(HttpDateParser.parse(lastModified));
                    }

                    String contentType = getElementText(prop, DAV, "getcontenttype");
                    if (contentType != null) {
                        file.setContentType(contentType.trim());
                    }

                    String etag = getElementText(prop, DAV, "getetag");
                    if (etag != null) {
                        file.setEtag(etag);
                    }

                    // 是否为文件夹：
                    // 优先看 resourcetype 是否包含 collection 子元素；
                    // 其次看 href 是否以 "/" 结尾（WebDAV 惯例）；
                    // 最后才退回 contentLength == 0 的启发式判断。
                    boolean isCollection = false;
                    Element resType = getChildElement(prop, DAV, "resourcetype");
                    if (resType != null) {
                        NodeList collection = resType.getElementsByTagNameNS(DAV, "collection");
                        isCollection = collection.getLength() > 0;
                    }
                    if (!isCollection && href.endsWith("/")) {
                        isCollection = true;
                    }
                    file.setCollection(isCollection);
                }
            }

            files.add(file);
        }

        return files;
    }

    /**
     * 把请求路径规范化为「相对 basePath 的路径」，用于与解析出的 relPath 比较。
     * 例如 basePath="/dav/"，传入 "/cmcc/music" → 返回 "cmcc/music"；
     * 传入 "/dav/cmcc" → 也返回 "cmcc"（容忍调用方误带 basePath 前缀）。
     */
    public static String normalizeRelPath(String path, String basePath) {
        if (path == null) return "";
        String p = urlDecode(path.trim());
        p = trimSlashes(p);

        // 若已带 basePath 前缀（如 "dav/cmcc"），剥掉它
        String bp = trimSlashes(basePath);
        if (!bp.isEmpty() && p.equals(bp)) return "";
        if (!bp.isEmpty() && p.startsWith(bp + "/")) {
            p = p.substring(bp.length() + 1);
        }
        return trimSlashes(p);
    }

    /** 取出 baseUrl 的 path 部分，如 https://host/dav/ -> "/dav/" */
    public static String getBasePath(String baseUrl) {
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            String p = u.getPath();
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Exception e) {
            return "/";
        }
    }

    /**
     * 从 href 中剥离 baseUrl 的 path 前缀。
     * href 可能形如 "/dav/cmcc/" 或 "https://host/dav/cmcc/"（部分服务器返回完整 URL），
     * 两种都要兼容。
     */
    public static String stripBasePath(String href, String basePath) {
        String h = href;

        // 若 href 是完整 URL，先取出其 path
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                h = new java.net.URL(h).getPath();
            } catch (Exception ignored) {
            }
        }

        // basePath 形如 "/dav/"；容忍不带尾斜杠的情况
        String bp = (basePath == null || basePath.isEmpty()) ? "/" : basePath;
        String prefix = bp.endsWith("/") ? bp : bp + "/";
        String prefixNoSlash = prefix.substring(0, prefix.length() - 1);

        if (h.equals(prefixNoSlash) || h.equals(prefixNoSlash + "/")) {
            return "";
        }
        if (h.startsWith(prefix)) {
            return h.substring(prefix.length());
        }
        if (h.startsWith(prefixNoSlash + "/")) {
            return h.substring(prefixNoSlash.length() + 1);
        }
        // 前缀不匹配（服务器配置差异）：退化为去掉首个 "/"
        return h.startsWith("/") ? h.substring(1) : h;
    }

    /**
     * 百分号解码（RFC 3986 路径语义）。
     *
     * 两条必须守住的规则：
     * 1. 不能碰 '+'。WebDAV 的 href 是标准 URI 路径编码，'+' 是合法字面字符；
     *    java.net.URLDecoder 是 form-urlencoded 语义，会把 '+' 解成空格，
     *    导致 nobodyknows+-Hero's Come Back!!.m4a 之类查询 404。
     * 2. 必须真的解出 %XX。曾用 new URI(null, null, s, null).getPath()，
     *    但那个四参构造是「把 path 当**已解码**文本再编码」，getPath() 又解回来，
     *    净效果是原样返回——中文目录于是显示成 %E5%8A%A8%E7%94%BB。
     */
    public static String urlDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            // 非法 %XX 或普通字符：按 UTF-8 原样写出（正确处理代理对）
            int cp = s.codePointAt(i);
            byte[] b = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            i += Character.charCount(cp);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 去掉首尾斜杠 */
    public static String trimSlashes(String s) {
        if (s == null) return "";
        String r = s;
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /** 取路径最后一段作为显示名 */
    public static String lastSegment(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 过滤系统元数据文件（macOS / Windows 产物） */
    public static boolean isSystemFile(String name) {
        String n = lastSegment(name);
        return n.startsWith("._")
                || n.equals(".DS_Store")
                || n.equals("Thumbs.db")
                || n.equals("desktop.ini")
                || n.equals("Desktop.ini");
    }

    private static String getElementText(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() > 0) {
            Element element = (Element) nodes.item(0);
            if (element != null) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private static Element getChildElement(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() > 0) {
            return (Element) nodes.item(0);
        }
        return null;
    }
}
