#!/usr/bin/env python3
"""Capture actual UI from the isolated Android fixture build via ADB."""
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
    # uiautomator can return success after a failed dump. Never read an old XML.
    path = "/sdcard/buylist-ui.xml"
    shell("rm", "-f", path)
    result = shell("uiautomator", "dump", path)
    if re.search(r"\b(?:error|exception|failed)\b", result, re.I):
        raise RuntimeError("UI hierarchy dump failed: " + result.strip())
    raw = adb("exec-out", "cat", path, binary=True)
    return raw, ET.fromstring(raw)

def system_error_title(root):
    return next((n.get("text", "") for n in root.iter("node")
                 if n.get("package") == "android" and n.get("resource-id") == "android:id/alertTitle"
                 and re.search(r"isn['’]t responding|keeps stopping|has stopped", n.get("text", ""), re.I)), "")

def launcher_close_target(root):
    # Never dismiss an application crash, permission prompt or arbitrary system dialog.
    if system_error_title(root).replace("’", "'").lower() != "pixel launcher isn't responding":
        return None
    for node in root.iter("node"):
        if (node.get("package") == "android" and node.get("resource-id") == "android:id/aerr_close"
                and node.get("clickable") == "true" and node.get("enabled") == "true"):
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
    return None

def disposable_emulator():
    return FIXTURE and any(shell("getprop", prop).strip() == "1"
                           for prop in ("ro.kernel.qemu", "ro.boot.qemu"))

def wait_until_booted():
    for _ in range(45):
        if shell("getprop", "sys.boot_completed").strip() == "1":
            if shell("getprop", "init.svc.bootanim").strip() in ("", "stopped"):
                return
        time.sleep(2)
    raise RuntimeError("Emulator did not finish booting")

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
    error = system_error_title(root)
    if error:
        raise RuntimeError("System error dialog blocks capture: " + error)
    nodes = [n for n in root.iter("node") if n.get("package") == PKG]
    labels = list(dict.fromkeys(v for n in nodes for v in (n.get("text"), n.get("content-desc")) if v))
    if not nodes or (expected and not any(x in labels for x in expected)):
        records.append(dict(theme=theme, screen=name, status="not_reached", expected=list(expected), visible=labels))
        return False
    folder = OUT / theme
    folder.mkdir(exist_ok=True)
    layout_name = None
    if FIXTURE:
        shell("run-as", PKG, "rm", "-f", "files/compose-layout.json")
        shell("am", "broadcast", "-n", PKG+"/com.mok.buylist.capture.LayoutCaptureReceiver")
        data = adb("exec-out", "run-as", PKG, "cat", "files/compose-layout.json", binary=True)
        layout = json.loads(data)
        if layout.get("schema") != 1 or not layout.get("roots"):
            raise RuntimeError("Compose layout export unavailable")
        layout_name = f"{theme}/{name}.layout.json"
        (OUT/layout_name).write_bytes(data)
    png = adb("exec-out", "screencap", "-p", binary=True)
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid screenshot")
    (folder / (name+".png")).write_bytes(png)
    (folder / (name+".xml")).write_bytes(raw)
    records.append(dict(theme=theme, screen=name, status="captured", screenshot=f"{theme}/{name}.png", hierarchy=f"{theme}/{name}.xml", sha256=hashlib.sha256(png).hexdigest(), visible=labels, layout=layout_name))
    return True

