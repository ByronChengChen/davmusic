package com.byron.davmusic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 服务器清单的持久化（Robolectric：SharedPreferences）。
 *
 * 这台清单决定了根目录里有哪些「服务器文件夹」，也是 v1.29 多服务器重构的
 * 唯一事实来源。活动指针指向已删除服务器、别名重名、旧配置迁移失败，
 * 都会让主界面直接跳回配置页 —— 用户看到的是「配置丢了」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ServerStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        SharedPreferences p = ServerStore.prefs(ctx);
        p.edit().clear().apply();
    }

    private static ServerProfile profile(String name, String url, String user) {
        return new ServerProfile(null, name, url, user, "pass");
    }

    @Test
    public void firstServerBecomesActiveAutomatically() {
        ServerProfile a = ServerStore.add(ctx, profile("云盘A", "https://a.example.com/dav/", "u"));
        ServerProfile b = ServerStore.add(ctx, profile("云盘B", "https://b.example.com/dav/", "u"));

        assertNotNull(a.getId());
        assertFalse(a.getId().isEmpty());
        assertEquals(2, ServerStore.list(ctx).size());
        assertEquals(a.getId(), ServerStore.getActive(ctx).getId());
        assertEquals("云盘A", ServerStore.getActive(ctx).getDisplayName());
        assertEquals(b.getId(), ServerStore.list(ctx).get(1).getId());
    }

    @Test
    public void setActiveReportsWhetherItChanged() {
        ServerProfile a = ServerStore.add(ctx, profile("A", "https://a/dav/", "u"));
        ServerProfile b = ServerStore.add(ctx, profile("B", "https://b/dav/", "u"));

        // 重复切到当前活动服务器不应触发界面重建
        assertFalse(ServerStore.setActive(ctx, a.getId()));
        assertTrue(ServerStore.setActive(ctx, b.getId()));
        assertFalse(ServerStore.setActive(ctx, b.getId()));
        assertEquals(b.getId(), ServerStore.getActive(ctx).getId());
    }

    @Test
    public void removingActiveServerPromotesFirstRemaining() {
        ServerProfile a = ServerStore.add(ctx, profile("A", "https://a/dav/", "u"));
        ServerProfile b = ServerStore.add(ctx, profile("B", "https://b/dav/", "u"));
        ServerProfile c = ServerStore.add(ctx, profile("C", "https://c/dav/", "u"));

        ServerStore.setActive(ctx, b.getId());
        ServerStore.remove(ctx, b.getId());

        assertEquals(2, ServerStore.list(ctx).size());
        // 活动指针不能悬空指向已删除的 id
        assertEquals(a.getId(), ServerStore.getActive(ctx).getId());
        assertNotEquals(c.getId(), ServerStore.getActive(ctx).getId());
    }

    @Test
    public void removingLastServerLeavesNoActive() {
        ServerProfile a = ServerStore.add(ctx, profile("A", "https://a/dav/", "u"));

        ServerStore.remove(ctx, a.getId());

        assertTrue(ServerStore.list(ctx).isEmpty());
        assertNull(ServerStore.getActive(ctx));
        assertNull(ServerStore.getActiveScope(ctx));
    }

    @Test
    public void removingUnknownIdIsANoOp() {
        ServerProfile a = ServerStore.add(ctx, profile("A", "https://a/dav/", "u"));

        ServerStore.remove(ctx, "no-such-id");

        assertEquals(1, ServerStore.list(ctx).size());
        assertEquals(a.getId(), ServerStore.getActive(ctx).getId());
    }

    @Test
    public void aliasUniquenessIgnoresCaseAndSurroundingSpaces() {
        ServerStore.add(ctx, profile("我的云盘", "https://a/dav/", "u"));

        assertTrue(ServerStore.isAliasTaken(ctx, "我的云盘", null));
        assertTrue(ServerStore.isAliasTaken(ctx, "  我的云盘  ", null));
        assertFalse(ServerStore.isAliasTaken(ctx, "别的名字", null));
        assertFalse(ServerStore.isAliasTaken(ctx, "", null));
        assertFalse(ServerStore.isAliasTaken(ctx, null, null));
    }

    @Test
    public void aliasUniquenessOfAsciiIsCaseInsensitiveAndEditSkipsSelf() {
        ServerProfile a = ServerStore.add(ctx, profile("CloudA", "https://a/dav/", "u"));

        assertTrue(ServerStore.isAliasTaken(ctx, "clouda", null));
        // 编辑自己时不改名，不应被自己挡住
        assertFalse(ServerStore.isAliasTaken(ctx, "CloudA", a));
    }

    @Test
    public void profileJsonRoundTrip() throws Exception {
        ServerProfile p = new ServerProfile("id-9", "别名", "https://h/dav/", "user", "secret");

        ServerProfile back = ServerProfile.fromJson(p.toJson());

        assertNotNull(back);
        assertEquals("id-9", back.getId());
        assertEquals("别名", back.getName());
        assertEquals("https://h/dav/", back.getUrl());
        assertEquals("user", back.getUsername());
        assertEquals("secret", back.getPassword());
    }

    @Test
    public void displayNameFallsBackToHostNeverBlank() {
        assertEquals("我的", new ServerProfile("1", "我的", "https://host/dav/", "u", "p").getDisplayName());
        assertEquals("host", new ServerProfile("1", "  ", "https://host/dav/", "u", "p").getDisplayName());
        assertEquals("未命名服务器", new ServerProfile("1", null, "", "u", "p").getDisplayName());
    }

    @Test
    public void scopeIdIsStableAndSeparatesUrlAndUser() {
        ServerProfile a = new ServerProfile("1", "A", "https://host/dav/", "u1", "p");
        ServerProfile same = new ServerProfile("2", "A2", "https://host/dav/", "u1", "p");
        ServerProfile otherUser = new ServerProfile("3", "B", "https://host/dav/", "u2", "p");
        ServerProfile otherUrl = new ServerProfile("4", "C", "https://other/dav/", "u1", "p");

        assertEquals(a.getScopeId(), same.getScopeId());
        assertNotEquals(a.getScopeId(), otherUser.getScopeId());
        assertNotEquals(a.getScopeId(), otherUrl.getScopeId());
        assertEquals(12, a.getScopeId().length());
        // 不含密码：同一台服务器同一账号，改密码不改变缓存作用域
        assertEquals(a.getScopeId(),
                new ServerProfile("5", "D", "https://host/dav/", "u1", "different").getScopeId());
    }

    @Test
    public void legacySingleServerConfigIsMigratedAsFirstEntry() {
        // 升级安装：servers_json 还不存在，但旧键里已有配置
        SharedPreferences p = ServerStore.prefs(ctx);
        p.edit()
                .putString(ServerStore.KEY_SERVER_URL, "https://legacy.example.com/dav/")
                .putString(ServerStore.KEY_USERNAME, "olduser")
                .putString(ServerStore.KEY_PASSWORD, "oldpass")
                .putBoolean(ServerStore.KEY_IS_CONFIGURED, true)
                .apply();

        List<ServerProfile> list = ServerStore.list(ctx);

        assertEquals(1, list.size());
        assertEquals("legacy.example.com", list.get(0).getDisplayName());
        assertEquals("https://legacy.example.com/dav/", list.get(0).getUrl());
        assertEquals("olduser", list.get(0).getUsername());
        assertEquals(list.get(0).getId(), ServerStore.getActive(ctx).getId());
    }

    @Test
    public void updateReplacesByIdKeepingPosition() {
        ServerProfile a = ServerStore.add(ctx, profile("A", "https://a/dav/", "u"));
        ServerStore.add(ctx, profile("B", "https://b/dav/", "u"));

        ServerProfile edited = new ServerProfile(a.getId(), "A-改名", "https://a2/dav/", "u", "p");
        ServerStore.update(ctx, edited);

        List<ServerProfile> list = ServerStore.list(ctx);
        assertEquals(2, list.size());
        assertEquals("A-改名", list.get(0).getDisplayName());
        assertEquals("https://a2/dav/", list.get(0).getUrl());
    }
}
