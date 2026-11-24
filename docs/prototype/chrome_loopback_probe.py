"""桌面 Chrome 对照。用公开 HTTPS 测试页跳到回环，只记录是否收到预期路径。"""

import argparse
import os
import signal
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Event, Thread
from urllib.parse import urlencode


class Listener(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/oauth/callback":
            self.server.received.set()
            self.send_response(200)
        else:
            self.send_response(404)
        self.end_headers()


def probe(chrome):
    with TemporaryDirectory() as home, ThreadingHTTPServer(("127.0.0.1", 0), Listener) as server:
        server.received = Event()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        callback = f"http://127.0.0.1:{server.server_port}/oauth/callback"
        url = "https://wpt.live/common/redirect.py?" + urlencode({"location": callback})
        args = [chrome, "--headless", "--no-first-run", "--no-default-browser-check",
                "--disable-background-networking", f"--user-data-dir={home}/profile", "--dump-dom", url]
        try:
            with subprocess.Popen(args, env={"HOME": home, "PATH": os.defpath},
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True) as proc:
                try:
                    return server.received.wait(45)
                finally:
                    if proc.poll() is None:
                        os.killpg(proc.pid, signal.SIGTERM)
                        try:
                            proc.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            os.killpg(proc.pid, signal.SIGKILL)
        finally:
            server.shutdown()
            worker.join()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("chrome", type=Path, help="桌面 Chrome 可执行文件路径")
    if not probe(parser.parse_args().chrome.resolve(strict=True)):
        raise SystemExit("desktop_chrome_https_to_loopback=unobserved")
    print("desktop_chrome_https_to_loopback=received")
