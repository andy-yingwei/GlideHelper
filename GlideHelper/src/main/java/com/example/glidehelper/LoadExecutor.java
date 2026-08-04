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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ====================================================
 * LoadExecutor（同步 AesImageLoader 最终版）
 * ----------------------------------------------------
 *
 * 按照 AesImageLoader 的成功模式重构：
 *   ✅ 移除 isAttachedToWindow() 检查（RecyclerView 中过于严格）
 *   ✅ OkHttp 异步 enqueue()，自定义线程池只负责解密
 *   ✅ 缓存命中也提交到 AES_EXECUTOR，可被 cancel 中断
 *   ✅ cancel 时同时取消 Call + Future
 *   ✅ Glide.with(context) 加载 byte[]，不受 pauseRequests 影响
 *
 * 对外 API 完全不变，Adapter 无需修改
 * ====================================================
 */
public class LoadExecutor {

    private static final String TAG = "LoadExecutor";

    private static final long CACHE_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final String AES_CACHE_PREFIX = "aes_";
    private static final String AES_CACHE_SUFFIX = ".cache";

    private static final int TAG_AES_URL = R.id.tag_aes_url;
    private static final int TAG_AES_CALL = R.id.tag_aes_call;
    private static final int TAG_AES_FUTURE = R.id.tag_aes_future;

    // ===================== OkHttpClient =====================

    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    // ===================== 线程池（只解密，不网络 IO）====================

