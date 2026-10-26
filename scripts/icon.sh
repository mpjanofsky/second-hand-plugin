#!/bin/bash
# Render the Community Plugins icon: docs/icon.svg -> assets/icon.png (256x256, transparent corners).
#   scripts/icon.sh            render and check size (256x256 PNG, at most 256 KB)
#   scripts/icon.sh banner     also render docs/banner.svg -> docs/banner.png (for a quick look; the README uses the SVG)
# Needs Google Chrome (headless). The SVG lives in docs/, not assets/, because assets/ is packaged whole.
# Material Icons glyphs are Apache-2.0: credit them in NOTICE. A kit.json build that stages files must copy assets/.
set -eu
cd "$(dirname "$0")/.."
chrome=${CHROME:-}
for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" google-chrome chromium; do
  [ -n "$chrome" ] && break
  if [ -x "$c" ] || command -v "$c" >/dev/null 2>&1; then chrome=$c; fi
done
[ -n "$chrome" ] || { echo "icon.sh: no Chrome found (set CHROME=/path/to/chrome)" >&2; exit 1; }
[ -f docs/icon.svg ] || { echo "icon.sh: docs/icon.svg is missing" >&2; exit 1; }

render() { # <svg> <png> <width> <height>
  local tmp; tmp=$(mktemp -d)
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --window-size="$3,$4" \
    --default-background-color=00000000 --screenshot="$tmp/out.png" "file://$PWD/$1" >/dev/null 2>&1 || true
  [ -s "$tmp/out.png" ] || { echo "icon.sh: Chrome produced no image for $1" >&2; rm -rf "$tmp"; exit 1; }
  mkdir -p "$(dirname "$2")"; mv "$tmp/out.png" "$2"; rm -rf "$tmp"
}

render docs/icon.svg assets/icon.png 256 256
size=$(wc -c < assets/icon.png | tr -d ' ')
dims=$(python3 - <<'PY'
import struct
d = open("assets/icon.png", "rb").read(32)
print(*struct.unpack(">II", d[16:24]))
PY
)
[ "$dims" = "256 256" ] || { echo "icon.sh: assets/icon.png is $dims, need 256 256 (give docs/icon.svg width, height 256)" >&2; exit 1; }
[ "$size" -le 262144 ] || { echo "icon.sh: assets/icon.png is $size bytes, over 256 KB" >&2; exit 1; }
echo "assets/icon.png: 256x256, $size bytes"
if [ "${1:-}" = banner ] && [ -f docs/banner.svg ]; then
  render docs/banner.svg docs/banner.png 900 340; echo "docs/banner.png rendered"
fi
