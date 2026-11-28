#!/usr/bin/env python3
"""Sync mapped Compose palette with the canonical Pixso file, using a three-way baseline."""
import argparse
import asyncio
import json
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / "design/pixso-sync.json"
BASELINE = ROOT / "design/pixso-baseline.json"
THEME = ROOT / "app/src/main/java/com/mok/buylist/ui/theme/Theme.kt"
SCRIPT = ROOT / "scripts/pixso-snapshot.js"
MODES = ("light", "dark")
# Multiple Compose roles intentionally share one semantic design token.
ROLES = {
    "Background": ("header", "background", "sheet", "widgetBg"),
    "Surface": ("card",),
    "Field": ("headerTrack", "boughtBg", "chipBg", "field"),
    "Text": ("onHeader", "text"),
    "Text secondary": ("textSecondary", "ring", "iconMuted"),
    "Border": ("chipBorder",),
    "Brand": ("headerFill", "accent", "chipText"),
    "Danger": ("error", "danger"),
}
EXTRA = {
    "light": {"onAccent": "White fixed", "onDanger": "White fixed"},
    "dark": {"onAccent": "On accent", "onDanger": "Background"},
}
HEX = r"[0-9A-Fa-f]{6}"

def load(path):
    return json.loads(path.read_text(encoding="utf-8"))

def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

def config(registry):
    if registry["schema"] != 2:
        raise ValueError("Expected canonical schema 2")
    return {
        "collection": registry["theme_collection"]["id"],
        "modes": registry["theme_collection"]["modes"],
        "targets": [{"key": t["key"], "frame_id": t["frame_id"]} for t in registry["targets"]],
        "components": {k: v["id"] for k, v in registry["shared_components"].items()},
    }

def fields(mode):
    result = {field: "Theme/" + token for token, names in ROLES.items() for field in names}
    result.update({field: "Theme/" + token for field, token in EXTRA[mode].items()})
    return result

def palette_blocks(source):
    blocks = {}
    for mode in MODES:
        pattern = r"(val " + mode.title() + r"Palette = ShoppingPalette\(\n)(.*?)(\n\))"
        matches = list(re.finditer(pattern, source, re.S))
        if len(matches) != 1:
            raise ValueError("Unexpected Kotlin palette structure: " + mode)
        blocks[mode] = matches[0]
    return blocks

def code_values(source):
    result = {}
    def add(token, mode, value):
        value = value.upper()
        values = result.setdefault(token, {})
        if mode in values and values[mode] != value:
            raise ValueError("Compose roles disagree for " + token + "/" + mode)
        values[mode] = value
    for mode, block in palette_blocks(source).items():
        for field, token in fields(mode).items():
            hits = re.findall(r"(?m)^\s*" + field + r" = Color\(0xFF(" + HEX + r")\),\s*$", block[2])
            if len(hits) != 1:
                raise ValueError("Missing or duplicate Kotlin role " + mode + "/" + field)
            add(token, mode, hits[0])
    for field, token in {"actionFill":"Theme/Accent", "actionInk":"Theme/On accent"}.items():
        hits = re.findall(r"val " + field + r": Color get\(\) = Color\(0xFF(" + HEX + r")\)", source)
        if len(hits) != 1:
            raise ValueError("Unexpected Kotlin action color: " + field)
        for mode in MODES:
            add(token, mode, hits[0])
    return result

def render(source, tokens):
    # Fixed action properties are currently shared across modes in ShoppingPalette.
    for name in ("Theme/Accent", "Theme/On accent"):
        if tokens[name]["light"] != tokens[name]["dark"]:
            raise ValueError("Theme-specific action colors need a Compose model change: " + name)
    for mode, block in reversed(list(palette_blocks(source).items())):
        body = block[2]
        for field, token in fields(mode).items():
            value = tokens[token][mode]
            body, n = re.subn(r"(?m)^(\s*" + field + r" = Color\(0xFF)" + HEX + r"(\),\s*)$", lambda m:m[1]+value+m[2], body)
            if n != 1:
                raise ValueError("Cannot safely patch " + field)
        source = source[:block.start(2)] + body + source[block.end(2):]
    for field, token in {"actionFill":"Theme/Accent", "actionInk":"Theme/On accent"}.items():
        source, n = re.subn(r"(val " + field + r": Color get\(\) = Color\(0xFF)" + HEX + r"(\))", lambda m:m[1]+tokens[token]["light"]+m[2], source)
        if n != 1:
            raise ValueError("Cannot patch " + field)
    return source

