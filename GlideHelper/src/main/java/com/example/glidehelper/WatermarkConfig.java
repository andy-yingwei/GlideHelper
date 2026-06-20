package com.example.glidehelper;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;

/**
 * ====================================================
 * WatermarkConfig
 * ----------------------------------------------------
 * ✅ 解耦 WatermarkOverlayView 与 LoadOption
 * ====================================================
 */
public class WatermarkConfig {

    private final String text;
    private final int gravity;
    private final int alpha;
    @ColorInt private final int color;
    @ColorRes private final int colorRes;
    private final float textSizeSp;

    private WatermarkConfig(Builder builder) {
        this.text = builder.text;
        this.gravity = builder.gravity;
        this.alpha = builder.alpha;
        this.color = builder.color;
        this.colorRes = builder.colorRes;
        this.textSizeSp = builder.textSizeSp;
    }

    public static WatermarkConfig from(LoadOption option) {
        return new Builder()
                .text(option.getWatermarkText())
                .gravity(option.getWatermarkGravity())
                .alpha(option.getWatermarkAlpha())
                .color(option.getWatermarkColor())
                .colorRes(option.getWatermarkColorRes())
                .textSizeSp(option.getWatermarkTextSize())
                .build();
    }

    public String getText() { return text; }
    public int getGravity() { return gravity; }
    public int getAlpha() { return alpha; }
    public int getColor() { return color; }
    public int getColorRes() { return colorRes; }
    public float getTextSizeSp() { return textSizeSp; }

    public static class Builder {
        private String text = "";
        private int gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        private int alpha = 180;
        @ColorInt private int color = 0xFFFFFFFF;
        @ColorRes private int colorRes = 0;
        private float textSizeSp = 14;

        public Builder text(String text) { this.text = text; return this; }
        public Builder gravity(int gravity) { this.gravity = gravity; return this; }
        public Builder alpha(int alpha) { this.alpha = alpha; return this; }
        public Builder color(@ColorInt int color) { this.color = color; return this; }
        public Builder colorRes(@ColorRes int colorRes) { this.colorRes = colorRes; return this; }
        public Builder textSizeSp(float sp) { this.textSizeSp = sp; return this; }
        public WatermarkConfig build() { return new WatermarkConfig(this); }
    }
}
