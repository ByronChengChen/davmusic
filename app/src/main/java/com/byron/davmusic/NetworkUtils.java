package com.byron.davmusic;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class NetworkUtils {
    private static final String TAG = "NetworkUtils";
    
    // 检查网络是否连接
    public static boolean isNetworkConnected(Context context) {
        if (context == null) {
            return false;
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception e) {
            Log.e(TAG, "检查网络连接失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    // 检查是否连接到 WiFi
    public static boolean isWifiConnected(Context context) {
        if (context == null) {
            return false;
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                int type = activeNetwork.getType();
                return type == ConnectivityManager.TYPE_WIFI;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查 WiFi 连接失败: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    // 检查是否连接到移动数据
    public static boolean isMobileDataConnected(Context context) {
        if (context == null) {
            return false;
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                int type = activeNetwork.getType();
                return type == ConnectivityManager.TYPE_MOBILE;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查移动数据连接失败: " + e.getMessage(), e);
        }
        
        return false;
    }
    
    // 获取当前网络类型
    public static String getNetworkType(Context context) {
        if (context == null) {
            return "UNKNOWN";
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "UNKNOWN";
            }
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                int type = activeNetwork.getType();
                
                switch (type) {
                    case ConnectivityManager.TYPE_WIFI:
                        return "WIFI";
                    case ConnectivityManager.TYPE_MOBILE:
                        return "MOBILE";
                    case ConnectivityManager.TYPE_ETHERNET:
                        return "ETHERNET";
                    case ConnectivityManager.TYPE_BLUETOOTH:
                        return "BLUETOOTH";
                    default:
                        return "OTHER";
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取网络类型失败: " + e.getMessage(), e);
        }
        
        return "DISCONNECTED";
    }
    
    // 检查是否有网络可用（连接不一定可用）
    public static boolean isNetworkAvailable(Context context) {
        if (context == null) {
            return false;
        }
        
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } catch (Exception e) {
            Log.e(TAG, "检查网络可用性失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    // 获取本地 IP 地址
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    
                    // 跳过回环地址和 IPv6 地址
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().indexOf(':') == -1) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "获取本地 IP 地址失败: " + e.getMessage(), e);
        }
        
        return "";
    }
    
    // 检查是否可以连接到特定主机
    public static boolean canConnectToHost(String host, int port, int timeout) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) {
            return false;
        }
        
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isReachable(timeout);
        } catch (Exception e) {
            Log.e(TAG, "连接到主机失败: " + host + ":" + port, e);
            return false;
        }
    }
    
    // 格式化网络状态信息
    public static String getNetworkStatusString(Context context) {
        if (!isNetworkAvailable(context)) {
            return "离线";
        }
        
        String networkType = getNetworkType(context);
        switch (networkType) {
            case "WIFI":
                return "WiFi 网络";
            case "MOBILE":
                return "移动网络";
            case "ETHERNET":
                return "有线网络";
            case "BLUETOOTH":
                return "蓝牙网络";
            case "OTHER":
                return "其他网络";
            default:
                return "未知网络";
        }
    }
    
    // 检查是否应该使用移动数据（用于下载大文件）
    public static boolean shouldUseMobileDataForDownload(Context context) {
        if (!isNetworkAvailable(context)) {
            return false;
        }
        
        // 如果是 WiFi，总是允许下载
        if (isWifiConnected(context)) {
            return true;
        }
        
        // 如果是移动数据，可能需要根据设置决定
        return false;
    }
}
