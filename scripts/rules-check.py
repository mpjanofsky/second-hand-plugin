#!/usr/bin/env python3
"""Checks the Kiosk Satellite rules a script can check (the kit's RULES.md marks them [checked]).

Prints failures and warnings only; exits 1 on a failure. A line that breaks a rule on purpose carries
`// ks-rules: allow <rule> <reason>` with the reason written down where the project records its decisions.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / "kiosk-satellite-plugin.json").read_text())
sources = {p: p.read_text() for p in sorted((ROOT / "src").rglob("*.java"))}
fails, warns = [], []


def lines(pattern, rule):
    for path, text in sources.items():
        for n, line in enumerate(text.splitlines(), 1):
            if re.search(pattern, line) and "ks-rules: allow" not in line:
                fails.append("%s:%d: %s: %s" % (path.relative_to(ROOT), n, rule, line.strip()[:100]))


# Only the SDK: no reflection, no KS internals, no microphone capture.
lines(r"java\.lang\.reflect|Class\.forName\(|getDeclared(Field|Method|Constructor)|setAccessible\(", "reflection (SDK only)")
lines(r"\bme\.jxl\.(?!kiosk\.plugins\b)", "KS internal class (SDK only)")
lines(r"\b(AudioRecord|MediaRecorder)\b", "microphone capture (the SDK excludes it)")

# Own package.
for path, text in sources.items():
    m = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
    if m and m.group(1).startswith("me.jxl"):
        fails.append("%s: package %s: use your own package, not me.jxl.*" % (path.relative_to(ROOT), m.group(1)))

# Capabilities: declared exactly when used.
uses = {
    "overlay": r"\.(showOverlay|hideOverlay)\(",
    "screensaver": r"\.(publishScreensaver|publishScreensaverAsset|removeScreensaver)\(",
    "entities": r"\.(publishSensor|publishTextSensor|publishBinarySensor|publishSwitch|publishSelect)\(",
    "shizuku": r"\.(executeShizuku|shizukuState)\(",
    "native": r"\.nativeLibraryPath\(",
}
code = "\n".join(sources.values())
declared = set(manifest.get("capabilities", []))
for cap, pattern in uses.items():
    used = re.search(pattern, code) is not None
    if used and cap not in declared:
        fails.append("capability '%s' is used but not declared in the manifest" % cap)
    if cap in declared and not used:
        fails.append("capability '%s' is declared but not used (request only what the plugin uses)" % cap)
host_used = re.search(r"\.(executeCommand|subscribe)\(", code) is not None
host_declared = declared & {"host.read", "host.control"}
if host_used and not host_declared:
    fails.append("executeCommand/subscribe used but neither host.read nor host.control is declared")
if host_declared and not host_used:
    fails.append("%s declared but no executeCommand/subscribe call" % ", ".join(sorted(host_declared)))

# No host calls in stop(): access is already revoked.
for path, text in sources.items():
    m = re.search(r"void\s+stop\s*\(\s*\)[^{]*\{", text)
    if not m:
        continue
    depth, i = 1, m.end()
    while i < len(text) and depth:
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    body = text[m.end():i]
    if re.search(r"\bhost\s*\.\s*\w+\(", body) and "ks-rules: allow" not in body:
        warns.append("%s: stop() calls the host; KS has already revoked access (RULES.md Lifecycle)" % path.relative_to(ROOT))

# README: size and the not-affiliated line.
readme = ROOT / "README.md"
if readme.exists():
    text = readme.read_text()
    if len(text.encode()) > 128 * 1024:
        fails.append("README.md is over 128 KB")
    if not re.search(r"not affiliated", text, re.I):
        warns.append("README.md has no 'not affiliated with or endorsed by Kiosk Satellite' line")

for f in fails:
    print("RULES: " + f)
for w in warns:
    print("RULES (warning): " + w)
sys.exit(1 if fails else 0)
