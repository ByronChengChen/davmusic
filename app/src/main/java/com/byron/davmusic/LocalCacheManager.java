package com.byron.davmusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class LocalCacheManager {
    private static final String TAG = "LocalCacheManager";
    private static final String PREF_NAME = "davmusic_cache";
    private static final String KEY_DOWNLOAD_MAP = "download_map";
    private static final String SNAPSHOT_DIR = "snapshots";
    private static final String DOWNLOAD_DIR = "music";

    private static LocalCacheManager instance;
    private Context context;
    private SharedPreferences preferences;
    private Map<String, String> downloadMap; // remotePath -> localPath

    private LocalCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadDownloadMap();
        // 旧版本快照曾存放在 cacheDir 下，升级后搬到持久目录；
        // 旧目录一旦迁移干净即被删除，之后这里只是一次负向 exists() 检查
        migrateLegacySnapshotsIfNeeded();
    }

    public static synchronized LocalCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new LocalCacheManager(context);
        }
        return instance;
    }

    // 下载文件存储管理
    public File getLocalFile(String remotePath) {
        String localPath = downloadMap.get(remotePath);
        if (localPath != null) {
            return new File(localPath);
        }
        return null;
    }

    public boolean isDownloaded(String remotePath) {
        if (downloadMap.containsKey(remotePath)) {
            File file = new File(downloadMap.get(remotePath));
            return file.exists();
        }
        return false;
    }

    public File getDownloadDestination(String remotePath) {
        String fileName = getHashFromPath(remotePath) + getFileExtension(remotePath);
        File downloadDir = getDownloadDirectory();
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        return new File(downloadDir, fileName);
    }

    public void markFileDownloaded(String remotePath, File localFile) {
        downloadMap.put(remotePath, localFile.getAbsolutePath());
        saveDownloadMap();
    }

    // ---- 离线可用文件索引 ----
    //
    // 背景：downloadMap 只记录 remotePath → localPath，缺少"这个文件属于哪个
    // 目录"的显式信息。离线时需要按目录树浏览已下载歌曲，因此额外维护一份
    // 元数据索引：每首已下载歌曲的名称、所属目录、大小。
    // 仅靠 remotePath 也能推导目录，但补上 size/name 后离线列表能直接渲染，
    // 无需再访问网络。

    private static final String KEY_OFFLINE_INDEX = "offline_index";

    /**
     * 记录一条离线可用条目。
     *
     * @param remotePath 远端路径（如 /cmcc/music/JAY/七里香/xxx.mp3）
     * @param displayName 显示名
     * @param size 文件字节数
     */
    public void indexOfflineFile(String remotePath, String displayName, long size) {
        try {
            JSONObject idx = loadOfflineIndex();
            JSONObject entry = new JSONObject();
            entry.put("name", displayName == null ? "" : displayName);
            entry.put("size", size);
            entry.put("ts", System.currentTimeMillis());
            idx.put(remotePath, entry);
            preferences.edit()
                    .putString(KEY_OFFLINE_INDEX, idx.toString())
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "写入离线索引失败: " + remotePath, e);
        }
    }

    /** 移除一条离线索引 */
    public void removeOfflineIndex(String remotePath) {
        try {
            JSONObject idx = loadOfflineIndex();
            idx.remove(remotePath);
            preferences.edit()
                    .putString(KEY_OFFLINE_INDEX, idx.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private JSONObject loadOfflineIndex() {
        try {
            String s = preferences.getString(KEY_OFFLINE_INDEX, null);
            if (s == null || s.isEmpty()) return new JSONObject();
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * 列出某目录下【已下载到本地】的音频，并推导出子目录。
     *
     * 返回的列表已按"文件夹在前、同名按名称排序"排好，可直接交给适配器。
     * 子目录是根据已下载文件的路径**推导**出来的 —— 因此离线时只能看到
     * 那些"至少下载过一首歌"的目录层级，这是符合预期的：
     * 完全没下载内容的目录，离线时本来也没有可播的东西。
     *
     * @param currentPath 当前目录（形如 "/cmcc/music/JAY"），"/" 表示根
     */
    public List<WebDAVFile> listLocalTree(String currentPath) {
        List<WebDAVFile> result = new ArrayList<>();
        String base = normalizePath(currentPath);

        try {
            JSONObject idx = loadOfflineIndex();
            java.util.Set<String> subDirs = new java.util.LinkedHashSet<>();

            java.util.Iterator<String> keys = idx.keys();
            while (keys.hasNext()) {
                String remotePath = keys.next();
                JSONObject entry = idx.optJSONObject(remotePath);
                if (entry == null) continue;

                File local = getLocalFile(remotePath);
                if (local == null || !local.exists() || local.length() == 0) {
                    continue;   // 本地文件已被清理，跳过
                }

                String parent = parentDir(remotePath);
                if (parent == null) continue;

                if (parent.equals(base)) {
                    // 直接子项：一首歌
                    String name = entry.optString("name", lastSegment(remotePath));
                    result.add(makeLocalFile(remotePath, name,
                            entry.optLong("size", local.length()), false));
                } else if (parent.startsWith(base + "/") || "/".equals(base)) {
                    // 深层条目：把中间层级作为子目录呈现
                    String rest = "/".equals(base)
                            ? parent.substring(1)
                            : parent.substring(base.length() + 1);
                    if (rest.isEmpty()) continue;
                    String firstDir = rest.split("/")[0];
                    if (!firstDir.isEmpty()) {
                        String dirPath = "/".equals(base)
                                ? "/" + firstDir
                                : base + "/" + firstDir;
                        subDirs.add(dirPath);
                    }
                }
            }

            // 子目录放在前面
            List<WebDAVFile> dirs = new ArrayList<>();
            for (String d : subDirs) {
                dirs.add(makeLocalFile(d, lastSegment(d), 0, true));
            }
            result.addAll(0, dirs);

        } catch (Exception e) {
            Log.e(TAG, "列举离线文件失败: " + e.getMessage(), e);
        }
        return result;
    }

    /** 归一化路径：去尾部斜杠、补前导斜杠 */
    private String normalizePath(String p) {
        if (p == null || p.isEmpty()) return "/";
        String s = p.trim();
        if (!s.startsWith("/")) s = "/" + s;
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 取父目录；无父目录返回 null */
    private String parentDir(String path) {
        if (path == null) return null;
        String p = normalizePath(path);
        int i = p.lastIndexOf('/');
        if (i < 0) return null;
        return i == 0 ? "/" : p.substring(0, i);
    }

    private String lastSegment(String path) {
        if (path == null) return "";
        String p = normalizePath(path);
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 构造一个本地文件对象（供离线列表渲染与点击播放） */
    private WebDAVFile makeLocalFile(String remotePath, String name,
                                     long size, boolean isDir) {
        WebDAVFile f = new WebDAVFile();
        f.setHref(remotePath);
        f.setRelativePath(remotePath.startsWith("/")
                ? remotePath.substring(1) : remotePath);
        f.setDisplayName(name);
        f.setContentLength(size);
        f.setCollection(isDir);
        // 让 UI 层能把它识别为音频（扩展名优先的判定在 WebDAVFile.isAudio）
        f.setContentType(isDir ? "httpd/unix-directory" : guessAudioMime(name));
        return f;
    }

    private String guessAudioMime(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".wma")) return "audio/x-ms-wma";
        if (n.endsWith(".ape")) return "audio/x-ape";
        return "application/octet-stream";
    }

    /** 已下载文件总数（离线模式提示用） */
    public int getOfflineFileCount() {
        int n = 0;
        try {
            JSONObject idx = loadOfflineIndex();
            java.util.Iterator<String> keys = idx.keys();
            while (keys.hasNext()) {
                String p = keys.next();
                File f = getLocalFile(p);
                if (f != null && f.exists() && f.length() > 0) n++;
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    public void removeDownloadedFile(String remotePath) {
        String localPath = downloadMap.remove(remotePath);
        if (localPath != null) {
            File file = new File(localPath);
            if (file.exists()) {
                file.delete();
            }
            saveDownloadMap();
        }
    }

    // 文件夹快照管理
    public void saveSnapshot(String folderPath, List<WebDAVFile> files) {
        try {
            JSONObject snapshot = new JSONObject();
            snapshot.put("path", folderPath);
            snapshot.put("timestamp", System.currentTimeMillis());
            
            JSONArray filesArray = new JSONArray();
            for (WebDAVFile file : files) {
                filesArray.put(fileToJson(file));
            }
            snapshot.put("files", filesArray);
            
            String snapshotJson = snapshot.toString();
            File snapshotFile = getSnapshotFile(folderPath);
            
            try (FileOutputStream fos = new FileOutputStream(snapshotFile)) {
                fos.write(snapshotJson.getBytes());
            }
            
            Log.d(TAG, "Saved snapshot for: " + folderPath);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to save snapshot: " + folderPath, e);
        }
    }

    public List<WebDAVFile> loadSnapshot(String folderPath) {
        File snapshotFile = getSnapshotFile(folderPath);
        if (!snapshotFile.exists()) {
            return null;
        }
        
        try {
            byte[] buffer = new byte[(int) snapshotFile.length()];
            try (FileInputStream fis = new FileInputStream(snapshotFile)) {
                fis.read(buffer);
            }
            
            String jsonStr = new String(buffer);
            JSONObject snapshot = new JSONObject(jsonStr);
            
            JSONArray filesArray = snapshot.getJSONArray("files");
            List<WebDAVFile> files = new ArrayList<>();
            
            for (int i = 0; i < filesArray.length(); i++) {
                JSONObject fileJson = filesArray.getJSONObject(i);
                WebDAVFile file = jsonToFile(fileJson);
                files.add(file);
            }
            
            Log.d(TAG, "Loaded snapshot for: " + folderPath + ", files: " + files.size());
            return files;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load snapshot: " + folderPath, e);
            return null;
        }
    }

    public void deleteSnapshot(String folderPath) {
        File snapshotFile = getSnapshotFile(folderPath);
        if (snapshotFile.exists()) {
            snapshotFile.delete();
        }
    }

    public boolean hasSnapshot(String folderPath) {
        File snapshotFile = getSnapshotFile(folderPath);
        return snapshotFile.exists();
    }

    // ---- 服务器归属隔离（多服务器 v1.29）----
    //
    // 设计：本地文件的索引键统一用 WebDAVFile.getCacheKey() = "serverId:相对路径"。
    // 两台服务器即使路径完全同名，键也不同，从根上不会互相命中。
    //
    // 为什么不再需要「当前服务器」这个概念：服务器已经成为根目录的第一层，
    // 每个条目自带 serverId，判定归属只需看条目本身，不必再维护全局状态。

    /** 清空所有目录快照（仅在「清空缓存」时用；切换服务器不再需要调用） */
    public void clearSnapshots() {
        try {
            File dir = getSnapshotDirectory();
            if (dir != null && dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) {
                            Log.w(TAG, "快照删除失败: " + f.getAbsolutePath());
                        }
                    }
                }
            }
            Log.d(TAG, "已清空全部目录快照");
        } catch (Exception e) {
            Log.e(TAG, "清空目录快照失败", e);
        }
    }

    // 清空缓存
    public void clearAllCache() {
        // 清空下载文件
        for (String localPath : downloadMap.values()) {
            File file = new File(localPath);
            if (file.exists()) {
                file.delete();
            }
        }
        downloadMap.clear();
        preferences.edit().remove(KEY_DOWNLOAD_MAP).apply();
        
        // 清空快照
        File snapshotDir = getSnapshotDirectory();
        if (snapshotDir.exists() && snapshotDir.isDirectory()) {
            File[] snapshotFiles = snapshotDir.listFiles();
            if (snapshotFiles != null) {
                for (File file : snapshotFiles) {
                    file.delete();
                }
            }
        }
        
        Log.d(TAG, "Cleared all cache");
    }

    public long getCacheSize() {
        long totalSize = 0;
        
        // 计算下载文件大小
        for (String localPath : downloadMap.values()) {
            File file = new File(localPath);
            if (file.exists()) {
                totalSize += file.length();
            }
        }
        
        // 计算快照文件大小
        File snapshotDir = getSnapshotDirectory();
        if (snapshotDir.exists() && snapshotDir.isDirectory()) {
            File[] snapshotFiles = snapshotDir.listFiles();
            if (snapshotFiles != null) {
                for (File file : snapshotFiles) {
                    totalSize += file.length();
                }
            }
        }
        
        return totalSize;
    }

    // 私有辅助方法
    private void loadDownloadMap() {
        String mapJson = preferences.getString(KEY_DOWNLOAD_MAP, "{}");
        try {
            JSONObject json = new JSONObject(mapJson);
            downloadMap = new HashMap<>();
            
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String remotePath = keys.next();
                String localPath = json.getString(remotePath);
                downloadMap.put(remotePath, localPath);
            }
        } catch (JSONException e) {
            downloadMap = new HashMap<>();
            Log.e(TAG, "Failed to parse download map", e);
        }
    }

    private void saveDownloadMap() {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : downloadMap.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }
            preferences.edit().putString(KEY_DOWNLOAD_MAP, json.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save download map", e);
        }
    }

    private File getDownloadDirectory() {
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir == null) {
            externalFilesDir = context.getFilesDir();
        }
        return new File(externalFilesDir, DOWNLOAD_DIR);
    }

    /**
     * 快照存储目录：应用私有持久目录 filesDir/snapshots。
     *
     * 早期版本放在 getCacheDir() 下，但 cacheDir 是系统可随时清理的目录
     * （存储紧张时 Android 会自行删除，且不通知应用），导致用户离线时
     * 曾经浏览过的目录快照无声消失、离线列表变空。
     *
     * 快照是本应用离线能力的唯一数据来源，且体积很小（每个目录一份 JSON
     * 元数据，不含音频本体），因此移到 filesDir 长期保留。
     *
     * 注：备份规则只 include sharedpref 域，files/ 不参与云备份与设备迁移，
     * 所以迁移不会把快照带进备份包。
     */
    private File getSnapshotDirectory() {
        return new File(context.getFilesDir(), SNAPSHOT_DIR);
    }

    /** 迁移前的旧快照目录（cacheDir/snapshots），仅用于一次性数据迁移 */
    private File getLegacySnapshotDirectory() {
        return new File(context.getCacheDir(), SNAPSHOT_DIR);
    }

    /**
     * 把旧版本遗留在 cacheDir 下的快照迁移到持久目录。
     *
     * 不设「已迁移」标志位：旧目录迁移干净后会被整体删除，之后的启动只是
     * 一次负向 exists() 检查，成本可忽略；这样也不存在标志位与实际文件
     * 状态不一致的可能（标志位说迁移过了、文件却没搬成）。
     */
    private void migrateLegacySnapshotsIfNeeded() {
        try {
            File legacy = getLegacySnapshotDirectory();
            if (!legacy.exists() || !legacy.isDirectory()) return;

            File[] files = legacy.listFiles();
            if (files == null || files.length == 0) {
                legacy.delete();
                return;
            }

            File target = getSnapshotDirectory();
            if (!target.exists() && !target.mkdirs()) {
                Log.w(TAG, "快照迁移失败：无法创建目标目录 " + target.getAbsolutePath());
                return;
            }

            int moved = 0;
            int failed = 0;
            for (File src : files) {
                if (src == null || !src.isFile()) continue;

                File dest = new File(target, src.getName());
                if (dest.exists() && dest.isFile()) {
                    // 目标已有同名快照，说明升级后已重新写入过，以新的为准。
                    // 必须是普通文件：若该名字被目录占用，走下面分支计为失败，
                    // 保留源文件下次重试，绝不在这里删源
                    if (src.delete()) moved++; else failed++;
                    continue;
                }
                if (src.renameTo(dest)) {
                    moved++;
                } else if (copyFile(src, dest)) {
                    if (src.delete()) moved++; else failed++;
                } else {
                    failed++;
                }
            }

            // 仅当全部迁移成功才移除旧目录：有残留就保留，下次启动重试，
            // 避免残留文件随后被系统清理时无声丢失
            if (failed == 0) {
                legacy.delete();
            }
            Log.d(TAG, "快照迁移：成功 " + moved + " 个，失败 " + failed + " 个");
        } catch (Exception e) {
            Log.e(TAG, "快照迁移异常", e);
        }
    }

    /** renameTo 失败时的兜底：复制字节流（失败则清掉半成品，不留损坏文件） */
    private boolean copyFile(File src, File dest) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制快照失败: " + src.getName(), e);
            // 只清理本次可能写出的半成品文件；若该路径是目录，绝不能删
            if (dest.isFile()) dest.delete();
            return false;
        }
    }

    /**
     * 快照文件路径。
     *
     * 键里带上服务器 id（folderPath 由调用方传入「服务器 id + 相对路径」的
     * cacheKey），这样两台服务器相同路径的目录快照各自独立 ——
     * 不需要在切服务器时清空全部快照了。
     */
    private File getSnapshotFile(String folderPath) {
        String hash = getHashFromPath(folderPath);
        File snapshotDir = getSnapshotDirectory();
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs();
        }
        return new File(snapshotDir, hash + ".json");
    }

    private String getHashFromPath(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(path.getBytes());
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // 取前16位
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(path.hashCode());
        }
    }

    private String getFileExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex != -1 && dotIndex < path.length() - 1) {
            return path.substring(dotIndex);
        }
        return "";
    }

    private JSONObject fileToJson(WebDAVFile file) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("href", file.getHref());
        // relativePath 必须持久化：导航、下载地址构造都依赖它，
        // 快照恢复后若缺失会退化成用 displayName 拼路径 —— 中文与
        // 特殊字符场景下会拼错路径，进而出现 404 或列表错乱
        json.put("relativePath", file.getRelativePath());
        json.put("displayName", file.getDisplayName());
        json.put("contentLength", file.getContentLength());
        json.put("contentType", file.getContentType());
        json.put("lastModified", file.getLastModified());
        json.put("etag", file.getEtag());
        json.put("isCollection", file.isCollection());
        json.put("downloadState", file.getDownloadState().name());
        json.put("localPath", file.getLocalPath());
        // 服务器归属必须持久化：快照恢复后条目要仍知道自己的来源，
        // 否则取流会退化成「用当前服务器地址取旧路径」
        json.put("serverId", file.getServerId());
        json.put("serverName", file.getServerName());
        return json;
    }

    private WebDAVFile jsonToFile(JSONObject json) throws JSONException {
        WebDAVFile file = new WebDAVFile();
        file.setHref(json.optString("href"));
        // 兼容旧版快照：此前未保存 relativePath，缺失时从 href 推导
        String rel = json.optString("relativePath", "");
        if (rel.isEmpty()) {
            String href = file.getHref();
            if (href != null && !href.isEmpty()) {
                rel = href.startsWith("/") ? href.substring(1) : href;
            }
        }
        file.setRelativePath(rel);
        file.setDisplayName(json.optString("displayName"));
        file.setContentLength(json.optLong("contentLength"));
        file.setContentType(json.optString("contentType"));
        file.setLastModified(json.optLong("lastModified"));
        file.setEtag(json.optString("etag"));
        file.setCollection(json.optBoolean("isCollection"));
        
        String downloadStateStr = json.optString("downloadState");
        if (!downloadStateStr.isEmpty()) {
            try {
                file.setDownloadState(WebDAVFile.DownloadState.valueOf(downloadStateStr));
            } catch (IllegalArgumentException e) {
                file.setDownloadState(WebDAVFile.DownloadState.NOT_DOWNLOADED);
            }
        }
        
        file.setLocalPath(json.optString("localPath"));
        file.setServerId(json.optString("serverId"));
        file.setServerName(json.optString("serverName"));
        return file;
    }
}
