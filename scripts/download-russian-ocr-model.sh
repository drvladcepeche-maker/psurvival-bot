#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="$ROOT/app/src/main/assets/tessdata/rus.traineddata"
URL="https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/rus.traineddata"
EXPECTED_SHA256="e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225"

mkdir -p "$(dirname "$TARGET")"
curl -fL --retry 3 --connect-timeout 30 "$URL" -o "$TARGET"
echo "$EXPECTED_SHA256  $TARGET" | sha256sum --check -
echo "Russian Tesseract model installed at: $TARGET"
