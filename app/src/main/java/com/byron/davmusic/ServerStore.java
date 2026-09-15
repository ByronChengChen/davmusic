package com.byron.davmusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * WebDAV 服务器清单的持久化。
 *
 * 存储结构（SharedPreferences "davmusic_config"）：
 *   · servers_json      —— ServerProfile 的 JSON 数组，全部服务器
 *   · active_server_id  —— 当前活动服务器的 id
 *   · server_url / username / password / is_configured —— 旧版单服务器键
 *
 * 为什么还要继续写旧键：MainActivity、ServerConfigActivity 等历史代码
 * 都直接读这三个键。让「活动服务器」始终镜像到旧键，这些代码就完全不用改，
 * 也不会出现「管理页里切了服务器、主界面还在用旧地址」的两套状态。
 * 旧键是派生数据，唯一事实来源是 servers_json + active_server_id。
 */
public class ServerStore {

    private static final String TAG = "ServerStore";

    public static final String PREFS_NAME = "davmusic_config";

    // 旧版单服务器键（继续同步写入）
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_IS_CONFIGURED = "is_configured";

    // 多服务器新增键
    private static final String KEY_SERVERS = "servers_json";
    private static final String KEY_ACTIVE_ID = "active_server_id";

    private ServerStore() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 读 ====================

    public static synchronized List<ServerProfile> list(Context context) {
        return load(context, prefs(context));
    }

    /** 当前活动服务器；没有任何服务器时返回 null */
    public static synchronized ServerProfile getActive(Context context) {
        SharedPreferences p = prefs(context);
        List<ServerProfile> all = load(context, p);
        return pickActive(all, p.getString(KEY_ACTIVE_ID, null));
    }

    /**
     * 活动服务器的缓存作用域标识；无服务器时返回 null。
     * 供 LocalCacheManager 区分「同一个远端路径属于哪台服务器」。
     */
    public static synchronized String getActiveScope(Context context) {
        ServerProfile active = getActive(context);
        return active == null ? null : active.getScopeId();
    }

    /**
     * 取活动服务器：优先 active_server_id，找不到就退回第一个。
     *
     * 为什么要退回：服务器可能被删除，而 active_server_id 还指着已删除的 id；
     * 也可能 servers_json 被外部改坏了。任何情况下都不能让「有服务器却选不出活动服务器」
     * 这种状态存在 —— 那会让主界面直接跳到配置页，用户以为配置丢了。
     */
    private static ServerProfile pickActive(List<ServerProfile> all, String activeId) {
        if (all == null || all.isEmpty()) return null;
        if (activeId != null) {
            for (ServerProfile s : all) {
                if (activeId.equals(s.getId())) return s;
            }
        }
        return all.get(0);
    }

    // ==================== 写 ====================

    /** 新增一台服务器，返回补好 id 的实例 */
    public static synchronized ServerProfile add(Context context, ServerProfile profile) {
        SharedPreferences p = prefs(context);
        List<ServerProfile> all = load(context, p);
        if (profile.getId() == null || profile.getId().isEmpty()) {
            profile.setId(UUID.randomUUID().toString());
        }
        all.add(profile);
        String activeId = p.getString(KEY_ACTIVE_ID, null);
        if (activeId == null || activeId.isEmpty()) {
            activeId = profile.getId();
        }
        save(context, all, activeId);
        Log.i(TAG, "新增服务器: " + profile.getDisplayName() + " 共 " + all.size() + " 台");
        return profile;
    }

