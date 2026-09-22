package com.example.rpiblecollector;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * 4주차 PDF 22쪽의 "응답 양식".
 *
 * <pre>
 * {
 *   "result": "Success",
 *   "message": "Data received from team TA successfully!",
 *   "received_data": { "team": "team TA", "sensor": "sensor TA" }
 * }
 * </pre>
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

    /** 보낸 팀명, 센서명. */
    @Expose
    @SerializedName("received_data")
    public ReceivedData received_data;

    public static final class ReceivedData {
        @Expose
        @SerializedName("team")
        public String team;

        @Expose
        @SerializedName("sensor")
        public String sensor;
    }

    public boolean isSuccess() {
        return result != null && result.equalsIgnoreCase("Success");
    }

    /** 화면과 로그에 남길 한 줄 요약. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(result == null ? "(result 없음)" : result);
        if (message != null && !message.isEmpty()) {
            sb.append(" · ").append(message);
        }
        if (received_data != null) {
            sb.append(" · received_data{team=").append(received_data.team)
                    .append(", sensor=").append(received_data.sensor).append('}');
        }
        return sb.toString();
    }
}
