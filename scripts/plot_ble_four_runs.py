"""Render four BLE CSV experiments as matching RSSI/timeline charts.

Run from the assignment directory:
    MPLCONFIGDIR=tmp/ble_analysis/mplcache python3 scripts/plot_ble_four_runs.py

The charts use app CSV callbacks consistently. HCI-only findings have separate
figures, because HCI logs were available only for the two September 19 runs.
"""

import csv
import math
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "tmp/ble_analysis"
OUTPUT = ROOT / "output"
ZONE = ZoneInfo("Asia/Seoul")
SLOT_SECONDS = 10
STALE_SECONDS = 60
COLORS = {
    "first": "#2F6FBB",
    "other": "#E07B19",
    "missing": "#DC6565",
    "foreground": "#F0F6FC",
    "background": "#FFF1E8",
    "boundary": "#525C67",
}

RUNS = [
    {
        "name": "2026-09-17",
        "csv": DATA / "ble_data_20260917_124617.csv",
        "output": OUTPUT / "ble_timeline_uniform_20260917.png",
        "change_minutes": 5,
        "before": "앱 화면 유지",
        "after": "다른 앱으로 전환",
    },
    {
        "name": "2026-09-18",
        "csv": DATA / "ble_data_20260918_123319.csv",
        "output": OUTPUT / "ble_timeline_uniform_20260918.png",
        "change_minutes": 5,
        "before": "앱 화면 유지",
        "after": "다른 앱 + Foreground Service",
    },
    {
        "name": "2026-09-19 1차",
        "csv": DATA / "ble_data_20260919_134238.csv",
        "output": OUTPUT / "ble_timeline_uniform_20260919_first.png",
        "single": "다른 앱 + Foreground Service · 절전 모드 해제",
    },
    {
        "name": "2026-09-19 2차 (절전 모드)",
        "csv": ROOT.parent / "ble_data_20260919_144455.csv",
        "output": OUTPUT / "ble_timeline_uniform_20260919_second.png",
        "change_at": "2026-09-19 14:22:45.653",
        "before": "화면 켜짐",
        "after": "화면 꺼짐",
    },
]


def local_epoch(text):
    return datetime.fromisoformat(text).replace(tzinfo=ZONE).timestamp()


def load_rows(path):
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"CSV가 비어 있습니다: {path}")
    for row in rows:
        row["time"] = local_epoch(row["received_at"])
        row["sensor_ts"] = int(row["sensor_unix_timestamp"])
        row["rssi_value"] = int(row["rssi"])
    rows.sort(key=lambda row: row["time"])
    return rows


def classify(rows):
    fresh, stale = [], []
    for row in rows:
        if row["time"] - row["sensor_ts"] > STALE_SECONDS:
            stale.append(row)
        else:
            fresh.append(row)
    first, repeated = [], []
    seen = set()
    for row in fresh:
        if row["sensor_ts"] in seen:
            repeated.append(row)
        else:
            first.append(row)
            seen.add(row["sensor_ts"])
    if not first:
        raise ValueError("새 측정값이 없어 측정 슬롯을 만들 수 없습니다.")

    first_sensor = min(seen)
    slot_for = lambda sensor_ts: round((sensor_ts - first_sensor) / SLOT_SECONDS)
    slot_ids = {slot_for(sensor_ts) for sensor_ts in seen}
    max_clock_error = max(abs(sensor_ts - first_sensor - slot_for(sensor_ts) * SLOT_SECONDS)
                          for sensor_ts in seen)
    if max_clock_error > 2 or len(slot_ids) != len(seen):
        raise ValueError("센서 시각을 10초 슬롯에 일대일로 정렬할 수 없습니다.")
    expected = list(range(max(slot_ids) + 1))
    missing = [index for index in expected if index not in slot_ids]
    return first, repeated + stale, first_sensor, slot_ids, expected, missing


def valid_rssi(value):
    return -127 <= value <= 20


