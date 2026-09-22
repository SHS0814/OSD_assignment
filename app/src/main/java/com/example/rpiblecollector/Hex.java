package com.example.rpiblecollector;

/** 바이트 배열과 대문자 hex 문자열 사이의 변환. Android 의존성이 없어 단위 테스트에서도 쓴다. */
public final class Hex {
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[value >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(chars);
    }
}
