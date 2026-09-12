package com.byron.davmusic;

import android.content.Context;
import android.util.Base64;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebDAVClient {
    private static WebDAVClient instance;
    private OkHttpClient client;
    private String baseUrl;
    private String username;
    private String password;

    private WebDAVClient() {
        // 初始化 OkHttpClient
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized WebDAVClient getInstance() {
        if (instance == null) {
            instance = new WebDAVClient();
        }
        return instance;
    }

    public void configure(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.username = username;
        this.password = password;
    }

    public boolean isConfigured() {
        return baseUrl != null && username != null && password != null;
    }

    // 列出文件夹内容
    public void listFolder(String path, final WebDAVCallback<List<WebDAVFile>> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }

        String url = baseUrl + (path.startsWith("/") ? path.substring(1) : path);
        if (!url.endsWith("/")) {
            url += "/";
        }

        String propfindXml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                    <D:getcontenttype/>
                    <D:getetag/>
                    <D:resourcetype/>
                </D:prop>
            </D:propfind>
            """;

        RequestBody body = RequestBody.create(propfindXml, MediaType.parse("application/xml"));
        
        Request request = new Request.Builder()
                .url(url)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .header("Authorization", getBasicAuthHeader())
                .header("Content-Type", "application/xml")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        String xml = response.body().string();
                        List<WebDAVFile> files = parsePropfindResponse(xml);
                        callback.onSuccess(files);
                    } else {
                        callback.onError(new Exception("HTTP " + response.code() + ": " + response.message()));
                    }
                } catch (Exception e) {
                    callback.onError(e);
                } finally {
                    response.close();
                }
            }
        });
    }

    // 下载文件
    public void download(String remotePath, File destFile, final ProgressCallback callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }

        String url = getDownloadUrl(remotePath);
        
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", getBasicAuthHeader())
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        long contentLength = response.body().contentLength();
                        InputStream inputStream = response.body().byteStream();
                        FileOutputStream outputStream = new FileOutputStream(destFile);
                        
                        byte[] buffer = new byte[8192];
                        long downloaded = 0;
                        int bytesRead;
                        
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            downloaded += bytesRead;
                            
                            if (contentLength > 0) {
                                int progress = (int) ((downloaded * 100) / contentLength);
                                callback.onProgress(progress);
                            }
                        }
                        
                        outputStream.close();
                        inputStream.close();
                        callback.onSuccess(destFile);
                    } else {
                        callback.onError(new Exception("Download failed: HTTP " + response.code()));
                    }
                } catch (Exception e) {
                    callback.onError(e);
                } finally {
                    response.close();
                }
            }
        });
    }

    // 上传文件
    public void upload(File localFile, String remotePath, final ProgressCallback callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }

        String url = baseUrl + (remotePath.startsWith("/") ? remotePath.substring(1) : remotePath);
        
        RequestBody requestBody = RequestBody.create(localFile, MediaType.parse("application/octet-stream"));
        
        Request request = new Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", getBasicAuthHeader())
                .header("Content-Type", "application/octet-stream")
                .header("Content-Length", String.valueOf(localFile.length()))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        callback.onSuccess(null);
                    } else {
                        callback.onError(new Exception("Upload failed: HTTP " + response.code()));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    // 获取下载 URL
    public String getDownloadUrl(String remotePath) {
        return baseUrl + (remotePath.startsWith("/") ? remotePath.substring(1) : remotePath);
    }

    // 测试连接
    public void testConnection(final WebDAVCallback<Boolean> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }

        Request request = new Request.Builder()
                .url(baseUrl)
                .method("PROPFIND", RequestBody.create("", null))
                .header("Depth", "0")
                .header("Authorization", getBasicAuthHeader())
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful()) {
                        callback.onSuccess(true);
                    } else {
                        callback.onSuccess(false);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    // 解析 PROPFIND 响应
    //
    // 关于 href 的处理（关键，曾导致路径错乱与 404）：
    //   - 服务器返回的 href 形如 "/dav/cmcc/music/"，是【以 URL 的 path 部分为基准】
    //     的绝对路径，不含 scheme/host。因此不能用 baseUrl（含 https://host）去剥前缀。
    //   - 正确做法：取出 baseUrl 的 path（如 "/dav/"），剥离它，再按 "/" 逐段拆分。
    //   - href 中的中文等非 ASCII 字符是 URL 编码的（%E5%9B%BD%E8%AF%AD），
    //     必须 URL 解码后再作显示名与路径，否则会乱码、且拼接出的请求 URL 打不开。
    private List<WebDAVFile> parsePropfindResponse(String xml) throws Exception {
        List<WebDAVFile> files = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // 计算 baseUrl 的 path 部分，用于从 href 中剥离前缀
        String basePath = getBasePath();

        // 当前请求目录的规范化路径（用于跳过"目录自身"这一条）
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");

        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);

            String href = getElementText(response, "DAV:", "href");
            if (href == null || href.isEmpty()) continue;

            // 1) 剥离 baseUrl 的 path 前缀，得到相对路径（如 "cmcc/music/苏慧伦"）
            String relPath = stripBasePath(href, basePath);

            // 2) URL 解码（处理中文、空格等）
            relPath = urlDecode(relPath);

            // 3) 去掉首尾斜杠
            relPath = trimSlashes(relPath);

            // 4) 跳过目录自身（相对路径为空）
            if (relPath.isEmpty()) continue;

            // 5) 跳过系统元数据文件
            if (isSystemFile(relPath)) continue;

            WebDAVFile file = new WebDAVFile();
            file.setHref(href);
            // 显示名 = 相对路径的最后一段
            file.setDisplayName(lastSegment(relPath));
            // 保存规范化后的相对路径，供快照与后续拼接使用
            file.setRelativePath(relPath);

            // 获取属性
            Element propstat = getChildElement(response, "DAV:", "propstat");
            if (propstat != null) {
                Element prop = getChildElement(propstat, "DAV:", "prop");
                if (prop != null) {
                    String contentLength = getElementText(prop, "DAV:", "getcontentlength");
                    if (contentLength != null && !contentLength.isEmpty()) {
                        try {
                            file.setContentLength(Long.parseLong(contentLength.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    String lastModified = getElementText(prop, "DAV:", "getlastmodified");
                    if (lastModified != null) {
                        file.setLastModified(System.currentTimeMillis());
                    }

                    String contentType = getElementText(prop, "DAV:", "getcontenttype");
                    if (contentType != null) {
                        file.setContentType(contentType.trim());
                    }

                    String etag = getElementText(prop, "DAV:", "getetag");
                    if (etag != null) {
                        file.setEtag(etag);
                    }

                    // 是否为文件夹：
                    // 优先看 resourcetype 是否包含 collection 子元素；
                    // 其次看 href 是否以 "/" 结尾（WebDAV 惯例）；
                    // 最后才退回 contentLength == 0 的启发式判断。
                    boolean isCollection = false;
                    Element resType = getChildElement(prop, "DAV:", "resourcetype");
                    if (resType != null) {
                        NodeList collection = resType.getElementsByTagNameNS("DAV:", "collection");
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

    /** 取出 baseUrl 的 path 部分，如 https://host/dav/ -> "/dav/" */
    private String getBasePath() {
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
    private String stripBasePath(String href, String basePath) {
        String h = href;

        // 若 href 是完整 URL，先取出其 path
        if (h.startsWith("http://") || h.startsWith("https://")) {
            try {
                h = new java.net.URL(h).getPath();
            } catch (Exception ignored) {
            }
        }

        // basePath 形如 "/dav/"；容忍不带尾斜杠的情况
        String prefix = basePath.endsWith("/") ? basePath : basePath + "/";
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

    /** URL 解码，失败则原样返回 */
    private String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 去掉首尾斜杠 */
    private String trimSlashes(String s) {
        String r = s;
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /** 取路径最后一段作为显示名 */
    private String lastSegment(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 过滤系统元数据文件（macOS / Windows 产物） */
    private boolean isSystemFile(String name) {
        String n = lastSegment(name);
        return n.startsWith("._")
                || n.equals(".DS_Store")
                || n.equals("Thumbs.db")
                || n.equals("desktop.ini")
                || n.equals("Desktop.ini");
    }

    private String getElementText(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() > 0) {
            Element element = (Element) nodes.item(0);
            if (element != null) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private Element getChildElement(Element parent, String namespace, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() > 0) {
            return (Element) nodes.item(0);
        }
        return null;
    }


    private String getBasicAuthHeader() {
        String credentials = username + ":" + password;
        String encoded = Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
        return "Basic " + encoded;
    }

    // 回调接口
    public interface WebDAVCallback<T> {
        void onSuccess(T result);
        void onError(Exception e);
    }

    public interface ProgressCallback {
        void onProgress(int progress);
        void onSuccess(Object result);
        void onError(Exception e);
    }
}
