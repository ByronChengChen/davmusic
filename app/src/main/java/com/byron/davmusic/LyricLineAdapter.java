package com.byron.davmusic;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** 歌词行列表：只负责「哪一行高亮」，滚动由 Activity 控制 */
public class LyricLineAdapter extends RecyclerView.Adapter<LyricLineAdapter.VH> {

    private static final int COLOR_ACTIVE = Color.parseColor("#FFFFFFFF");
    private static final int COLOR_NORMAL = Color.parseColor("#80FFFFFF");

    private List<LrcParser.Line> lines = new ArrayList<>();
    private int currentIndex = -1;

    public void setLines(List<LrcParser.Line> lines) {
        this.lines = lines == null ? new ArrayList<>() : lines;
        this.currentIndex = -1;
        notifyDataSetChanged();
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    /** @return 高亮行是否发生变化（调用方据此决定要不要滚动） */
    public boolean setCurrentIndex(int index) {
        if (index == currentIndex) return false;
        int old = currentIndex;
        currentIndex = index;
        if (old >= 0 && old < lines.size()) notifyItemChanged(old);
        if (currentIndex >= 0 && currentIndex < lines.size()) notifyItemChanged(currentIndex);
        return true;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lyric_line, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        LrcParser.Line line = lines.get(position);
        holder.text.setText(line.text.isEmpty() ? "♪" : line.text);
        boolean active = position == currentIndex;
        holder.text.setTextColor(active ? COLOR_ACTIVE : COLOR_NORMAL);
        holder.text.setTextSize(active ? 19f : 16f);
        holder.text.setAlpha(active ? 1f : 0.75f);
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.lyricText);
        }
    }
}
