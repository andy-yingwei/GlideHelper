package com.example.glidehelper;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.Gravity;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ====================================================
 * LoadOption
 * ----------------------------------------------------
 * ✅ 基础加载配置（普通图 / AES 图通用）
 * ✅ 包含 Cookie / 缓存 / 图片质量 / UI 变换
 * ✅ 支持「无 GPU 风险」的水印（View 叠加方案）
 * ====================================================
 */
public class LoadOption {

    // ========== 占位图 ==========
    private int placeholderResId;
    private int errorResId;

    // ========== 变换 ==========
    private int cornerRadius;
    private boolean isCircleCrop;

    // ========== 列表优化 ==========
    private boolean isListMode;

    // ========== Cookie Header ==========
    private final Map<String, String> headers = new LinkedHashMap<>();

    // ========== 缓存配置 ==========
    private DiskCacheStrategy diskCacheStrategy = DiskCacheStrategy.AUTOMATIC;
    private boolean skipMemoryCache;

    // ========== 图片质量 ==========
    private DecodeFormat decodeFormat = DecodeFormat.DEFAULT;

    // ========== 缩略图比例 ==========
    private float thumbnail = 0f;

    // ========== ✅ 水印配置（View 叠加方案） ==========
    private String watermarkText;
    private int watermarkGravity = Gravity.BOTTOM | Gravity.END;
    private int watermarkAlpha = 180;
    private int watermarkColor = 0xFFFFFFFF;
    private int watermarkColorRes;
    private float watermarkTextSize = 14; // ✅ 默认文字大小（sp）

    // ========== 基础配置方法 ==========

    public LoadOption placeholder(@DrawableRes int resId) {
        this.placeholderResId = resId;
        return this;
    }

    public LoadOption error(@DrawableRes int resId) {
        this.errorResId = resId;
        return this;
    }

    public LoadOption round(int radius) {
        this.cornerRadius = radius;
        this.isCircleCrop = false;
        return this;
    }

    public LoadOption asCircle() {
        this.isCircleCrop = true;
        this.cornerRadius = 0;
        return this;
    }

    public LoadOption forList() {
        this.isListMode = true;
        return this;
    }

    // ========== Cookie Header 方法 ==========

    public LoadOption addCookie(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public LoadOption addCookies(Map<String, String> cookies) {
        if (cookies != null) headers.putAll(cookies);
        return this;
    }

    // ========== 缓存配置方法 ==========

    public LoadOption diskCacheStrategy(DiskCacheStrategy strategy) {
        this.diskCacheStrategy = strategy;
        return this;
    }

    public LoadOption skipMemoryCache(boolean skip) {
        this.skipMemoryCache = skip;
        return this;
    }

    // ========== 图片质量方法 ==========

    public LoadOption decodeFormat(DecodeFormat format) {
        this.decodeFormat = format;
        return this;
    }

    // ========== 缩略图方法 ==========

    public LoadOption thumbnail(float size) {
        if (size > 0f && size <= 1f) {
            this.thumbnail = size;
        }
        return this;
    }

    // ========== ✅ 水印配置（无 GPU 风险） ==========

    /**
     * ✅ 设置水印（支持直接颜色值）
     */
    public LoadOption watermark(
            String text,
            int gravity,
            int alpha,
            @ColorInt int color,
            float textSizeSp
    ) {
        this.watermarkText = text;
        this.watermarkGravity = gravity;
        this.watermarkAlpha = alpha;
        this.watermarkColor = color;
        this.watermarkColorRes = 0;
        this.watermarkTextSize = textSizeSp;
        return this;
    }

    /**
     * ✅ 设置水印（支持颜色资源 ID）
     */
    public LoadOption watermarkRes(
            String text,
            int gravity,
            int alpha,
            @ColorRes int colorRes,
            float textSizeSp
    ) {
        this.watermarkText = text;
        this.watermarkGravity = gravity;
        this.watermarkAlpha = alpha;
        this.watermarkColorRes = colorRes;
        this.watermarkColor = 0;
        this.watermarkTextSize = textSizeSp;
        return this;
    }

    // ========== 内部方法（LoadExecutor 用） ==========

    @SuppressLint("CheckResult")
    RequestOptions toRequestOptions() {
        RequestOptions options = new RequestOptions();

        if (placeholderResId != 0) options.placeholder(placeholderResId);
        if (errorResId != 0) options.error(errorResId);

        if (cornerRadius > 0) {
            options.transform(new RoundedCorners(dp2px(cornerRadius)));
        } else if (isCircleCrop) {
            options.transform(new CircleCrop());
        }

        if (isListMode) {
            options.format(DecodeFormat.PREFER_RGB_565)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true);
        }

        options.diskCacheStrategy(diskCacheStrategy)
                .skipMemoryCache(skipMemoryCache)
                .format(decodeFormat);

        return options;
    }

    Map<String, String> getHeaders() {
        return headers;
    }

    float getThumbnail() {
        return thumbnail;
    }

    boolean isListMode() {
        return isListMode;
    }

    // ========== ✅ 水印 Getter（必须） ==========

    public String getWatermarkText() {
        return watermarkText;
    }

    public int getWatermarkGravity() {
        return watermarkGravity;
    }

    public int getWatermarkAlpha() {
        return watermarkAlpha;
    }

    public int getWatermarkColor() {
        return watermarkColor;
    }

    public int getWatermarkColorRes() {
        return watermarkColorRes;
    }

    public float getWatermarkTextSize() {
        return watermarkTextSize;
    }

    // ========== ✅ 原有 Getter（必须） ==========

    public int getPlaceholderResId() {
        return placeholderResId;
    }

    public int getErrorResId() {
        return errorResId;
    }

    // ========== 私有工具 ==========

    private int dp2px(int dp) {
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        return (int) (dp * metrics.density + 0.5f);
    }
}