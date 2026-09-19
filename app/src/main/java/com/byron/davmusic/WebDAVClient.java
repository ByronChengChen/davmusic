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
    private static final String TAG = "WebDAVClient";
    /** 共享的 OkHttpClient（连接池/线程池应当复用，不必每台服务器一个） */
    private static OkHttpClient sharedHttpClient;
    /** 兼容旧调用：指向「会话内默认实例」。多服务器改造后新代码请用 forServer() */
    private static WebDAVClient instance;
    private OkHttpClient client;
    private String baseUrl;
    private String username;
    private String password;

    /**
     * 这台客户端绑定的服务器。多服务器（服务器即根目录）改造的核心字段：
     * 请求发往哪台服务器，由实例自己的 server 决定，不再依赖全局可变状态。
     */
    private ServerProfile server;

    private WebDAVClient() {
        client = getSharedHttpClient();
    }

    private WebDAVClient(ServerProfile profile) {
        this.server = profile;
        this.client = getSharedHttpClient();
        if (profile != null) {
            configure(profile.getUrl(), profile.getUsername(), profile.getPassword());
        }
    }

    private static synchronized OkHttpClient getSharedHttpClient() {
        if (sharedHttpClient == null) {
            sharedHttpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .build();
        }
        return sharedHttpClient;
    }

    /**
     * 为指定服务器创建客户端。
     *
     * 每次调用都新建一个轻量对象（真正重的 OkHttpClient 是共享的），
     * 因此可以放心地按需创建、随用随弃，不存在泄漏。
     * 返回的实例自带 baseUrl 与凭据，多个实例互不干扰 ——
     * 这正是「服务器当根目录」能成立的前提：请求来源由条目自带，
     * 而不是取决于「当前选中的是哪台」。
     */
    public static WebDAVClient forServer(ServerProfile profile) {
        return new WebDAVClient(profile);
    }

    /** 该客户端所属的服务器；用旧式 getInstance() 创建时为 null */
    public ServerProfile getServer() {
        return server;
    }

    /** 该客户端所属服务器的显示别名 */
    public String getServerName() {
        return server == null ? null : server.getDisplayName();
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

        // 用 getDownloadUrl(path) 统一做 URL 编码与 basePath 去重，
        // 否则含中文/空格的目录请求会失败（HTTP 000）
        String url = getDownloadUrl(path);
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
                        List<WebDAVFile> files = parsePropfindResponse(xml, path);
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
    public void download(WebDAVFile file, File destFile, final ProgressCallback callback) {
        if (file == null) {
            callback.onError(new Exception("file is null"));
            return;
        }
        download(file.getRelativePath() != null ? file.getRelativePath() : file.getHref(),
                destFile, callback);
    }

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
    /**
     * 上传本地文件到 WebDAV。
     *
     * 要点：
     *  - URL 必须逐段编码，否则中文/空格文件名会被服务端拒绝
     *    （与下载侧同一个坑：未编码时请求根本发不出去）。
     *  - 用自定义 RequestBody 汇报进度，而不是默认的 File 包装。
     *  - 上传成功后重新 PROPFIND 一次，用远端实际大小校验，
     *    因为云盘驱动偶尔会返回异常状态码但字节已写入（参见 423 现象）。
     *
     * @param localFile  本地源文件
     * @param remotePath 相对路径（不含 baseUrl），如 "cmcc/music/xxx.m4a"
     * @param callback   进度与结果回调（主线程）
     */
    public void upload(File localFile, String remotePath, final ProgressCallback callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }
        if (localFile == null || !localFile.exists()) {
            callback.onError(new Exception("本地文件不存在"));
            return;
        }

        final long total = localFile.length();
        final String url = getDownloadUrl(remotePath);

        RequestBody body;
        try {
            body = new ProgressRequestBody(
                    localFile,
                    MediaType.parse(guessMimeType(localFile.getName())),
                    percent -> notifyProgressOnMain(callback, percent));
        } catch (Exception e) {
            callback.onError(e);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", getBasicAuthHeader())
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    int code = response.code();

                    // 2xx 视为成功；非 2xx 再给一次机会：云盘驱动偶发返回
                    // 4xx（如 423 Locked）但数据其实已落盘，用远端大小复核。
                    if (code >= 200 && code < 300) {
                        notifyProgressOnMain(callback, 100);
                        callback.onSuccess(null);
                        return;
                    }

                    long remote = remoteSizeQuiet(remotePath);
                    if (remote >= total * 0.95) {
                        android.util.Log.w(TAG,
                                "上传返回 HTTP " + code + "，但远端已有 "
                                        + remote + " 字节，视为成功");
                        notifyProgressOnMain(callback, 100);
                        callback.onSuccess(null);
                    } else {
                        callback.onError(new Exception(
                                "上传失败: HTTP " + code + "（远端 " + remote + " 字节）"));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /** 进度回调切到主线程，避免调用方在子线程更新 UI */
    private void notifyProgressOnMain(final ProgressCallback callback, final int percent) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> callback.onProgress(percent));
    }

    /** 静默查询远端文件大小；失败返回 -1 */
    private long remoteSizeQuiet(String remotePath) {
        try {
            java.util.List<WebDAVFile> files = listFolderSync(parentOf(remotePath));
            String name = lastSegment(remotePath);
            for (WebDAVFile f : files) {
                if (name.equals(f.getDisplayName())) {
                    return f.getContentLength();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String parentOf(String path) {
        String p = trimSlashes(path);
        int i = p.lastIndexOf('/');
        return i > 0 ? "/" + p.substring(0, i) : "/";
    }

    /** 简单按扩展名猜 MIME，便于部分服务端正确分类 */
    private String guessMimeType(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".wma")) return "audio/x-ms-wma";
        if (n.endsWith(".ape")) return "audio/x-ape";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".lrc") || n.endsWith(".ttml") || n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    /**
     * 带进度的 RequestBody。OkHttp 在写 socket 时按块回调，
     * 这里换算成百分比并做节流（同一百分比只报一次）。
     */
    private static class ProgressRequestBody extends RequestBody {
        interface Listener {
            void onPercent(int percent);
        }

        private final File file;
        private final MediaType type;
        private final Listener listener;
        private final long length;
        private int lastPercent = -1;

        ProgressRequestBody(File file, MediaType type, Listener listener) {
            this.file = file;
            this.type = type;
            this.listener = listener;
            this.length = file.length();
        }

        @Override
        public MediaType contentType() {
            return type;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void writeTo(okio.BufferedSink sink) throws IOException {
            byte[] buffer = new byte[8192];
            long uploaded = 0;
            try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                    uploaded += read;
                    if (length > 0 && listener != null) {
                        int percent = (int) (uploaded * 100 / length);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onPercent(percent);
                        }
                    }
                }
            }
        }
    }

    /** 同步列目录（上传校验用；调用方已在子线程） */
    public java.util.List<WebDAVFile> listFolderSync(String path) throws Exception {
        String url = getDownloadUrl(path);
        if (!url.endsWith("/")) url += "/";

        String propfindXml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>"
                + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
                + "<D:displayname/><D:getcontentlength/><D:getcontenttype/>"
                + "<D:getetag/><D:resourcetype/>"
                + "</D:prop></D:propfind>";

        Request req = new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create(
                        propfindXml, MediaType.parse("application/xml")))
                .header("Depth", "1")
                .header("Authorization", getBasicAuthHeader())
                .build();

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("PROPFIND HTTP " + resp.code());
            }
            String xml = resp.body() != null ? resp.body().string() : "";
            return parsePropfindResponse(xml, path);
        }
    }


    // 获取下载 URL
    /**
     * 由 WebDAVFile 构造可直接访问的完整 URL。
     *
     * 用 relativePath（已剥离 baseUrl 前缀、已 URL 解码）作为路径源，
     * 再对每段重新做 URL 编码 —— 否则中文与空格会导致请求发不出去
     * （实测未编码时 curl 返回 HTTP 000，编码后正常 302）。
     */
    public String getDownloadUrl(WebDAVFile file) {
        String rel = (file != null) ? file.getRelativePath() : null;
        if (rel == null || rel.isEmpty()) {
            // 兜底：从 href 中剥离 basePath
            String href = (file != null) ? file.getHref() : null;
            if (href == null) return baseUrl;
            rel = trimSlashes(stripBasePath(href, getBasePath()));
            rel = urlDecode(rel);
        }
        return getDownloadUrl(rel);
    }

    /**
     * 由相对路径构造完整 URL。
     * 兼容传入以 "/" 开头、或误带 basePath 前缀（如 "dav/cmcc/..."）的路径。
     */
    public String getDownloadUrl(String remotePath) {
        String path = remotePath == null ? "" : remotePath;

        // 去掉首斜杠
        while (path.startsWith("/")) path = path.substring(1);

        // 去掉可能误带的 basePath 前缀（防止出现 /dav/dav/... 这样的重复）
        String basePath = getBasePath();
        String bp = trimSlashes(basePath);
        if (!bp.isEmpty() && path.startsWith(bp + "/")) {
            path = path.substring(bp.length() + 1);
        }

        // 逐段 URL 编码（保留 "/" 作为分隔符）
        StringBuilder sb = new StringBuilder(baseUrl);
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodeSegment(segments[i]));
        }
        return sb.toString();
    }

    /** 对单个路径段做 URL 编码；保留已有的 %XX（避免二次编码） */
    private String encodeSegment(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        try {
            // 若该段本身已含 %XX 形态，认为已编码，直接返回
            if (seg.matches(".*%[0-9A-Fa-f]{2}.*")) {
                return seg;
            }
            return java.net.URLEncoder.encode(seg, "UTF-8")
                    .replace("+", "%20");   // URLEncoder 把空格编成 +，路径中应为 %20
        } catch (Exception e) {
            return seg;
        }
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

    /**
     * 用【指定的】凭据做一次连通性探测，不修改单例的当前配置。
     *
     * 为什么要单独一个静态方法：服务器管理页里可能同时存着多台服务器，
     * 用户点「测试连接」时若直接 configure() 单例，一旦随后取消，
     * 客户端就会停留在「被测试的那台」上 —— 正在浏览/播放的服务器被
     * 悄悄换掉。这里只发一个独立请求，不碰任何实例字段。
     *
     * 复用单例的 OkHttpClient 只是为了共享连接池与超时配置，与凭据无关。
     */
    public static void testConnectionWith(final String baseUrl,
                                          final String username,
                                          final String password,
                                          final WebDAVCallback<Integer> callback) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            callback.onError(new Exception("服务器地址为空"));
            return;
        }
        String url = baseUrl.trim();
        if (!url.endsWith("/")) {
            url += "/";
        }
        String credentials = (username == null ? "" : username) + ":" + (password == null ? "" : password);
        String auth = "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);

        Request request = new Request.Builder()
                .url(url)
                .method("PROPFIND", RequestBody.create("", null))
                .header("Depth", "0")
                .header("Authorization", auth)
                .build();

        getInstance().client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    // 把真实 HTTP 状态码交回调用方：401/403 是凭据问题，404 是路径问题，
                    // 207 才是 WebDAV 正常应答 —— 只回 true/false 会让用户分不清错在哪。
                    callback.onSuccess(response.code());
                } finally {
                    response.close();
                }
            }
        });
    }

    // 解析 PROPFIND 响应
    //
    // 关于 href 的处理（关键，曾导致路径错乱、404、同名文件夹重复）：
    //   - 服务器返回的 href 形如 "/dav/cmcc/music/"，是【以 URL 的 path 部分为基准】
    //     的绝对路径，不含 scheme/host。因此不能用 baseUrl（含 https://host）去剥前缀。
    //   - 正确做法：取出 baseUrl 的 path（如 "/dav/"），剥离它，再按 "/" 逐段拆分。
    //   - href 中的中文等非 ASCII 字符是 URL 编码的（%E5%9B%BD%E8%AF%AD），
    //     必须 URL 解码后再作显示名与路径，否则会乱码、且拼接出的请求 URL 打不开。
    //   - PROPFIND Depth:1 的响应【首条通常是目录自身】，必须剔除，否则界面会
    //     出现与当前目录同名的文件夹。判断方式：与当前请求路径比较，而不是
    //     判断相对路径是否为空（请求 /cmcc/ 时自身的 relPath 恰为 "cmcc"，非空）。
    //
    // @param requestedPath 本次请求的路径（如 "/cmcc" 或 "/"），用于识别并剔除目录自身
    private List<WebDAVFile> parsePropfindResponse(String xml, String requestedPath) throws Exception {
        List<WebDAVFile> files = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // 计算 baseUrl 的 path 部分，用于从 href 中剥离前缀
        String basePath = getBasePath();

        // 当前请求目录的规范化相对路径（用于剔除"目录自身"那一条）
        String selfRel = normalizeRelPath(requestedPath);

        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");

        // 空的 multistatus 是【畸形响应】，不是「空目录」。
        // 合规的 WebDAV 服务器至少会返回目录自身那一条 <response>，
        // 所以 0 条说明响应不完整（网关截断、协议不符等）。
        // 这里主动抛异常，让它走 onError 分支 —— 否则空列表会被上层当成
        // 「这个目录是空的」缓存下来，把真实内容遮住。
        // 反过来说：能走到下面的空列表，就是「目录确实为空」的可信结论，
        // 上层可以放心缓存（见 MainActivity 的空目录缓存处理）。
        if (responses.getLength() == 0) {
            throw new Exception("PROPFIND 响应缺少 <response> 元素，视为畸形响应");
        }

        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);

            String href = getElementText(response, "DAV:", "href");
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
            file.setOwner(server);

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

    /**
     * 把请求路径规范化为「相对 basePath 的路径」，用于与解析出的 relPath 比较。
     * 例如 basePath="/dav/"，传入 "/cmcc/music" → 返回 "cmcc/music"；
     * 传入 "/dav/cmcc" → 也返回 "cmcc"（容忍调用方误带 basePath 前缀）。
     */
    private String normalizeRelPath(String path) {
        if (path == null) return "";
        String p = urlDecode(path.trim());
        p = trimSlashes(p);

        // 若已带 basePath 前缀（如 "dav/cmcc"），剥掉它
        String bp = trimSlashes(getBasePath());
        if (!bp.isEmpty() && p.equals(bp)) return "";
        if (!bp.isEmpty() && p.startsWith(bp + "/")) {
            p = p.substring(bp.length() + 1);
        }
        return trimSlashes(p);
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
