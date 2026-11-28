#!/usr/bin/env python3
"""Fail closed if current-commit Android capture evidence is incomplete.

This gate validates the registered capture contract, not visual parity or all user interactions.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def check(registry, manifest, root, commit, expected_count=98):
    errors = []
    expected = [row["key"] for row in registry["screens"]]
    expected_set = set(expected)
    if len(expected) != expected_count or len(expected_set) != len(expected):
        errors.append("Invalid registry count or duplicate keys")
    pairs = collections.Counter(key.split("/", 1)[1] for key in expected_set)
    if any(n != 2 for n in pairs.values()) or {k.split("/", 1)[0] for k in expected_set} != {"light", "dark"}:
        errors.append("Registry must include both themes for every scenario")
    if not commit or manifest.get("commit") != commit:
        errors.append("Capture commit differs from requested commit")
    if manifest.get("coverage_complete") is not True or manifest.get("auth_complete") is not True:
        errors.append("Manifest reports incomplete coverage or authentication")
    by_key = collections.defaultdict(list)
    for record in manifest["screens"]:
        by_key[record["theme"] + "/" + record["screen"]].append(record)
    missing = sorted(expected_set - by_key.keys())
    unexpected = sorted(by_key.keys() - expected_set)
    if missing:
        errors.append("Missing captures: " + ", ".join(missing))
    if unexpected:
        errors.append("Unexpected captures: " + ", ".join(unexpected))
    valid = []
    for key in sorted(expected_set & by_key.keys()):
        records = by_key[key]
        if len(records) != 1 or records[0].get("status") != "captured":
            errors.append("Duplicate or failed capture: " + key)
            continue
        record = records[0]
        try:
            paths = {}
            for field in ("screenshot", "hierarchy", "layout"):
                path = (root / record[field]).resolve()
                if not path.is_relative_to(root.resolve()) or not path.is_file():
                    raise ValueError("Invalid or missing " + field)
                paths[field] = path
            png = paths["screenshot"].read_bytes()
            if not png.startswith(b"\x89PNG\r\n\x1a\n") or hashlib.sha256(png).hexdigest() != record.get("sha256"):
                raise ValueError("PNG signature/hash mismatch")
            if ET.parse(paths["hierarchy"]).getroot().tag != "hierarchy":
                raise ValueError("Invalid Android hierarchy")
            layout = json.loads(paths["layout"].read_text(encoding="utf-8"))
            if layout.get("schema") != 1 or not layout.get("roots"):
                raise ValueError("Invalid Compose layout")
            valid.append(key)
        except (OSError, ValueError, KeyError, ET.ParseError) as exc:
            errors.append(key + ": " + str(exc))
    return {"commit": commit, "expected": len(expected_set), "valid": len(valid),
            "missing": missing, "unexpected": unexpected, "errors": errors,
            "visual_parity_verified": False, "passed": not errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--handoff", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--expected-count", type=int, default=98)
    args = parser.parse_args()
    report = check(json.loads(args.registry.read_text(encoding="utf-8")),
                   json.loads((args.handoff / "manifest.json").read_text(encoding="utf-8")),
                   args.handoff, args.commit, args.expected_count)
    (args.handoff / "release-gate.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
