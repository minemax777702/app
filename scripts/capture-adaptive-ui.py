#!/usr/bin/env python3
"""Targeted responsive/IME audit; separate from the canonical Pixso capture set."""
import argparse
import importlib.util
import itertools
import json
import os
from pathlib import Path
import re
import time

HERE = Path(__file__).resolve().parent
SIZES = ((320, 640), (360, 720), (411, 844))
SCALES = ("1.0", "1.3", "2.0")
SMOKE = ((SIZES[0], "2.0"), (SIZES[1], "1.3"), (SIZES[2], "1.0"))


def load_capture():
    spec = importlib.util.spec_from_file_location("capture_ui", HERE / "capture-ui.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ime_visible(ui):
    return bool(re.search(r"mInputShown=true|mIsInputViewShown=true|isInputViewShown=true",
                          ui.shell("dumpsys", "input_method")))


def check(ui, name, ok, reason):
    ui.records.append(dict(theme=ui.theme, screen=name,
                           status="passed" if ok else "failed", reason=reason))


def audit_case(ui, mode):
    ui.theme = mode
    ui.shell("cmd", "uimode", "night", "yes" if mode == "dark" else "no")
    ui.shell("pm", "clear", ui.PKG)
    ui.shell("pm", "grant", ui.PKG, "android.permission.POST_NOTIFICATIONS")
    ui.start()
    if ui.capture("welcome", ("Продолжить без входа",)):
        check(ui, "continue-offline", ui.tap("Продолжить без входа"), "Offline onboarding action")
    ui.capture("shopping", ("Список", "Добавить", "Добавить товар"))
    if ui.route("add", ("Добавить", "Добавить товар"),
                ("Создать свой товар", "Что нужно купить?", "Введите название или штрихкод")):
        if ui.route("custom-product", ("Создать свой товар",), ("Название товара", "Свой товар")):
            entered = ui.fill_field(0, "AuditProduct")
            time.sleep(.5)
            visible = entered and ime_visible(ui)
            check(ui, "custom-product-ime-open", visible, "First input opens the keyboard")
            if visible:
                ui.capture("custom-product-with-ime", ("Название товара", "Свой товар", "AuditProduct"))
                ui.back()
                time.sleep(.5)
                check(ui, "custom-product-ime-back", not ime_visible(ui), "Back closes keyboard")
                ui.capture("custom-product-after-ime", ("Название товара", "Свой товар", "AuditProduct"))
    for name, label, expected in (
        ("catalog", "Товары", ("Магазины", "Действия каталога", "Нет товаров")),
        ("archive", "Архив", ("Архив списков", "Архив пуст")),
        ("statistics", "Статистика", ("Обзор", "Фильтры")),
        ("profile", "Профиль", ("Оформление", "Обновления приложения")),
    ):
        ui.start()
        ui.close_sheet()
        ui.route(name, (label,), expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", action="store_true", help="All 18 size/scale/theme combinations")
    args = parser.parse_args()
    if os.getenv("CAPTURE_FIXTURE") != "1":
        parser.error("CAPTURE_FIXTURE=1 is required; production APKs are not supported")
    ui = load_capture()
    if not ui.disposable_emulator():
        parser.error("A disposable Android emulator is required")
    ui.wait_until_booted()
    density_output = ui.shell("wm", "density")
    density_matches = re.findall(r"(?:Physical|Override) density: (\d+)", density_output)
    if not density_matches:
        raise RuntimeError("Unable to determine emulator density")
    density = int(density_matches[-1])
    size_output = ui.shell("wm", "size")
    size_override = re.search(r"Override size: (\d+x\d+)", size_output)
    original_scale = ui.shell("settings", "get", "system", "font_scale").strip()
    original_night = re.search(r"Night mode:\s*(yes|no|auto|custom)", ui.shell("cmd", "uimode", "night"))
    if not original_night:
        raise RuntimeError("Unable to determine initial night mode")
    output = Path("ui-adaptive-audit")
    output.mkdir(exist_ok=True)
    cases = list(itertools.product(SIZES, SCALES)) if args.full else SMOKE
    report = []
    try:
        for (width, height), scale in cases:
            pixels = f"{round(width * density / 160)}x{round(height * density / 160)}"
            ui.shell("wm", "size", pixels)
            ui.shell("settings", "put", "system", "font_scale", scale)
            for mode in ("light", "dark"):
                case = f"{width}x{height}dp-font-{scale}-{mode}"
                ui.OUT = output / case
                ui.OUT.mkdir(exist_ok=True)
                ui.records.clear()
                ui.theme = mode
                try:
                    audit_case(ui, mode)
                except Exception as exc:
                    ui.records.append(dict(theme=mode, screen="case-error", status="error", reason=str(exc)))
                report.append(dict(case=case, size_dp=[width, height], font_scale=scale, theme=mode,
                                   actual_size=ui.shell("wm", "size").strip(),
                                   actual_font_scale=ui.shell("settings", "get", "system", "font_scale").strip(),
                                   records=list(ui.records)))
                (output / "manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                                       encoding="utf-8")
    finally:
        ui.shell("wm", "size", size_override.group(1) if size_override else "reset")
        if original_scale == "null":
            ui.shell("settings", "delete", "system", "font_scale")
        else:
            ui.shell("settings", "put", "system", "font_scale", original_scale)
        ui.shell("cmd", "uimode", "night", original_night.group(1))
    if any(r["status"] not in ("captured", "passed") for case in report for r in case["records"]):
        raise SystemExit("Adaptive/IME audit found failed or unreachable checks; inspect manifest")


if __name__ == "__main__":
    main()
