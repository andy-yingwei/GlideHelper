package com.example.glidehelper;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * ====================================================
 * WatermarkOverlayView
 * ----------------------------------------------------
 * ✅ 纯 View 绘制水印（CPU）
 * ✅ 无 Bitmap / GPU / Gralloc 风险
 * ✅ 支持文字、透明度、位置、颜色（多种值）、文字大小
 * ====================================================
 */
public class WatermarkOverlayView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String text = "";
    private int alpha = 180;
    private int gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
    private float textSizeSp = 14; // ✅ 默认文字大小（sp）

    public WatermarkOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(Color.WHITE);
        paint.setTextSize(sp2px(textSizeSp));
        paint.setAlpha(alpha);
    }

    /**
     * ✅ 设置水印文字
     */
    public void setWatermarkText(String text) {
        this.text = text;
        invalidate();
    }

    /**
     * ✅ 设置透明度（0~255）
     */
    public void setWatermarkAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        paint.setAlpha(this.alpha);
        invalidate();
    }

    /**
     * ✅ 设置位置（Gravity）
     */
    public void setWatermarkGravity(int gravity) {
        this.gravity = gravity;
        invalidate();
    }

    /**
     * ✅ 设置文字大小（单位：sp）
     */
    public void setWatermarkTextSize(float sp) {
        this.textSizeSp = Math.max(8, sp);
        paint.setTextSize(sp2px(this.textSizeSp));
        invalidate();
    }

    /**
     * ✅ 设置颜色（直接值）
     */
    public void setWatermarkColor(@ColorInt int color) {
        paint.setColor(color);
        invalidate();
    }

    /**
     * ✅ 设置颜色（资源 ID）
     */
    public void setWatermarkColorRes(@ColorRes int colorRes) {
        try {
            int color = getResources().getColor(colorRes, null);
            paint.setColor(color);
            invalidate();
        } catch (Resources.NotFoundException e) {
            paint.setColor(Color.WHITE);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (text == null || text.isEmpty()) return;

        float x, y;

        if ((gravity & android.view.Gravity.START) != 0) {
            x = 40;
        } else {
            x = getWidth() - paint.measureText(text) - 40;
        }

        if ((gravity & android.view.Gravity.TOP) != 0) {
            y = 80;
        } else {
            y = getHeight() - 80;
        }

        canvas.drawText(text, x, y, paint);
    }


    /**
     * ✅ 自动从 LoadOption 应用所有水印配置（推荐）
     *
     * @param option LoadOption（必须包含 watermark 配置）
     */
    public void applyFromOption(LoadOption option) {
        if (option == null) return;

        // 文字
        setWatermarkText(option.getWatermarkText());

        // 透明度
        setWatermarkAlpha(option.getWatermarkAlpha());

        // 位置
        setWatermarkGravity(option.getWatermarkGravity());

        // ✅ 颜色（优先资源 ID）
        if (option.getWatermarkColorRes() != 0) {
            setWatermarkColorRes(option.getWatermarkColorRes());
        } else {
            setWatermarkColor(option.getWatermarkColor());
        }

        // 文字大小
        setWatermarkTextSize(option.getWatermarkTextSize());
    }

    /**
     * ✅ sp 转 px（适配不同屏幕）
     */
    private int sp2px(float sp) {
        return (int) (sp * getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }
}
