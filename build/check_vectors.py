"""Fail early if any vector drawable has a malformed arc command.

Android's VectorDrawable parser throws at inflate time (which crashes the app
on launch) when an `a`/`A` command is not followed by a multiple of 7 numbers,
for example `0 015,5` where the two flags run into the x value. This scans the
source vectors so the mistake is caught at build time instead.

Run:  python3 build/check_vectors.py [res-dir]
"""
import glob
import re
import sys

DEFAULT_RES = "android/app/src/main/res"


def arc_run_is_valid(path_data: str) -> bool:
    i, n = 0, len(path_data)
    while i < n:
        if path_data[i] in "aA":
            j = i + 1
            run = ""
            while j < n and (path_data[j].isdigit() or path_data[j] in ".,-+ eE"):
                run += path_data[j]
                j += 1
            nums = re.findall(r"-?\d*\.?\d+(?:[eE][-+]?\d+)?", run)
            if len(nums) % 7 != 0:
                return False
            i = j
        else:
            i += 1
    return True


def main() -> int:
    res_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RES
    bad = []
    for path in glob.glob(f"{res_dir}/**/*.xml", recursive=True):
        text = open(path, encoding="utf-8", errors="ignore").read()
        for match in re.finditer(r'pathData="([^"]*)"', text):
            if not arc_run_is_valid(match.group(1)):
                bad.append(path)
                break
    for path in sorted(set(bad)):
        print(f"malformed arc in vector drawable: {path}")
    if bad:
        print(f"{len(set(bad))} file(s) would crash at launch")
        return 1
    print("vector pathData OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