def compare(base, live, code):
    structural = {}
    for section in ("targets", "components"):
        structural[section] = sorted(k for k in base[section].keys() | live[section].keys() if base[section].get(k) != live[section].get(k))
    structural["pages"] = base["pages"] != live["pages"]
    conflicts, unmapped, pulls, pushes = [], [], [], []
    merged = json.loads(json.dumps(live["tokens"]))
    for key in sorted(base["tokens"].keys() | live["tokens"].keys()):
        old, new = base["tokens"].get(key), live["tokens"].get(key)
        if not old or not new or old["id"] != new["id"]:
            conflicts.append({"token":key,"reason":"token added, removed or replaced"})
            continue
        for mode in MODES:
            b, p = old[mode], new[mode]
            c = code.get(key, {}).get(mode)
            if c is None:
                if p != b:
                    unmapped.append({"token":key,"mode":mode,"before":b,"pixso":p})
                continue
            if p != b and c != b and p != c:
                conflicts.append({"token":key,"mode":mode,"base":b,"pixso":p,"code":c})
            elif p != c and p != b:
                pulls.append({"token":key,"mode":mode,"before":c,"after":p})
            elif p != c and c != b:
                pushes.append({"token":key,"mode":mode,"before":p,"after":c})
                merged[key][mode] = c
    blocked = bool(conflicts or unmapped or structural["targets"] or structural["components"] or structural["pages"])
    return {"structural":structural,"conflicts":conflicts,"unmapped":unmapped,"pulls":pulls,"pushes":pushes,"blocked":blocked}, merged

def result_json(result):
    if result.isError:
        raise RuntimeError("Pixso tool returned an error")
    data = result.structuredContent
    if not data:
        for item in result.content:
            if item.type == "text":
                try:
                    data = json.loads(item.text)
                    if isinstance(data, str):
                        data = json.loads(data)
                    if isinstance(data, dict):
                        break
                except (ValueError, TypeError):
                    continue
    if not isinstance(data, dict) or data.get("error") or data.get("verified") is False:
        raise RuntimeError("Invalid Pixso tool response")
    return data

async def run(args):
    from mcp import ClientSession
    from mcp.client.streamable_http import streamablehttp_client
    registry, base = load(REGISTRY), load(BASELINE)
    cfg = config(registry)
    token = os.environ.get("PIXSO_ACCESS_TOKEN")
    if not token:
        raise RuntimeError("Configure repository secret PIXSO_ACCESS_TOKEN")
    source = THEME.read_text(encoding="utf-8")
    code = code_values(source)
    async with streamablehttp_client("https://pixso.net/mcp", headers={"Token":token}) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            available = await session.list_tools()
            tool = next((t for t in available.tools if t.name == "eval_script"), None)
            if not tool:
                raise RuntimeError("Remote MCP does not expose eval_script for this account")
            props = tool.inputSchema.get("properties", {})
            file_arg = next((x for x in ("file_key","fileKey") if x in props), None)
            script_arg = next((x for x in ("script","code") if x in props), None)
            if not file_arg or not script_arg:
                raise RuntimeError("Unsupported Pixso eval_script input schema")
            async def call(script):
                return result_json(await session.call_tool(tool.name, {file_arg:registry["file_key"],script_arg:script}))
            snapshot = "const CONFIG=" + json.dumps(cfg, ensure_ascii=False) + ";\n" + SCRIPT.read_text(encoding="utf-8")
            live = await call(snapshot)
            dump(args.output / "pixso-live.json", live)
            report, merged = compare(base, live, code)
            report["applied"] = False
            dump(args.output / "report.json", report)
            if report["blocked"]:
                print("Design review required; no changes applied.")
                return 2
            updated_source = render(source, merged)
            if code_values(updated_source) != {k:{m:merged[k][m] for m in v} for k,v in code.items()}:
                raise RuntimeError("Kotlin round-trip check failed")
            if not args.apply:
                print("Read-only check complete.")
                return 0
            if report["pushes"]:
                # Re-read the whole baseline before mutation. Guard each token in the same remote script.
                if await call(snapshot) != live:
                    raise RuntimeError("Pixso changed during planning; retry")
                payload = [{"id":live["tokens"][x["token"]]["id"],"mode":cfg["modes"][x["mode"]],"before":x["before"],"after":x["after"]} for x in report["pushes"]]
                js = """const changes=PAYLOAD, api=pixso.variables, prepared=[];
const hex=v=>[v.r,v.g,v.b].map(x=>Math.round(x*255).toString(16).padStart(2,"0")).join("").toUpperCase();
for(const ch of changes){const v=await api.getVariableByIdAsync(ch.id);if(!v||hex(v.valuesByMode[ch.mode])!==ch.before)throw Error("Concurrent token edit");prepared.push([v,ch]);}
for(const [v,ch] of prepared){const h=ch.after;v.setValueForMode(ch.mode,{r:parseInt(h.slice(0,2),16)/255,g:parseInt(h.slice(2,4),16)/255,b:parseInt(h.slice(4,6),16)/255,a:1});}
for(const [v,ch] of prepared)if(hex(v.valuesByMode[ch.mode])!==ch.after)throw Error("Token readback mismatch");
return {verified:true,count:prepared.length};"""
                ack = await call("const PAYLOAD="+json.dumps(payload)+";\n"+js)
                if ack.get("count") != len(payload):
                    raise RuntimeError("Pixso write count mismatch")
            after = await call(snapshot)
            if after["tokens"] != merged or any(after[k]!=live[k] for k in ("targets","components","pages")):
                raise RuntimeError("Concurrent design change or unexpected write; do not advance baseline")
            if updated_source != source:
                THEME.write_text(updated_source, encoding="utf-8")
            if after != base:
                dump(BASELINE, after)
            report["applied"] = True
            dump(args.output / "report.json", report)
            print("Mapped palette synchronized.")
            return 0

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT/"pixso-sync-report")
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args)))

if __name__ == "__main__":
    main()
