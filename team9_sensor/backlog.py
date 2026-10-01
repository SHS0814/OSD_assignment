"""폰의 과거 데이터 요청 처리.

폰이 요청 광고로 want_ts를 보내면 그 시각에 가장 가까운 샘플을 골라 두고,
광고 슬롯을 실시간 / 과거로 번갈아 채운다. 요청이 timeout초 동안 안 보이면
다시 실시간만 보낸다. D-Bus와 무관한 순수 로직이라 맥에서도 테스트할 수 있다.
"""
from packet import build_no_data, build_payload


class Backlog:
    def __init__(self, store, tolerance, timeout):
        self.store = store
        self.tolerance = tolerance
        self.timeout = timeout
        self.want_ts = None
        self.payload = None
        self.last_seen = None
        self._backlog_turn = False

    def on_request(self, want_ts, now):
        """요청 수신. 과거 슬롯의 페이로드가 바뀌었으면 True."""
        self.last_seen = now
        if want_ts == 0:
            sample = self.store.oldest()
        else:
            sample = self.store.nearest(want_ts, self.tolerance)
        payload = build_payload(sample) if sample else build_no_data(want_ts)
        changed = want_ts != self.want_ts or payload != self.payload
        self.want_ts = want_ts
        self.payload = payload
        return changed

    def active(self, now):
        return self.payload is not None and now - self.last_seen <= self.timeout

    def next_payload(self, live_payload, now):
        """이번 슬롯에 광고할 페이로드."""
        if not self.active(now):
            self._backlog_turn = False
            return live_payload
        self._backlog_turn = not self._backlog_turn
        return self.payload if self._backlog_turn else live_payload
