#!/usr/bin/env bash
# Run on an EC2 Graviton4 instance launched from the "Cloud Optimized OpenCV for AWS Graviton4" AMI (Ubuntu 24.04).
# Same machine, same code, same inputs; only the OpenCV build changes:
#   stock - opencv-python-headless 5.0.0 from PyPI, in a fresh venv
#   cool  - COOL's own Python 3.12 environment at /opt/cool/venvs/python_3.12
#
#   git clone https://github.com/mridulbansal4/Vakyam_2.git && cd Vakyam_2
#   bash eyelab/bench/run_on_graviton.sh 0.0453        # the instance's USD/hour, for cost per 1M frames
#
# Results land in eyelab/bench/results/<instance-type>/: stock.json, cool.json, results.md, and the build
# information of both, which shows that COOL's cv2 was the one loaded.
set -euo pipefail

PRICE="${1:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
EYELAB="$(dirname "$HERE")"
COOL_VENV=/opt/cool/venvs/python_3.12

test -d "$COOL_VENV" || { echo "No COOL environment at $COOL_VENV: launch this on the COOL Graviton4 AMI." >&2; exit 1; }
TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60" || true)
ITYPE=$(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/instance-type || echo local)
OUT="$HERE/results/$ITYPE"
mkdir -p "$OUT"

echo "== stock OpenCV 5.0.0 from PyPI"
python3.12 -m venv /tmp/stock-venv
/tmp/stock-venv/bin/pip install -q -r "$HERE/requirements-stock.txt"
(cd "$EYELAB" && /tmp/stock-venv/bin/python bench/cool_bench.py --label stock --out "$OUT/stock.json")
/tmp/stock-venv/bin/python -c "import cv2; print(cv2.getBuildInformation())" > "$OUT/stock-build-info.txt"

echo "== COOL"
# shellcheck disable=SC1091
source "$COOL_VENV/bin/activate"
python -c "import numpy" 2>/dev/null || pip install -q "numpy==2.2.6"
python -c "import cv2; print(f'Active OpenCV: {cv2.__version__} from {cv2.__file__}')" | tee "$OUT/cool-active.txt"
(cd "$EYELAB" && python bench/cool_bench.py --label cool --out "$OUT/cool.json")
python -c "import cv2; print(cv2.getBuildInformation())" > "$OUT/cool-build-info.txt"
deactivate

python3.12 "$HERE/compare.py" "$OUT/stock.json" "$OUT/cool.json" ${PRICE:+--price-per-hour "$PRICE"} --out "$OUT/results.md"
echo "Wrote $OUT"
