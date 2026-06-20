package com.example.glidehelper;

import android.content.Context;
import android.widget.ImageView;

import androidx.annotation.NonNull;

/**
 * ====================================================
 * ImageLoader
 * ----------------------------------------------------
 * 对外唯一入口
 * 普通图 / AES 图统一加载
 * 使用范例：
 * ImageLoader.with(context)
 *         .load(url, imageView, option);
 * ImageLoader.with(context)
 *         .loadAesBitmap(url, imageView, option, aesOption);
 * ====================================================
 */
public class ImageLoader {

    /** 单例实例 */
    private static volatile ImageLoader instance;

    /** 应用上下文 */
    private final Context context;

    /**
     * 私有构造
     */
    private ImageLoader(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 获取 ImageLoader 实例
     */
    public static ImageLoader with(@NonNull Context context) {
        if (instance == null) {
            synchronized (ImageLoader.class) {
                if (instance == null) {
                    instance = new ImageLoader(context);
                }
            }
        }
        return instance;
    }

    /**
     * 加载普通图（支持 Cookie Header）
     * [FIX] 移除 AtomicBoolean，避免全局阻塞图片加载
     */
    public void load(
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option
    ) {
        LoadExecutor.executeNormal(context, url, imageView, option);
    }

    /**
     * AES 图（不写文件，直接 Bitmap）
     * [FIX] 移除 AtomicBoolean，避免并发加载被错误拦截
     */
    public void loadAesBitmap(
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option,
            @NonNull AesOption aesOption
    ) {
        LoadExecutor.executeAesBitmap(context, url, imageView, option, aesOption);
    }
}