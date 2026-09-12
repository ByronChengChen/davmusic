package com.byron.davmusic;

import android.util.Log;

import java.io.File;
import java.text.DecimalFormat;

public class FileUtils {
    private static final String TAG = "FileUtils";
    
    // 音频文件扩展名
    private static final String[] AUDIO_EXTENSIONS = {
        ".mp3", ".m4a", ".flac", ".wav", ".wma", ".aac", ".ogg", ".ape", ".opus"
    };
    
    // 图片文件扩展名
    private static final String[] IMAGE_EXTENSIONS = {
        ".jpg", ".jpeg", ".png", ".webp"
    };
    
    // 歌词文件扩展名
    private static final String[] LYRICS_EXTENSIONS = {
        ".lrc", ".ttml"
    };
    
    // 判断是否为音频文件
    public static boolean isAudioFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        
        fileName = fileName.toLowerCase();
        for (String ext : AUDIO_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    // 判断是否为图片文件
    public static boolean isImageFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        
        fileName = fileName.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    // 判断是否为歌词文件
    public static boolean isLyricsFile(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        
        fileName = fileName.toLowerCase();
        for (String ext : LYRICS_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
    
    // 格式化文件大小
    public static String formatFileSize(long size) {
        if (size <= 0) return "0 B";
        
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        
        if (digitGroups >= units.length) {
            digitGroups = units.length - 1;
        }
        
        DecimalFormat df = new DecimalFormat("#,##0.#");
        return df.format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
    
    // 格式化时长（毫秒转 mm:ss）
    public static String formatDuration(int durationMs) {
        if (durationMs <= 0) return "--:--";
        
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    // 从文件名中提取文件名（不含扩展名）
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
    
    // 从文件名中提取文件扩展名（不含点）
    public static String getFileExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }
    
    // 检查文件是否存在
    public static boolean fileExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        
        try {
            File file = new File(filePath);
            return file.exists() && file.isFile();
        } catch (Exception e) {
            Log.e(TAG, "检查文件是否存在失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    // 获取文件大小
    public static long getFileSize(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return 0;
        }
        
        try {
            File file = new File(filePath);
            return file.exists() ? file.length() : 0;
        } catch (Exception e) {
            Log.e(TAG, "获取文件大小失败: " + e.getMessage(), e);
            return 0;
        }
    }
    
    // 确保目录存在
    public static boolean ensureDirectoryExists(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return false;
        }
        
        try {
            File dir = new File(dirPath);
            if (!dir.exists()) {
                return dir.mkdirs();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "创建目录失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    // 从路径中提取文件名
    public static String getFileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        
        // 去除尾部的斜杠
        while (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSeparator >= 0 && lastSeparator < path.length() - 1) {
            return path.substring(lastSeparator + 1);
        }
        
        return path;
    }
    
    // 获取父目录路径
    public static String getParentPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        
        // 去除尾部的斜杠
        while (path.endsWith("/") || path.endsWith("\\")) {
            path = path.substring(0, path.length() - 1);
        }
        
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSeparator > 0) {
            return path.substring(0, lastSeparator);
        }
        
        return "";
    }
}
