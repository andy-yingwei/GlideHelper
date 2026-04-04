package com.example.myglidehelper;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.example.glidehelper.AesOption;
import com.example.glidehelper.ImageLoader;
import com.example.glidehelper.LoadOption;
import com.example.glidehelper.GlideConstants;
import com.example.glidehelper.WatermarkOverlayView;

public class MainActivity extends AppCompatActivity {

    // ✅ 标准 HTTPS 测试图
    private static final String TEST_HTTPS =
            "https://fuss10.elemecdn.com/e/5d/4a731a90594a4af544c0c25941171jpeg.jpeg";

    // ✅ 原图（未加密）
    private static final String ORIGINAL_URL =
            "https://cube.elemecdn.com/6/94/4d3ea53c084bad6931a56d5158a48jpeg.jpeg";

    // ✅ AES 加密图
    private static final String AES_URL =
            "https://pic.vugogg.cn/upload/upload/20240118/2024011821364466878.jpg";

    @SuppressLint("ResourceAsColor")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ImageView ivTestHttps = findViewById(R.id.iv_test_https);
        ImageView ivOriginal = findViewById(R.id.iv_original);
        FrameLayout container = findViewById(R.id.image_container);
        ImageView ivAes = findViewById(R.id.iv_aes);
        WatermarkOverlayView watermarkView = findViewById(R.id.watermark_view);

        // ========== 1️⃣ 标准 HTTPS ==========
        ImageLoader.with(this).load(TEST_HTTPS,ivTestHttps,new LoadOption().asCircle());

        // ========== 2️⃣ 原图（未加密） ==========
        ImageLoader.with(this).load(ORIGINAL_URL,ivOriginal,new LoadOption().round(40));

        // ========== 3️⃣ AES 加密图（✅ 最终稳定方案） ==========

        String key = "102_53_100_57_54_53_100_102_55_53_51_51_54_50_55_48";
        String iv = "57_55_98_54_48_51_57_52_97_98_99_50_102_98_101_49";

        LoadOption aesOption = new LoadOption()
                .asCircle()
                .placeholder(GlideConstants.PLACEHOLDER)
                .error(GlideConstants.ERROR)
                .decodeFormat(DecodeFormat.PREFER_ARGB_8888)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .watermarkRes("你好呀", Gravity.TOP | Gravity.START, 60, R.color.teal_700,30);

        AesOption aesOpt = AesOption.create(key, iv);

        // ✅ 加载 AES 图
        ImageLoader.with(this).loadAesBitmap(AES_URL, ivAes, aesOption, aesOpt);
        // ✅ 自动应用所有水印配置
        watermarkView.applyFromOption(aesOption);
    }
}