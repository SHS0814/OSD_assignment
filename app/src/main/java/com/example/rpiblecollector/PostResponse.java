package com.example.rpiblecollector;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.Locale;

/**
 * 4주차 PDF 22쪽의 "응답 양식" + 실제 서버가 내려주는 전체 필드.
 *
 * <p>서버(203.255.81.72:10021)에 직접 요청해 확인한 실제 응답 스키마:
 *
 * <p>성공 (HTTP 200):
 * <pre>
 * {
 *   "result": "Success",
 *   "message": "Data received from team 9 successfully!",
 *   "received_data": { "team": "team 9", "sensor": "opensrc_week_3" },
 *   "verified": true
 * }
 * </pre>
 *
 * <p>실패 (HTTP 400) — status 로 실패 원인을 구분한다:
 * <ul>
 *   <li>{@code no_raw}        : raw 필드가 없습니다</li>
 *   <li>{@code bad_raw}       : raw 길이가 N바이트입니다 (21바이트여야 함)</li>
 *   <li>{@code bad_tag}       : 태그 검증 실패 (raw 또는 mac이 수신한 패킷과 다릅니다)</li>
 *   <li>{@code value_mismatch}: 파싱 값 불일치 (expected 에 raw 해석값이 담긴다)</li>
 * </ul>
 * key 가 틀리면 {@code result}·{@code message} 만 오고 나머지 필드는 없다.
 */
public final class PostResponse {
    /** 데이터 전송 성공(Success), 실패(Fail) 여부. */
    @Expose
    @SerializedName("result")
    public String result;

    /** result 에 따른 메시지. */
    @Expose
    @SerializedName("message")
    public String message;

    /** 보낸 팀명, 센서명. key 오류 시에는 없음(null). */
    @Expose
    @SerializedName("received_data")
    public ReceivedData received_data;

    /** HMAC 태그 검증 통과 여부. 성공 시 true, 검증 단계 실패 시 false, key 오류 시 없음(null). */
    @Expose
    @SerializedName("verified")
    public Boolean verified;

    /** 실패 원인 코드: no_raw / bad_raw / bad_tag / value_mismatch. 성공 시에는 없음(null). */
    @Expose
    @SerializedName("status")
    public String status;

    /** value_mismatch 일 때만 존재. 서버가 raw 를 직접 해석한 "정답" 센서값. */
    @Expose
    @SerializedName("expected")
    public Expected expected;

    public static final class ReceivedData {
        @Expose
        @SerializedName("team")
        public String team;

        @Expose
        @SerializedName("sensor")
        public String sensor;
    }

    /** value_mismatch 응답의 {@code expected} 객체. raw 에서 디코딩한 실제 센서값. */
    public static final class Expected {
        @Expose
        @SerializedName("temp")
        public Double temp;

        @Expose
        @SerializedName("humidity")
        public Double humidity;

        @Expose
        @SerializedName("AQI")
        public Integer AQI;

        @Expose
        @SerializedName("TVOC")
        public Integer TVOC;

        @Expose
        @SerializedName("eCO2")
        public Integer eCO2;

        @Expose
        @SerializedName("timestamp")
        public Long timestamp;

        public String summary() {
            return String.format(Locale.US,
                    "temp=%s humidity=%s AQI=%s TVOC=%s eCO2=%s timestamp=%s",
                    temp, humidity, AQI, TVOC, eCO2, timestamp);
        }
    }

    public boolean isSuccess() {
        return result != null && result.equalsIgnoreCase("Success");
    }

    /** 태그 검증까지 통과했는지. 서버가 verified 를 안 주면 false 로 본다. */
    public boolean isVerified() {
        return Boolean.TRUE.equals(verified);
    }

    /** 화면과 로그에 남길 한 줄 요약. 실제 응답의 모든 필드를 반영한다. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(result == null ? "(result 없음)" : result);
        if (status != null && !status.isEmpty()) {
            sb.append(" [").append(status).append(']');
        }
        if (message != null && !message.isEmpty()) {
            sb.append(" · ").append(message);
        }
        if (verified != null) {
            sb.append(" · verified=").append(verified);
        }
        if (received_data != null) {
            sb.append(" · received_data{team=").append(received_data.team)
                    .append(", sensor=").append(received_data.sensor).append('}');
        }
        if (expected != null) {
            sb.append(" · expected{").append(expected.summary()).append('}');
        }
        return sb.toString();
    }
}
