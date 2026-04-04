package com.example.glidehelper;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

/**
 * ====================================================
 * GlideScrollListener
 * ----------------------------------------------------
 * ✅ RecyclerView 滚动时暂停 / 恢复 Glide
 *
 * ✅ 使用范例：
 *
 * recyclerView.addOnScrollListener(
 *         new GlideScrollListener()
 * );
 *
 * ====================================================
 */
public class GlideScrollListener extends RecyclerView.OnScrollListener {

    @Override
    public void onScrollStateChanged(RecyclerView rv, int newState) {
        switch (newState) {
            case RecyclerView.SCROLL_STATE_IDLE:
                Glide.with(rv.getContext()).resumeRequests();
                break;
            case RecyclerView.SCROLL_STATE_DRAGGING:
            case RecyclerView.SCROLL_STATE_SETTLING:
                Glide.with(rv.getContext()).pauseRequests();
                break;
        }
    }
}
