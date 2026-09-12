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
import java.util.Collections;
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
    
    /**
     * 设置文件列表。
     *
     * 只保留「文件夹」和「音频文件」，其余（图片、歌词、文档、系统文件等）一律不展示。
     * 过滤在此处集中完成，保证后续的点击、播放列表、下载等逻辑看到的
     * 都是可见项，避免下标错位。
     */
    public void setFiles(List<WebDAVFile> files) {
        List<WebDAVFile> visible = new ArrayList<>();
        if (files != null) {
            for (WebDAVFile f : files) {
                if (f == null) continue;
                // 展示文件夹，以及音频文件；其余全部丢弃
                if (f.isCollection() || f.isAudio()) {
                    visible.add(f);
                }
            }
        }
        this.files = sortFiles(visible);
        notifyDataSetChanged();
    }

    /**
     * 排序：文件夹在前，音频文件在后；同类按名称排序（区分大小写不敏感）。
     */
    private List<WebDAVFile> sortFiles(List<WebDAVFile> list) {
        List<WebDAVFile> result = new ArrayList<>(list);
        Collections.sort(result, (a, b) -> {
            // 1) 文件夹优先
            if (a.isCollection() != b.isCollection()) {
                return a.isCollection() ? -1 : 1;
            }
            // 2) 同类型按名称
            String na = a.getDisplayName() != null ? a.getDisplayName() : "";
            String nb = b.getDisplayName() != null ? b.getDisplayName() : "";
            return na.compareToIgnoreCase(nb);
        });
        return result;
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
