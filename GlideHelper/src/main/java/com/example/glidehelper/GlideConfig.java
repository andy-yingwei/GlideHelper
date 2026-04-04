package com.example.glidehelper;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

/**
 * ====================================================
 * GlideConfig
 * ----------------------------------------------------
 * ✅ 提供默认 RequestOptions
 * ✅ 不负责加载逻辑
 * ====================================================
 */
public final class GlideConfig {

    private GlideConfig() {}

    /**
     * 基础默认配置（普通图片）
     */
    @NonNull
    public static RequestOptions base(@NonNull Context context) {
        return new RequestOptions()
                .placeholder(GlideConstants.PLACEHOLDER)
                .error(GlideConstants.ERROR)
                .fallback(GlideConstants.FALLBACK)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .format(DecodeFormat.PREFER_ARGB_8888);
    }

    /**
     * 列表专用配置（RecyclerView）
     */
    @NonNull
    public static RequestOptions list(@NonNull Context context) {
        return new RequestOptions()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .format(DecodeFormat.PREFER_RGB_565);
    }
}