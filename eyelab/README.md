# Eye Lab

The Vakyam eye pipeline as a web service on AWS: open a page, calibrate seven looks with your webcam, answer a
question with your eyes, and hear the sentence Claude writes from the words you chose. It lets anyone try Vakyam
without an Android phone, and it is the OpenCV 5 + AWS half of the
[OpenCV AI Competition 2026](../docs/hackathon/opencv-aws-2026.md) entry.

```mermaid
flowchart LR
    B["Browser<br/>webcam · 480 px JPEG<br/>≤ 15 fps, one in flight"] -- "WSS" --> CF["CloudFront<br/>HTTPS"]
    CF -- "HTTP + secret header" --> ALB["Application<br/>Load Balancer<br/>403 otherwise"]
    ALB --> T["ECS Fargate · Graviton arm64<br/>FastAPI + eyecore (OpenCV 5)"]
    T -- "keywords + question" --> BR["Amazon Bedrock<br/>Claude"]
    T -- "sentence" --> P["Amazon Polly"]
    T -- "EMF metrics, no images" --> CW["CloudWatch<br/>dashboard + alarms"]
```

## What runs per frame (`eyecore/`)

The same stages as the phone's OpenCV 5 pipeline
([`VisionPipeline`](../app/src/main/java/com/demo/opencv/vision/VisionPipeline.kt)), ported to Python:

| Stage | OpenCV 5 | Module |
|---|---|---|
| Find the face and 5 landmarks | `cv2.FaceDetectorYN` (YuNet, the model bundled in the app) on a 320 px copy | [`face.py`](eyecore/face.py) |
| Check the image | variance of the Laplacian, exposure and backlight, head pose from `cv2.solvePnP` (SQPnP) | [`quality.py`](eyecore/quality.py) |
| Normalise light | `cv2.createCLAHE(2.0, (4, 4))` on the face region | [`pipeline.py`](eyecore/pipeline.py) |
| Cut each eye | one `cv2.warpAffine` per eye, rotated by head roll, scaled by eye distance | [`geometry.py`](eyecore/geometry.py) |
| Match the calibrated looks | `cv2.matchTemplate` `TM_CCOEFF_NORMED`, both eyes summed, clear-winner rule | [`matching.py`](eyecore/matching.py) |
| Turn looks into choices | the phone's time-based dwell selector, ported line for line | [`dwell.py`](eyecore/dwell.py) |
| Calibrate hands-free | settle, steady hold, refuse look-alike and bad-quality captures | [`calibration.py`](eyecore/calibration.py) |

**One difference from the phone:** ML Kit's eyelid contours only exist on Android, so each eye crop is placed from
YuNet's landmarks. By default (`anchor_mode="frozen"`) each eye centre is stored relative to the nose tip, in axes
set by the eye line and scaled by the eye distance, while the user looks straight during calibration. A look moves
both eye points the same way, so neither the eye line nor the nose moves, and the crop stays put on the face.
`anchor_mode="landmark"` centres the crops on YuNet's eye points instead. On the synthetic faces in `tests/` both
classify every look correctly, even when the eye points follow the pupils fully; real-camera data should decide
between them.

Closed eyes are a seventh calibrated template here, because ML Kit's eye-open probability is phone-only.

## Run it locally

Python 3.12. From the repository root:

```bash
python -m venv .venv
.venv/Scripts/pip install -r eyelab/requirements-dev.txt     # .venv/bin/pip on macOS/Linux
.venv/Scripts/python -m uvicorn server.app:app --app-dir eyelab --port 8080
```

Open <http://localhost:8080>. Browsers allow the webcam on `localhost`; anywhere else the page needs HTTPS, which
CloudFront provides in the deployed stack. Without AWS credentials everything works except Claude and Polly: the
sentence comes from the same built-in fallback the phone uses, and the browser's own voice reads it.

With credentials (`aws login`, and Claude enabled under Amazon Bedrock model access), Claude writes the sentence.

## Test

```bash
cd eyelab
../.venv/Scripts/python -m pytest -q
```

