"""
The Vakyam eye pipeline on OpenCV 5, for servers and desktops.

It follows the Android app's OpenCV 5 pipeline (app/src/main/java/com/demo/opencv/vision) stage by stage:
YuNet face detection, a quality gate (Laplacian sharpness, exposure, solvePnP head pose), CLAHE, one roll-aligned
warpAffine per eye, matchTemplate scoring against calibrated looks, and the same time-based dwell selector.

The one deliberate difference is where each eye crop is anchored. The phone uses ML Kit's eyelid contours,
which only exist on Android; here each eye is placed from YuNet's landmarks instead, by default relative to the
nose tip with offsets frozen while the user looks straight during calibration. See geometry.EyeAnchors.
"""

from .looks import Look, CALIBRATION_ORDER  # noqa: F401
from .pipeline import EyePipeline, PipelineConfig  # noqa: F401

__version__ = "0.1.0"
