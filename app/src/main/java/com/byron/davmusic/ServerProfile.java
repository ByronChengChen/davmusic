package com.byron.davmusic;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * 一台 WebDAV 服务器的配置。
 *
 * 多服务器支持引入的新模型：此前整个 App 只认「一组」地址/账号/密码
 * （存在 SharedPreferences 的 server_url / username / password 三个键里），
 * 现在改为一个列表 + 一个「当前活动服务器」指针。
 *
 * 注意：密码与旧版实现一样以明文存在应用私有 SharedPreferences 中。
 * 这是沿用既有约定（未做加密），App 未申请外部存储权限，
 * 私有目录其他应用读不到；如需更强保护需引入 EncryptedSharedPreferences。
 */
public class ServerProfile {

    private String id;
    private String name;
    private String url;
    private String username;
    private String password;

    public ServerProfile() {
    }

    public ServerProfile(String id, String name, String url, String username, String password) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** 列表里显示的名字：用户起的名称优先，没起名就用主机名兜底，保证永远不显示空白行 */
    public String getDisplayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String host = getHost();
        return host.isEmpty() ? "未命名服务器" : host;
    }

    /** 从 URL 里抠出主机名（仅用于显示与生成默认名称，不做校验） */
    public String getHost() {
        if (url == null) return "";
        String u = url.trim();
        int schemeIdx = u.indexOf("://");
        if (schemeIdx >= 0) u = u.substring(schemeIdx + 3);
        int slash = u.indexOf('/');
        if (slash >= 0) u = u.substring(0, slash);
        return u;
    }

    /**
     * 缓存作用域标识。
     *
     * 用途：下载索引与目录快照都是「按远端路径」做键的，而不同服务器的
     * 远端路径完全可能重名（两台都用 /dav/cmcc/music/... ）。若不加区分，
     * 在 A 服务器下载过的歌曲，切到 B 服务器后会被判定为「已下载」，
     * 直接播放 A 的本地文件 —— 听感上就是「切了服务器却还是老歌」。
     *
     * 取值：url + 用户名 的 SHA-256 前 12 位十六进制。把用户名也算进来是
     * 为了区分同一台服务上的不同账号（不同账号可见的目录往往不同）。
     * 不含密码，避免密码散列落到缓存文件名里。
     */
    public String getScopeId() {
        String seed = (url == null ? "" : url.trim().toLowerCase(Locale.US))
                + "|" + (username == null ? "" : username.trim());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(seed.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.substring(0, 12);
        } catch (Exception e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id == null ? "" : id);
        o.put("name", name == null ? "" : name);
        o.put("url", url == null ? "" : url);
        o.put("username", username == null ? "" : username);
        o.put("password", password == null ? "" : password);
        return o;
    }

    public static ServerProfile fromJson(JSONObject o) {
        if (o == null) return null;
        ServerProfile p = new ServerProfile();
        p.setId(o.optString("id", ""));
        p.setName(o.optString("name", ""));
        p.setUrl(o.optString("url", ""));
        p.setUsername(o.optString("username", ""));
        p.setPassword(o.optString("password", ""));
        return p;
    }

    /** 配置是否可用（三项齐全），列表里用来标红「不完整」的条目 */
    public boolean isComplete() {
        return url != null && !url.trim().isEmpty()
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}