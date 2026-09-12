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

    private File getSnapshotDirectory() {
        File cacheDir = context.getCacheDir();
        return new File(cacheDir, SNAPSHOT_DIR);
    }

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
        json.put("displayName", file.getDisplayName());
        json.put("contentLength", file.getContentLength());
        json.put("contentType", file.getContentType());
        json.put("lastModified", file.getLastModified());
        json.put("etag", file.getEtag());
        json.put("isCollection", file.isCollection());
        json.put("downloadState", file.getDownloadState().name());
        json.put("localPath", file.getLocalPath());
        return json;
    }

    private WebDAVFile jsonToFile(JSONObject json) throws JSONException {
        WebDAVFile file = new WebDAVFile();
        file.setHref(json.optString("href"));
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
        return file;
    }
}
