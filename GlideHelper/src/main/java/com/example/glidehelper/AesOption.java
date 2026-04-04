package com.example.glidehelper;

/**
 * ====================================================
 * AesOption
 * ----------------------------------------------------
 * ✅ AES 解密专用配置
 * ✅ 隔离 AES 复杂度（key / iv）
 * ====================================================
 */
public class AesOption {

    private final byte[] key;
    private final byte[] iv;

    private AesOption(byte[] key, byte[] iv) {
        this.key = key;
        this.iv = iv;
    }

    /**
     * ✅ 创建 AES 配置
     */
    public static AesOption create(String keyStr, String ivStr) {
        return new AesOption(
                AesByteUtil.parseToBytes(keyStr),
                AesByteUtil.parseToBytes(ivStr)
        );
    }

    byte[] getKey() {
        return key;
    }

    byte[] getIv() {
        return iv;
    }
}