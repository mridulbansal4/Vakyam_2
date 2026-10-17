"""
Compare two cool_bench.py results (baseline first) as a Markdown report.

    python bench/compare.py stock.json cool.json --price-per-hour 0.0453 --out results.md

--price-per-hour is the instance's on-demand price plus any software fee, for the cost per million frames.
"""

from __future__ import annotations

import argparse
import json
import pathlib


def load(path: str) -> dict:
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def describe(env: dict) -> str:
    where = "COOL (/opt/cool)" if env["cool_build"] else "stock"
    return (f"OpenCV {env['opencv_version']} {where}, KleidiCV in build info: "
            f"{'yes' if env['kleidicv_in_build'] else 'no'}, {env['instance_type'] or env['machine']}, "
            f"{env['vcpus']} vCPU, {env['cpu']}, Python {env['python']}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--price-per-hour", type=float, help="instance price in USD/hour, for cost per 1M frames")
    parser.add_argument("--out")
    args = parser.parse_args()
    base, cand = load(args.baseline), load(args.candidate)
    b_name, c_name = base["environment"]["label"], cand["environment"]["label"]

    lines = [f"# {c_name} vs {b_name}", "",
             f"- **{b_name}:** {describe(base['environment'])}",
             f"- **{c_name}:** {describe(cand['environment'])}",
             f"- `cv2` loaded from `{cand['environment']['cv2_file']}`", "",
             "## Per call, single thread (median us, lower is better)", "",
             f"| Operation | {b_name} | {c_name} | Speed-up |", "|---|---|---|---|"]
    speedups = []
    for name, b in base["ops"].items():
        c = cand["ops"].get(name)
        if not c:
            continue
        s = b["median_us"] / c["median_us"] if c["median_us"] else float("nan")
        speedups.append(s)
        lines.append(f"| {name} | {b['median_us']:.1f} | {c['median_us']:.1f} | **{s:.2f}x** |")
    geo = 1.0
    for s in speedups:
        geo *= s
    geo **= 1 / len(speedups)
    bf, cf = base["frame"]["median_us"], cand["frame"]["median_us"]
    lines += [f"| **whole frame** | **{bf:.0f}** | **{cf:.0f}** | **{bf / cf:.2f}x** |", "",
              f"Geometric mean over the {len(speedups)} operations: **{geo:.2f}x**.", "",
              "## Capacity, all cores (frames per second, higher is better)", "",
              f"| | {b_name} | {c_name} |", "|---|---|---|",
              f"| Frames per second | {base['capacity']['frames_per_second']} | {cand['capacity']['frames_per_second']} |",
              f"| Sessions at 15 fps | {base['capacity']['sessions_at_15fps']} | {cand['capacity']['sessions_at_15fps']} |"]
    if args.price_per_hour:
        def per_million(r):
            return args.price_per_hour / (r["capacity"]["frames_per_second"] * 3600) * 1e6
        lines.append(f"| USD per 1M frames at ${args.price_per_hour}/h | {per_million(base):.4f} | "
                     f"{per_million(cand):.4f} |")
    report = "\n".join(lines) + "\n"
    print(report)
    if args.out:
        pathlib.Path(args.out).write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
