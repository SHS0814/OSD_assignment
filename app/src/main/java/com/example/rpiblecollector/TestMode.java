package com.example.rpiblecollector;

/**
 * 전송 페이지의 테스트 모드.
 *
 * <p>서버 수신 현황(export.csv)이 내려주는 상태값을 각각 재현해,
 * 서버 검증이 실제로 동작하는지 확인하기 위한 것이다.
 */
public enum TestMode {
    /** 정상 전송. 서버 상태 {@code verified} 를 기대한다. */
    NORMAL,
    /** raw 필드를 빼고 전송. 서버 상태 {@code no_raw} 를 기대한다. */
    NO_RAW,
    /** raw 안의 HMAC 태그 마지막 바이트를 뒤집어 전송. {@code bad_tag} 를 기대한다. */
    BAD_TAG,
    /** raw 는 그대로 두고 온도만 바꿔 전송. 값 불일치를 기대한다. */
    BAD_VALUE;

    public String label() {
        switch (this) {
            case NO_RAW:
                return "raw 제외";
            case BAD_TAG:
                return "태그 변조";
            case BAD_VALUE:
                return "값 변조";
            default:
                return "정상";
        }
    }
}
