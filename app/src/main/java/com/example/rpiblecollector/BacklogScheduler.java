package com.example.rpiblecollector;

import java.util.NavigableSet;
import java.util.Set;

/**
 * 라즈베리 파이에 요청할 다음 과거 시각을 고른다.
 *
 * <p>굵은 간격(24시간)부터 채우고, 그 간격이 모두 채워지면 다음 간격(12시간 …
 * 5분)으로 내려간다. 같은 간격 안에서는 오래된 날짜부터 채운다. 어느 시점에
 * 수집을 멈춰도 서버에 올라간 데이터는 등간격 시계열이 된다.
 *
 * <p>상태를 들고 있지 않고 매번 처음부터 계산한다. 그래서 날짜가 바뀌어 새로 생긴
 * 24시간 격자점이 이미 진행 중인 세밀한 간격보다 자동으로 먼저 요청된다.
 */
public final class BacklogScheduler {
    /** 격자 간격(초). 굵은 것부터. */
    public static final long[] LEVEL_SECONDS = {
            86_400L, 43_200L, 21_600L, 10_800L, 3_600L, 1_800L, 600L, 300L
    };
    /** 격자 시각에서 이 범위 안에 샘플이 있으면 채워진 것으로 본다. 파이 응답 범위와 같다. */
    public static final long TOLERANCE_SECONDS = 30L;
    /** 최근 이 시간 안의 격자는 실시간 광고가 채우므로 요청하지 않는다. */
    public static final long LIVE_MARGIN_SECONDS = 120L;
    /** 한국 표준시(UTC+9). 격자의 하루는 00:00 KST 에 시작한다. */
    private static final long KST_OFFSET_SECONDS = 9L * 3_600L;
    private static final long DAY_SECONDS = 86_400L;

    private BacklogScheduler() {
    }

    /** ts 가 속한 날의 00:00 KST (Unix 초). */
    public static long dayStart(long ts) {
        // Unix 시각은 양수라 일반 나눗셈으로 충분하다 (Math.floorDiv 는 API 24 부터).
        return (ts + KST_OFFSET_SECONDS) / DAY_SECONDS * DAY_SECONDS - KST_OFFSET_SECONDS;
    }

    /**
     * @param dataStart 파이 데이터가 처음 시작된 시각. 격자는 이 날의 00:00 KST 에서
     *                  시작하지만, 이보다 이른 격자는 데이터가 없으므로 요청하지 않는다.
     * @param now   현재 시각 (Unix 초)
     * @param have  이미 받은 샘플의 timestamp
     * @param gaps  파이가 "데이터 없음"이라고 답한 격자 시각
     * @return 다음에 요청할 격자 시각. 더 요청할 것이 없으면 -1
     */
    public static long nextRequest(long dataStart, long now,
                                   NavigableSet<Long> have, Set<Long> gaps) {
        long start = dayStart(dataStart);
        long first = dataStart - TOLERANCE_SECONDS;
        long end = now - LIVE_MARGIN_SECONDS;
        for (long step : LEVEL_SECONDS) {
            // 격자의 기준점(00:00 KST)은 그대로 두고, 데이터 시작 전 격자만 건너뛴다.
            long t = start + Math.max(0L, (first - start + step - 1) / step) * step;
            for (; t <= end; t += step) {
                if (!gaps.contains(t) && !isCovered(have, t)) {
                    return t;
                }
            }
        }
        return -1L;
    }

    /** t 가 처음 등장하는 (가장 굵은) 격자 간격. 화면 표시용. */
    public static long levelOf(long start, long t) {
        for (long step : LEVEL_SECONDS) {
            if ((t - start) % step == 0L) {
                return step;
            }
        }
        return LEVEL_SECONDS[LEVEL_SECONDS.length - 1];
    }

    static boolean isCovered(NavigableSet<Long> have, long t) {
        Long near = have.ceiling(t - TOLERANCE_SECONDS);
        return near != null && near <= t + TOLERANCE_SECONDS;
    }
}