67 tests: the pipeline stages, a drawn face (`tests/synthetic.py`) calibrated and driven through every look under
head tilt and movement in both anchor modes, the WebSocket service with a stand-in for Claude, the guard rails and
fallbacks, and the CloudWatch metric format. Set `EYELAB_FACE_IMAGE=/path/to/face.jpg` to also run YuNet and the
quality meter on a real photo.

## Deploy to AWS

Needs Docker (with arm64 emulation if you are not on an arm64 machine), Node 18+, AWS credentials, and Claude
enabled under **Amazon Bedrock → Model access** in the target region.

```bash
cd eyelab/infra
python -m venv .venv && .venv/Scripts/pip install -r requirements.txt
npx aws-cdk@2.1145.0 bootstrap
npx aws-cdk@2.1145.0 deploy --app ".venv/Scripts/python app.py"
```

The stack prints `EyeLabUrl` (the HTTPS page), `DashboardName` and `LogGroupName`. Context values
(`-c bedrock_model=…`, `-c max_sessions=…`, `-c polly_voice=…`) override the defaults. `cdk destroy` removes it.

| Resource | Why |
|---|---|
| VPC, 2 public subnets, no NAT | the task reaches AWS APIs directly; no NAT bill |
| ECS Fargate, arm64 (Graviton), 1 vCPU / 2 GB, 1-4 tasks on CPU | one uvicorn worker per task; sessions live in memory |
| Application Load Balancer, 300 s idle timeout | long-lived WebSockets; default action is 403 |
| CloudFront, caching off, all viewer headers forwarded | HTTPS for the webcam; carries the WebSocket upgrade; adds the header the ALB checks |
| Task role: `bedrock-mantle:CreateInference`, `polly:SynthesizeSpeech` | nothing else |
| CloudWatch log group (30 days), dashboard, two alarms | frame time p95 over 150 ms; five Claude failures in five minutes |
| Deployment circuit breaker with rollback, 100% minimum healthy | a bad image never replaces a working one; deploys never drop sessions |

## Responsible operation

- **No images are kept.** Frames are decoded, processed in memory and dropped. Logs carry numbers only:
  frame times, rates and counts, never images, keywords or sentences.
- **Abuse limits:** at most 20 sessions per task (`EYELAB_MAX_SESSIONS`), 200 KB per frame, bursts above twice
  the 15 fps pace refused, sessions closed after 120 s idle, keywords limited to four short words of letters.
- **Never silent:** if Claude is unavailable, slow, refuses, or returns a single word or an echo of the keywords,
  the user still gets a complete sentence from the phone's built-in fallback, labelled as such on screen.
- **Human in control:** every tile is also a button; a caregiver can always choose for the user.
- **Not a medical device**, and the demo is not a replacement for a calibrated AAC system.

## Configuration

| Variable | Default | |
|---|---|---|
| `EYELAB_BEDROCK_MODEL` | `anthropic.claude-opus-5-5` | Claude model on Bedrock |
| `EYELAB_BEDROCK_REGION` | `AWS_REGION`, else `us-east-1` | |
| `EYELAB_SENTENCE_TIMEOUT_S` | `8` | then the fallback sentence |
| `EYELAB_POLLY`, `EYELAB_POLLY_VOICE` | `0`, `Joanna` | `1` in the AWS stack |
| `EYELAB_MAX_SESSIONS`, `EYELAB_MAX_FPS`, `EYELAB_MAX_FRAME_BYTES` | `20`, `15`, `204800` | |
| `EYELAB_SESSION_IDLE_S` | `120` | |
| `EYELAB_DEBUG_CROPS_EVERY` | `3` | eye crops sent to the "Under the hood" panel every N frames |
| `EYELAB_MODEL_PATH` | bundled YuNet model | |

## Measured so far

- **Server time per frame:** 6.6 ms at 480 px with real YuNet, on a desktop CPU (Windows, x86). The round trip
  over localhost was 9 ms, and a frame is about 32 KB of JPEG. Not yet measured on Graviton.
- **Synthetic faces:** in both anchor modes, all six choosable looks are selected correctly with the head
  upright, tilted ±10-12°, moved, or both.
- **Not measured yet:** accuracy on real webcams and real users, which needs recorded sessions; latency of
  Claude on Bedrock and of Polly, which needs the deployed stack.
