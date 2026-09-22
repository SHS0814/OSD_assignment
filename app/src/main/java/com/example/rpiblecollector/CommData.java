package com.example.rpiblecollector;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

/**
 * 4주차 PDF 19~20쪽의 {@code comm_data} 인터페이스.
 *
 * <p>PDF 19쪽 "전송 parameter annotation" 표에 따라 세 가지 전송 방식을 모두 선언한다.
 * <ul>
 *   <li>{@code @Field} : POST 통신 시 개별 매개 변수 전송, {@code @FormUrlEncoded} 로 encode</li>
 *   <li>{@code @Body}  : POST 통신 시 객체를 JSON 형식으로 전송</li>
 *   <li>{@code @Query} : GET 통신 시 query parameter 를 url 뒤에 추가</li>
 * </ul>
 *
 * <p>실제 제출용 API 는 PDF 21쪽의 {@code POST /sensor/opensrc/test/} 이므로
 * {@link #post_json(PostData)} 를 사용한다.
 */
public interface CommData {
    /** PDF 21쪽 요청 양식: URL {@code /sensor/opensrc/test/}, Method POST, Body JSON. */
    @POST(SensorUploader.SEND_PATH)
    Call<PostResponse> post_json(@Body PostData pd);

    /** 서버 응답을 JSON 으로 파싱하지 않고 원문 그대로 확인하고 싶을 때(ScalarsConverterFactory). */
    @POST(SensorUploader.SEND_PATH)
    Call<String> post_json_raw(@Body PostData pd);

    /** PDF 19쪽 "Key-Value 형식의 데이터 전송" 예시. */
    @FormUrlEncoded
    @POST(SensorUploader.SEND_PATH)
    Call<String> post(@Field("user") String user, @Field("data") String data);

    /** PDF 19쪽 "GET 방식의 데이터 전송" 예시. 수집 현황 확인 페이지 조회에 사용한다. */
    @GET(SensorUploader.CHECK_PATH)
    Call<String> get(@Query("team") String team, @Query("sensor") String sensor);
}
