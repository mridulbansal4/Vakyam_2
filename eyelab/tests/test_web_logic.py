"""Runs the page's JavaScript unit tests (tests/js/web_logic.test.js) with Node, when Node is installed."""

import pathlib
import shutil
import subprocess

import pytest

TEST = pathlib.Path(__file__).resolve().parent / "js" / "web_logic.test.js"


@pytest.mark.skipif(shutil.which("node") is None, reason="Node.js is not installed")
def test_page_logic():
    result = subprocess.run(["node", str(TEST)], capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stdout + result.stderr
