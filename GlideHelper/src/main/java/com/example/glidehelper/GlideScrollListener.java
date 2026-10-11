package com.example.glidehelper;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

/**
 * ====================================================
 * GlideScrollListener
 * ----------------------------------------------------
 * RecyclerView 滚动时暂停 / 恢复 Glide
 * 使用范例：

 * recyclerView.addOnScrollListener(
 *         new GlideScrollListener()
 * );
 * ====================================================
 */
public class GlideScrollListener extends RecyclerView.OnScrollListener {

    @Override
    public void onScrollStateChanged(RecyclerView rv, int newState) {
        switch (newState) {
            case RecyclerView.SCROLL_STATE_IDLE:
                // [FIX] 滚动停止后恢复加载
                Glide.with(rv).resumeRequests();
                break;
            case RecyclerView.SCROLL_STATE_DRAGGING:
                // [FIX] 手指拖拽时暂停，避免快速滑动浪费带宽
                Glide.with(rv).pauseRequests();
                break;
            case RecyclerView.SCROLL_STATE_SETTLING:
                // [FIX] 惯性滑动不暂停，否则快速滚动到底部时
                // 最后几个可见 item 的图片永远不会加载
                break;
        }
    }
}
