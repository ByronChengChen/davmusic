package com.byron.davmusic;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


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

    /**
     * 读取一个小文件（歌词等）到内存，不落盘、不计数、不重试。
     *
     * 与 download 的区别：歌词是「有就显示、没有就算了」的附属数据，
     * 不该占用下载索引，也不该在 404 时弹播放错误。调用方按异常信息里的
     * HTTP 状态码区分「没有这个文件」和「真的失败了」。
     */
    public void fetchBytes(String remotePath, final WebDAVCallback<byte[]> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }
        String url = getDownloadUrl(remotePath);
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", getBasicAuthHeader())
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        callback.onSuccess(response.body().bytes());
                    } else {
                        callback.onError(new Exception("HTTP " + response.code()));
                    }
                } catch (IOException e) {
                    callback.onError(e);
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 上传一小段字节（歌词等），不覆盖服务器上已有的同名文件。
     *
     * `If-None-Match: *` 是条件写：文件已存在时服务器返回 412，避免把用户
     * 原有的歌词静默替换掉。部分 WebDAV 驱动忽略该头，此时调用方拿到的仍是
     * 2xx —— 因此这只在「我们刚确认过 404」的场景下使用。
     */
    public void putBytes(String remotePath, byte[] data, String contentType,
                         final WebDAVCallback<Boolean> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }
        if (data == null) {
            callback.onError(new Exception("内容为空"));
            return;
        }
        String url = getDownloadUrl(remotePath);
        RequestBody body = RequestBody.create(data,
                MediaType.parse(contentType == null ? "application/octet-stream" : contentType));
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", getBasicAuthHeader())
                .header("If-None-Match", "*")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    int code = response.code();
                    if (code >= 200 && code < 300) {
                        callback.onSuccess(Boolean.TRUE);
                    } else if (code == 412) {
                        callback.onError(new Exception("服务器已有同名文件，未覆盖"));
                    } else {
                        callback.onError(new Exception("HTTP " + code));
                    }
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
            String name = WebDAVResponseParser.lastSegment(remotePath);
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
        String p = WebDAVResponseParser.trimSlashes(path);
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
            rel = WebDAVResponseParser.trimSlashes(WebDAVResponseParser.stripBasePath(href, getBasePath()));
            rel = WebDAVResponseParser.urlDecode(rel);
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
        String bp = WebDAVResponseParser.trimSlashes(basePath);
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
            // 逐字节编码：只有 unreserved 字符原样保留，其余全部 %XX。
            // 不能用 URLEncoder.encode —— 它把空格编成 '+'，且无法区分字面 '+'，
            // 若再 replace("+", "%20") 会把刚生成的 %2B 也改坏。
            StringBuilder sb = new StringBuilder();
            for (byte b : seg.getBytes("UTF-8")) {
                int c = b & 0xFF;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append((char) c);
                } else {
                    sb.append('%').append(String.format("%02X", c));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return seg;
        }
    }

    // 删除远端文件/文件夹
    public void deleteRemote(String remotePath, boolean isCollection,
                             final WebDAVCallback<Integer> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }
        // 空路径会退化成对服务器根目录发 DELETE —— 必须挡住，
        // 否则一次误调用就是把整台服务器的内容清空。
        if (remotePath == null || WebDAVResponseParser.trimSlashes(remotePath).isEmpty()) {
            callback.onError(new Exception("拒绝删除服务器根目录"));
            return;
        }

        String url = getDownloadUrl(remotePath);
        if (isCollection && !url.endsWith("/")) {
            url += "/";
        }

        Request request = new Request.Builder()
                .url(url)
                .delete()
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
                        // 防御：重定向可能让 DELETE 被改写成 GET，
                        // 此时 2xx 并不能证明条目真的被删掉了，不能报成功
                        if (!"DELETE".equals(response.request().method())) {
                            callback.onError(new Exception(
                                    "服务端重定向后未执行删除（" + response.request().method() + "）"));
                            return;
                        }
                        callback.onSuccess(response.code());
                    } else {
                        callback.onError(new Exception("HTTP " + response.code()
                                + ": " + response.message()));
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    // 新建远端文件夹
    public void createFolder(String remotePath, final WebDAVCallback<Integer> callback) {
        if (!isConfigured()) {
            callback.onError(new Exception("WebDAV client not configured"));
            return;
        }
        // 空路径会退化成对服务器根目录发 MKCOL —— 必须挡住，
        // 根目录是既有事实，不是能"新建"的对象
        if (remotePath == null || WebDAVResponseParser.trimSlashes(remotePath).isEmpty()) {
            callback.onError(new Exception("文件夹路径为空"));
            return;
        }

        String url = getDownloadUrl(remotePath);
        if (!url.endsWith("/")) {
            url += "/";
        }

        Request request = new Request.Builder()
                .url(url)
                .method("MKCOL", RequestBody.create("", MediaType.parse("text/plain")))
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
                    if (response.isSuccessful()) {
                        // 与删除同一道防御：重定向会把非 GET/HEAD 请求改写，
                        // 此时 2xx 并不能证明请求真的是 MKCOL、也证明不了建成了
                        if (!"MKCOL".equals(response.request().method())) {
                            callback.onError(new Exception(
                                    "服务端重定向后未执行创建（" + response.request().method() + "）"));
                            return;
                        }
                        callback.onSuccess(response.code());
                    } else {
                        callback.onError(new Exception("HTTP " + response.code()
                                + ": " + response.message()));
                    }
                } finally {
                    response.close();
                }
            }
        });
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
        // 解析逻辑已抽到 WebDAVResponseParser（纯 JVM 可测），这里只负责注入
        // 当前服务器的 basePath 与归属信息。设计与踩坑说明见该类的注释。
        return WebDAVResponseParser.parse(xml, requestedPath, getBasePath(), server);
    }

    /** 取出 baseUrl 的 path 部分，如 https://host/dav/ -> "/dav/" */
    private String getBasePath() {
        return WebDAVResponseParser.getBasePath(baseUrl);
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
