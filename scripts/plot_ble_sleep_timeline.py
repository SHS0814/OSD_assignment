"""Plot the screen-off BLE experiment from its CSV and HCI snoop log.

Run from the assignment directory:
    MPLCONFIGDIR=tmp/ble_analysis/mplcache python3 scripts/plot_ble_sleep_timeline.py
"""

import argparse
import csv
import struct
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

ROOT = Path(__file__).resolve().parents[1]
LOCAL_ZONE = ZoneInfo("Asia/Seoul")
HCI_CLOCK_OFFSET_SECONDS = 9 * 3600
SNOOP_EPOCH_DELTA = 0x00DC_DDB3_0F2F_8000
TARGET_ADDRESS = bytes.fromhex("2e89c1dd3ad8")
TARGET_UUID = b"\x1a\x18"
SAMPLE_PERIOD_SECONDS = 10
SCAN_REDUCED_AT = "2026-09-19 14:17:12.225"
SCREEN_OFF_AT = "2026-09-19 14:22:45.653"
DEEP_IDLE_AT = "2026-09-19 14:24:48.170"


def epoch(local_time):
    return datetime.fromisoformat(local_time).replace(tzinfo=LOCAL_ZONE).timestamp()


def valid_rssi(value):
    return -127 <= value <= 20


def ad_service_data(data):
    offset = 0
    while offset < len(data):
        length = data[offset]
        if not length or offset + length + 1 > len(data):
            break
        if (length >= 3 and data[offset + 1] == 0x16
                and data[offset + 2:offset + 4] == TARGET_UUID):
            service = data[offset + 4:offset + length + 1]
            if len(service) == 13:
                return service
        offset += length + 1
    return None


