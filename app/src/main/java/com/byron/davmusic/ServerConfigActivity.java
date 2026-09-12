package com.byron.davmusic;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerConfigActivity extends AppCompatActivity {
    
    private static final String PREFS_NAME = "davmusic_config";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_IS_CONFIGURED = "is_configured";
    
    private EditText serverUrlEditText;
    private EditText usernameEditText;
    private EditText passwordEditText;
    private Button testButton;
    private Button saveButton;
    
    private ProgressDialog progressDialog;
    private ExecutorService executorService;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server_config);

        // 注意：executorService 必须在任何提前 return 之前初始化，
        // 否则后续 testConnection() 使用它时会 NPE
        executorService = Executors.newSingleThreadExecutor();

        // 检查是否已配置，如果已配置则直接跳转到主界面
        if (isConfigured()) {
            startMainActivity();
            finish();
            return;
        }

        initViews();
        loadSavedConfig();
    }
    
    private void initViews() {
        serverUrlEditText = findViewById(R.id.serverUrlEditText);
        usernameEditText = findViewById(R.id.usernameEditText);
        passwordEditText = findViewById(R.id.passwordEditText);
        testButton = findViewById(R.id.testButton);
        saveButton = findViewById(R.id.saveButton);
        
        // 测试连接按钮点击事件
        testButton.setOnClickListener(v -> testConnection());
        
        // 保存按钮点击事件
        saveButton.setOnClickListener(v -> saveConfiguration());
    }
    
    private void loadSavedConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String serverUrl = prefs.getString(KEY_SERVER_URL, "");
        String username = prefs.getString(KEY_USERNAME, "");
        
        if (!TextUtils.isEmpty(serverUrl)) {
            serverUrlEditText.setText(serverUrl);
        }
        
        if (!TextUtils.isEmpty(username)) {
            usernameEditText.setText(username);
        }
    }
    
    private void testConnection() {
        // 验证输入
        if (!validateInputs()) {
            return;
        }
        
        String serverUrl = serverUrlEditText.getText().toString().trim();
        String username = usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        
        // 显示进度对话框
        showProgressDialog("正在测试连接...");
        
        // 配置 WebDAV 客户端
        WebDAVClient.getInstance().configure(serverUrl, username, password);
        
        // 在后台线程测试连接
        executorService.execute(() -> {
            WebDAVClient.getInstance().testConnection(new WebDAVClient.WebDAVCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    runOnUiThread(() -> {
                        dismissProgressDialog();
                        if (result) {
                            Toast.makeText(ServerConfigActivity.this, "连接成功", Toast.LENGTH_SHORT).show();
                            saveButton.setEnabled(true);
                        } else {
                            Toast.makeText(ServerConfigActivity.this, "连接失败，请检查配置", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                
                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> {
                        dismissProgressDialog();
                        Toast.makeText(ServerConfigActivity.this, "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }
    
    private void saveConfiguration() {
        // 验证输入
        if (!validateInputs()) {
            return;
        }
        
        String serverUrl = serverUrlEditText.getText().toString().trim();
        String username = usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        
        // 保存配置到 SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SERVER_URL, serverUrl);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_PASSWORD, password); // 注意：这里没有加密，实际应用中应该加密存储
        editor.putBoolean(KEY_IS_CONFIGURED, true);
        editor.apply();
        
        // 配置 WebDAV 客户端
        WebDAVClient.getInstance().configure(serverUrl, username, password);
        
        Toast.makeText(this, "配置保存成功", Toast.LENGTH_SHORT).show();
        
        // 跳转到主界面
        startMainActivity();
        finish();
    }
    
    private boolean validateInputs() {
        String serverUrl = serverUrlEditText.getText().toString().trim();
        String username = usernameEditText.getText().toString().trim();
        String password = passwordEditText.getText().toString().trim();
        
        if (TextUtils.isEmpty(serverUrl)) {
            serverUrlEditText.setError("请输入服务器地址");
            serverUrlEditText.requestFocus();
            return false;
        }
        
        // 检查 URL 格式
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            serverUrlEditText.setError("请输入完整的 URL (以 http:// 或 https:// 开头)");
            serverUrlEditText.requestFocus();
            return false;
        }
        
        if (TextUtils.isEmpty(username)) {
            usernameEditText.setError("请输入用户名");
            usernameEditText.requestFocus();
            return false;
        }
        
        if (TextUtils.isEmpty(password)) {
            passwordEditText.setError("请输入密码");
            passwordEditText.requestFocus();
            return false;
        }
        
        return true;
    }
    
    private void showProgressDialog(String message) {
        runOnUiThread(() -> {
            if (progressDialog == null) {
                progressDialog = new ProgressDialog(this);
                progressDialog.setCancelable(false);
            }
            progressDialog.setMessage(message);
            progressDialog.show();
        });
    }
    
    private void dismissProgressDialog() {
        runOnUiThread(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        });
    }
    
    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }
    
    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_IS_CONFIGURED, false);
    }
    
    public static String getServerUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_URL, "");
    }
    
    public static String getUsername(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, "");
    }
    
    public static String getPassword(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PASSWORD, "");
    }
    
    private boolean isConfigured() {
        return isConfigured(this);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
