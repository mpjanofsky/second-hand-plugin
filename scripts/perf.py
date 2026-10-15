#!/usr/bin/env python3
"""Measure what the plugin costs the kiosk: Kiosk Satellite's CPU, memory and temperature over time, read only.

Usage: perf.py <label> [minutes=10] [interval_s=15]
  Run once with the plugin disabled (label "off") and once enabled ("on"), same screen state, then compare the
  summaries. Samples go to private/perf/<label>-<time>.csv (private/, not published). Needs `adb connect` first.
  Reads only: dumpsys cpuinfo/meminfo and thermal zones. Changes nothing on the device.
"""
import csv, datetime, re, statistics, subprocess, sys, time
from pathlib import Path

PKG = "me.jxl.kiosk_satellite"


def adb(*args):
    return subprocess.run(["adb", "shell", *args], capture_output=True, text=True, timeout=20).stdout


_last = {}


def cpu_pct():
    """KS CPU over the last interval, in % of one core, from its process CPU time (utime + stime, in clock ticks).
    dumpsys cpuinfo is a rolling average and too coarse to compare plugin on and off."""
    pid = adb("pidof", PKG).strip().split()
    fields = adb("cat", "/proc/%s/stat" % pid[0]).rsplit(")", 1)[-1].split() if pid else []
    if len(fields) < 13:
        return None
    ticks, now = int(fields[11]) + int(fields[12]), time.time()
    prev = _last.get(pid[0])
    _last.clear()
    _last[pid[0]] = (ticks, now)
    return round((ticks - prev[0]) / 100.0 / (now - prev[1]) * 100, 1) if prev else None  # USER_HZ = 100 on Android


def sample():
    # meminfo: "TOTAL PSS:   302116" in KB
    mem = re.search(r"TOTAL PSS:\s+(\d+)", adb("dumpsys", "meminfo", PKG))
    temps = [int(t) for t in re.findall(r"^-?\d+$", adb("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null"), re.M)]
    hottest = max(temps) / (1000 if temps and max(temps) > 1000 else 1) if temps else None
    return (cpu_pct(), int(mem.group(1)) // 1024 if mem else None, hottest)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    label = sys.argv[1]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", label):
        sys.exit("label: letters, digits, - and _ only")
    # Bounded so a typo can't hammer the kiosk or run all night.
    minutes = min(float(sys.argv[2]) if len(sys.argv) > 2 else 10, 240)
    interval = max(float(sys.argv[3]) if len(sys.argv) > 3 else 15, 5)
    devices = [l.split()[0] for l in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip().endswith("device")]
    if len(devices) != 1:
        sys.exit("connect exactly one device with adb (found %d)" % len(devices))
    print("sampling %s every %ds for %g min" % (devices[0], interval, minutes))
    out = Path(__file__).resolve().parents[1] / "private" / "perf"
    out.mkdir(parents=True, exist_ok=True)
    path = out / ("%s-%s.csv" % (label, datetime.datetime.now().strftime("%Y%m%d-%H%M")))
    rows, end = [], time.time() + minutes * 60
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["time", "ks_cpu_pct", "ks_pss_mb", "hottest_c"])
        while time.time() < end:
            row = sample()
            rows.append(row)
            w.writerow([datetime.datetime.now().isoformat(timespec="seconds"), *row])
            f.flush()
            time.sleep(interval)

    def stat(i, name, unit):
        vals = [r[i] for r in rows if r[i] is not None]
        if not vals:
            return "%s: no data" % name
        return "%s median %.1f%s, max %.1f%s" % (name, statistics.median(vals), unit, max(vals), unit)

    print("%s: %d samples -> %s" % (label, len(rows), path.relative_to(path.parents[2])))
    print("  " + "; ".join([stat(0, "KS CPU", "%"), stat(1, "KS memory", " MB"), stat(2, "hottest zone", " C")]))
    if rows and rows[0][1] and rows[-1][1]:
        print("  memory change over the run: %+d MB" % (rows[-1][1] - rows[0][1]))


main()
