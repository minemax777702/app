#!/usr/bin/env python3
"""Build a Pixso change plan against the last *verified design*, never the previous CI run."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--registry", type=Path, required=True)
    p.add_argument("--handoff", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    registry = json.loads(a.registry.read_text(encoding="utf-8"))
    manifest = json.loads((a.handoff / "manifest.json").read_text(encoding="utf-8"))
    if registry.get("schema") != 1:
        raise ValueError("Unsupported Pixso registry schema")
    known = {s["key"]: s for s in registry["screens"]}
    if len(known) != len(registry["screens"]):
        raise ValueError("Duplicate screen key")
    frame_ids = [s["frame_id"] for s in known.values() if s.get("frame_id")]
    if len(frame_ids) != len(set(frame_ids)):
        raise ValueError("Duplicate Pixso frame ID")
    captured = {}
    for record in manifest["screens"]:
        key = record["theme"] + "/" + record["screen"]
        if key in captured:
            raise ValueError("Duplicate capture: " + key)
        captured[key] = record
    a.output.mkdir(parents=True, exist_ok=True)
    items = []
    for key in sorted(known.keys() | captured.keys()):
        s = known.get(key)
        r = captured.get(key)
        row = {"key": key, "frame_id": s.get("frame_id") if s else None,
               "page": s.get("page") if s else None,
               "section": s.get("section") if s else None}
        if not r or r.get("status") != "captured":
            row["action"] = "preserve_missing_capture"
            items.append(row)
            continue
        paths = {}
        for field in ("screenshot", "hierarchy", "layout"):
            name = r.get(field)
            if not name:
                raise ValueError(f"Missing {field}: {key}")
            src = (a.handoff / name).resolve()
            if not src.is_relative_to(a.handoff.resolve()) or not src.is_file():
                raise ValueError(f"Invalid capture path: {name}")
            paths[field] = src
        hashes = {field: sha(path) for field, path in paths.items()}
        if hashes["screenshot"] != r["sha256"]:
            raise ValueError("PNG checksum mismatch: " + key)
        layout = json.loads(paths["layout"].read_text(encoding="utf-8"))
        if layout.get("schema") != 1 or not layout.get("roots"):
            raise ValueError("Invalid Compose layout: " + key)
        row["hashes"] = hashes
        verified = s.get("last_verified") if s else None
        if not s:
            action = "register_new"
        elif not row["frame_id"]:
            action = "create"
        elif s.get("status") == "verified" and verified and verified.get("hashes") == hashes:
            action = "unchanged"
        else:
            action = "update_in_place"
        row["action"] = action
        if action != "unchanged":
            row["files"] = {}
            for field, src in paths.items():
                relative = src.relative_to(a.handoff.resolve())
                dest = a.output / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, dest)
                row["files"][field] = str(relative)
        items.append(row)
    counts = {action: sum(r["action"] == action for r in items)
              for action in sorted({r["action"] for r in items})}
    result = {"schema": 1, "file_key": registry["file_key"],
              "commit": manifest["commit"], "version": manifest["version"],
              "coverage_complete": manifest.get("coverage_complete", False),
              "counts": counts, "screens": items}
    (a.output / "plan.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    shutil.copyfile(a.handoff / "manifest.json", a.output / "manifest.json")
    print(json.dumps(counts, ensure_ascii=False))

if __name__ == "__main__":
    main()
