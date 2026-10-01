package com.example.rpiblecollector;

import com.google.gson.JsonElement;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * 5주차 PDF 49쪽의 "응답 양식과 상태".
 *
 * <p>성공 (HTTP 200):
 * <pre>
 * {
 *   "result": "Success",
 *   "message": "Data received from Team3 successfully!",
 *   "received_data": { "team": "Team3", "sensor": "team3sensor" },
 *   "status": "ok"
 * }
 * </pre>
 *
 * <p>status 의미:
 * <ul>
 *   <li>{@code ok}           : 정상</li>
 *   <li>{@code out_of_range} : 값이 물리적 범위를 벗어남(저장은 됨). {@code detail} 의 필드를 확인</li>
 * </ul>
 * key 오류 또는 필수 값 누락은 HTTP 400 으로 {@code result}·{@code message} 만 온다.
 * 실패 응답은 errorBody() 로 읽는다.
 */
public final class PostResponse {
    public static final String STATUS_OK = "ok";
    public static final String STATUS_OUT_OF_RANGE = "out_of_range";

    /** 데이터 전송 성공(Success), 실패(Fail) 여부. */
    @Expose
    @SerializedName("result")
    public String result;

    /** result 에 따른 메시지. */
    @Expose
    @SerializedName("message")
    public String message;

    /** 서버가 key 로 판별한 팀명과 보낸 센서명. key 오류 시에는 없음(null). */
    @Expose
    @SerializedName("received_data")
    public ReceivedData received_data;

    /** 저장된 데이터의 상태: ok / out_of_range. 실패 응답에는 없음(null). */
    @Expose
    @SerializedName("status")
    public String status;

    /**
     * out_of_range 일 때 범위를 벗어난 필드 정보. PDF 에 형식이 명시돼 있지 않아
     * 문자열·객체·배열 어느 쪽이 와도 받을 수 있게 JsonElement 로 둔다.
     */
    @Expose
    @SerializedName("detail")
    public JsonElement detail;

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

    /** 저장은 됐지만 값이 물리적 범위를 벗어났다고 서버가 표시한 경우. */
    public boolean isOutOfRange() {
        return STATUS_OUT_OF_RANGE.equalsIgnoreCase(status);
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
        if (received_data != null) {
            sb.append(" · received_data{team=").append(received_data.team)
                    .append(", sensor=").append(received_data.sensor).append('}');
        }
        if (detail != null && !detail.isJsonNull()) {
            sb.append(" · detail=").append(detail.isJsonPrimitive()
                    ? detail.getAsString() : detail.toString());
        }
        return sb.toString();
    }
}
