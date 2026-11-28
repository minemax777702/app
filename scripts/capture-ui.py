#!/usr/bin/env python3
"""Capture real release UI via ADB, without compiling a second APK."""
import hashlib
import json
import os
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = pathlib.Path("ui-handoff")
OUT.mkdir(exist_ok=True)
FIXTURE = os.getenv("CAPTURE_FIXTURE") == "1"
PKG = "com.mok.buylist.capture" if FIXTURE else "com.mok.buylist"
ACT = PKG + "/com.mok.buylist.ui.MainActivity"
records = []
theme = "light"

def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], timeout=35, text=not binary)

def shell(*args):
    return adb("shell", *args)

def hierarchy():
    shell("uiautomator", "dump", "/sdcard/buylist-ui.xml")
    raw = adb("exec-out", "cat", "/sdcard/buylist-ui.xml", binary=True)
    return raw, ET.fromstring(raw)

def tap(*labels):
    for _ in range(3):
        _, root = hierarchy()
        for label in labels:
            for n in root.iter("node"):
                if n.get("package") != PKG:
                    continue
                if label not in (n.get("text"), n.get("content-desc")):
                    continue
                b = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
                if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
                    shell("input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
                    time.sleep(.4)
                    return True
        time.sleep(.5)
    return False

def capture(name, expected=()):
    raw, root = hierarchy()
    nodes = [n for n in root.iter("node") if n.get("package") == PKG]
    labels = list(dict.fromkeys(v for n in nodes for v in (n.get("text"), n.get("content-desc")) if v))
    if not nodes or (expected and not any(x in labels for x in expected)):
        records.append(dict(theme=theme, screen=name, status="not_reached", expected=list(expected), visible=labels))
        return False
    folder = OUT / theme
    folder.mkdir(exist_ok=True)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid screenshot")
    (folder / (name+".png")).write_bytes(png)
    (folder / (name+".xml")).write_bytes(raw)
    records.append(dict(theme=theme, screen=name, status="captured", screenshot=f"{theme}/{name}.png", hierarchy=f"{theme}/{name}.xml", sha256=hashlib.sha256(png).hexdigest(), visible=labels))
    return True

def start():
    shell("am", "force-stop", PKG)
    shell("am", "start", "-W", "-n", ACT)
    time.sleep(1)

def back():
    shell("input", "keyevent", "4")
    time.sleep(.4)

def route(name, labels, expected=()):
    if tap(*labels):
        return capture(name, expected or labels)
    records.append(dict(theme=theme, screen=name, status="not_reached", expected=list(labels)))
    return False

def child(name, labels, expected=()):
    if route(name, labels, expected):
        back()
    else:
        # A tap may have opened an unrecognized state; reset via caller if necessary.
        pass

def run_theme(mode):
    global theme
    theme = mode
    shell("cmd", "uimode", "night", "yes" if mode == "dark" else "no")
    start()
    if capture("welcome", ("Продолжить без входа",)):
        child("registration", ("Регистрация",), ("Зарегистрироваться",))
        # Registration is a tab, not a separate destination: return to welcome.
        start()
        tap("Продолжить без входа")
    capture("shopping", ("Добавить", "Добавить товар", "Список"))
    child("notifications", ("Уведомления",), ("Уведомления", "Пока нет уведомлений"))
    if route("add", ("Добавить", "Добавить товар"), ("Создать свой товар", "Введите название или штрихкод", "Что нужно купить?")):
        child("custom-product", ("Создать свой товар",), ("Название", "Создать товар", "Сохранить"))
        start()
    if route("catalog", ("Товары",), ("Сохранённых товаров пока нет", "Действия каталога", "Магазины")):
        route("stores", ("Магазины",), ("Магазины",))
    start()
    route("archive", ("Архив",), ("Архив списков", "Архив пуст"))
    start()
    if route("statistics", ("Статистика",), ("Фильтры", "Обзор")):
        child("stats-filters", ("Фильтры",), ("Фильтры статистики", "Применить"))
        for tab in ("Магазины", "Товары", "Динамика"):
            route("stats-"+{"Магазины":"stores","Товары":"products","Динамика":"trend"}[tab], (tab,), ("Фильтры", "Обзор"))
    start()
    if route("profile", ("Профиль",), ("Оформление", "Обновления приложения")):
        child("theme", ("Оформление",), ("Светлая", "Тёмная", "Тема оформления", "Системная"))
        start()
        tap("Профиль")
        child("updates", ("Обновления приложения",), ("Текущая версия", "Обновления"))
        start()
        tap("Профиль")
        child("backup", ("Резервная копия",), ("Сохранить в файл", "Восстановить из файла"))
        start()
        tap("Профиль")
        if route("account", ("Личный профиль",), ("Вход", "Регистрация", "Электронная почта")):
            route("account-registration", ("Регистрация",), ("Зарегистрироваться",))


def fill_field(index, value):
    _, root = hierarchy()
    fields = [n for n in root.iter("node") if n.get("package") == PKG and n.get("class") == "android.widget.EditText"]
    if len(fields) <= index:
        return False
    b = list(map(int, re.findall(r"\d+", fields[index].get("bounds", ""))))
    shell("input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
    shell("input", "text", value)
    return True

def capture_authenticated():
    start()
    if not tap("Профиль") or not tap("Личный профиль"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached"))
        return
    tap("Вход")
    if not fill_field(0,"admin@example.test") or not fill_field(1,"CaptureOnly-2026!"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Login fields unavailable"))
        return
    back()
    if not tap("Войти"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached"))
        return
    for _ in range(8):
        time.sleep(1)
        _, root = hierarchy()
        labels = [n.get("text") for n in root.iter("node")]
        if "Алексей" in labels or "Группы" in labels:
            break
    if not capture("authenticated", ("Алексей","Группы","Списки")):
        return
    if route("cloud-groups", ("Группы",), ("Семья","Дача","Пустая группа")):
        if route("group-detail", ("Семья",), ("Участники · 2","Мария","Списки")):
            child("group-add-member", ("Добавить участников",), ("Добавить участников","Поиск пользователя","Нет пользователей для добавления"))
            back()
    start()
    tap("Профиль")
    if route("administration", ("Администрирование",), ("Пользователи","Группы")):
        child("admin-user", ("Мария",), ("Роль","Доступ к приложению","Группы"))
        route("admin-groups", ("Группы",), ("Семья","Дача"))
    start()
    tap("Профиль")
    tap("Личный профиль","Алексей")
    child("security", ("Безопасность",), ("Вход без пароля","Сеансы"))
    start()
    capture("shopping-populated", ("Молоко 1,4 л","Хлеб ржаной","Яблоки"))
    if route("catalog-populated", ("Товары",), ("Молоко 1,4 л","Хлеб ржаной","Яблоки")):
        child("product-detail", ("Молоко 1,4 л",), ("Молоко 1,4 л",))
    start()
    route("archive-populated", ("Архив",), ("Молоко 1,4 л","Хлеб ржаной","Завершённый поход"))
    start()
    route("statistics-populated", ("Статистика",), ("Фильтры","Обзор"))

def main():
    # The emulator is disposable. No production credentials or account mutations.
    shell("settings", "put", "global", "window_animation_scale", "0")
    shell("settings", "put", "global", "transition_animation_scale", "0")
    shell("settings", "put", "global", "animator_duration_scale", "0")
    shell("settings", "put", "system", "font_scale", "1.0")
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    for command in [("enter",), ("clock","hhmm","0941"), ("battery","level","100","plugged","false")]:
        shell("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", command[0], *[v for k,vv in zip(command[1::2],command[2::2]) for v in ("-e",k,vv)])
    # Prevent cloud/catalog writes and avoid capturing personal data.
    if not FIXTURE:
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
    shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    for mode in ("light", "dark"):
        try:
            if FIXTURE:
                shell("pm", "clear", PKG)
                shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
            run_theme(mode)
            if FIXTURE:
                capture_authenticated()
        except (subprocess.SubprocessError, ET.ParseError, RuntimeError) as e:
            records.append(dict(theme=mode, screen="capture-error", status="error", reason=str(e)))
    metadata = {"commit":os.getenv("GITHUB_SHA"),"version":os.getenv("CAPTURE_VERSION"),"device":shell("wm","size").strip(),"density":shell("wm","density").strip(),"api":shell("getprop","ro.build.version.sdk").strip(),"screens":records}
    (OUT/"manifest.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding="utf-8")
    lines=["# UI handoff — real APK", "", f"Commit: {metadata['commit']}", f"Version: {metadata['version']}", f"Device: {metadata['device']}; {metadata['density']}; API {metadata['api']}", "", "Capture uses a disposable emulator and isolated synthetic fixtures when enabled. XML bounds are physical pixels, not editable Figma layers.", "", "## Screens"]
    for r in records:
        lines.append(f"\n### {r['theme']} / {r['screen']} — {r['status']}")
        if "screenshot" in r:
            lines.append(f"![Screenshot]({r['screenshot']})")
            lines.append(f"Hierarchy: [{r['hierarchy']}]({r['hierarchy']})")
        lines.append("Visible labels: "+ " · ".join(r.get("visible",[])))
    (OUT/"README.md").write_text("\n".join(lines),encoding="utf-8")
    if not any(r["status"]=="captured" for r in records):
        raise SystemExit("No real app screenshots captured")

if __name__ == "__main__":
    main()