    private static final ExecutorService AES_EXECUTOR;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int poolSize = (cores <= 4) ? 4 : (cores <= 6) ? cores : 6;
        AES_EXECUTOR = Executors.newFixedThreadPool(poolSize);
        Log.d(TAG, "AES Executor initialized, CPU cores=" + cores + ", pool=" + poolSize);
    }

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // ====================================================
    // 公开方法（API 不变）
    // ====================================================

    static void executeNormal(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option
    ) {
        RequestOptions options = option.toRequestOptions();
        GlideUrl glideUrl = buildGlideUrl(url, option.getFinalHeaders());

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
     * AES 图加载（AesImageLoader 模式）
     */
    @SuppressLint("CheckResult")
    static void executeAesBitmap(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option,
            @NonNull AesOption aesOption
    ) {
        // 1. 绑定 URL Tag
        imageView.setTag(TAG_AES_URL, url);
        Log.d(TAG, "开始加载: " + url);

        // 2. 取消旧任务（Call + Future）
        cancelTask(imageView);

        // 3. 预计算缓存路径
        final File cacheDir = getCacheDir(context);
        final String cacheFileName = generateCacheFileName(url, aesOption);
        final File cacheFile = new File(cacheDir, cacheFileName);

        // 4. 缓存命中：提交到线程池读取，避免主线程 IO，同时可被 cancel
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Log.d(TAG, "缓存命中: " + cacheFileName);
            Future<?> future = AES_EXECUTOR.submit(() -> {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    byte[] data = readFileToBytes(cacheFile);
                    postLoad(context, imageView, url, data, option);
                } catch (Exception e) {
                    Log.e(TAG, "缓存读取失败: " + url, e);
                    cacheFile.delete();
                    postError(imageView, url, option);
                } finally {
                    clearFutureTag(imageView, url);
                }
            });
            imageView.setTag(TAG_AES_FUTURE, future);
            return;
        }

        // 5. 构建 OkHttp 异步请求
        Request.Builder requestBuilder = new Request.Builder().url(url);
        Map<String, String> headers = option.getFinalHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        final Request request = requestBuilder.build();
        final Call call = OK_HTTP_CLIENT.newCall(request);
        imageView.setTag(TAG_AES_CALL, call);

        // 6. 提交异步请求（网络 IO 由 OkHttp 处理，不阻塞 AES_EXECUTOR）
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled()) {
                    Log.d(TAG, "Call 被取消: " + url);
                    return;
                }
                Log.e(TAG, "网络请求失败: " + url, e);
                postError(imageView, url, option);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                if (call.isCanceled()) {
                    response.close();
                    Log.d(TAG, "Call 已取消，丢弃响应: " + url);
                    return;
                }

                if (!response.isSuccessful()) {
                    response.close();
                    Log.e(TAG, "HTTP 错误: " + response.code() + " for " + url);
                    postError(imageView, url, option);
                    return;
                }

                // 7. 提交解密任务到 AES_EXECUTOR（纯 CPU）
                Future<?> future = AES_EXECUTOR.submit(() -> {
                    try {
                        ResponseBody body = response.body();
                        if (body == null) {
                            response.close();
                            postError(imageView, url, option);
                            return;
                        }

                        byte[] encrypted = body.bytes();
                        response.close();
                        Log.d(TAG, "下载完成: " + url + ", 大小=" + encrypted.length);

                        if (call.isCanceled() || Thread.currentThread().isInterrupted()) {
                            Log.d(TAG, "下载完成后被取消，跳过解密: " + url);
                            return;
                        }

                        // 解密
                        byte[] decrypted = decrypt(encrypted, aesOption);
                        Log.d(TAG, "解密完成: " + url + ", 大小=" + decrypted.length);

                        // 写入缓存
                        writeCacheFile(cacheFile, decrypted);

                        // 主线程加载
                        postLoad(context, imageView, url, decrypted, option);

                    } catch (Exception e) {
                        Log.e(TAG, "解密/缓存失败: " + url, e);
                        postError(imageView, url, option);
                    } finally {
                        clearFutureTag(imageView, url);
                    }
                });

                imageView.setTag(TAG_AES_FUTURE, future);
            }
        });
    }

    // ====================================================
    // 公开工具方法
    // ====================================================

    /**
     * RecyclerView 回收时调用
     */
    public static void cancelAndClear(@NonNull ImageView imageView) {
        cancelTask(imageView);
        imageView.setTag(TAG_AES_URL, null);
        try {
            Glide.with(imageView).clear(imageView);
        } catch (Exception ignored) {
        }
    }

    /**
     * 清理过期的 AES 缓存文件
     */
    public static void clearExpiredAesCache(@NonNull Context context) {
        File cacheDir = getCacheDir(context);
        if (cacheDir == null || !cacheDir.exists()) return;

        File[] files = cacheDir.listFiles((dir, name) ->
                name.startsWith(AES_CACHE_PREFIX) && name.endsWith(AES_CACHE_SUFFIX));

        if (files == null) return;

        long now = System.currentTimeMillis();
        for (File file : files) {
            try {
                if (now - file.lastModified() > CACHE_MAX_AGE) {
                    if (!file.delete()) {
                        Log.w(TAG, "删除过期缓存失败: " + file.getName());
                    }
                }
            } catch (SecurityException e) {
                Log.w(TAG, "无权限删除: " + file.getName(), e);
            }
        }
    }

    // ====================================================
    // 私有方法
    // ====================================================

    /**
     * 取消 ImageView 上正在执行的任务（Call + Future）
     */
    private static void cancelTask(@NonNull ImageView imageView) {
        Object callTag = imageView.getTag(TAG_AES_CALL);
        if (callTag instanceof Call) {
            ((Call) callTag).cancel();
        }

        Object futureTag = imageView.getTag(TAG_AES_FUTURE);
        if (futureTag instanceof Future) {
            ((Future<?>) futureTag).cancel(true);
        }

        imageView.setTag(TAG_AES_CALL, null);
        imageView.setTag(TAG_AES_FUTURE, null);
    }

    /**
     * 主线程加载解密后的图片（移除 isAttachedToWindow 检查）
     */
    private static void postLoad(Context context, ImageView imageView, String url,
                                 byte[] data, LoadOption option) {
        MAIN_HANDLER.post(() -> {
            // 只保留 Tag 校验，足够防止错乱
            Object currentTag = imageView.getTag(TAG_AES_URL);
            if (!url.equals(currentTag)) {
                Log.d(TAG, "View 已复用，丢弃: " + url);
                return;
            }

            // [关键修复] 不再检查 isAttachedToWindow()
            // RecyclerView 滚动/动画期间此检查过于严格，会误杀正常加载

            // 清除 Glide 旧请求
            try {
                Glide.with(imageView).clear(imageView);
            } catch (Exception ignored) {
            }

            RequestOptions glideOptions = option.toRequestOptions();
            glideOptions.diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(false);

            Log.d(TAG, "提交 Glide 加载: " + url + ", bytes=" + data.length);

            Glide.with(context)
                    .load(data)
                    .apply(glideOptions)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(
                                @Nullable GlideException e,
                                Object model,
                                @NonNull Target<Drawable> target,
                                boolean isFirstResource) {
                            Log.e(TAG, "❌ Glide 加载失败: " + url, e);
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
                                boolean isFirstResource) {
                            Log.d(TAG, "✅ Glide 加载成功: " + url);
                            return false;
                        }
                    })
                    .into(imageView);
        });
    }

    private static void postError(ImageView imageView, String url, LoadOption option) {
        MAIN_HANDLER.post(() -> {
            Object currentTag = imageView.getTag(TAG_AES_URL);
            if (url.equals(currentTag) && option.getErrorResId() != 0) {
                imageView.setImageResource(option.getErrorResId());
            }
        });
    }

    private static void clearFutureTag(ImageView imageView, String url) {
        MAIN_HANDLER.post(() -> {
            if (url.equals(imageView.getTag(TAG_AES_URL))) {
                imageView.setTag(TAG_AES_FUTURE, null);
                imageView.setTag(TAG_AES_CALL, null);
            }
        });
    }

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

    private static byte[] decrypt(byte[] data, AesOption aesOption) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(aesOption.getKey(), "AES"),
                new IvParameterSpec(aesOption.getIv()));
        return cipher.doFinal(data);
    }

    private static String generateCacheFileName(String url, AesOption aesOption) {
        String urlHash = md5(url);
        String keyHash = md5(bytesToHex(aesOption.getKey()));
        return AES_CACHE_PREFIX + urlHash + "_" + keyHash + AES_CACHE_SUFFIX;
    }

    private static File getCacheDir(Context context) {
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            dir = context.getCacheDir();
        }
        return dir;
    }

    private static byte[] readFileToBytes(File file) throws IOException {
        FileInputStream fis = null;
        ByteArrayOutputStream bos = null;
        try {
            fis = new FileInputStream(file);
            bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } finally {
            if (fis != null) fis.close();
            if (bos != null) bos.close();
        }
    }

    private static void writeCacheFile(File cacheFile, byte[] data) throws IOException {
        File dir = cacheFile.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        File tempFile = new File(dir, cacheFile.getName() + ".tmp");

        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(data);
            fos.flush();
        }

        if (cacheFile.exists()) {
            cacheFile.delete();
        }

        if (tempFile.renameTo(cacheFile)) {
            return;
        }

        Log.w(TAG, "renameTo 失败，降级直接写入: " + cacheFile.getName());
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(data);
            fos.flush();
        }
        tempFile.delete();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            return bytesToHex(digest);
        } catch (Exception e) {
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}