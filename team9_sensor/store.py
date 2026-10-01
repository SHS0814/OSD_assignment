"""측정값 저장소 (SQLite).

폰이 과거 시각을 요청하면 여기서 가장 가까운 샘플을 찾아 광고한다.
과제 검증용 CSV는 ble_beacon.py가 그대로 따로 남긴다.
"""
import csv
import os
import sqlite3

COLUMNS = ("timestamp", "temp", "humidity", "AQI", "TVOC", "eCO2", "validity")


class SampleStore:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.execute("""
            CREATE TABLE IF NOT EXISTS samples (
                ts       INTEGER PRIMARY KEY,
                temp     REAL    NOT NULL,
                humidity REAL    NOT NULL,
                aqi      INTEGER NOT NULL,
                tvoc     INTEGER NOT NULL,
                eco2     INTEGER NOT NULL,
                validity TEXT
            )""")
        self.db.commit()

    def add(self, d):
        self.db.execute(
            "INSERT OR IGNORE INTO samples VALUES (?, ?, ?, ?, ?, ?, ?)",
            (d["timestamp"], d["temp"], d["humidity"], d["AQI"], d["TVOC"],
             d["eCO2"], d.get("validity")))
        self.db.commit()

    def count(self):
        return self.db.execute("SELECT COUNT(*) FROM samples").fetchone()[0]

    def import_csv(self, csv_path):
        """DB가 비어 있을 때 기존 sensor_log.csv를 한 번 가져온다. 가져온 행 수 반환."""
        if self.count() > 0 or not os.path.exists(csv_path):
            return 0
        with open(csv_path, newline="") as f:
            rows = [(int(r["timestamp"]), float(r["temp"]), float(r["humidity"]),
                     int(r["AQI"]), int(r["TVOC"]), int(r["eCO2"]), r.get("validity"))
                    for r in csv.DictReader(f)]
        self.db.executemany(
            "INSERT OR IGNORE INTO samples VALUES (?, ?, ?, ?, ?, ?, ?)", rows)
        self.db.commit()
        return len(rows)

    def nearest(self, ts, tolerance):
        """ts에서 tolerance초 안의 가장 가까운 샘플. 없으면 None."""
        before = self.db.execute(
            "SELECT * FROM samples WHERE ts <= ? ORDER BY ts DESC LIMIT 1", (ts,)).fetchone()
        after = self.db.execute(
            "SELECT * FROM samples WHERE ts >= ? ORDER BY ts ASC LIMIT 1", (ts,)).fetchone()
        best = min((r for r in (before, after) if r), key=lambda r: abs(r[0] - ts),
                   default=None)
        if best is None or abs(best[0] - ts) > tolerance:
            return None
        return self._to_dict(best)

    def oldest(self):
        row = self.db.execute(
            "SELECT * FROM samples ORDER BY ts ASC LIMIT 1").fetchone()
        return self._to_dict(row) if row else None

    def close(self):
        self.db.close()

    @staticmethod
    def _to_dict(row):
        return dict(zip(COLUMNS, row))
