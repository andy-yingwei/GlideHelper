package com.example.glidehelper;

/**
 * ====================================================
 * LoadListener
 * ----------------------------------------------------
 * ✅ 图片加载回调接口
 * ✅ 解耦 UI 与加载逻辑
 * ====================================================
 */
public interface LoadListener {

    /**
     * 图片加载成功回调
     */
    void onSuccess();

    /**
     * 图片加载失败回调
     *
     * @param error 错误信息
     */
    void onError(String error);
}