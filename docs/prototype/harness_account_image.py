"""用 ARM64 DSH 的账号模型发一张测试图。模型列表、文件上传和推理都只到回环服务。"""

import argparse
import base64
import json
import logging
import os
import secrets
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread

from deepseek_harness import DeepSeekHarness


# 1×1 RGB PNG，无元数据。
IMAGE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPgCm4CAAFKAODZK0t+AAAAAElFTkSuQmCC"
PNG = base64.b64decode(IMAGE)
QUESTION = "图里是什么颜色？"
REPLY = "这是固定回放"
FILE_ID = "file-fixture-pixel"
MODEL = "deepseek-flash"


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def authorized(self):
        valid = (self.headers.get("x-dsh-auth-token") == self.server.test_grant
                 and "x-api-key" not in self.headers and "Authorization" not in self.headers)
        if not valid:
            self.send_error(401)
        return valid

    def send_json(self, body):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())

    def do_GET(self):
        if self.path != "/models":
            self.send_error(404)
        elif self.authorized():
            self.server.seen.add("models")
            self.send_json({"data": [{
                "id": MODEL, "input_modalities": ["text", "image"], "output_modalities": ["text"],
                "api_capabilities": {"anthropic_messages": {"system_prompt_update": "in-history"}},
            }]})

    def do_POST(self):
        if not self.authorized():
            return
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/v1/files":
            content_type = self.headers.get("Content-Type", "")
            multipart = BytesParser(policy=policy.default).parsebytes(
                f"Content-Type: {content_type}\r\n\r\n".encode("ascii") + body
            )
            files = [p for p in multipart.iter_parts() if p.get_param("name", header="content-disposition") == "file"]
            if (multipart.get_content_type() != "multipart/form-data" or len(files) != 1
                    or files[0].get_content_type() != "image/png" or files[0].get_payload(decode=True) != PNG):
                self.send_error(400)
                return
            self.server.seen.add("png")
            self.send_json({"id": FILE_ID, "type": "file", "mime_type": "image/png", "size_bytes": len(PNG),
                            "created_at": "2026-11-23T00:00:00Z", "filename": "pixel.png"})
            return
        if self.path != "/v1/messages":
            self.send_error(404)
            return
        payload = json.loads(body)
        image = {"type": "image", "source": {"type": "file", "file_id": FILE_ID}}

        def has_question_and_image(message):
            blocks = message["content"]
            return (message["role"] == "user" and image in blocks
                    and any(b["type"] == "text" and QUESTION in b["text"] for b in blocks))

        if (payload.get("model") != MODEL or payload.get("stream") is not True
                or not any(has_question_and_image(m) for m in payload["messages"])):
            self.send_error(400)
            return
        self.server.seen.add("text_and_image")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        events = [
            ("message_start", {"message": {"id": "fixture", "model": MODEL, "usage": {"input_tokens": 1}}}),
            ("content_block_start", {"index": 0, "content_block": {"type": "text", "text": ""}}),
            ("content_block_delta", {"index": 0, "delta": {"type": "text_delta", "text": REPLY}}),
            ("content_block_stop", {"index": 0}),
            ("message_delta", {"delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 1}}),
            ("message_stop", {}),
        ]
        for kind, fields in events:
            data = json.dumps({"type": kind, **fields})
            self.wfile.write(f"event: {kind}\ndata: {data}\n\n".encode())


def run(launcher, root, server):
    origin = f"http://127.0.0.1:{server.server_port}"
    # 沙盘账号只对本次回环服务有效。用独立目录，完全不接触使用者的凭据。
    dsh_home = root / "dsh"
    dsh_home.mkdir()
    grant = {"version": 1, "token": server.test_grant, "issuer": origin}
    record = {"deepseek-account-platform/default": {"kind": "grant", "payload": grant}}
    (dsh_home / ".credentials.yaml").write_text(json.dumps({"version": 1, "records": record}))
    patch = root / "loopback.yml"
    patch.write_text(json.dumps([
        {"id": "deepseek-account", "config": {"platformOrigin": origin, "harnessOrigin": origin,
                                              "inferenceOrigin": origin, "allowLoopbackHttp": True}},
        {"id": "llm-deepseek-account", "config": {"baseURL": origin}},
    ]))
    with DeepSeekHarness(dsh_bin=str(launcher), profile="sdk", provider="deepseek-account", model=MODEL,
                         patches=(str(patch),), base_url=origin, cwd=str(root), dsh_home=str(dsh_home),
                         initialize_timeout_seconds=90, request_timeout_seconds=90) as sdk:
        result = sdk.run([
            {"type": "text", "text": QUESTION},
            {"type": "image", "mimeType": "image/png", "data": IMAGE},
        ])
    if (server.seen != {"models", "png", "text_and_image"} or result.finish_reason != "completed"
            or result.final_response != REPLY):
        raise SystemExit(f"未通过。模型端实际观察到的步骤 {sorted(server.seen)}")
    print("provider=deepseek-account model=deepseek-flash models_auth=pass")
    print(f"files_png_bytes={len(PNG)} messages_user_text_and_file_id=pass replay_reply=pass")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("launcher", type=Path)
    launcher = parser.parse_args().launcher.resolve(strict=True)
    logging.disable(logging.CRITICAL)
    os.umask(0o077)
    with TemporaryDirectory() as work, ThreadingHTTPServer(("127.0.0.1", 0), Fixture) as server:
        os.environ.clear()
        os.environ.update(PATH=os.defpath, HOME=work, TMPDIR=work, DSH_TELEMETRY_DISABLED="1")
        server.test_grant = secrets.token_urlsafe(24)
        server.seen = set()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            run(launcher, Path(work), server)
        except Exception as exc:
            # SDK 异常可能包含子进程的原始诊断。
            raise SystemExit(f"验证失败（{type(exc).__name__}）。已观察到 {sorted(server.seen)}") from None
        finally:
            server.shutdown()
            worker.join()
