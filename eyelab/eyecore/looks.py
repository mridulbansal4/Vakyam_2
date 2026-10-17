"""Look tags, shared with the Android app (Model.tags and LookData) so logs and results line up."""

from enum import IntEnum


class Look(IntEnum):
    STRAIGHT = 0
    LEFT = 1
    RIGHT = 2
    UP = 3
    CLOSED = 5
    LEFT_UP = 6
    RIGHT_UP = 7

    @property
    def label(self) -> str:
        return self.name.replace("_", "-").lower()


# Android calibrates the six open-eye looks; here closed eyes are a seventh template, because ML Kit's
# eye-open probability is not available off the phone.
CALIBRATION_ORDER = (Look.STRAIGHT, Look.LEFT, Look.RIGHT, Look.UP, Look.LEFT_UP, Look.RIGHT_UP, Look.CLOSED)

CALIBRATION_PROMPTS = {
    Look.STRAIGHT: "Look at the centre of the screen.",
    Look.LEFT: "Look at the left edge of the screen.",
    Look.RIGHT: "Look at the right edge of the screen.",
    Look.UP: "Look at the top edge of the screen.",
    Look.LEFT_UP: "Look at the top left corner.",
    Look.RIGHT_UP: "Look at the top right corner.",
    Look.CLOSED: "Close your eyes until you hear the beep.",
}
