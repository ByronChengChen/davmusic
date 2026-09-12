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
    private List<WebDAVFile> parsePropfindResponse(String xml) throws Exception {
        List<WebDAVFile> files = new ArrayList<>();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        
        NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
        
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i);
            
            // 跳过根目录自身
            String href = getElementText(response, "DAV:", "href");
            if (href == null) continue;
            
            // 去除 baseUrl 部分
            String relativeHref = href.replaceFirst("^" + baseUrl, "");
            if (relativeHref.isEmpty()) continue;
            
            WebDAVFile file = new WebDAVFile();
            file.setHref(href);
            file.setDisplayName(getDisplayNameFromHref(relativeHref));
            
            // 获取属性
            Element propstat = getChildElement(response, "DAV:", "propstat");
            if (propstat != null) {
                Element prop = getChildElement(propstat, "DAV:", "prop");
                if (prop != null) {
                    String contentLength = getElementText(prop, "DAV:", "getcontentlength");
                    if (contentLength != null && !contentLength.isEmpty()) {
                        file.setContentLength(Long.parseLong(contentLength));
                    }
                    
                    String lastModified = getElementText(prop, "DAV:", "getlastmodified");
                    if (lastModified != null) {
                        // 转换为时间戳（简化的解析，实际应该处理日期格式）
                        file.setLastModified(System.currentTimeMillis());
                    }
                    
                    String contentType = getElementText(prop, "DAV:", "getcontenttype");
                    if (contentType != null) {
                        file.setContentType(contentType);
                    }
                    
                    String etag = getElementText(prop, "DAV:", "getetag");
                    if (etag != null) {
                        file.setEtag(etag);
                    }
                    
                    // 检查是否是文件夹
                    NodeList resourceType = prop.getElementsByTagNameNS("DAV:", "resourcetype");
                    if (resourceType.getLength() > 0) {
                        Element resType = (Element) resourceType.item(0);
                        NodeList collection = resType.getElementsByTagNameNS("DAV:", "collection");
                        file.setCollection(collection.getLength() > 0);
                    } else {
                        // 如果没有 resourceType 元素，通过 contentLength 判断
                        file.setCollection(file.getContentLength() == 0);
                    }
                }
            }
            
            files.add(file);
        }
        
        return files;
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

    private String getDisplayNameFromHref(String href) {
        if (href.endsWith("/")) {
            href = href.substring(0, href.length() - 1);
        }
        
        int lastSlash = href.lastIndexOf('/');
        if (lastSlash != -1) {
            return href.substring(lastSlash + 1);
        }
        return href;
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
