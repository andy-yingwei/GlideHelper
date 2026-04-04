package com.example.glidehelper;

/**
 * ====================================================
 * AesByteUtil
 * ----------------------------------------------------
 * ✅ 解析你的 key / iv 字符串
 * 格式：102_53_100_57...
 * ====================================================
 */
public final class AesByteUtil {

    private AesByteUtil() {}

    /**
     * 将 "102_53_100..." 转成 byte[]
     */
    public static byte[] parseToBytes(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("AES string is empty");
        }
        String[] parts = str.split("_");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return bytes;
    }
}