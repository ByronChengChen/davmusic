package com.byron.davmusic;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.UUID;

/**
 * WebDAV 服务器管理页。
 *
 * 能力：
 *   · 列出全部已保存的 WebDAV 服务器，标出「当前使用」的那台
 *   · 新增一台（对话框：名称/地址/账号/密码 + 测试连接）
 *   · 删除一台（删除活动服务器时自动把活动指针挪到剩下的第一台；
 *     删完最后一台则回到首次配置页）
 *   · 点击列表项 = 切换到该服务器 （这一条是「A 服务器播完切到 B 服务器」
 *     需求的入口：切换动作本身在这里只改持久化指针，真正重建客户端、
 *     清缓存、停播放由 MainActivity.onResume 统一执行，避免两处各改一半）
 *
 * 本页只负责「改配置」，不直接触碰 WebDAVClient 单例 —— 否则会出现在本页
 * 切了服务器、返回主界面却还在用旧地址的两套状态问题。
 */
public class ServerManageActivity extends AppCompatActivity implements ServerListAdapter.Listener {

    private static final String TAG = "ServerManage";

    private RecyclerView recyclerView;
    private ServerListAdapter adapter;
    private TextView emptyView;
    private TextView countView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_manage);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_server_manage);
        }

        emptyView = findViewById(R.id.emptyView);
        countView = findViewById(R.id.serverCountText);
        recyclerView = findViewById(R.id.serverRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ServerListAdapter(this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.addServerButton).setOnClickListener(v -> showEditDialog(null));

        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 重新读一遍清单并刷新界面 */
    private void refresh() {
        List<ServerProfile> servers = ServerStore.list(this);
        ServerProfile active = ServerStore.getActive(this);
        adapter.setData(servers, active == null ? null : active.getId());

        boolean empty = servers.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        countView.setVisibility(empty ? View.GONE : View.VISIBLE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            countView.setText(getString(R.string.label_server_count, servers.size()));
        }
    }

    // ==================== 列表交互 ====================

    @Override
    public void onSwitch(ServerProfile profile) {
        if (profile == null) return;

        ServerProfile active = ServerStore.getActive(this);
        if (active != null && active.getId() != null && active.getId().equals(profile.getId())) {
            toast(getString(R.string.msg_already_active_server));
            return;
        }
        if (!profile.isComplete()) {
            toast(getString(R.string.msg_server_incomplete));
            return;
        }

        ServerStore.setActive(this, profile.getId());
        Log.i(TAG, "切换到服务器: " + profile.getDisplayName() + " url=" + profile.getUrl());
        toast(getString(R.string.msg_switched_server, profile.getDisplayName()));
        setResult(RESULT_OK);
        refresh();
    }

    @Override
    public void onEdit(ServerProfile profile) {
        showEditDialog(profile);
    }

    @Override
    public void onDelete(ServerProfile profile) {
        if (profile == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_delete_server)
                .setMessage(getString(R.string.msg_confirm_delete_server, profile.getDisplayName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.menu_delete_server, (d, w) -> doDelete(profile))
                .show();
    }

    private void doDelete(ServerProfile profile) {
        ServerProfile before = ServerStore.getActive(this);
        boolean wasActive = before != null && before.getId() != null
                && before.getId().equals(profile.getId());

        ServerStore.remove(this, profile.getId());
        Log.i(TAG, "已删除服务器: " + profile.getDisplayName() + "（是否为活动服务器=" + wasActive + "）");
        setResult(RESULT_OK);

        List<ServerProfile> remain = ServerStore.list(this);
        if (remain.isEmpty()) {
            // 最后一台被删掉：主界面已无可用的服务器，
            // 回首次配置页重新录入，避免主界面拿空地址发请求
            toast(getString(R.string.msg_deleted_last_server));
            Intent intent = new Intent(this, ServerConfigActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
            return;
        }

        if (wasActive) {
            ServerProfile now = ServerStore.getActive(this);
            toast(getString(R.string.msg_server_switched_after_delete,
                    now == null ? "" : now.getDisplayName()));
        } else {
            toast(getString(R.string.msg_server_deleted, profile.getDisplayName()));
        }
        refresh();
    }

    // ==================== 新增 / 编辑对话框 ====================

    private void showEditDialog(final ServerProfile existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_server_edit, null);
        final EditText nameInput = view.findViewById(R.id.editServerName);
        final EditText urlInput = view.findViewById(R.id.editServerUrl);
        final EditText userInput = view.findViewById(R.id.editServerUsername);
        final EditText passInput = view.findViewById(R.id.editServerPassword);
        final Button testButton = view.findViewById(R.id.btnTestConnection);
        final TextView resultView = view.findViewById(R.id.testResultText);

        if (existing != null) {
            nameInput.setText(existing.getName());
            urlInput.setText(existing.getUrl());
            userInput.setText(existing.getUsername());
            passInput.setText(existing.getPassword());
        }

        testButton.setOnClickListener(v -> {
            final String url = urlInput.getText().toString().trim();
            if (!isValidUrl(url)) {
                showTestResult(resultView, getString(R.string.error_invalid_url), false);
                return;
            }
            testButton.setEnabled(false);
            showTestResult(resultView, getString(R.string.msg_testing), null);

            final String user = userInput.getText().toString().trim();
            final String pass = passInput.getText().toString();
            WebDAVClient.testConnectionWith(url, user, pass,
                    new WebDAVClient.WebDAVCallback<Integer>() {
                        @Override
                        public void onSuccess(Integer code) {
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                testButton.setEnabled(true);
                                if (code != null && code >= 200 && code < 300) {
                                    showTestResult(resultView,
                                            getString(R.string.msg_connection_success)
                                                    + "（HTTP " + code + "）", true);
                                } else {
                                    showTestResult(resultView, getString(
                                            R.string.msg_connection_http_error,
                                            code == null ? -1 : code), false);
                                }
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                testButton.setEnabled(true);
                                String detail = (e == null || e.getMessage() == null) ? "" : e.getMessage();
                                showTestResult(resultView,
                                        getString(R.string.msg_connection_failed) + "：" + detail, false);
                            });
                        }
                    });
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.title_add_server : R.string.title_edit_server)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.btn_save, null)
                .create();
        dialog.show();

        // 手动接管「保存」：setPositiveButton 的回调执行后对话框会无条件关闭，
        // 那样输入不合法时用户得重敲一遍。这里校验不过就保持打开。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ServerProfile saved = collectAndSave(existing, nameInput, urlInput, userInput, passInput);
            if (saved != null) {
                dialog.dismiss();
                setResult(RESULT_OK);
                refresh();
            }
        });
    }

    private ServerProfile collectAndSave(ServerProfile existing, EditText nameInput, EditText urlInput,
                                         EditText userInput, EditText passInput) {
        String name = nameInput.getText().toString().trim();
        String url = urlInput.getText().toString().trim();
        String user = userInput.getText().toString().trim();
        String pass = passInput.getText().toString();

        if (TextUtils.isEmpty(url)) {
            toast(getString(R.string.error_server_url_required));
            return null;
        }
        if (!isValidUrl(url)) {
            toast(getString(R.string.error_invalid_url));
            return null;
        }
        if (TextUtils.isEmpty(user)) {
            toast(getString(R.string.error_username_required));
            return null;
        }
        if (TextUtils.isEmpty(pass)) {
            toast(getString(R.string.error_password_required));
            return null;
        }

        ServerProfile p = existing != null ? existing : new ServerProfile();
        if (p.getId() == null || p.getId().isEmpty()) {
            p.setId(UUID.randomUUID().toString());
        }
        p.setName(name);
        p.setUrl(url);
        p.setUsername(user);
        p.setPassword(pass);

        if (existing == null) {
            ServerStore.add(this, p);
            Log.i(TAG, "新增服务器: " + p.getDisplayName() + " url=" + url);
            toast(getString(R.string.msg_server_added, p.getDisplayName()));
        } else {
            ServerStore.update(this, p);
            Log.i(TAG, "更新服务器: " + p.getDisplayName() + " url=" + url);
            toast(getString(R.string.msg_server_updated, p.getDisplayName()));
        }
        return p;
    }

    private void showTestResult(TextView tv, String text, Boolean ok) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
        int colorRes;
        if (ok == null) {
            colorRes = R.color.text_secondary;
        } else if (ok) {
            colorRes = R.color.network_online;
        } else {
            colorRes = R.color.error;
        }
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}