def save_launch_diagnostics(attempt, result):
    # Only disposable test-device data; retain diagnostics, not a design capture.
    folder = OUT / "diagnostics" / theme
    folder.mkdir(parents=True, exist_ok=True)
    prefix = folder / f"launch-{attempt}"
    prefix.with_suffix(".txt").write_text(result, encoding="utf-8")
    for suffix, command in (
        (".activity.txt", ("shell", "dumpsys", "activity", "activities")),
        (".crash.txt", ("logcat", "-b", "crash", "-d", "-t", "100")),
    ):
        try:
            prefix.with_suffix(suffix).write_text(adb(*command), encoding="utf-8")
        except subprocess.SubprocessError:
            pass
    try:
        raw, _ = hierarchy()
        prefix.with_suffix(".xml").write_bytes(raw)
        prefix.with_suffix(".png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    except (subprocess.SubprocessError, ET.ParseError, RuntimeError):
        pass

def start():
    # Pixel Launcher ANR can obscure an otherwise correctly resumed capture Activity.
    # Close only the known launcher error, only on an isolated emulator, and keep evidence.
    allow_recovery = disposable_emulator()
    recovered = False
    for attempt in range(1, 4):
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("am", "force-stop", PKG)
        result = shell("am", "start", "-W", "-n", ACT)
        print(f"[{theme}] launch {attempt}: {result.strip()}", flush=True)
        for _ in range(12):
            try:
                _, root = hierarchy()
            except (subprocess.SubprocessError, ET.ParseError, RuntimeError):
                time.sleep(1)
                continue
            error = system_error_title(root)
            if error:
                save_launch_diagnostics(f"{attempt}-system-error", result + "\n" + error)
                point = launcher_close_target(root)
                if allow_recovery and not recovered and point:
                    recovered = True
                    print(f"[{theme}] Recovering known Pixel Launcher ANR on fixture emulator", flush=True)
                    shell("input", "tap", str(point[0]), str(point[1]))
                    time.sleep(2)
                    continue
                raise RuntimeError("System error dialog blocks launch: " + error)
            if any(n.get("package") == PKG for n in root.iter("node")):
                return
            time.sleep(1)
        save_launch_diagnostics(attempt, result)
    raise RuntimeError("Application window unavailable after three launches; see diagnostics")

def back():
    shell("input", "keyevent", "4")
    time.sleep(.4)

def route(name, labels, expected=()):
    if tap(*labels):
        return capture(name, expected or labels)
    records.append(dict(theme=theme, screen=name, status="not_reached", expected=list(labels)))
    return False

def close_sheet():
    _, root = hierarchy()
    labels = {v for n in root.iter("node") if n.get("package") == PKG
              for v in (n.get("text"), n.get("content-desc")) if v}
    if "Close sheet" in labels or "Закрыть" in labels:
        return tap("Close sheet", "Закрыть")
    return False

def child(name, labels, expected=()):
    if route(name, labels, expected):
        if not close_sheet():
            back()
    else:
        close_sheet()

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
    child("notifications", ("Уведомления",), ("Уведомления", "Нет уведомлений"))
    if route("add", ("Добавить", "Добавить товар"), ("Создать свой товар", "Введите название или штрихкод", "Что нужно купить?")):
        child("custom-product", ("Создать свой товар",), ("Название товара", "Свой товар"))
        start()
    if route("catalog", ("Товары",), ("Нет товаров", "Действия каталога", "Магазины")):
        if route("stores", ("Магазины",), ("Магазины",)) and FIXTURE:
            child("store-edit", ("Добавить магазин",), ("Новый магазин",))
            if route("store-detail-empty", ("Лента",), ("Нет товаров",)):
                if route("store-product-picker-empty", ("Добавить товар",), ("Товар магазина",)):
                    close_sheet()
                back()
    start()
    route("archive", ("Архив",), ("Архив списков", "Архив пуст"))
    start()
    if route("statistics", ("Статистика",), ("Фильтры", "Обзор")):
        child("stats-filters", ("Фильтры",), ("Фильтры статистики", "Применить"))
        for tab in ("Магазины", "Товары", "Динамика"):
            route("stats-"+{"Магазины":"stores","Товары":"products","Динамика":"trend"}[tab], (tab,), ("Фильтры", "Обзор"))
    start()
    if route("profile", ("Профиль",), ("Оформление", "Обновления приложения")):
        # Capture account before updates: the update sheet may restore on relaunch.
        if route("account", ("Личный профиль",), ("Войти",)):
            route("account-registration", ("Регистрация",), ("Зарегистрироваться",))
        start()
        close_sheet()
        tap("Профиль")
        child("theme", ("Оформление",), ("Светлая", "Тёмная", "Тема оформления", "Системная"))
        start()
        close_sheet()
        tap("Профиль")
        child("backup", ("Резервная копия",), ("Сохранить в файл", "Восстановить из файла"))
        start()
        close_sheet()
        tap("Профиль")
        child("updates", ("Обновления приложения",), ("Текущая версия",))


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
    # Start from the actual first-run login sheet, not the profile navigation.
    shell("pm", "clear", PKG)
    shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    start()
    if not capture("login-form", ("Войти", "Электронная почта")):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Welcome login form unavailable"))
        return
    if not fill_field(0,"admin@example.test") or not fill_field(1,"CaptureOnly-2026!"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Login fields unavailable"))
        return
    # Back only when the IME is shown; otherwise it dismisses the welcome sheet.
    ime = shell("dumpsys", "input_method")
    if re.search(r"mInputShown=true|mIsInputViewShown=true|isInputViewShown=true", ime):
        back()
    if not tap("Войти"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Login button unavailable"))
        return
    # Successful authentication closes onboarding and loads the cloud directory.
    for _ in range(15):
        time.sleep(1)
        _, root = hierarchy()
        labels = [n.get("text") for n in root.iter("node")]
        if "Войти" not in labels and "Профиль" in labels:
            break
    else:
        capture("login-failure")
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Welcome did not close after login"))
        return
    for _ in range(8):
        if tap("Профиль"):
            break
        time.sleep(1)
    if not tap("Личный профиль", "Алексей"):
        records.append(dict(theme=theme, screen="authenticated", status="not_reached", reason="Cloud profile unavailable"))
        return
    for _ in range(15):
        _, root = hierarchy()
        labels = [n.get("text") for n in root.iter("node")]
        if "Алексей" in labels and "Группы" in labels:
            break
        time.sleep(1)
    if not capture("authenticated", ("Группы",)):
        return
    if route("cloud-groups", ("Группы",), ("Семья","Дача","Пустая группа")):
        if route("group-detail", ("Семья",), ("Участники · 2","Мария")):
            child("group-add-member", ("Добавить участников",), ("Добавить участников","Поиск пользователя","Создать пользователя"))
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
    tap("Список")
    for _ in range(10):
        _, root = hierarchy()
        if any(n.get("text") == "Молоко 1,4 л" for n in root.iter("node")):
            break
        time.sleep(1)
    capture("shopping-populated", ("Молоко 1,4 л","Хлеб ржаной","Яблоки"))
    if route("catalog-populated", ("Товары",), ("Молоко 1,4 л","Хлеб ржаной","Яблоки")):
        child("product-detail", ("Молоко 1,4 л",), ("Молоко 1,4 л",))
    start()
    if tap("Товары") and tap("Магазины"):
        if route("store-detail", ("Магнит",), ("Молоко 1,4 л", "Хлеб ржаной")):
            if route("store-product-picker", ("Добавить товар",), ("Товар магазина",)):
                if route("store-product-price", ("Молоко 1,4 л",), ("Цена в магазине",)):
                    close_sheet()
                else:
                    close_sheet()
            if tap("Добавить товар"):
                if fill_field(0, "zzzznotfound"):
                    ime = shell("dumpsys", "input_method")
                    if re.search(r"mInputShown=true|mIsInputViewShown=true|isInputViewShown=true", ime):
                        back()
                    capture("store-product-picker-no-results", ("Товары не найдены",))
                if tap("Создать свой товар"):
                    capture("store-custom-product", ("Свой товар",))
                close_sheet()
            if tap("Действия с ценой"):
                child("store-remove-product", ("Убрать из магазина",), ("Убрать товар из магазина?",))
    start()
    # Collapsed archive rows only expose date and summary, not product names.
    if route("archive-populated", ("Архив",), ("Личный список · 3 похода",)):
        route("archive-expanded", ("Развернуть",), ("Молоко 1,4 л", "Хлеб ржаной"))
    start()
    if route("statistics-populated", ("Статистика",), ("Что купили","Расходы за выбранный период")):
        route("stats-stores-populated", ("Магазины",), ("По магазинам",))
        route("stats-products-populated", ("Товары",), ("Чаще всего покупаю",))
        route("stats-trend-populated", ("Динамика",), ("Динамика расходов",))

def main():
    # The emulator is disposable. No production credentials or account mutations.
    wait_until_booted()
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
        except (subprocess.SubprocessError, ET.ParseError, RuntimeError, ValueError) as e:
            records.append(dict(theme=mode, screen="capture-error", status="error", reason=str(e)))
    required = {"welcome", "registration", "shopping", "notifications", "add", "custom-product", "catalog", "stores", "archive", "statistics", "stats-filters", "stats-stores", "stats-products", "stats-trend", "profile", "theme", "updates", "backup", "account", "account-registration", "login-form", "authenticated", "cloud-groups", "group-detail", "group-add-member", "administration", "admin-user", "admin-groups", "security", "shopping-populated", "catalog-populated", "product-detail", "archive-populated", "archive-expanded", "statistics-populated"}
    if FIXTURE:
        required.update({"store-detail", "store-detail-empty", "store-product-picker", "store-product-picker-empty",
                         "store-product-picker-no-results", "store-product-price", "store-custom-product",
                         "store-edit", "store-remove-product", "stats-stores-populated",
                         "stats-products-populated", "stats-trend-populated"})
    captured = {(r["theme"], r["screen"]) for r in records if r["status"] == "captured"}
    auth_complete = all((mode, "authenticated") in captured for mode in ("light", "dark"))
    coverage_complete = auth_complete and all((mode, screen) in captured for mode in ("light", "dark") for screen in required) and all(r["status"] == "captured" for r in records)
    metadata = {"auth_complete": auth_complete, "coverage_complete": coverage_complete, "commit":os.getenv("GITHUB_SHA"),"version":os.getenv("CAPTURE_VERSION"),"device":shell("wm","size").strip(),"density":shell("wm","density").strip(),"api":shell("getprop","ro.build.version.sdk").strip(),"screens":records}
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
    if FIXTURE and not auth_complete:
        raise SystemExit("Fixture UI authentication failed; see manifest and screenshots")
    if FIXTURE and not coverage_complete:
        raise SystemExit("Incomplete fixture UI coverage; see manifest and screenshots")

if __name__ == "__main__":
    main()
