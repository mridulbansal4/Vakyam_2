# Testing instructions

## 1. Try the live Eye Lab (about 5 minutes, no install)

Open **https://eyelab.duckdns.org** on a laptop with a webcam, in Chrome or Edge. Sit about an arm's length from
the screen, with your face lit from the front.

1. Press **Start camera** and allow the webcam. Nothing is recorded: frames are processed in memory and dropped.
2. **Calibrate.** Follow the circle on screen and hold each look until its bar fills; seven looks are captured
   hands-free, including closed eyes. Calibration refuses blurred, dark or look-alike captures and asks again.
3. **Calibration Coach** (Agentic Vision). Press **Calibration Coach**. The agent measures the camera, the templates
   and every look with OpenCV 5, then acts: it tells you what to fix, re-records one look, or asks the caregiver.
   It asks before changing any setting (**Done** / **I can't**). Turn on **Under the hood** to watch its
   measurements, decisions and tool calls.
4. **Look test.** Press **Look test** (one minute) to see which looks are read as which.
5. **Answer a question with your eyes.** In **Quick chat** or **Type**, type a caregiver question such as *What
   would you like to drink?*. Choose words by looking at the tiles and closing your eyes ½–1 s to select. The
   service writes a full sentence from the chosen words with Gemini and reads it aloud with Amazon Polly.

No webcam, or the camera struggles? Press **No webcam? Use the tiles**: every tile is also a button, so the whole
flow, including the sentence and speech, works by clicking.

If Gemini is unavailable the sentence comes from the built-in fallback, labelled on screen; the app never returns
a bare word.

## 2. Run and test it locally (Python 3.12)

```bash
git clone https://github.com/mridulbansal4/Vakyam_2 && cd Vakyam_2
python -m venv .venv
.venv/Scripts/pip install -r eyelab/requirements-dev.txt        # .venv/bin/pip on macOS/Linux
.venv/Scripts/python -m uvicorn server.app:app --app-dir eyelab --port 8080
```

Open http://localhost:8080. Everything works without keys; set `GEMINI_API_KEY` to use Gemini.

Tests (103, including the Calibration Coach on simulated faults and a synthetic face driven through every look):

```bash
cd eyelab && ../.venv/Scripts/python -m pytest -q
```

## 3. Deploy it to your own AWS account

Infrastructure as code in `eyelab/infra` (AWS CDK, Python). Store the Gemini key once in Secrets Manager as
`vakyam/gemini-api-key`, then from `eyelab/infra`:

```bash
npx aws-cdk@2.1145.0 deploy --app ".venv/Scripts/python app.py"                 # Fargate + ALB + CloudFront
npx aws-cdk@2.1145.0 deploy --app ".venv/Scripts/python app.py" -c compute=ec2 -c domain=<your-domain> -c eip_allocation=<eipalloc-id>
```

The second form is how the live endpoint runs: one Graviton instance on a fixed IP with automatic HTTPS. Full
details in `eyelab/README.md`.

## 4. The Android app

APK for 64-bit ARM phones (Android 8.0+):
https://github.com/mridulbansal4/Vakyam_2/releases/download/v2.0-opencv26/Vakyam-2.0-arm64.apk

Install it, allow the camera and follow Calibrate. The OpenCV 5 pipeline is under **Settings → Vision pipeline**.
The on-device Gemma model is too large to bundle; copy it with
`adb push gemma-4-E2B-it.litertlm /sdcard/Android/data/com.vakyam.app/files/`. Without it, sentences come from the
built-in fallback. To build it yourself: `./gradlew assembleDebug`. See the main `README.md`.
