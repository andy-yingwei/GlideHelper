package com.example.glidehelper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ====================================================
 * LoadExecutor
 * ----------------------------------------------------
 * ✅ Glide 实际执行者
 * ✅ 负责普通加载 / AES 解密加载
 * ✅ 封装线程切换与错误处理逻辑
 * ====================================================
 */
public class LoadExecutor {

    private static final String TAG = "LoadExecutor";
    private static final long CACHE_MAX_AGE = 7 * 24 * 60 * 60 * 1000L; // 7天

    /**
     * [IMPROVE] 网络超时常量
     */
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;

    /**
     * [IMPROVE] 单线程池，避免 AES 并发写同一文件
     */
    private static final ExecutorService AES_EXECUTOR =
            Executors.newSingleThreadExecutor();

    /**
     * [IMPROVE] 主线程 Handler 单例，避免频繁创建对象
     */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * 普通图（支持 Cookie / Header）
     * [FIX] 移除 AtomicBoolean，避免全局阻塞图片加载
     * [FIX] 使用 GlideUrl 携带请求头，解决原实现 Header 失效问题
     */
    static void executeNormal(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option
    ) {
        RequestOptions options = option.toRequestOptions();

        // [FIX] 构建带 Header 的 GlideUrl，统一请求头逻辑
        GlideUrl glideUrl = buildGlideUrl(url, option.getFinalHeaders());

        // [IMPROVE] 支持 thumbnail
        if (option.getThumbnail() > 0f) {
            Glide.with(context)
                    .load(glideUrl)
                    .thumbnail(option.getThumbnail())
                    .apply(options)
                    .into(imageView);
        } else {
            Glide.with(context)
                    .load(glideUrl)
                    .apply(options)
                    .into(imageView);
        }
    }

