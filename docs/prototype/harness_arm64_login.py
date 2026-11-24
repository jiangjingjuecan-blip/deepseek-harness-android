"""验证 DSH Web 的正式登录入口。只初始化、探测空回调、取消，不打开授权页。"""

import argparse
import http.client
import json
import os
import queue
import re
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit


class ProbeError(Exception):
    """只包含预先选定的诊断信息。"""


def collect_startup(stream, ready):
    # DSH 会打印含本机启动凭据的 URL。原始输出只进匿名管道。
    for line in stream:
        match = re.fullmatch(rb"dsh web: http://127\.0\.0\.1:(\d+)(/\?token=[^\s]+)", line.strip())
        if match:
            ready.put((int(match[1]), match[2].decode("ascii")))
    ready.put(None)


def verify(port, launch_path):
    origin = f"http://127.0.0.1:{port}"

    def request(method, path, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        try:
            connection.request(method, path, body, headers or {})
            response = connection.getresponse()
            return response.status, response.getheader("Set-Cookie", ""), response.read()
        finally:
            connection.close()

    status, cookie_header, _ = request("GET", launch_path)
    if status != 303 or not cookie_header:
        raise ProbeError(f"本机会话建立失败，HTTP {status}")
    cookie = cookie_header.partition(";")[0]

    def rpc(method, **args):
        endpoint = "account/" + method
        envelope = {"type": "client-request", "rpcId": str(uuid.uuid4()),
                    "method": endpoint, "payload": {"args": args}}
        headers = {"Cookie": cookie, "Origin": origin, "Content-Type": "application/json"}
        status, _, raw = request("POST", "/api/" + endpoint, json.dumps(envelope), headers)
        if status != 200:
            raise ProbeError(f"{endpoint} HTTP {status}")
        result = json.loads(raw)["result"]
        if result.get("ok") is not True:
            raise ProbeError(f"{endpoint} 被拒绝")
        return result["value"]

    if rpc("getState")["status"] != "signed-out":
        raise ProbeError("临时账号目录状态异常")
    print("web_loopback=ready bootstrap_http=303 account=signed-out", flush=True)
    before = request("GET", "/oauth/callback")[0]
    client = {"version": "0.2.2-rc.2", "locale": "zh-CN", "timezoneOffsetSeconds": 0}
    view = rpc("startSignIn", client=client, callbackOrigin=origin, loginSource="web")
    attempt_id = view["attempt"]["id"]
    try:
        deadline = time.monotonic() + 45
        while view["attempt"]["phase"] == "initializing" and time.monotonic() < deadline:
            time.sleep(0.2)
            view = rpc("getState")
        attempt = view["attempt"]
        if attempt["phase"] != "waiting-browser":
            code = attempt.get("errorCode")
            safe_code = code if code in ("network", "no-response", "expired", "storage", "protocol") else "unknown"
            raise ProbeError(f"未进入 waiting-browser，状态码 {safe_code}")
        url = urlsplit(attempt["authorizeUrl"])
        if (url.scheme, url.netloc, url.path) != ("https", "platform.deepseek.com", "/dsh/authorize"):
            raise ProbeError("授权页来源或路径不匹配")
        print("auth_init=waiting-browser source=web authorize=https://platform.deepseek.com/dsh/authorize")
        during = request("GET", "/oauth/callback")[0]
    finally:
        rpc("cancelSignIn", attemptId=attempt_id)
    after = request("GET", "/oauth/callback")[0]
    print(f"empty_callback_http before={before} waiting={during} after_cancel={after}")
    view = rpc("getState")
    if (before, during, after) != (404, 400, 404) or view["status"] != "signed-out":
        raise ProbeError("回调或取消后的账号状态异常")
    print("cancelled=signed-out")


def run(launcher):
    with tempfile.TemporaryDirectory() as work:
        env = {"HOME": work, "DSH_HOME": work + "/dsh", "TMPDIR": work,
               "PATH": os.defpath, "DSH_TELEMETRY_DISABLED": "1"}
        command = [launcher, "web", "--no-open", "--host", "127.0.0.1", "--port", "0"]
        with subprocess.Popen(command, env=env, cwd=work, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True) as proc:
            ready = queue.Queue()
            reader = threading.Thread(target=collect_startup, args=(proc.stdout, ready), daemon=True)
            reader.start()
            try:
                result = ready.get(timeout=120)
                if result is None:
                    raise ProbeError("DSH 启动行缺失")
                verify(*result)
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid, signal.SIGTERM)
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait()
                reader.join(timeout=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher", type=Path, help="指向 ARM64 DSH 的本地启动脚本")
    try:
        run(parser.parse_args().launcher.resolve(strict=True))
    except ProbeError as exc:
        raise SystemExit(str(exc)) from None
    except Exception as exc:
        # HTTP 和解析异常可能包含带查询串的 URL。
        raise SystemExit(f"验证失败（{type(exc).__name__}）") from None