def target_hci_reports(path):
    content = path.read_bytes()
    if content[:8] != b"btsnoop\0":
        raise ValueError("btsnoop 헤더를 찾지 못했습니다.")
    reports = []
    offset = 16
    while offset + 24 <= len(content):
        _, included_length, _, _, snoop_time = struct.unpack_from(">IIIIQ", content, offset)
        offset += 24
        packet = content[offset:offset + included_length]
        offset += included_length
        if len(packet) < 5 or packet[:2] != b"\x04\x3e" or packet[3] != 0x0D:
            continue
        position = 5
        for _ in range(packet[4]):
            if position + 24 > len(packet):
                raise ValueError("잘린 LE Extended Advertising Report")
            address = packet[position + 3:position + 9]
            rssi = struct.unpack_from("b", packet, position + 13)[0]
            data_length = packet[position + 23]
            data = packet[position + 24:position + 24 + data_length]
            position += 24 + data_length
            if address == TARGET_ADDRESS:
                reports.append({
                    "raw_time": (snoop_time - SNOOP_EPOCH_DELTA) / 1_000_000,
                    "rssi": rssi,
                    "service": ad_service_data(data),
                })
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, default=ROOT.parent / "ble_data_20260919_144455.csv")
    parser.add_argument("--hci", type=Path,
                        default=ROOT / "tmp/ble_analysis/btsnoop_hci_20260919_sleep.log")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "output/ble_sleep_timeline_20260919.png")
    args = parser.parse_args()

    with args.csv.open(encoding="utf-8", newline="") as handle:
        app = list(csv.DictReader(handle))
    if not app:
        raise ValueError("CSV에 수신 기록이 없습니다.")
    for row in app:
        row["time"] = epoch(row["received_at"])
        row["sensor_ts"] = int(row["sensor_unix_timestamp"])
        row["rssi_value"] = int(row["rssi"])
        row["payload"] = bytes.fromhex(row["service_data_hex"])
    app.sort(key=lambda row: row["time"])
    start = app[0]["time"]
    end = app[-1]["time"]

    ads = []
    for report in target_hci_reports(args.hci):
        if report["service"] is None:
            continue
        report_time = report["raw_time"] - HCI_CLOCK_OFFSET_SECONDS
        if not start - 10 <= report_time <= end + 1:
            continue
        ads.append({"time": report_time,
                    "sensor_ts": int.from_bytes(report["service"][9:13], "little"),
                    "rssi": report["rssi"],
                    "payload": report["service"]})
    if not ads:
        raise ValueError("실험 시간대의 대상 장치 HCI 광고가 없습니다.")

    # Validate that the HCI clock correction matches every app callback.
    for row in app:
        matches = [ad for ad in ads
                   if ad["payload"] == row["payload"]
                   and 0 <= row["time"] - ad["time"] < 0.1]
        if len(matches) != 1:
            raise ValueError(f"HCI 시각 보정 또는 앱/HCI 매칭 실패: {row['received_at']}")

    first_app, repeated_app = [], []
    seen_app = set()
    for row in app:
        if row["sensor_ts"] in seen_app:
            repeated_app.append(row)
        else:
            first_app.append(row)
            seen_app.add(row["sensor_ts"])

    hci_only = {}
    for ad in ads:
        if ad["sensor_ts"] not in seen_app:
            hci_only.setdefault(ad["sensor_ts"], ad)
    all_sensor_times = seen_app | set(hci_only)
    first_sensor = min(all_sensor_times)
    last_sensor = max(all_sensor_times)

    # The sender clock drifts by up to two seconds during this run. Snap each
    # sensor timestamp to the nearest nominal ten-second measurement slot.
    slot_for = lambda sensor_ts: round((sensor_ts - first_sensor) / SAMPLE_PERIOD_SECONDS)
    slot_ids = {slot_for(sensor_ts) for sensor_ts in all_sensor_times}
    if len(slot_ids) != len(all_sensor_times):
        raise ValueError("서로 다른 센서 시각이 같은 10초 슬롯에 배정됐습니다.")
    if max(abs(sensor_ts - first_sensor - slot_for(sensor_ts) * SAMPLE_PERIOD_SECONDS)
           for sensor_ts in all_sensor_times) > 2:
        raise ValueError("센서 시각이 예상 10초 측정 주기에서 2초 넘게 벗어났습니다.")
    slots = range(slot_for(last_sensor) + 1)
    missing = [index for index in slots if index not in slot_ids]

    scan_reduced = epoch(SCAN_REDUCED_AT)
    screen_off = epoch(SCREEN_OFF_AT)
    deep_idle = epoch(DEEP_IDLE_AT)
    on_slots = sum(first_sensor + index * SAMPLE_PERIOD_SECONDS < screen_off for index in slots)
    off_slots = len(slots) - on_slots
    app_on = sum(sensor_ts < screen_off for sensor_ts in seen_app)
    hci_on = sum(sensor_ts < screen_off for sensor_ts in all_sensor_times)
    app_off = len(seen_app) - app_on
    hci_off = len(all_sensor_times) - hci_on

    plt.rcParams.update({
        "font.family": ["AppleGothic", "DejaVu Sans"],
        "axes.unicode_minus": False,
        "font.size": 11,
    })
    fig, axis = plt.subplots(figsize=(15, 7.6))
    x_end = 32.0
    y_bottom, y_top = -91, -61.5
    minutes_from_start = lambda timestamp: (timestamp - start) / 60
    x_reduced = minutes_from_start(scan_reduced)
    x_off = minutes_from_start(screen_off)
    x_idle = minutes_from_start(deep_idle)

    axis.axvspan(0, x_off, color="#edf4fb", alpha=0.68, zorder=0)
    axis.axvspan(x_off, x_end, color="#fff0e7", alpha=0.72, zorder=0)
    axis.vlines([minutes_from_start(first_sensor + index * SAMPLE_PERIOD_SECONDS)
                 for index in missing], y_bottom, y_top,
                color="#db6464", linewidth=0.95, alpha=0.34, zorder=1)

    def scatter(rows, marker, color, size, time_key, rssi_key, zorder):
        visible = [row for row in rows if valid_rssi(row[rssi_key])]
        axis.scatter([minutes_from_start(row[time_key]) for row in visible],
                     [row[rssi_key] for row in visible],
                     marker=marker, s=size, color=color, edgecolor="white",
                     linewidth=0.75, zorder=zorder)
        return len(rows) - len(visible)

    unknown_count = scatter(first_app, "o", "#2f6fbb", 50, "time", "rssi_value", 5)
    unknown_count += scatter(repeated_app, "s", "#e07b19", 44,
                             "time", "rssi_value", 6)
    unknown_count += scatter(list(hci_only.values()), "D", "#7256a8", 47,
                             "time", "rssi", 7)

    axis.axvline(x_reduced, color="#4b78a8", linestyle="--", linewidth=1.7, alpha=0.9, zorder=3)
    axis.axvline(x_off, color="#c05b32", linewidth=2.4, alpha=0.95, zorder=3)
    axis.axvline(x_idle, color="#a46b44", linestyle="--", linewidth=1.7, alpha=0.9, zorder=3)
    axis.text(x_reduced + 0.16, y_top - 1.1, "스캔 축소\n100% → 10.9%",
              color="#35618f", va="top", fontsize=10, fontweight="bold")
    axis.text(x_off + 0.18, y_top - 1.1, "화면 꺼짐\n10.9% → 5.1%",
              color="#a14b28", va="top", fontsize=10, fontweight="bold")
    axis.text(x_idle + 0.18, y_top - 4.2, "깊은 유휴 진입",
              color="#865737", va="top", fontsize=10)

    axis.grid(axis="y", color="#d7d7d7", linewidth=0.75, alpha=0.9)
    axis.spines[["top", "right"]].set_visible(False)
    axis.set_xlim(0, x_end)
    axis.set_ylim(y_bottom, y_top)
    axis.set_xticks(range(0, 33, 2))
    axis.set_yticks([-90, -85, -80, -75, -70, -65])
    axis.set_xlabel("수집 시작 후 경과 시간 (분)")
    axis.set_ylabel("RSSI (dBm)")
    axis.set_title("실제 BLE 콜백과 누락된 10초 측정 슬롯",
                   loc="left", pad=13, fontweight="bold")

    legend = [
        Line2D([], [], marker="o", linestyle="None", markersize=7,
               markerfacecolor="#2f6fbb", markeredgecolor="white",
               label=f"앱: 새 측정값 첫 수신 ({len(first_app)}개)"),
        Line2D([], [], marker="s", linestyle="None", markersize=7,
               markerfacecolor="#e07b19", markeredgecolor="white",
               label=f"앱: 같은 측정값 반복 콜백 ({len(repeated_app)}건)"),
        Line2D([], [], marker="D", linestyle="None", markersize=7,
               markerfacecolor="#7256a8", markeredgecolor="white",
               label=f"HCI에만 있는 새 측정값 ({len(hci_only)}개)"),
        Line2D([], [], color="#db6464", linewidth=2,
               label=f"앱·HCI 모두 없는 10초 슬롯 ({len(missing)}개)"),
    ]
    axis.legend(handles=legend, loc="upper center", bbox_to_anchor=(0.5, -0.105),
                frameon=False, ncols=2, columnspacing=2.3, fontsize=10)

    fig.suptitle("BLE 수신 타임라인 — 2026-09-19 절전 모드 수집",
                 x=0.065, y=0.982, ha="left", fontsize=18, fontweight="bold")
    fig.text(0.065, 0.915,
             f"CSV 130건 · 새 측정값 앱 {len(seen_app)}/{len(slots)}개 ({len(seen_app)/len(slots):.0%}) · "
             f"HCI 포함 {len(all_sensor_times)}/{len(slots)}개 ({len(all_sensor_times)/len(slots):.0%}) · "
             f"수신 기록 {(end-start)/60:.1f}분",
             color="#535b64", fontsize=11)
    fig.text(0.065, 0.885,
             f"화면 켜짐: 앱 {app_on}/{on_slots}, HCI 포함 {hci_on}/{on_slots}   |   "
             f"화면 꺼짐: 앱 {app_off}/{off_slots}, HCI 포함 {hci_off}/{off_slots}"
             + (f"   |   RSSI 미상 {unknown_count}건은 점에서 제외" if unknown_count else ""),
             color="#656b72", fontsize=10)
    fig.subplots_adjust(left=0.065, right=0.987, top=0.815, bottom=0.19)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.output, dpi=180, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    print(f"{args.output}: app_first={len(first_app)}, app_repeat={len(repeated_app)}, "
          f"hci_only={len(hci_only)}, missing={len(missing)}, slots={len(slots)}, "
          f"on={app_on}/{hci_on}/{on_slots}, off={app_off}/{hci_off}/{off_slots}, "
          f"unknown_rssi={unknown_count}")


if __name__ == "__main__":
    main()
