package com.byron.davmusic;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 运行日志收集与上报。
 *
 * 目的：定位只在真机出现的播放问题（尤其是锁屏/后台切歌失败），
 * 这些场景在开发机上无法复现，必须靠现场日志。
 *
 * 设计：
 *   1. 所有日志同时写入应用私有目录的滚动文件（单文件上限 ~512KB，
 *      超出后自动轮转），避免占用过多空间。
 *   2. 提供 upload() 把日志文件 PUT 到【多个】远端收集端点
 *      （互为备份，逐个尝试，任一失败不影响其他端点）。
 *   3. 上报走独立的 OkHttpClient 与线程池，且用 try-catch 全包，
 *      任何失败都只记本地日志、绝不影响主流程。
 *
 * 注意：本类只负责"记录"和"上报"，不改变任何播放行为。
 */
public class RemoteLogger {

    private static final String TAG = "RemoteLogger";

    /** 单个上报端点 */
    private static final class Endpoint {
        final String label;     // 仅用于日志区分
        final String url;
        final String user;
        final String pass;

        Endpoint(String label, String url, String user, String pass) {
            this.label = label;
            this.url = url;
            this.user = user;
            this.pass = pass;
        }
    }

    /**
     * 上报端点列表，逐个尝试。
     *
     * 两个端点互为备份：
     *   · 124.223.184.150 —— 原有收集端（Caddy + Let's Encrypt IP 证书）
     *   · 140.245.120.89  —— 本机新增的一份，便于在本地直接读日志排障
     *
     * 任一端点不可达只记一条本地日志，既不影响另一个端点，也不影响主流程。
     */
    private static final Endpoint[] ENDPOINTS = {
            new Endpoint("124.223.184.150",
                    "https://124.223.184.150/davmusic-logs/",
                    "davmusic", "ck20181220ck"),
            new Endpoint("本机",
                    "http://140.245.120.89:9339/davmusic-logs/",
                    "davmusic", "358FJuNQT-OZcD6s0jssUo4q_C5wveDu"),
    };

    /** 单文件上限，超过则轮转为 .1 备份 */
    private static final long MAX_FILE_SIZE = 512 * 1024;

    private static RemoteLogger instance;

    private final Context context;
    private final File logFile;
    private final File logFileOld;
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private final ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
    /** 上报共用同一个 OkHttpClient：连接池/线程池复用，不必每次上传都新建 */
    private final OkHttpClient uploadClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private RemoteLogger(Context context) {
        this.context = context.getApplicationContext();
        File dir = new File(this.context.getFilesDir(), "logs");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.logFile = new File(dir, "davmusic.log");
        this.logFileOld = new File(dir, "davmusic.log.1");
    }

    public static synchronized RemoteLogger getInstance(Context context) {
        if (instance == null) {
            instance = new RemoteLogger(context);
        }
        return instance;
    }

    // ---------- 写日志 ----------

    public void d(String tag, String msg) {
        write("D", tag, msg);
        Log.d(tag, msg);
    }

    public void i(String tag, String msg) {
        write("I", tag, msg);
        Log.i(tag, msg);
    }

    public void w(String tag, String msg) {
        write("W", tag, msg);
        Log.w(tag, msg);
    }

    public void e(String tag, String msg) {
        write("E", tag, msg);
        Log.e(tag, msg);
    }

