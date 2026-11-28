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
LONG_PRODUCT = "Audit Wholegrain Lactose Free Ultra Pasteurized Milk Large Family Package 1.4 L"


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



def labels(ui):
    _, root = ui.hierarchy()
    return {v for n in root.iter("node") if n.get("package") == ui.PKG
            for v in (n.get("text"), n.get("content-desc")) if v}


def wait_label(ui, label, attempts=8):
    for _ in range(attempts):
        if label in labels(ui):
            return True
        time.sleep(.5)
    return False


def tap_enabled(ui, label):
    """Select the smallest enabled clickable node containing the exact action label."""
    _, root = ui.hierarchy()
    candidates = []
    for node in root.iter("node"):
        if node.get("package") != ui.PKG or node.get("clickable") != "true" or node.get("enabled") != "true":
            continue
        if not any(label in (child.get("text"), child.get("content-desc")) for child in node.iter("node")):
            continue
        b = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
            candidates.append(((b[2]-b[0])*(b[3]-b[1]), b))
    if not candidates:
        return False
    _, b = min(candidates, key=lambda item: item[0])
    ui.shell("input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
    time.sleep(.5)
    return True



def swipe_product(ui, fraction):
    """Use the text center as a safe vertical coordinate; stay clear of system edges."""
    _, root = ui.hierarchy()
    bounds = None
    for node in root.iter("node"):
        if node.get("package") == ui.PKG and node.get("text") == LONG_PRODUCT:
            b = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(b) == 4 and b[3] > b[1]:
                bounds = b
                break
    if bounds is None:
        return False
    sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", ui.shell("wm", "size"))
    if not sizes:
        return False
    width = int(sizes[-1][0])
    y = (bounds[1] + bounds[3]) // 2
    start = round(width * .80)
    end = max(round(width * .08), round(start - width * fraction))
    ui.shell("input", "swipe", str(start), str(y), str(end), str(y), "450")
    time.sleep(.7)
    return True


def wait_absent(ui, label, attempts=8):
    for _ in range(attempts):
        if label not in labels(ui):
            return True
        time.sleep(.5)
    return False


def audit_populated_card(ui):
    # Called from the disposable offline product creation form, after entering LONG_PRODUCT.
    price_entered = ui.fill_field(2, "123456.78")
    check(ui, "large-price-input", price_entered, "Large price field reachable")
    if ime_visible(ui):
        ui.back()
    if not price_entered or not tap_enabled(ui, "Добавить товар"):
        check(ui, "long-product-save", False, "Create product action unavailable")
        return
    # The creation form closes first; the search sheet remains underneath it.
    if wait_label(ui, "Создать свой товар"):
        ui.close_sheet()
    saved = wait_label(ui, LONG_PRODUCT)
    check(ui, "long-product-save", saved, "Full long product name exposed in list")
    if not saved:
        ui.capture("long-product-save-failure")
        return
    ui.capture("shopping-long-product", (LONG_PRODUCT,))
    short = swipe_product(ui, .15) and wait_label(ui, LONG_PRODUCT)
    check(ui, "short-swipe-cancel", short, "Short swipe keeps the product in the list")
    increased = tap_enabled(ui, "Увеличить количество") and wait_label(ui, "Количество: 2. Действия с товаром")
    check(ui, "quantity-increase", increased, "Plus updates quantity without opening product detail")
    if increased:
        ui.capture("shopping-quantity-increased", (LONG_PRODUCT,))
        decreased = tap_enabled(ui, "Уменьшить количество") and wait_label(ui, "Количество: 1. Действия с товаром")
        check(ui, "quantity-decrease", decreased, "Minus restores quantity")
    if ui.route("catalog-long-product", ("Товары",), (LONG_PRODUCT,)):
        check(ui, "catalog-long-product-price", any("123" in x and "456,78" in x for x in labels(ui)),
              "Large formatted price remains available")
        if ui.tap("Список") and wait_label(ui, LONG_PRODUCT):
            removed = swipe_product(ui, .72) and wait_absent(ui, LONG_PRODUCT)
            check(ui, "long-swipe-remove", removed, "Deliberate left swipe removes only the test product")


def audit_navigation(ui):
    # Only fixture data. Do not save an address or mutate a real store.
    ui.start()
    ui.close_sheet()
    if not ui.tap("Товары") or not wait_label(ui, "Найти в Ленте"):
        check(ui, "catalog-direct-search", False, "Search action is visible without overflow menu")
        return
    opened = tap_enabled(ui, "Найти в Ленте") and wait_label(ui, "Добавить продукт")
    check(ui, "catalog-direct-search", opened, "Search opens in one action")
    if opened:
        if ui.fill_field(0, "AuditFlow"):
            if ime_visible(ui):
                ui.back()
            if wait_label(ui, "Указать адрес", attempts=3):
                address_open = tap_enabled(ui, "Указать адрес") and wait_label(ui, "Магазин Ленты")
                check(ui, "address-inline", address_open, "Address settings open over search")
                if address_open:
                    closed = ui.close_sheet() and wait_label(ui, "AuditFlow")
                    check(ui, "address-return", closed, "Closing settings preserves query")
        ui.close_sheet()
    ui.start()
    ui.close_sheet()
    if not ui.tap("Товары") or not ui.tap("Магазины") or not ui.tap("Лента"):
        check(ui, "store-direct-add", False, "Store detail is reachable")
        return
    opened = tap_enabled(ui, "Добавить товар") and wait_label(ui, "Товар магазина")
    check(ui, "store-direct-add", opened, "Store picker opens from bottom action")
    if not opened:
        return
    entered = ui.fill_field(0, "AuditStore")
    if ime_visible(ui):
        ui.back()
    created = entered and tap_enabled(ui, "Создать свой товар") and wait_label(ui, "AuditStore")
    check(ui, "store-create-prefill", created, "Create form retains search text")
    if created:
        returned = ui.close_sheet() and wait_label(ui, "Товар магазина") and wait_label(ui, "AuditStore")
        check(ui, "store-create-cancel", returned, "Cancel returns to picker with search")
    ui.close_sheet()


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
            entered = ui.fill_field(0, LONG_PRODUCT.replace(" ", "%s"))
            time.sleep(.5)
            visible = entered and ime_visible(ui)
            check(ui, "custom-product-ime-open", visible, "First input opens the keyboard")
            if visible:
                ui.capture("custom-product-with-ime", ("Название товара", "Свой товар", LONG_PRODUCT))
                ui.back()
                time.sleep(.5)
                check(ui, "custom-product-ime-back", not ime_visible(ui), "Back closes keyboard")
                ui.capture("custom-product-after-ime", ("Название товара", "Свой товар", LONG_PRODUCT))
                audit_populated_card(ui)
    for name, label, expected in (
        ("catalog", "Товары", ("Магазины", "Действия каталога", "Нет товаров")),
        ("archive", "Архив", ("Архив списков", "Архив пуст")),
        ("statistics", "Статистика", ("Обзор", "Фильтры")),
        ("profile", "Профиль", ("Оформление", "Обновления приложения")),
    ):
        ui.start()
        ui.close_sheet()
        ui.route(name, (label,), expected)
    audit_navigation(ui)


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