    /**
     * [FIX] 构建带 Header（含 Cookie）的 GlideUrl
     * 统一普通加载与 AES 加载的请求头处理逻辑
     */
    private static GlideUrl buildGlideUrl(String url, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return new GlideUrl(url);
        }
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return new GlideUrl(url, builder.build());
    }

    /**
     * 鸿蒙最终方案：解密 → 写文件 → Glide 加载文件
     */
    @SuppressLint("CheckResult")
    static void executeAesBitmap(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option,
            @NonNull AesOption aesOption
    ) {
        try {
            // [FIX] 捕获线程池拒绝异常，防止崩溃并提供降级处理
            AES_EXECUTOR.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    // 1. 下载（使用统一拼接后的 Header，含标准 Cookie）
                    conn = openConnection(url, option.getFinalHeaders());
                    byte[] encrypted = downloadEncrypted(conn);

                    // 2. 解密
                    byte[] decrypted = decrypt(encrypted, aesOption);

                    // 3. 写文件（鸿蒙允许）
                    File cacheFile = writeToCache(context, url, decrypted);

                    MAIN_HANDLER.post(() -> {
                        // [FIX] 防止 Activity 销毁后继续操作 View 导致泄漏或崩溃
                        if (!imageView.isAttachedToWindow()) {
                            return;
                        }

                        RequestOptions options = option.toRequestOptions();

                        // 强制 NONE 缓存（鸿蒙关键）
                        options.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                .skipMemoryCache(true);

                        Glide.with(context)
                                .load(cacheFile)
                                .apply(options)
                                .listener(new RequestListener<Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(
                                            @Nullable GlideException e,
                                            Object model,
                                            @NonNull Target<Drawable> target,
                                            boolean isFirstResource
                                    ) {
                                        Log.e(TAG, "解密文件加载失败: " + cacheFile.getName(), e);
                                        if (option.getErrorResId() != 0) {
                                            imageView.setImageResource(option.getErrorResId());
                                        }
                                        return true;
                                    }

                                    @Override
                                    public boolean onResourceReady(
                                            @NonNull Drawable resource,
                                            @NonNull Object model,
                                            Target<Drawable> target,
                                            @NonNull DataSource dataSource,
                                            boolean isFirstResource
                                    ) {
                                        return false;
                                    }
                                })
                                .into(imageView);
                    });

                } catch (Exception e) {
                    Log.e(TAG, "AES进程失败: " + url, e);
                    MAIN_HANDLER.post(() -> {
                        if (option.getErrorResId() != 0) {
                            imageView.setImageResource(option.getErrorResId());
                        }
                    });
                } finally {
                    // [FIX] 确保 HttpURLConnection 被关闭，释放资源
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // [FIX] 线程池饱和或已关闭时的降级处理
            Log.e(TAG, "aes执行器拒绝了url的任务: " + url, e);
            if (option.getErrorResId() != 0) {
                imageView.setImageResource(option.getErrorResId());
            }
        }
    }

    /**
     * [IMPROVE] 封装连接创建逻辑，便于统一管理超时和请求头
     */
    private static HttpURLConnection openConnection(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setDoInput(true);
        // [IMPROVE] 禁止系统缓存，确保获取最新数据
        conn.setUseCaches(false);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        return conn;
    }

    /**
     * [IMPROVE] 增加 HTTP Response Code 校验
     * 注意：此方法不关闭 HttpURLConnection，连接由调用者在 finally 块中关闭。
     */
    private static byte[] downloadEncrypted(HttpURLConnection conn) throws Exception {
        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            // [FIX] 完全消费错误流，防止连接泄漏
            try (InputStream errorStream = conn.getErrorStream()) {
                if (errorStream != null) {
                    byte[] buffer = new byte[1024];
                    while (errorStream.read(buffer) != -1) {
                        // 丢弃数据，确保连接被完全释放
                    }
                }
            }
            throw new IOException("HTTP 错误代码: " + responseCode + " for URL: " + conn.getURL().toString());
        }

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toByteArray();
        }
    }

    private static byte[] decrypt(byte[] data, AesOption aesOption) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(aesOption.getKey(), "AES"),
                new IvParameterSpec(aesOption.getIv())
        );
        return cipher.doFinal(data);
    }

    /**
     * 写文件（鸿蒙兼容方案）
     * [IMPROVE] 使用 UUID 避免 hash 冲突
     * [FIX] 写入失败时清理不完整文件
     */
    private static File writeToCache(Context context, String url, byte[] data) throws Exception {
        File cacheDir = context.getExternalCacheDir(); // 鸿蒙推荐
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        File cacheFile = new File(
                cacheDir,
                "aes_" + Math.abs(url.hashCode()) + "_" + UUID.randomUUID() + ".tmp"
        );
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(data);
        } catch (Exception e) {
            // [FIX] 写入失败时删除不完整文件
            if (cacheFile.exists()) {
                boolean deleted = cacheFile.delete();
                if (!deleted) {
                    Log.w(TAG, "未能删除不完整的缓存文件: " + cacheFile.getName());
                }
            }
            throw e; // 重新抛出异常，让上层处理
        }
        return cacheFile;
    }

    /**
     * ====================================================
     * [IMPROVE] 清理过期的 AES 缓存文件
     * ----------------------------------------------------
     * ✅ 建议在应用启动或退出时调用
     * ✅ 删除超过 7 天的临时文件，避免磁盘占用过大
     * ====================================================
     */
    public static void clearExpiredAesCache(@NonNull Context context) {
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        if (cacheDir == null || !cacheDir.exists()) {
            return;
        }

        File[] files = cacheDir.listFiles(
                (dir, name) -> name.startsWith("aes_") && name.endsWith(".tmp")
        );

        if (files == null || files.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();
        for (File file : files) {
            try {
                if (now - file.lastModified() > CACHE_MAX_AGE) {
                    boolean deleted = file.delete();
                    if (!deleted) {
                        Log.w(TAG, "未能删除过期的缓存: " + file.getName());
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "没有权限删除: " + file.getName(), e);
            }
        }
    }
}