    public void e(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg);
        sb.append(" | ").append(t.getClass().getSimpleName())
          .append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(8, st.length); i++) {
            sb.append("\n    at ").append(st[i].toString());
        }
        write("E", tag, sb.toString());
        Log.e(tag, msg, t);
    }

    /** 分线程写入，避免在主线程做 IO */
    private void write(final String level, final String tag, final String msg) {
        final String line = String.format(Locale.getDefault(),
                "%s %s/%-16s %s\n",
                fmt.format(new Date()), level, tag, msg);

        new Thread(() -> {
            synchronized (RemoteLogger.this) {
                try {
                    rotateIfNeeded();
                    try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                        fos.write(line.getBytes("UTF-8"));
                    }
                } catch (IOException ignored) {
                    // 日志写失败不能再抛，否则会连锁影响主流程
                }
            }
        }, "remotelog-writer").start();
    }

    /** 日志落盘 */
    public void flush() {
        synchronized (this) {
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(("[%s] --- flush ---\n".replace("%s",
                        fmt.format(new Date()))).getBytes("UTF-8"));
            } catch (IOException ignored) {
            }
        }
    }

    private void rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_FILE_SIZE) {
            //noinspection ResultOfMethodCallIgnored
            logFileOld.delete();
            //noinspection ResultOfMethodCallIgnored
            logFile.renameTo(logFileOld);
        }
    }

    // ---------- 上报 ----------

    /**
     * 上报日志到服务器。
     *
     * @param note 附加上下文说明（例如用户当时在做什么）
     */
    public void upload(final String note) {
        final String fileName = "davmusic_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date())
                + ".log";

        uploadExecutor.execute(() -> {
            try {
                StringBuilder content = new StringBuilder();
                content.append("=== davmusic 日志上报 ===\n");
                content.append("时间: ").append(new Date()).append("\n");
                content.append("说明: ").append(note == null ? "-" : note).append("\n");
                content.append("设备: ").append(android.os.Build.MANUFACTURER)
                       .append(" ").append(android.os.Build.MODEL).append("\n");
                content.append("系统: Android ").append(android.os.Build.VERSION.RELEASE)
                       .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
                content.append("===== 日志开始 =====\n\n");

                // 先放旧文件（时间更早），再放当前文件
                content.append(readFileSafe(logFileOld));
                content.append(readFileSafe(logFile));
                content.append("\n===== 日志结束 =====\n");

                byte[] body = content.toString().getBytes("UTF-8");

                // 逐个端点上报：互不影响，任一失败都不影响其他端点与主流程
                for (Endpoint ep : ENDPOINTS) {
                    uploadTo(ep, fileName, body);
                }
            } catch (Exception ex) {
                write("E", TAG, "日志上报异常: " + ex.getMessage());
            }
        });
    }

    /**
     * 向单个端点上报日志。
     *
     * 刻意把异常吞在这里：一个端点不可达（例如云防火墙尚未放行端口）
     * 不应该让另一个端点也收不到日志，更不应该影响主流程。
     */
    private void uploadTo(Endpoint ep, String fileName, byte[] body) {
        try {
            Request request = new Request.Builder()
                    .url(ep.url + fileName)
                    .put(RequestBody.create(body,
                            MediaType.parse("text/plain; charset=utf-8")))
                    .header("Authorization", Credentials.basic(ep.user, ep.pass))
                    .build();

            try (Response resp = uploadClient.newCall(request).execute()) {
                if (resp.isSuccessful()) {
                    write("I", TAG, "日志已上报 " + ep.label + ": " + fileName
                            + " (" + body.length + " 字节)");
                } else {
                    write("W", TAG, "日志上报失败 " + ep.label
                            + ": HTTP " + resp.code());
                }
            }
        } catch (Exception ex) {
            write("E", TAG, "日志上报异常 " + ep.label + ": " + ex.getMessage());
        }
    }

    private String readFileSafe(File f) {
        if (f == null || !f.exists()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            // 只取末尾部分，避免日志过大导致内存/请求体膨胀
            long maxRead = 256 * 1024;
            long start = Math.max(0, len - maxRead);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            String head = start > 0 ? "…（前部已截断）\n" : "";
            return head + new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 清空本地日志 */
    public void clear() {
        synchronized (this) {
            //noinspection ResultOfMethodCallIgnored
            logFile.delete();
            //noinspection ResultOfMethodCallIgnored
            logFileOld.delete();
        }
    }

    /** 本地日志路径（用于提示用户） */
    public String getLogPath() {
        return logFile.getAbsolutePath();
    }

    public long getLogSize() {
        long n = 0;
        if (logFile.exists()) n += logFile.length();
        if (logFileOld.exists()) n += logFileOld.length();
        return n;
    }
}
