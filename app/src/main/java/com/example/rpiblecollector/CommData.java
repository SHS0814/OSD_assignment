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
 * <p>실제 제출용 API 는 5주차 PDF 48쪽의 {@code POST /sensor/opensrc/upload/} 이므로
 * {@link #post_json(PostData)} 를 사용한다.
 */
public interface CommData {
    @POST(SensorUploader.SEND_PATH)
    Call<PostResponse> post_json(@Body PostData pd);
}
