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
    /** PDF 21쪽 요청 양식의 URL: {@code 203.255.81.72:10021/sensor/opensrc/test/} */
    public static final String BASE_URL = "http://203.255.81.72:10021/";
    public static final String SEND_PATH = "sensor/opensrc/test/";
    /** PDF 26쪽: 수집한 데이터 실시간 확인 페이지. */
    public static final String CHECK_PATH = "sensor/opensrc/check/";
    public static final String CHECK_URL = BASE_URL + CHECK_PATH;

    /** 전송 결과 콜백. Retrofit 이 Android 메인 스레드에서 호출한다. */
    public interface UploadCallback {
        void onUploadSuccess(PostData sent, PostResponse body);
        void onUploadFailure(PostData sent, String message);
    }

    private final Retrofit retrofit;
    private final CommData service;

    public SensorUploader() {
        Gson gson = new GsonBuilder().setLenient().create();
        retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        // PDF 21쪽: 생성한 interface 객체를 retrofit 을 통해 생성
        service = retrofit.create(CommData.class);
    }

    public String baseUrl() {
        return retrofit.baseUrl().toString();
    }

    /**
     * PDF 21쪽 "데이터 전송 코드".
     * enqueue: 데이터의 비동기 전송 / onResponse: 통신 성공 시 응답 처리 / onFailure: 통신 실패 시 수행.
     */
    public void send(final PostData body, final UploadCallback callback) {
        Call<PostResponse> call = service.post_json(body);
        call.enqueue(new Callback<PostResponse>() {
            @Override
            public void onResponse(Call<PostResponse> call, Response<PostResponse> response) {
                if (!response.isSuccessful()) {
                    // PDF 13쪽의 상태 코드: 400 Bad Request, 404 Not Found, 500 Internal Server Error ...
                    callback.onUploadFailure(body,
                            "HTTP " + response.code() + " " + statusText(response.code()));
                    return;
                }
                PostResponse parsed = response.body();
                if (parsed == null) {
                    callback.onUploadFailure(body, "HTTP 200 이지만 응답 본문이 비어 있습니다.");
                    return;
                }
                if (parsed.isSuccess()) {
                    callback.onUploadSuccess(body, parsed);
                } else {
                    callback.onUploadFailure(body, parsed.summary());
                }
            }

            @Override
            public void onFailure(Call<PostResponse> call, Throwable t) {
                callback.onUploadFailure(body,
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
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
