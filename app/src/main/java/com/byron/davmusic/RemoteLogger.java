package com.byron.davmusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 *   2. 提供 upload() 把日志文件 PUT 到远端收集端点。
 *   3. 上报走独立的 OkHttpClient 与线程池，且用 try-catch 全包，
 *      任何失败都只记本地日志、绝不影响主流程。
 *
 * ⚠️ 鉴权（v1.33 起）：HMAC-SHA256 签名，不再使用 Basic Auth。
 *
 *   旧实现把「用户名 + 口令」明文写在 ENDPOINTS 常量里，而本仓库是公开的，
 *   等于把两个收集端点的凭据一起公开了。现在改为：
 *
 *     · 令牌由用户在「设置上报令牌」里手动输入，存在应用私有目录
 *       （SharedPreferences），**不进源码、不进仓库、不进 APK**。
 *     · 每次上报现算签名，令牌本身**从不上线**：
 *         canonical = "PUT\n<path>\n<timestamp>\n<nonce>\n<sha256(body)>"
 *         signature = hex(HMAC-SHA256(key = 令牌, msg = canonical))
 *     · 链路上即使被嗅探，拿到的也只是一个绑定「路径 + 时间戳 + nonce」
 *       的签名；服务端会消费掉 nonce，所以既不能伪造新请求也不能重放。
 *
 *   注意：本方案的价值全部来自「令牌不在客户端分发物里」，与算法是否
 *   隐藏无关 —— HMAC-SHA256 是公开标准，把签名逻辑放进 .so 不增加任何
 *   安全性（令牌不在 .so 里），只会带来 NDK 构建的代价。故保持纯 Java。
 *
 * 注意：本类只负责"记录"和"上报"，不改变任何播放行为。
 */
public class RemoteLogger {

    private static final String TAG = "RemoteLogger";

    /** 上报端点。只有这一个：腾讯那台（公网 443 + 已泄漏口令）已停用。 */
    private static final String ENDPOINT_LABEL = "本机";
    private static final String ENDPOINT_BASE = "http://140.245.120.89:9339";
    private static final String ENDPOINT_PATH = "/davmusic-logs/";

    /** 令牌存放位置：应用私有 SharedPreferences */
    private static final String PREF_NAME = "davmusic_log_prefs";
    private static final String PREF_TOKEN = "log_token";

    /** 单文件上限，超过则轮转为 .1 备份 */
    private static final long MAX_FILE_SIZE = 512 * 1024;

    /** 上报结果回调。用于把「失败原因」告诉用户，而不是静默吞掉。 */
    public interface UploadCallback {
        /**
         * @param ok      true 表示服务端已接收（HTTP 201）
         * @param message 面向用户的简短说明
         */
        void onResult(boolean ok, String message);
    }

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

