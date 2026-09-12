package com.byron.davmusic;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.ViewHolder> {
    
    public interface OnItemClickListener {
        void onItemClick(WebDAVFile file);
        void onItemLongClick(WebDAVFile file, View view);
        void onDownloadClick(WebDAVFile file);
        void onDeleteClick(WebDAVFile file);
    }
    
    private Context context;
    private List<WebDAVFile> files;
    private OnItemClickListener listener;
    private boolean isOfflineMode;
    
    public FileListAdapter(Context context) {
        this.context = context;
        this.files = new ArrayList<>();
    }
    
    public void setFiles(List<WebDAVFile> files) {
        this.files = files != null ? new ArrayList<>(files) : new ArrayList<>();
        notifyDataSetChanged();
    }
    
    public void setOfflineMode(boolean offlineMode) {
        this.isOfflineMode = offlineMode;
        notifyDataSetChanged();
    }
    
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }
    
    public void updateFile(WebDAVFile file) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getHref().equals(file.getHref())) {
                files.set(i, file);
                notifyItemChanged(i);
                break;
            }
        }
    }
    
    public void updateDownloadState(String href, WebDAVFile.DownloadState state) {
        for (int i = 0; i < files.size(); i++) {
            WebDAVFile file = files.get(i);
            if (file.getHref().equals(href)) {
                file.setDownloadState(state);
                notifyItemChanged(i);
                break;
            }
        }
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WebDAVFile file = files.get(position);
        
        if (file.isCollection()) {
            // 文件夹项
            holder.iconImageView.setImageResource(R.drawable.ic_folder);
            holder.downloadButton.setVisibility(View.GONE);
            holder.deleteButton.setVisibility(View.GONE);
            holder.progressBar.setVisibility(View.GONE);
            holder.fileNameTextView.setText(file.getDisplayName());
            holder.fileSizeTextView.setText("");
            holder.fileDurationTextView.setText("");
            holder.fileNameTextView.setTextColor(context.getResources().getColor(android.R.color.black));
            
            // 点击事件
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(file);
                }
            });
            
            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(file, v);
                }
                return true;
            });
            
        } else {
            // 文件项
            if (file.isAudio()) {
                holder.iconImageView.setImageResource(R.drawable.ic_music);
            } else if (file.isImage()) {
                holder.iconImageView.setImageResource(R.drawable.ic_image);
            } else if (file.isLyrics()) {
                holder.iconImageView.setImageResource(R.drawable.ic_document);
            } else {
                holder.iconImageView.setImageResource(R.drawable.ic_file);
            }
            
            holder.fileNameTextView.setText(file.getDisplayName());
            holder.fileSizeTextView.setText(FileUtils.formatFileSize(file.getContentLength()));
            holder.fileDurationTextView.setText(""); // 时长可以从元数据中获取，这里暂时留空
            
            // 根据下载状态设置颜色和按钮
            switch (file.getDownloadState()) {
                case NOT_DOWNLOADED:
                    holder.fileNameTextView.setTextColor(context.getResources().getColor(android.R.color.black));
                    holder.downloadButton.setVisibility(View.VISIBLE);
                    holder.deleteButton.setVisibility(View.GONE);
                    holder.progressBar.setVisibility(View.GONE);
                    break;
                    
                case DOWNLOADING:
                    holder.fileNameTextView.setTextColor(context.getResources().getColor(R.color.download_state_downloaded));
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.deleteButton.setVisibility(View.GONE);
                    holder.progressBar.setVisibility(View.VISIBLE);
                    break;
                    
                case DOWNLOADED:
                    holder.fileNameTextView.setTextColor(context.getResources().getColor(R.color.download_state_downloaded));
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.deleteButton.setVisibility(View.VISIBLE);
                    holder.progressBar.setVisibility(View.GONE);
                    break;
            }
            
            // 离线模式下，只显示已下载的文件
            if (isOfflineMode && file.getDownloadState() != WebDAVFile.DownloadState.DOWNLOADED) {
                holder.itemView.setVisibility(View.GONE);
                holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(0, 0));
            } else {
                holder.itemView.setVisibility(View.VISIBLE);
                holder.itemView.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            
            // 点击事件
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(file);
                }
            });
            
            holder.itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(file, v);
                }
                return true;
            });
            
            // 下载按钮点击
            holder.downloadButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDownloadClick(file);
                }
            });
            
            // 删除按钮点击
            holder.deleteButton.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(file);
                }
            });
        }
    }
    
    @Override
    public int getItemCount() {
        return files.size();
    }
    
    public List<WebDAVFile> getFiles() {
        return new ArrayList<>(files);
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView iconImageView;
        TextView fileNameTextView;
        TextView fileSizeTextView;
        TextView fileDurationTextView;
        View downloadButton;
        View deleteButton;
        ProgressBar progressBar;
        
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iconImageView = itemView.findViewById(R.id.iconImageView);
            fileNameTextView = itemView.findViewById(R.id.fileNameTextView);
            fileSizeTextView = itemView.findViewById(R.id.fileSizeTextView);
            fileDurationTextView = itemView.findViewById(R.id.fileDurationTextView);
            downloadButton = itemView.findViewById(R.id.downloadButton);
            deleteButton = itemView.findViewById(R.id.deleteButton);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