def plot_run(config):
    rows = load_rows(config["csv"])
    first, other, first_sensor, received_slots, expected, missing = classify(rows)
    start = rows[0]["time"]
    end = rows[-1]["time"]
    duration = end - start
    change_time = (start + config["change_minutes"] * 60
                   if "change_minutes" in config else
                   local_epoch(config["change_at"]) if "change_at" in config else None)
    change_x = (change_time - start) / 60 if change_time is not None else None
    change_slot = (round((change_time - first_sensor) / SLOT_SECONDS)
                   if change_time is not None else None)
    x_max = max(11, math.ceil(duration / 60))
    if x_max - duration / 60 < 0.4:
        x_max += 1
    x_tick_step = 1 if x_max <= 13 else 2
    y_min, y_max = -91, -62

    fig, axis = plt.subplots(figsize=(14, 7.15))
    if change_x is None:
        axis.axvspan(0, x_max, color=COLORS["background"], alpha=0.67, zorder=0)
    else:
        axis.axvspan(0, change_x, color=COLORS["foreground"], alpha=0.72, zorder=0)
        axis.axvspan(change_x, x_max, color=COLORS["background"], alpha=0.72, zorder=0)
        axis.axvline(change_x, color=COLORS["boundary"], linestyle="--",
                     linewidth=1.7, zorder=3)
    axis.vlines([(first_sensor + index * SLOT_SECONDS - start) / 60
                 for index in missing], y_min, y_max,
                color=COLORS["missing"], linewidth=1.05, alpha=0.48, zorder=1)
    axis.grid(axis="y", color="#D7DADD", linewidth=0.75, alpha=0.9)
    axis.spines[["top", "right"]].set_visible(False)

    def scatter(group, marker, color, size):
        visible = [row for row in group if valid_rssi(row["rssi_value"])]
        axis.scatter([(row["time"] - start) / 60 for row in visible],
                     [row["rssi_value"] for row in visible],
                     s=size, marker=marker, color=color,
                     edgecolor="white", linewidth=0.7, zorder=4)
        return len(group) - len(visible)

    unknown_rssi = scatter(first, "o", COLORS["first"], 47)
    unknown_rssi += scatter(other, "s", COLORS["other"], 45)

    def top_label(x, label, received, total, callbacks, color):
        percent = received / total if total else 0
        axis.text(x, y_max - 0.65,
                  f"{label}  |  {received}/{total} 수신 ({percent:.0%})  |  콜백 {callbacks}건",
                  ha="center", va="top", color=color, fontsize=10.1)

    if change_x is None:
        top_label(x_max / 2, config["single"], len(first), len(expected), len(rows), "#A45424")
    else:
        before_slots = min(max(change_slot, 0), len(expected))
        before_received = sum(index < before_slots for index in received_slots)
        before_callbacks = sum(row["time"] < change_time for row in rows)
        top_label(change_x / 2, config["before"], before_received,
                  before_slots, before_callbacks, "#315D8C")
        top_label((change_x + x_max) / 2, config["after"],
                  len(first) - before_received, len(expected) - before_slots,
                  len(rows) - before_callbacks, "#A45424")

    axis.set(xlim=(0, x_max), ylim=(y_min, y_max),
             xticks=list(range(0, x_max + 1, x_tick_step)),
             yticks=[-90, -85, -80, -75, -70, -65],
             xlabel="수집 시작 후 경과 시간 (분)", ylabel="RSSI (dBm)")
    axis.set_title(f"실제 BLE 콜백 {len(rows)}건과 누락된 10초 측정 슬롯",
                   loc="left", pad=13, fontweight="bold")

    legend = [
        Line2D([], [], marker="o", linestyle="None", markersize=7,
               markerfacecolor=COLORS["first"], markeredgecolor="white",
               label=f"새 측정값 첫 수신 ({len(first)}건)"),
        Line2D([], [], marker="s", linestyle="None", markersize=7,
               markerfacecolor=COLORS["other"], markeredgecolor="white",
               label=f"새 측정값 외 콜백 ({len(other)}건)"),
        Line2D([], [], color=COLORS["missing"], linewidth=2,
               label=f"앱 수신 없는 10초 슬롯 ({len(missing)}개)"),
    ]
    axis.legend(handles=legend, loc="lower left", frameon=False,
                ncols=3, fontsize=9.8, columnspacing=1.8)

    minutes, seconds = divmod(round(duration), 60)
    fig.suptitle(f"BLE 수신 타임라인 — {config['name']} 전체 수집 구간",
                 x=0.065, y=0.982, ha="left", fontsize=17)
    summary = (f"수신 기록 {minutes}분 {seconds:02d}초 · 콜백 {len(rows)}건 · "
               f"고유 측정값 {len(first)}/{len(expected)}개 수신 ({len(first)/len(expected):.0%})")
    if unknown_rssi:
        summary += f" · RSSI 미상 {unknown_rssi}건 점 제외"
    fig.text(0.065, 0.927, summary, color="#535A63", fontsize=10.6)
    fig.subplots_adjust(left=0.065, right=0.985, top=0.81, bottom=0.115)
    config["output"].parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(config["output"], dpi=180, facecolor="white")
    plt.close(fig)
    print(f"{config['name']}: callbacks={len(rows)}, first={len(first)}, "
          f"other={len(other)}, "
          f"received={len(first)}/{len(expected)}, missing={len(missing)}, "
          f"output={config['output']}")


if __name__ == "__main__":
    plt.rcParams.update({"font.family": ["AppleGothic", "DejaVu Sans"],
                         "axes.unicode_minus": False, "font.size": 11})
    for run in RUNS:
        plot_run(run)
