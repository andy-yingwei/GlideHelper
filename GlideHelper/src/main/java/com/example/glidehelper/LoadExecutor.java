package com.example.glidehelper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class LoadExecutor {

    static void executeNormal(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option
    ) {
        RequestOptions options = option.toRequestOptions();
        Glide.with(context)
                .load(url)
                .apply(options)
                .into(imageView);
    }

    /**
     * ✅ 鸿蒙最终方案：解密 → 写文件 → Glide 加载文件
     */
    static void executeAesBitmap(
            @NonNull Context context,
            @NonNull String url,
            @NonNull ImageView imageView,
            @NonNull LoadOption option,
            @NonNull AesOption aesOption
    ) {
        new Thread(() -> {
            try {
                // 1. 下载
                byte[] encrypted = downloadEncrypted(url, option.getHeaders());

                // 2. 解密
                byte[] decrypted = decrypt(encrypted, aesOption);

                // 3. ✅ 写文件（鸿蒙允许）
                File cacheFile = writeToCache(context, url, decrypted);

                new Handler(Looper.getMainLooper()).post(() -> {
                    RequestOptions options = option.toRequestOptions();

                    // ✅ 强制 NONE 缓存（鸿蒙关键）
                    options.diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .skipMemoryCache(true);

                    Glide.with(context)
                            .load(cacheFile)
                            .apply(options)
                            .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                                @Override
                                public boolean onLoadFailed(
                                        @androidx.annotation.Nullable GlideException e,
                                        Object model,
                                        Target<android.graphics.drawable.Drawable> target,
                                        boolean isFirstResource
                                ) {
                                    if (option.getErrorResId() != 0) {
                                        imageView.setImageResource(option.getErrorResId());
                                    }
                                    return true;
                                }

                                @Override
                                public boolean onResourceReady(
                                        android.graphics.drawable.Drawable resource,
                                        Object model,
                                        Target<android.graphics.drawable.Drawable> target,
                                        DataSource dataSource,
                                        boolean isFirstResource
                                ) {
                                    return false;
                                }
                            })
                            .into(imageView);
                });

            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (option.getErrorResId() != 0) {
                        imageView.setImageResource(option.getErrorResId());
                    }
                });
            }
        }).start();
    }

    private static byte[] downloadEncrypted(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoInput(true);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
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
     * ✅ 写文件（鸿蒙兼容方案）
     */
    private static File writeToCache(Context context, String url, byte[] data) throws Exception {
        File cacheDir = context.getExternalCacheDir(); // ✅ 鸿蒙推荐
        if (cacheDir == null) {
            cacheDir = context.getCacheDir();
        }
        File cacheFile = new File(cacheDir, "aes_" + url.hashCode());
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            fos.write(data);
        }
        return cacheFile;
    }
}