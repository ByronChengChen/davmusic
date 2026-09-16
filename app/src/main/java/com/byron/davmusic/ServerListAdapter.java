package com.byron.davmusic;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「WebDAV 服务器管理」列表适配器。
 *
 * 交互约定（与产品需求对齐）：
 *   · 点击整项 → 把该项切换为当前使用的服务器
 *   · 长按整项 → 编辑（改地址/账号，避免删掉重建）
 *   · 点右侧图标 → 删除该服务器
 */
public class ServerListAdapter extends RecyclerView.Adapter<ServerListAdapter.ViewHolder> {

    public interface Listener {
        void onSwitch(ServerProfile profile);

        void onEdit(ServerProfile profile);

        void onDelete(ServerProfile profile);
    }

    private final List<ServerProfile> data = new ArrayList<>();
    private final Listener listener;

    public ServerListAdapter(Listener listener) {
        this.listener = listener;
    }

    /** 整体替换数据。服务器已无「当前使用」概念，故不再需要 activeServerId */
    public void setData(List<ServerProfile> servers) {
        data.clear();
        if (servers != null) {
            data.addAll(servers);
        }
        notifyDataSetChanged();
    }

    public int getServerCount() {
        return data.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_server, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        ServerProfile p = data.get(position);

        h.nameText.setText(p.getDisplayName());
        h.urlText.setText(p.getUrl());

        String account = p.getUsername();
        if (TextUtils.isEmpty(account)) {
            h.accountText.setText("未设置账号");
        } else {
            h.accountText.setText("账号：" + account);
        }

        // 服务器已无「当前使用」概念：点击=进入浏览，因此不显示标记
        h.activeBadge.setVisibility(View.GONE);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) {
                // 用 getAdapterPosition 而不是闭包捕获的 position：
                // 列表可能刚被 notifyDataSetChanged 换过，闭包里的下标会失效。
                int pos = h.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onSwitch(data.get(pos));
                }
            }
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onEdit(data.get(pos));
                return true;
            }
            return false;
        });
        h.deleteButton.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) {
                listener.onDelete(data.get(pos));
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView nameText;
        final TextView urlText;
        final TextView accountText;
        final TextView activeBadge;
        final ImageButton deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            nameText = itemView.findViewById(R.id.serverNameText);
            urlText = itemView.findViewById(R.id.serverUrlText);
            accountText = itemView.findViewById(R.id.serverAccountText);
            activeBadge = itemView.findViewById(R.id.activeBadge);
            deleteButton = itemView.findViewById(R.id.deleteServerButton);
        }
    }
}