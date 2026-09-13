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

    /** 正在播放的曲目 href，用于高亮对应列表项；null 表示没有在播放 */
    private String playingHref;
    
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

        // 诊断日志：列表内容变化时完整记录一次。
        // "条目时有时无"这类问题必须看到"每次渲染了哪些项"才能定位，
        // 只看总数无法判断是数据源变了还是渲染被覆盖。
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("setFiles: 输入=").append(files == null ? 0 : files.size())
              .append(" 可见=").append(this.files.size())
              .append(" → ");
            for (WebDAVFile f : this.files) {
                sb.append(f.isCollection() ? "[D]" : "[F]")
                  .append(f.getDisplayName()).append(" ");
            }
            RemoteLogger.getInstance(context).i("FileListAdapter", sb.toString());
        } catch (Exception ignored) {
        }

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

    /**
     * 设置/清除正在播放的曲目，触列表高亮刷新。
     *
     * @param href 正在播放曲目的 href；传 null 表示停止高亮
     */
    public void setPlayingHref(String href) {
        if (href == null ? playingHref == null : href.equals(playingHref)) {
            return;   // 无变化，避免无谓刷新
        }
        this.playingHref = href;
        notifyDataSetChanged();
    }

    /** 当前高亮的曲目 href */
    public String getPlayingHref() {
        return playingHref;
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

        // 是否正在播放本条目
        boolean isPlaying = playingHref != null
                && file.getHref() != null
                && playingHref.equals(file.getHref());
        // 用 activated 状态驱动背景选择器（保留水波纹点击反馈）
        holder.itemView.setActivated(isPlaying);

        if (file.isCollection()) {
            // 文件夹项
            // 先重置可能从复用中带来的残留状态（例如上一个条目是文件、
            // 被设过 GONE 或特殊尺寸），保证每次绑定从干净状态开始
            holder.itemView.setVisibility(View.VISIBLE);
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
            
            // 正在播放的条目：文字用高亮色，优先于下载状态色
            int nameColor;
            if (isPlaying) {
                nameColor = context.getResources().getColor(R.color.item_playing_text);
            } else {
                switch (file.getDownloadState()) {
                    case DOWNLOADED:
                    case DOWNLOADING:
                        nameColor = context.getResources().getColor(R.color.download_state_downloaded);
                        break;
                    default:
                        nameColor = context.getResources().getColor(android.R.color.black);
                        break;
                }
            }
            holder.fileNameTextView.setTextColor(nameColor);

            // 根据下载状态控制按钮/进度条显示
            switch (file.getDownloadState()) {
                case NOT_DOWNLOADED:
                    holder.downloadButton.setVisibility(View.VISIBLE);
                    holder.deleteButton.setVisibility(View.GONE);
                    holder.progressBar.setVisibility(View.GONE);
                    break;
                    
                case DOWNLOADING:
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.deleteButton.setVisibility(View.GONE);
                    holder.progressBar.setVisibility(View.VISIBLE);
                    break;
                    
                case DOWNLOADED:
                    holder.downloadButton.setVisibility(View.GONE);
                    holder.deleteButton.setVisibility(View.VISIBLE);
                    holder.progressBar.setVisibility(View.GONE);
                    break;
            }

            // 注意：这里【不再】用 View.GONE + 0×0 LayoutParams 隐藏未下载项。
            //
            // 那套做法是早期"离线只显示已下载"设计的遗留，且是严重错误：
            // RecyclerView 的 ViewHolder 会被复用，一旦某个 holder 被设成
            // GONE 与 0×0 布局参数，它被复用去渲染别的条目时会带着这些
            // 残留状态，导致条目时有时无、位置错乱 —— 这正是"滑动时
            // JAY 有时在第 1 位、有时第 2 位、有时消失"的原因，
            // 且因为是通用逻辑，其他目录同样错乱。
            //
            // 正确的做法：需要隐藏的项应在 setFiles() 阶段就从数据集里
            // 过滤掉，让适配器只持有真正要显示的条目。当前需求是
            // "离线也显示完整目录"，所以这里无需任何过滤。

            // 重置可能被复用残留的状态，保证每次绑定都是干净起点
            holder.itemView.setVisibility(View.VISIBLE);
            
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