    // ---------- 令牌管理 ----------

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 是否已配置上报令牌。未配置时 upload() 直接跳过，不发任何请求。 */
    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    public String getToken() {
        try {
            return prefs().getString(PREF_TOKEN, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 保存令牌。传空串等于清除。 */
    public void setToken(String token) {
        try {
            prefs().edit().putString(PREF_TOKEN,
                    token == null ? "" : token.trim()).apply();
        } catch (Exception e) {
            Log.w(TAG, "保存上报令牌失败: " + e.getMessage());
        }
    }

    /** 已配置令牌的掩码形式，用于在界面上确认「配的是哪一个」而不暴露它 */
    public String getMaskedToken() {
        String t = getToken();
        if (t.isEmpty()) return "（未配置）";
        if (t.length() <= 8) return "••••";
        return t.substring(0, 4) + "••••" + t.substring(t.length() - 4);
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

    // ---------- 签名 ----------

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return toHex(md.digest(data));
    }

    private static String hmacSha256Hex(String key, String msg) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        return toHex(mac.doFinal(msg.getBytes("UTF-8")));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String newNonce() {
        byte[] buf = new byte[16];
        new SecureRandom().nextBytes(buf);
        return toHex(buf);
    }

    /**
     * 构造签名串。必须与服务端 log_sink.py 的 canonical_string() 逐字节一致：
     *   "PUT\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex
     */
    private static String canonicalString(String path, String timestamp,
                                          String nonce, String bodySha256Hex) {
        return "PUT\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodySha256Hex;
    }

    // ---------- 上报 ----------

    /** 上报日志（无回调，失败只记本地日志）。 */
    public void upload(final String note) {
        upload(note, null);
    }

    /**
     * 上报日志到服务器。
     *
     * @param note 附加上下文说明（例如用户当时在做什么）
     * @param cb   结果回调；可为 null。回调在**后台线程**触发，调用方需自行
     *             切回主线程再更新 UI。
     */
    public void upload(final String note, final UploadCallback cb) {
        if (!hasToken()) {
            // 未配置令牌：不发请求，并且要明确告知，不能静默失败。
            write("W", TAG, "未配置上报令牌，跳过日志上报");
            if (cb != null) {
                cb.onResult(false, "未配置上报令牌\n（菜单 → 设置上报令牌）");
            }
            return;
        }

        final String fileName = "davmusic_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(new Date())
                + ".log";

        uploadExecutor.execute(() -> {
            try {
                // 记录读取时的长度：上报成功后据此精确清理，
                // 只删「本次确实已发出」的内容（见 clearUploaded 的说明）
                final long lenOld = logFileOld.exists() ? logFileOld.length() : 0L;
                final long lenCur = logFile.exists() ? logFile.length() : 0L;

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
                String result = uploadTo(fileName, body);

                // 只在成功后清理：失败（断网/令牌无效）必须保留本地日志，
                // 否则「上传失败」会连带把唯一的副本也弄丢
                if (result == null) {
                    clearUploaded(lenOld, lenCur);
                }

                if (cb != null) {
                    cb.onResult(result == null, result);
                }
            } catch (Exception ex) {
                write("E", TAG, "日志上报异常: " + ex.getMessage());
                if (cb != null) {
                    cb.onResult(false, "上报异常: " + ex.getMessage());
                }
            }
        });
    }

    /**
     * 上报成功后，清掉「本次确实已发出」的那部分日志。
     *
     * ⛔ 不能简单地删掉整个文件。读取快照（T1）到上报完成（T2）之间隔着
     * 1–3 秒，这期间新写入的日志还没发出去；T2 直接删就会把它们永久丢掉。
     * 而本机制要抓的「锁屏切歌失败」恰好可能发生在任意时刻 ——
     * 在上报窗口里丢日志，等于给最需要的那段现场挖了个洞。
     *
     * 做法：logFileOld 整体已随本次上报发出，可删；
     * logFile 只保留 [lenCur, 当前长度) 这段新写入的内容。
     *
     * @param lenOld 读取时 logFileOld 的长度
     * @param lenCur 读取时 logFile 的长度
     */
    private void clearUploaded(long lenOld, long lenCur) {
        synchronized (this) {
            try {
                // 上报期间若发生了轮转（logFile 被改名成 logFileOld），
                // 文件状态已与读取时不一致，此时任何删除都可能误伤新内容。
                // 保守起见本次不清理 —— 宁可下次多发一遍，也不冒丢日志的险。
                if (logFileOld.exists() && logFileOld.length() != lenOld) {
                    Log.i(TAG, "上报期间日志发生轮转，跳过本次清理");
                    return;
                }

                if (logFileOld.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    logFileOld.delete();
                }

                if (!logFile.exists()) return;

                long now = logFile.length();
                if (now <= lenCur) {
                    // 读取之后没有新内容写入
                    //noinspection ResultOfMethodCallIgnored
                    logFile.delete();
                    return;
                }

                // 保留 [lenCur, now)：这是读取之后新写的，尚未上报过
                byte[] all = new byte[(int) now];
                try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                    raf.readFully(all);
                }
                try (FileOutputStream out = new FileOutputStream(logFile, false)) {
                    out.write(all, (int) lenCur, (int) (now - lenCur));
                }
            } catch (IOException e) {
                // 清理失败不影响上报本身，下次上报会重发一遍，不丢内容
                Log.w(TAG, "清理已上报日志失败: " + e.getMessage());
            }
        }
    }

    /**
     * 向端点上报日志。
     *
     * @return null 表示成功；否则是面向用户的失败说明。
     */
    private String uploadTo(String fileName, byte[] body) {
        String path = ENDPOINT_PATH + fileName;
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            String nonce = newNonce();
            String bodySha = sha256Hex(body);
            String signature = hmacSha256Hex(getToken(),
                    canonicalString(path, timestamp, nonce, bodySha));

            Request request = new Request.Builder()
                    .url(ENDPOINT_BASE + path)
                    .put(RequestBody.create(body,
                            MediaType.parse("text/plain; charset=utf-8")))
                    .header("X-Davmusic-Timestamp", timestamp)
                    .header("X-Davmusic-Nonce", nonce)
                    .header("X-Davmusic-Body-Sha256", bodySha)
                    .header("X-Davmusic-Signature", signature)
                    .build();

            try (Response resp = uploadClient.newCall(request).execute()) {
                if (resp.isSuccessful()) {
                    write("I", TAG, "日志已上报 " + ENDPOINT_LABEL + ": " + fileName
                            + " (" + body.length + " 字节)");
                    return null;
                }
                int code = resp.code();
                String err = resp.header("X-Davmusic-Error");
                write("W", TAG, "日志上报失败 " + ENDPOINT_LABEL
                        + ": HTTP " + code + " (err=" + err + ")");
                return describeFailure(code, err);
            }
        } catch (Exception ex) {
            write("E", TAG, "日志上报异常 " + ENDPOINT_LABEL + ": " + ex.getMessage());
            return "无法连接服务器\n" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage();
        }
    }

    /** 把 HTTP 失败翻译成用户看得懂、且能据此行动的一句话 */
    private static String describeFailure(int code, String errHeader) {
        if ("clock_skew".equals(errHeader)) {
            // 时钟偏移是这套方案最容易咬人的地方，必须明确提示
            return "设备时间不准，签名被拒\n请打开「自动设置时间」后重试";
        }
        if ("replay".equals(errHeader)) {
            return "请求被判定为重放\n（设备时间异常或重复提交）";
        }
        switch (code) {
            case 401:
                return "令牌无效或已过期\n请在菜单里重新设置上报令牌";
            case 403:
                return "服务器拒绝了该请求";
            case 413:
                return "日志文件过大，服务器拒绝接收";
            case 507:
                return "服务器日志配额已满";
            case 503:
                return "服务器未配置令牌，端点不可用";
            default:
                return "上报失败：HTTP " + code;
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
