# -*- coding: utf-8 -*-
"""
服务看门狗：探活 rag-service(8080) 与 oa-approval(7000)，
连续 3 次失败（90s 窗口，避开人工重启停机）→ 杀端口残留进程 → 自动拉起 → 飞书 webhook 告警。

用法（Windows，脱离终端运行）：
    powershell Start-Process python -ArgumentList 'D:\\project\\rag-service\\scripts\\watchdog.py' \
        -WorkingDirectory 'D:\\project\\rag-service' -WindowStyle Hidden \
        -RedirectStandardOutput 'D:\\dsh-data\\temp\\watchdog.log' \
        -RedirectStandardError 'D:\\dsh-data\\temp\\watchdog-err.log'
停止：tasklist 找 python.exe 中跑 watchdog.py 的 PID → taskkill //F //PID <pid>
日志：logs/watchdog.log（重启与告警动作都落盘）
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from datetime import datetime

# ---------------- 配置 ----------------
CHECK_INTERVAL = 30          # 探活间隔（秒）
FAIL_THRESHOLD = 3           # 连续失败阈值：人工重启停机约 45-60s，3 次=90s 窗口不误触发
COOLDOWN = 90                # 拉起后的冷却秒数（服务启动需 15~40s）
HTTP_TIMEOUT = 5

SERVICES = [
    {
        "name": "rag-service",
        "port": 8080,
        "jar": r"D:\project\rag-service\target\rag-service-1.0.0-SNAPSHOT.jar",
        "workdir": r"D:\project\rag-service",
        "log": r"D:\dsh-data\temp\rag-service.log",
        "err": r"D:\dsh-data\temp\rag-service-err.log",
    },
    {
        "name": "oa-approval",
        "port": 7000,
        "jar": r"D:\project\oa-approval\target\oa-approval-1.0.0.jar",
        "workdir": r"D:\project\oa-approval",
        "log": r"D:\dsh-data\temp\oa-service.log",
        "err": r"D:\dsh-data\temp\oa-service-err.log",
    },
]

ENV_FILE = r"D:\project\rag-service\.env"
LOG_FILE = r"D:\project\rag-service\logs\watchdog.log"

state = {s["name"]: {"fails": 0, "cooldown_until": 0.0} for s in SERVICES}


def log(msg):
    line = f"{datetime.now().strftime('%Y-%m-%d %H:%M:%S')} [watchdog] {msg}"
    print(line, flush=True)
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def read_env(key):
    try:
        with open(ENV_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    return v.strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def check_health(port):
    try:
        req = urllib.request.Request(f"http://localhost:{port}/healthz")
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as resp:
            body = json.loads(resp.read().decode("utf-8", "ignore"))
            return resp.status == 200 and body.get("status") == "UP"
    except Exception:
        return False


def kill_port(port):
    """杀占用端口的残留进程（服务假死/半死时端口仍被占，必须先杀再拉起）"""
    try:
        out = subprocess.run(
            ["netstat", "-ano"], capture_output=True, text=True, timeout=15
        ).stdout
        pids = set()
        for line in out.splitlines():
            if f":{port} " in line and "LISTENING" in line:
                pid = line.split()[-1]
                if pid.isdigit():
                    pids.add(pid)
        for pid in pids:
            subprocess.run(["taskkill", "//PID", pid, "//F"],
                           capture_output=True, text=True, timeout=20)
        return list(pids)
    except Exception as e:
        log(f"杀端口 {port} 异常: {e}")
        return []


def start_service(svc):
    ps = (
        "Start-Process -FilePath java -ArgumentList "
        f"'-jar','{svc['jar']}' -WorkingDirectory '{svc['workdir']}' "
        "-WindowStyle Hidden "
        f"-RedirectStandardOutput '{svc['log']}' -RedirectStandardError '{svc['err']}'"
    )
    subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                   capture_output=True, text=True, timeout=60)


def alert_feishu(title, text):
    webhook = read_env("FEISHU_ALERT_WEBHOOK_URL")
    if not webhook:
        log("无 webhook 配置，跳过飞书告警")
        return
    payload = json.dumps({
        "msg_type": "interactive",
        "card": {
            "header": {"title": {"tag": "plain_text", "content": title},
                       "template": "red"},
            "elements": [{"tag": "div",
                          "text": {"tag": "lark_md", "content": text}}],
        },
    }).encode("utf-8")
    try:
        req = urllib.request.Request(webhook, data=payload,
                                     headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=10)
    except Exception as e:
        log(f"飞书告警发送失败: {e}")


def handle(svc):
    name, port = svc["name"], svc["port"]
    st = state[name]
    now = time.time()
    if now < st["cooldown_until"]:
        return  # 拉起后冷却期内不检查
    if check_health(port):
        if st["fails"]:
            log(f"{name}:{port} 已恢复")
        st["fails"] = 0
        return
    st["fails"] += 1
    log(f"{name}:{port} 探活失败（第 {st['fails']} 次）")
    if st["fails"] < FAIL_THRESHOLD:
        return
    # 连续失败 → 杀残留 → 拉起 → 告警
    log(f"*** {name} 连续 {FAIL_THRESHOLD} 次探活失败，执行拉起 ***")
    killed = kill_port(port)
    if killed:
        log(f"已杀残留进程 PID={killed}")
    start_service(svc)
    st["fails"] = 0
    st["cooldown_until"] = now + COOLDOWN
    alert_feishu(f"⚠️ 服务拉起：{name}",
                 f"{name}（端口 {port}）连续 {FAIL_THRESHOLD} 次探活失败，"
                 f"看门狗已自动拉起（残留进程 {killed or '无'}）。\n"
                 f"时间：{datetime.now().strftime('%m-%d %H:%M:%S')}")


def main():
    log(f"看门狗启动，探活 {[s['name'] for s in SERVICES]}，"
        f"间隔 {CHECK_INTERVAL}s / 阈值 {FAIL_THRESHOLD} 次 / 冷却 {COOLDOWN}s")
    while True:
        for svc in SERVICES:
            try:
                handle(svc)
            except Exception as e:
                log(f"检查 {svc['name']} 异常: {e}")
        time.sleep(CHECK_INTERVAL)


if __name__ == "__main__":
    main()