    /** 就地更新一台服务器（按 id 匹配） */
    public static synchronized void update(Context context, ServerProfile profile) {
        SharedPreferences p = prefs(context);
        List<ServerProfile> all = load(context, p);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(profile.getId())) {
                all.set(i, profile);
                break;
            }
        }
        save(context, all, p.getString(KEY_ACTIVE_ID, null));
    }

    /**
     * 设为活动服务器。
     *
     * 返回值刻意设计成「是否发生了变化」：主界面在 onResume 里靠它判断
     * 是否需要重建客户端与清空缓存。重复点同一台服务器不应该触发重建。
     */
    public static synchronized boolean setActive(Context context, String id) {
        SharedPreferences p = prefs(context);
        String current = p.getString(KEY_ACTIVE_ID, null);
        if (id != null && id.equals(current)) {
            return false;
        }
        save(context, load(context, p), id);
        Log.i(TAG, "活动服务器切换: " + current + " -> " + id);
        return true;
    }

    /**
     * 删除一台服务器。
     *
     * 若删掉的正是活动服务器：自动把活动指针挪到剩下的第一台；
     * 若删完一台不剩：清空旧键并把 is_configured 置回 false，
     * 主界面据此回到「首次配置」流程，而不是拿一台不存在的服务器去发请求。
     */
    public static synchronized void remove(Context context, String id) {
        SharedPreferences p = prefs(context);
        List<ServerProfile> all = load(context, p);
        ServerProfile removed = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(id)) {
                removed = all.remove(i);
                break;
            }
        }
        if (removed == null) return;

        String activeId = p.getString(KEY_ACTIVE_ID, null);
        if (id.equals(activeId)) {
            activeId = all.isEmpty() ? null : all.get(0).getId();
            Log.i(TAG, "删除的是活动服务器，活动指针改为: " + activeId);
        }
        save(context, all, activeId);
        Log.i(TAG, "删除服务器: " + removed.getDisplayName() + " 剩 " + all.size() + " 台");
    }

    // ==================== 内部 ====================

    /** 读列表；首次调用时把旧版单服务器配置迁移成列表里的第一台 */
    private static List<ServerProfile> load(Context context, SharedPreferences p) {
        List<ServerProfile> list = new ArrayList<>();
        String raw = p.getString(KEY_SERVERS, null);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    ServerProfile s = ServerProfile.fromJson(arr.optJSONObject(i));
                    if (s != null && s.getId() != null && !s.getId().isEmpty()) {
                        list.add(s);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "servers_json 解析失败，按空列表处理: " + e);
            }
            if (!list.isEmpty()) return list;
        }

        // 迁移：升级安装后 servers_json 还不存在，但旧键里已有配置
        String url = p.getString(KEY_SERVER_URL, "");
        boolean configured = p.getBoolean(KEY_IS_CONFIGURED, false);
        if (configured && url != null && !url.trim().isEmpty()) {
            ServerProfile first = new ServerProfile(
                    UUID.randomUUID().toString(),
                    "",
                    url,
                    p.getString(KEY_USERNAME, ""),
                    p.getString(KEY_PASSWORD, ""));
            list.add(first);
            save(context, list, first.getId());
            Log.i(TAG, "旧版单服务器配置已迁移为列表第 1 台: " + first.getHost());
        }
        return list;
    }

    /** 落盘：写列表 + 活动指针，并把活动服务器镜像到旧键 */
    private static void save(Context context, List<ServerProfile> all, String activeId) {
        SharedPreferences p = prefs(context);
        JSONArray arr = new JSONArray();
        for (ServerProfile s : all) {
            try {
                arr.put(s.toJson());
            } catch (Exception e) {
                Log.w(TAG, "序列化服务器失败，跳过: " + e);
            }
        }

        SharedPreferences.Editor editor = p.edit()
                .putString(KEY_SERVERS, arr.toString())
                .putString(KEY_ACTIVE_ID, activeId == null ? "" : activeId);

        ServerProfile active = pickActive(all, activeId);
        if (active == null) {
            // 一台都不剩：清掉旧键，让主界面回到首次配置
            editor.remove(KEY_SERVER_URL)
                    .remove(KEY_USERNAME)
                    .remove(KEY_PASSWORD)
                    .putBoolean(KEY_IS_CONFIGURED, false);
        } else {
            editor.putString(KEY_SERVER_URL, active.getUrl() == null ? "" : active.getUrl())
                    .putString(KEY_USERNAME, active.getUsername() == null ? "" : active.getUsername())
                    .putString(KEY_PASSWORD, active.getPassword() == null ? "" : active.getPassword())
                    .putBoolean(KEY_IS_CONFIGURED, active.isComplete());
        }
        editor.apply();
    }
}