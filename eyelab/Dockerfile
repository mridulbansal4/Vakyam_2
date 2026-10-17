# Eye Lab service image. Build from the repository root, so the YuNet model can come from the Android app's assets:
#   docker build -f eyelab/Dockerfile -t vakyam-eyelab .
# Runs on x86_64 and on arm64 (AWS Graviton); every pinned wheel ships for both.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /srv/eyelab

COPY eyelab/requirements.txt .
RUN pip install -r requirements.txt

COPY app/src/main/assets/face_detection_yunet_2023mar.onnx models/
COPY eyelab/eyecore eyecore
COPY eyelab/server server

RUN useradd --system --uid 10001 eyelab
USER eyelab

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/healthz', timeout=2)"

# One worker: each session's calibration lives in that process's memory, and a WebSocket stays on one task.
CMD ["uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "8080", "--proxy-headers", "--forwarded-allow-ips", "*", "--ws-max-size", "262144"]
