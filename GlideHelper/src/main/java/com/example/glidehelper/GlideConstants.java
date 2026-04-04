package com.example.glidehelper;

/**
 * ====================================================
 * GlideConstants
 * ----------------------------------------------------
 * ✅ 所有 Glide 相关常量
 * ✅ 不依赖 Context
 * ====================================================
 */
public final class GlideConstants {

    private GlideConstants() {}

    public static final int PLACEHOLDER =
            R.drawable.bg_image_placeholder;

    public static final int ERROR =
            R.drawable.bg_image_error;

    public static final int FALLBACK =
            R.drawable.bg_image_fallback;

    /** 列表缩略图比例 */
    public static final float LIST_THUMBNAIL = 0.3f;

    /** 普通图片最大尺寸 */
    public static final int MAX_IMAGE_SIZE = 1080;

    /** GIF 最大尺寸 */
    public static final int MAX_GIF_SIZE = 720;
}