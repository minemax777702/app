#!/usr/bin/env python3
"""Compare real captures to the most recent available Beta handoff."""
import json
import os
import pathlib
import subprocess
import tempfile

root = pathlib.Path("ui-handoff")
current = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
repo = os.environ["GITHUB_REPOSITORY"]
run_id = int(os.environ["GITHUB_RUN_ID"])

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True, timeout=45)

def index(manifest):
    return {(s["theme"], s["screen"]): s for s in manifest["screens"] if s["status"] == "captured"}

previous = None
baseline_run = None
try:
    runs = json.loads(gh("api", f"repos/{repo}/actions/workflows/figma-redesign-apk.yml/runs?branch=beta&status=completed&per_page=20"))["workflow_runs"]
    for run in runs:
        if run["id"] == run_id:
            continue
        artifacts = json.loads(gh("api", f"repos/{repo}/actions/runs/{run['id']}/artifacts"))["artifacts"]
        if not any(a["name"] == "fresh-cut-ui-handoff" and not a["expired"] for a in artifacts):
            continue
        with tempfile.TemporaryDirectory() as tmp:
            gh("run", "download", str(run["id"]), "--repo", repo, "--name", "fresh-cut-ui-handoff", "--dir", tmp)
            path = pathlib.Path(tmp) / "manifest.json"
            if path.exists():
                previous = json.loads(path.read_text(encoding="utf-8"))
                baseline_run = run["id"]
                break
except (subprocess.SubprocessError, OSError, ValueError, KeyError) as e:
    print(f"Baseline unavailable: {e}")

old, new = index(previous or {"screens": []}), index(current)
changes = []
for key, screen in sorted(new.items()):
    prior = old.get(key)
    status = "baseline" if previous is None else "new" if prior is None else "changed" if screen["sha256"] != prior["sha256"] else "unchanged"
    changes.append({"theme":key[0],"screen":key[1],"status":status,"screenshot":screen["screenshot"]})
for key in sorted(old.keys() - new.keys()):
    changes.append({"theme":key[0],"screen":key[1],"status":"not_captured_in_current_run"})
report = {"baseline_run":baseline_run,"baseline_commit":(previous or {}).get("commit"),"current_commit":current.get("commit"),"changes":changes}
(root/"changes.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
lines = ["# Visual changes", "", f"Baseline: {report['baseline_commit'] or 'none; first available capture'}", "", "Comparison uses exact PNG hashes. Dynamic content may cause differences; missing captures are not proof of screen removal.", "", "| Theme | Screen | Status | Preview |", "|---|---|---|---|"]
for s in changes:
    link = f"[PNG]({s['screenshot']})" if "screenshot" in s else "—"
    lines.append(f"| {s['theme']} | {s['screen']} | {s['status']} | {link} |")
(root/"CHANGES.md").write_text("\n".join(lines),encoding="utf-8")
