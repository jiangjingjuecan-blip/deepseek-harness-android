"""Linux 验证用。回环地址回放两段固定文字，不访问模型服务。"""

import argparse
import json
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
from urllib.request import Request, urlopen

from deepseek_harness import DeepSeekHarness


FIRST, LAST = "青竹", "白鹭"
QUESTION = "第一轮离线问题"
seen_first = Event()
early_delivery = []
requests_seen = []


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != "/v1/messages":
            self.send_error(404)
            return
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        history = [
            (message["role"], json.dumps(message["content"], ensure_ascii=False))
            for message in body["messages"]
        ]
        requests_seen.append((
            body.get("stream") is True,
            any(role == "user" and QUESTION in text for role, text in history),
            any(role == "assistant" and FIRST + LAST in text for role, text in history),
        ))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        def emit(kind, **fields):
            payload = json.dumps({"type": kind, **fields}, ensure_ascii=False)
            self.wfile.write(f"event: {kind}\ndata: {payload}\n\n".encode())
            self.wfile.flush()

        emit("message_start", message={
            "id": "fixture", "model": "deepseek-flash",
            "usage": {"input_tokens": 1, "output_tokens": 0},
        })
        emit("content_block_start", index=0, content_block={"type": "text", "text": ""})
        emit("content_block_delta", index=0, delta={"type": "text_delta", "text": FIRST})
        if len(requests_seen) <= 2:
            early_delivery.append(seen_first.wait(timeout=2))
        emit("content_block_delta", index=0, delta={"type": "text_delta", "text": LAST})
        emit("content_block_stop", index=0)
        emit("message_delta", delta={"stop_reason": "end_turn"}, usage={"output_tokens": 2})
        emit("message_stop")


def observe(notification):
    # 只检查测试文字，全程不输出事件内容。
    if FIRST in json.dumps(notification.payload, ensure_ascii=False):
        seen_first.set()


def run(root, origin, profile):
    # 对照组直接读相同的 Messages SSE 响应。
    body = {"model": "deepseek-flash", "stream": True, "max_tokens": 16,
            "messages": [{"role": "user", "content": "对照组"}]}
    request = Request(origin + "/v1/messages", json.dumps(body).encode(),
                      headers={"Content-Type": "application/json"})
    pieces = []
    with urlopen(request, timeout=8) as response:
        for line in response:
            if line.startswith(b"data: "):
                event = json.loads(line[6:])
                if event["type"] == "content_block_delta":
                    pieces.append(event["delta"]["text"])
                    if pieces == [FIRST]:
                        seen_first.set()
    assert pieces == [FIRST, LAST] and early_delivery == [True]
    seen_first.clear()

    def launch():
        # 官方适配器要求非空字段。该占位串仅用于上面的回环地址。
        return DeepSeekHarness(
            profile=profile, model="deepseek-flash", cwd=str(root),
            dsh_home=str(root / "dsh"), base_url=origin, api_key="fixture-only",
            request_timeout_seconds=15,
        )

    with launch() as sdk:
        first = sdk.run(QUESTION, session_id="gate", on_notification=observe)
    assert first.finish_reason == "completed" and first.final_response == FIRST + LAST
    assert seen_first.is_set()
    assert any((root / "dsh" / "sessions").rglob("*.jsonl*"))
    with launch() as sdk:
        resumed = sdk.resume_session("gate").run("继续第二轮")
    assert resumed.finish_reason == "completed" and resumed.final_response == FIRST + LAST
    # 对照组、首轮、续聊依次检查。每项记录流式标志、是否带首问、是否带首答。
    assert requests_seen == [(True, False, False), (True, True, False), (True, True, True)]
    print("loopback_sse_parts=2")
    print("sdk_first_text_before_second_frame=" + str(early_delivery[1]).lower())
    print("sdk_turns=2 restart_history=pass")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("sdk", "sdk-minimal"), default="sdk")
    profile = parser.parse_args().profile
    logging.disable(logging.CRITICAL)
    with TemporaryDirectory() as directory, ThreadingHTTPServer(("127.0.0.1", 0), Fixture) as server:
        # 给子进程一个空的用户目录和环境，禁止继承调用者的凭据、代理或 DSH 配置。
        os.environ.clear()
        os.environ.update(PATH=os.defpath, HOME=directory, DSH_TELEMETRY_DISABLED="1")
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            run(Path(directory), f"http://127.0.0.1:{server.server_port}", profile)
        except Exception as exc:
            raise SystemExit(f"验证失败（{type(exc).__name__}）") from None
        finally:
            server.shutdown()
            worker.join()
