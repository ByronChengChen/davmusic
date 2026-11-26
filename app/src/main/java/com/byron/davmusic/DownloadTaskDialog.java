package com.byron.davmusic;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;

final class DownloadTaskDialog {
    final AlertDialog dialog;
    final TextView message;
    final Button action;
    private final DirectoryDownloadTask task;
    DownloadTaskDialog(Context context, DirectoryDownloadTask task) {
        this.task=task;
        int pad=Math.round(24*context.getResources().getDisplayMetrics().density);
        LinearLayout body=new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad,pad,pad,pad);
        message=new TextView(context); body.addView(message);
        action=new Button(context);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=Math.round(8*context.getResources().getDisplayMetrics().density);
        body.addView(action,params);
        dialog=new AlertDialog.Builder(context).setTitle("下载歌曲").setView(body).create();
        dialog.setCanceledOnTouchOutside(false);
        // Back hides the dialog; menu can reopen it. It does not silently cancel the task.
        action.setOnClickListener(v->{ if (task.finished) dialog.dismiss(); else { task.cancelRemaining(); render(); } });
        render();
    }
    void show() { dialog.show(); }
    void render() {
        int done=task.succeeded+task.skipped+task.failed;
        String text=(task.finished ? "下载结束" : "正在下载")+" "+done+" / "+task.total
                +"\n成功 "+task.succeeded+" · 跳过 "+task.skipped+" · 失败 "+task.failed;
        if (task.current!=null) text+="\n"+task.current.getDisplayName()+" · "+task.percent+"%";
        if (task.cancelled) text+="\n"+(task.finished ? "未下载 "+task.remaining() : "已取消剩余，等待当前歌曲完成");
        if (task.lastError!=null) text+="\n最后失败："+task.lastError+"\n失败歌曲可手动重下，不会自动重试";
        message.setText(text); action.setText(task.finished ? "关闭" : "取消剩余");
        action.setEnabled(task.finished || !task.cancelled);
    }
}
