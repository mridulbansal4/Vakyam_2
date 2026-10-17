"""
Desktop check of the OpenCV 5 eye path in the Android app, using the same steps and the same matrix maths:

    YuNet face  ->  CLAHE on the face region  ->  one warpAffine per eye (EyeCropGeometry.kt)
                ->  matchTemplate TM_CCOEFF_NORMED with a 2 px margin (EyeDetection.java)

It tilts a face photo by several angles, moves the eyelid contours with the image (what a perfect eyelid
tracker would report), and compares each eye crop with the upright one, with and without roll alignment.
Error is 1 - normalised cross-correlation: 0 = identical, about 1 = unrelated.

    python eval/alignment_check.py path/to/face.jpg

The face image is not in the repository; OpenCV's samples/data/lena.jpg is the one used in the report.
"""
import math
import pathlib
import sys

import cv2
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = ROOT / "app/src/main/assets/face_detection_yunet_2023mar.onnx"
W, H = 44, 18          # Model.IMAGE_WIDTH / IMAGE_HEIGHT
PADDING = 0.15         # EyeCropGeometry.PADDING
MARGIN = 2             # EyeDetection.TEMPLATE_MARGIN
TILTS = [5, 10, 15, -15, 25]


def eyelid_contour(cx, cy, width, height, roll, n=16):
    """An almond outline standing in for ML Kit's 16-point eyelid contour."""
    t = np.linspace(0, 2 * math.pi, n, endpoint=False)
    u, v = width / 2 * np.cos(t), height / 2 * np.sin(t)
    return np.float32(np.stack([cx + u * math.cos(roll) - v * math.sin(roll),
                                cy + u * math.sin(roll) + v * math.cos(roll)], axis=1))


def crop_transform(contour, roll, origin_x, origin_y):
    """EyeCropGeometry.fromContour(...).forwardMatrix(origin_x, origin_y)."""
    c, s = math.cos(roll), math.sin(roll)
    u = c * contour[:, 0] + s * contour[:, 1]
    v = -s * contour[:, 0] + c * contour[:, 1]
    mid_u, mid_v = (u.min() + u.max()) / 2, (v.min() + v.max()) / 2
    cx, cy = c * mid_u - s * mid_v, s * mid_u + c * mid_v
    scale = W / ((u.max() - u.min()) * (1 + 2 * PADDING))
    a, b, cc, d = scale * c, scale * s, -scale * s, scale * c
    sx, sy = origin_x - cx, origin_y - cy
    return np.float64([[a, b, a * sx + b * sy + W / 2], [cc, d, cc * sx + d * sy + H / 2]])


def eye_crops(image, contours, roll, face_box, clahe):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    x, y, w, h = face_box
    gx, gy = int(w * 0.15), int(h * 0.15)
    x0, y0 = max(0, x - gx), max(0, y - gy)
    x1, y1 = min(gray.shape[1], x + w + gx), min(gray.shape[0], y + h + gy)
    region = clahe.apply(gray[y0:y1, x0:x1])
    return [cv2.warpAffine(region, crop_transform(c, roll, x0, y0), (W, H),
                           flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE) for c in contours]


def template_error(live, stored):
    core = stored[MARGIN:H - MARGIN, MARGIN:W - MARGIN]
    return 1.0 - float(cv2.matchTemplate(live, core, cv2.TM_CCOEFF_NORMED).max())


def main(path):
    image = cv2.imread(path)
    if image is None:
        sys.exit(f"Cannot read {path}")
    detector = cv2.FaceDetectorYN.create(str(MODEL), "", (image.shape[1], image.shape[0]), 0.6, 0.3, 5)
    _, faces = detector.detect(image)
    if faces is None:
        sys.exit("No face found")
    face = faces[0]
    landmarks = face[4:14].reshape(5, 2)
    eyes = sorted([landmarks[0], landmarks[1]], key=lambda p: p[0])
    roll0 = math.atan2(eyes[1][1] - eyes[0][1], eyes[1][0] - eyes[0][0])
    spacing = math.dist(eyes[0], eyes[1])
    contours = [eyelid_contour(e[0], e[1], 0.55 * spacing, 0.22 * spacing, roll0) for e in eyes]
    box = [int(round(v)) for v in face[:4]]
    clahe = cv2.createCLAHE(2.0, (4, 4))  # Model.CLAHE_CLIP_LIMIT, CLAHE_TILES
    upright = eye_crops(image, contours, roll0, box, clahe)

    print(f"OpenCV {cv2.__version__}, face score {face[14]:.2f}, eye spacing {spacing:.0f} px")
    print(f"{'tilt':>6} | {'aligned L':>9} {'aligned R':>9} | {'unaligned L':>11} {'unaligned R':>11} | {'wrong eye':>9}")
    for angle in TILTS:
        m = cv2.getRotationMatrix2D((image.shape[1] / 2, image.shape[0] / 2), angle, 1.0)
        tilted = cv2.warpAffine(image, m, (image.shape[1], image.shape[0]))
        moved = [cv2.transform(c[None], m)[0] for c in contours]
        pts = np.vstack(moved)
        moved_box = (int(pts[:, 0].min() - box[2] * 0.3), int(pts[:, 1].min() - box[3] * 0.4), box[2], box[3])
        roll = roll0 - math.radians(angle)
        aligned = eye_crops(tilted, moved, roll, moved_box, clahe)
        unaligned = eye_crops(tilted, moved, 0.0, moved_box, clahe)
        print(f"{angle:+4d}deg | {template_error(aligned[0], upright[0]):9.3f} {template_error(aligned[1], upright[1]):9.3f} | "
              f"{template_error(unaligned[0], upright[0]):11.3f} {template_error(unaligned[1], upright[1]):11.3f} | "
              f"{template_error(aligned[0], upright[1]):9.3f}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
