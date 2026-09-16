package com.byron.davmusic;

import android.app.Activity;
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
 * 与「服务器列表」（根目录）的关系：
 *   · 根目录的服务器列表是【浏览】层级 —— 点一台就进去浏览它的目录树，
 *     返回键按「子目录 → 服务器根目录 → 服务器列表 → 退出」逐层退回。
 *   · 本页是【配置】层级 —— 只做增删改，浏览时不经过这里，避免把
 *     「返回」变成「浏览跳设置」的层级错位。
 * 两处都能改配置（根目录长按可编辑、可删除），本页则是集中管理的地方。
 */
public class ServerManageActivity extends AppCompatActivity implements ServerListAdapter.Listener {

    private static final String TAG = "ServerManage";

    /** 主界面从结果里读这个 key，拿到要进入的服务器 id */
    public static final String EXTRA_ENTER_SERVER_ID = "enter_server_id";

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
        // 【服务器即根目录】没有「当前使用的服务器」这个概念了 ——
        // 哪台在用取决于用户进到哪个服务器的目录树，因此列表不渲染「当前使用」标记。
        adapter.setData(servers);

        boolean empty = servers.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        countView.setVisibility(empty ? View.GONE : View.VISIBLE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!empty) {
            countView.setText(getString(R.string.label_server_count, servers.size()));
        }
    }

    // ==================== 列表交互 ====================

    /** 点击列表项 = 进入该服务器浏览（回传 id 给主界面） */
    @Override
    public void onSwitch(ServerProfile profile) {
        if (profile == null) return;
        if (!profile.isComplete()) {
            toast(getString(R.string.msg_server_incomplete));
            return;
        }
        Log.i(TAG, "进入服务器: " + profile.getDisplayName() + " url=" + profile.getUrl());

        Intent data = new Intent();
        data.putExtra(EXTRA_ENTER_SERVER_ID, profile.getId());
        setResult(RESULT_OK, data);
        finish();
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
        ServerStore.remove(this, profile.getId());
        Log.i(TAG, "已删除服务器: " + profile.getDisplayName());
        setResult(RESULT_OK);

        List<ServerProfile> remain = ServerStore.list(this);
        if (remain.isEmpty()) {
            // 全删光了：主界面无服务器可用，引导去添加
            toast(getString(R.string.msg_deleted_last_server));
            finish();
            return;
        }

        // 【服务器即根目录】删除不再需要「自动切到剩下第一台」——
        // 没有活动服务器这回事了。主界面在 onResume 会自行判断
        // 「正在浏览的那台是否已被删除」，必要时退回根目录。
        toast(getString(R.string.msg_server_deleted, profile.getDisplayName()));
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

        // 【别名】—— 服务器现在是根目录的第一层，别名就是「文件夹名」，
        // 因此必填且必须唯一：重复的别名会让用户在根目录里无法分辨两台，
        // 也会让「定位到当前歌曲」提示的服务器名产生歧义。
        if (TextUtils.isEmpty(name)) {
            toast(getString(R.string.error_alias_required));
            return null;
        }
        if (ServerStore.isAliasTaken(this, name, existing)) {
            toast(getString(R.string.error_alias_duplicate, name));
            return null;
        }

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
