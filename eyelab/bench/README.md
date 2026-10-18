# COOL benchmark

Does the **Cloud-Optimized OpenCV Library (COOL)** make the Eye Lab's real per-frame work faster on AWS Graviton,
and what does that do to cost? This is the evidence for the Best Use of COOL award.

## Method

- **Same machine, same code, same inputs.** On one Graviton4 instance launched from the COOL AMI, the benchmark
  runs twice. The only difference is the OpenCV build:
  - `stock`: `opencv-python-headless==5.0.0.93` from PyPI, in a fresh virtual environment;
  - `cool`: COOL's own environment, `/opt/cool/venvs/python_3.12` (COOL 3.1, built on OpenCV 5.0).
- **The workload is the service's own.** [`cool_bench.py`](cool_bench.py) measures three things:
  1. **ops**: each OpenCV call the server makes per frame, single-threaded, median and p95. Those calls are JPEG
     decode, resize, colour conversion, YuNet, Laplacian, CLAHE, `warpAffine`, `matchTemplate` and `solvePnP`.
  2. **frame**: one whole frame, as the server processes it. That is decode, then YuNet, then the eyecore
     pipeline on a calibrated session (quality gate, CLAHE, eye crops, 14 template matches, dwell).
  3. **capacity**: one single-threaded worker per vCPU running whole frames flat out. It reports frames per
     second per instance, sessions at 15 fps, and USD per million frames.
- **Proof of which build ran.** Each result records `cv2.__file__` (`/opt/cool/...` for COOL), whether KleidiCV
  appears in `cv2.getBuildInformation()`, the CPU, and the EC2 instance type. The script also saves both builds'
  full build information next to the results.

## Run it on Graviton4

1. **Subscribe** in AWS Marketplace to *Cloud Optimized OpenCV for AWS Graviton4*. It has a 7-day free trial.
2. **Launch** an instance from that AMI: `c8g.xlarge` for a quick run, or `m8g.4xlarge` as the listing recommends.
   Connect with SSM Session Manager or SSH.
3. **Run** both builds and the comparison. The argument is the instance's on-demand USD/hour plus the COOL
   software fee, used for cost per million frames:

   ```bash
   sudo apt-get update && sudo apt-get install -y git python3.12-venv
   git clone https://github.com/mridulbansal4/Vakyam_2.git && cd Vakyam_2
   bash eyelab/bench/run_on_graviton.sh 0.0453
   ```

4. **Copy back** `eyelab/bench/results/<instance-type>/`: `results.md`, both JSON files, both build-info files and
   `cool-active.txt`. Commit them as the evidence.

Optional: run `cool_bench.py --label stock` on an x86 instance (for example `c7i.xlarge`) at a similar price. That
adds the Graviton-versus-x86 comparison the rubric mentions.

## Run COOL in production

The stack can run the Eye Lab itself on COOL. See [the deploy section](../README.md#deploy-to-aws):

```bash
npx aws-cdk@2.1145.0 deploy --app ".venv/Scripts/python app.py" -c compute=cool -c cool_ami=ami-...
```

Each instance installs the service into COOL's Python environment. It **refuses to start** unless `cv2` is loaded
from `/opt/cool`. Once it is serving, `GET /healthz` reports `"cool": true` and the COOL file path, and every
CloudWatch record carries `opencv_build: cool`.

## What to expect

A desktop run of the stock build (Windows, Intel x86, 20 threads, 4 workers) shows the shape of the workload. These
are not Graviton numbers:

| Operation | Median µs |
|---|---|
| YuNet detect 320x240 | 3502 |
| resize 480x360 → 320x240 | 372 |
| JPEG decode 480x360 | 240 |
| CLAHE on the face | 133 |
| cvtColor, face resize, Laplacian, solvePnP | 59-78 each |
| matchTemplate (×14 a frame), warpAffine (×2) | 15, 8 |
| **Whole frame** | **6475** |
| Capacity, 4 workers | 430 frames/s, 28 sessions at 15 fps |

**YuNet is more than half of each frame.** COOL's published gains are in image processing, core and I/O functions
(resize, thresholding, contours and similar). So expect large gains on resize, decode and conversion, and a smaller
whole-frame gain. That is unless COOL's build also speeds up the DNN module. The Graviton run measures this rather
than assuming it; report what it shows.

## Replaying a look test

With `EYELAB_RECORD_DIR=<folder>` set on a local server (never a shared one: it writes the user's face to disk),
each session is recorded from Calibrate to the end of the Look test. `python -m bench.replay <recording>` feeds the
same frames, at the same moments, through a fresh pipeline and prints the look test report; `--sweep` compares the
resting bias and sensitivity settings on that one face.
