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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ====================================================
 * LoadExecutor（OkHttp 最终修复版）
 * ----------------------------------------------------
 *
 * 【必须依赖】
 *   implementation 'com.squareup.okhttp3:okhttp:4.12.0'
 *
 * 修复点：
 *   ✅ 修复 "Variable 'future' might not have been initialized"
 *      原因：Lambda finally 中通过闭包引用 future，编译器认为可能未初始化
 *      方案：finally 中改用 url 相等性判断清理 Tag，不再引用 future/call
 *
 * 核心特性：
 *   ✅ OkHttp Call.cancel() 真正关闭 Socket，彻底释放线程
 *   ✅ 子线程零 View 访问，主线程 Tag 双重校验防错乱
 *   ✅ 缓存文件名 = URL MD5 + Key MD5，永久命中不重复解密
 *   ✅ 自适应线程池（4~6 线程）
 *
 * 需要在 res/values/ids.xml 中定义：
 *   <item name="tag_aes_url" type="id"/>
 *   <item name="tag_aes_future" type="id"/>
 *   <item name="tag_aes_call" type="id"/>
 *
 * Adapter 中需要在 onViewRecycled 调用：
 *   LoadExecutor.cancelAndClear(holder.imageView);
 *
 * ====================================================
 */
public class LoadExecutor {

    private static final String TAG = "LoadExecutor";

    // ===================== 常量 =====================

    private static final long CACHE_MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final String AES_CACHE_PREFIX = "aes_";
    private static final String AES_CACHE_SUFFIX = ".cache";

    private static final int TAG_AES_URL = R.id.tag_aes_url;
    private static final int TAG_AES_FUTURE = R.id.tag_aes_future;
    private static final int TAG_AES_CALL = R.id.tag_aes_call;

    // ===================== OkHttpClient =====================

    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    // ===================== 线程池 =====================

    private static final ExecutorService AES_EXECUTOR;

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int poolSize = (cores <= 4) ? 4 : (cores <= 6) ? cores : 6;
        AES_EXECUTOR = Executors.newFixedThreadPool(poolSize);
        Log.d(TAG, "AES Executor initialized, CPU cores=" + cores + ", pool=" + poolSize);
    }

    // ===================== Handler =====================

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
     * AES 图加载（OkHttp 版）
     */
    @SuppressLint("CheckResult")
    static void executeAesBitmap(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option,
            @NonNull AesOption aesOption
    ) {
        // ===== Step 1: 主线程绑定 URL Tag =====
        imageView.setTag(TAG_AES_URL, url);

        // ===== Step 2: 取消旧任务（关闭旧 Socket + 中断旧线程） =====
        cancelTask(imageView);

        // ===== Step 3: 主线程预计算缓存路径 =====
        final File cacheDir = getCacheDir(context);
        final String cacheFileName = generateCacheFileName(url, aesOption);

        // ===== Step 4: 构建 OkHttp Request & Call =====
        Request.Builder requestBuilder = new Request.Builder().url(url);
        Map<String, String> headers = option.getFinalHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        final Request request = requestBuilder.build();
        final Call call = OK_HTTP_CLIENT.newCall(request);

        // [FIX] 将 Call 绑定到 View Tag，供 cancel 时使用
        imageView.setTag(TAG_AES_CALL, call);

        // ===== Step 5: 提交到线程池 =====
        try {
            Future<?> future = AES_EXECUTOR.submit(() -> {
                try {
                    // [核心原则] 子线程中绝不访问 imageView！

                    File cacheFile = new File(cacheDir, cacheFileName);
                    final File validCacheFile;

                    // 1. 检查本地缓存是否已存在
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        validCacheFile = cacheFile;
                    } else {
                        // 2. 同步执行网络请求
                        Response response = call.execute();

                        if (call.isCanceled()) {
                            Log.d(TAG, "Call 已被取消，中断任务: " + url);
                            return;
                        }

                        if (!response.isSuccessful()) {
                            throw new IOException("HTTP " + response.code() + " for " + url);
                        }

                        ResponseBody body = response.body();
                        if (body == null) {
                            throw new IOException("Response body is null: " + url);
                        }
                        byte[] encrypted = body.bytes();

                        // 3. 检查线程中断状态
                        if (Thread.currentThread().isInterrupted()) {
                            Log.d(TAG, "线程被中断，跳过解密: " + url);
                            return;
                        }

                        // 4. 解密
                        byte[] decrypted = decrypt(encrypted, aesOption);

                        // 5. 写入缓存文件
                        writeCacheFile(cacheFile, decrypted);
                        validCacheFile = cacheFile;
                    }

                    // 6. 回到主线程加载
                    MAIN_HANDLER.post(() -> {
                        // 主线程中校验 Tag
                        Object currentTag = imageView.getTag(TAG_AES_URL);
                        if (!url.equals(currentTag)) {
                            Log.d(TAG, "View 已复用，丢弃结果: " + url);
                            return;
                        }

                        if (!imageView.isAttachedToWindow()) {
                            return;
                        }

                        // 先清除 Glide 旧请求，防止上一张图闪现
                        Glide.with(imageView).clear(imageView);

                        RequestOptions glideOptions = option.toRequestOptions();
                        glideOptions.diskCacheStrategy(DiskCacheStrategy.NONE)
                                .skipMemoryCache(false);

                        Glide.with(imageView)
                                .load(validCacheFile)
                                .apply(glideOptions)
                                .listener(new RequestListener<Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(
                                            @Nullable GlideException e,
                                            Object model,
                                            @NonNull Target<Drawable> target,
                                            boolean isFirstResource) {
                                        Log.e(TAG, "Glide 加载失败: " + url, e);
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
                                        return false;
                                    }
                                })
                                .into(imageView);
                    });

                } catch (IOException e) {
                    if (call.isCanceled() || Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "任务正常取消: " + url);
                        return;
                    }
                    Log.e(TAG, "AES 网络/IO 错误: " + url, e);
                    MAIN_HANDLER.post(() -> {
                        Object currentTag = imageView.getTag(TAG_AES_URL);
                        if (url.equals(currentTag) && option.getErrorResId() != 0) {
                            imageView.setImageResource(option.getErrorResId());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "AES 任务失败: " + url, e);
                    MAIN_HANDLER.post(() -> {
                        Object currentTag = imageView.getTag(TAG_AES_URL);
                        if (url.equals(currentTag) && option.getErrorResId() != 0) {
                            imageView.setImageResource(option.getErrorResId());
                        }
                    });
                } finally {
                    // [FIX] 不再引用 future/call，改用 url 相等性判断清理 Tag
                    // 避免 "Variable 'future' might not have been initialized" 编译错误
                    MAIN_HANDLER.post(() -> {
                        Object currentUrl = imageView.getTag(TAG_AES_URL);
                        if (url.equals(currentUrl)) {
                            imageView.setTag(TAG_AES_CALL, null);
                            imageView.setTag(TAG_AES_FUTURE, null);
                        }
                    });
                }
            });

            // 绑定 Future 到 View Tag
            imageView.setTag(TAG_AES_FUTURE, future);

        } catch (RejectedExecutionException e) {
            Log.e(TAG, "AES 线程池拒绝任务: " + url, e);
            if (option.getErrorResId() != 0) {
                imageView.setImageResource(option.getErrorResId());
            }
        }
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
        imageView.setTag(TAG_AES_FUTURE, null);
        imageView.setTag(TAG_AES_CALL, null);
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
     * 取消 ImageView 上正在执行的任务
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
