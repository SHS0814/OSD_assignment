package com.example.rpiblecollector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * 4주차 PDF 18쪽 "통신 기본 설정"과 21쪽 "데이터 전송 코드"를 그대로 옮긴 HTTP 전송기.
 *
 * <p>PDF 18쪽의 Retrofit 변수 생성:
 * <pre>
 * Gson gson = new GsonBuilder().setLenient().create();
 * retrofit = new Retrofit.Builder()
 *         .baseUrl("http://203.255.81.72:10021/")
 *         .addConverterFactory(ScalarsConverterFactory.create())   // Response 를 String 형태로 받을 때
 *         .addConverterFactory(GsonConverterFactory.create(gson))  // Response 를 Json 형태로 받을 때
 *         .build();
 * </pre>
 */
public final class SensorUploader {
    /**
     * 5주차 PDF 48쪽 요청 양식의 URL: {@code 203.255.81.72:10021/sensor/opensrc/upload/}
     * (4주차의 {@code .../test/} 에서 바뀌었다.)
     */
    public static final String BASE_URL = "http://203.255.81.72:10021/";
    public static final String SEND_PATH = "sensor/opensrc/upload/";
    /** 5주차 PDF 50쪽: 팀별 수집 현황 페이지. */
    public static final String CHECK_PATH = "sensor/opensrc/teams/";
    public static final String CHECK_URL = BASE_URL + CHECK_PATH;

    /** 전송 결과 콜백. Retrofit 이 Android 메인 스레드에서 호출한다. */
    public interface UploadCallback {
        void onUploadSuccess(PostData sent, PostResponse body);

        /**
         * @param message 사람이 읽을 요약 문자열.
         * @param response 서버가 내려준 실패 응답(result/message).
         *                 네트워크 오류나 파싱 실패 등 서버 응답이 없을 때는 {@code null}.
         */
        void onUploadFailure(PostData sent, String message, PostResponse response);
    }

    private final Retrofit retrofit;
    private final CommData service;
    /** 실패 응답(HTTP 400)의 errorBody 를 PostResponse 로 파싱하기 위해 보관한다. */
    private final Gson gson;

    public SensorUploader() {
        gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        service = retrofit.create(CommData.class);
    }

    public String baseUrl() {
        return retrofit.baseUrl().toString();
    }

    /**
     * 장부 행 하나를 서버로 보낸다. 서버로 나가는 유일한 입구다.
     *
     * <p>{@link BacklogDb.Row} 는 장부 DB 에서만 만들어지므로, 장부에 기록되지 않은 데이터는
     * 이 메서드로 보낼 수 없다. 이미 전송된 행과 "데이터 없음" 행도 여기서 거부한다.
     *
     * @return 실제로 보낸 요청 본문 (로그용)
     */
    public PostData send(BacklogDb.Row row, String key, String sensor, String sender,
                         UploadCallback callback) {
        if (row.noData) {
            throw new IllegalArgumentException("데이터 없음 행은 서버로 보내지 않습니다: " + row.timestamp);
        }
        if (row.uploaded) {
            throw new IllegalArgumentException("이미 전송된 행입니다: " + row.timestamp);
        }
        PostData body = new PostData();
        body.set_data(key, sensor, row.mac, row.temp, row.humidity,
                row.aqi, row.tvoc, row.eco2, row.timestamp, row.lat, row.lon, sender);
        post(body, callback);
        return body;
    }

    /**
     * PDF 21쪽 "데이터 전송 코드".
     * enqueue: 데이터의 비동기 전송 / onResponse: 통신 성공 시 응답 처리 / onFailure: 통신 실패 시 수행.
     */
    private void post(final PostData body, final UploadCallback callback) {
        Call<PostResponse> call = service.post_json(body);
        call.enqueue(new Callback<PostResponse>() {
            @Override
            public void onResponse(Call<PostResponse> call, Response<PostResponse> response) {
                if (!response.isSuccessful()) {
                    // PDF 13쪽의 상태 코드: 400 Bad Request, 404 Not Found, 500 Internal Server Error ...
                    // HTTP 400 실패 응답의 본문은 body 가 아니라 errorBody 로 온다.
                    // 5주차 PDF 49쪽: key 오류·필수 값 누락은 errorBody() 로 읽는다.
                    PostResponse err = parseError(response);
                    if (err != null && (err.message != null || err.status != null)) {
                        callback.onUploadFailure(body,
                                "HTTP " + response.code() + " · " + err.summary(), err);
                    } else {
                        callback.onUploadFailure(body,
                                "HTTP " + response.code() + " " + statusText(response.code()), err);
                    }
                    return;
                }
                PostResponse parsed = response.body();
                if (parsed == null) {
                    callback.onUploadFailure(body, "HTTP 200 이지만 응답 본문이 비어 있습니다.", null);
                    return;
                }
                if (parsed.isSuccess()) {
                    callback.onUploadSuccess(body, parsed);
                } else {
                    callback.onUploadFailure(body, parsed.summary(), parsed);
                }
            }

            @Override
            public void onFailure(Call<PostResponse> call, Throwable t) {
                callback.onUploadFailure(body,
                        t.getClass().getSimpleName() + ": " + t.getMessage(), null);
            }
        });
    }

    /** 실패 응답(HTTP 400)의 errorBody 를 PostResponse 로 파싱한다. 실패하면 null. */
    private PostResponse parseError(Response<PostResponse> response) {
        if (response.errorBody() == null) {
            return null;
        }
        try {
            return gson.fromJson(response.errorBody().charStream(), PostResponse.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** PDF 13쪽의 대표 상태 코드. */
    private static String statusText(int code) {
        switch (code) {
            case 200:
                return "Success";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 500:
                return "Internal Server Error";
            default:
                return "";
        }
    }
}
