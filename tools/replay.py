"""Private pipe fixture for HarnessReplayTest. The only network peer is this loopback listener."""
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import stat
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]
PHOTO = (ROOT / 'app/src/test/resources/photo.jpg').read_bytes()


class Server(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def authorized(self):
        if self.headers.get('x-dsh-auth-token') == self.server.grant:
            return True
        self.send_error(401)
        return False

    def json(self, obj):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode())

    def do_GET(self):
        if not self.authorized():
            return
        if self.path == '/models':
            self.json({'data': [{'id': 'deepseek-flash', 'input_modalities': ['text', 'image'],
                       'output_modalities': ['text'], 'api_capabilities': {
                           'anthropic_messages': {'system_prompt_update': 'in-history'}}}]})
        elif self.path == '/auth-api/v0/users/current':
            self.json({'code': 0, 'data': {'biz_code': 0, 'biz_data': {'id': 'fixture', 'email': ''}}})
        elif self.path == '/api/v0/users/get_user_summary':
            self.json({'code': 0, 'data': {'biz_code': 0, 'biz_data': {
                'normal_wallets': [{'currency':'CNY','balance':'3.25'}], 'bonus_wallets':[]}}})
        else:
            self.send_error(404)

    def do_POST(self):
        if not self.authorized():
            return
        data = self.rfile.read(int(self.headers['Content-Length']))
        if self.path == '/auth-api/v0/users/logout':
            self.json({'code':0,'data':{'biz_code':0,'biz_data':{}}})
            return
        if self.path == '/v1/files':
            mime = BytesParser(policy=policy.default).parsebytes(
                ('Content-Type: ' + self.headers['Content-Type'] + '\r\n\r\n').encode() + data)
            ok = any(p.get_content_type() == 'image/jpeg' and p.get_payload(decode=True) == PHOTO
                     for p in mime.iter_parts())
            if not ok:
                self.send_error(400)
                return
            self.server.seen.add('photo')
            self.json({'id':'file-pixel','type':'file','mime_type':'image/jpeg','size_bytes':len(PHOTO),
                       'created_at':'2026-11-23T00:00:00Z','filename':'test.jpg'})
            return
        if self.path != '/v1/messages':
            self.send_error(404)
            return
        obj = json.loads(data)
        msgs = obj['messages']
        first = any(m['role'] == 'user' and isinstance(m['content'], list)
                    and any(x.get('type')=='text' and '看图' in x['text'] for x in m['content'])
                    and any(x.get('source',{}).get('file_id')=='file-pixel' for x in m['content'])
                    for m in msgs)
        second = '继续' in json.dumps(msgs[-1], ensure_ascii=False)
        if obj.get('model') != 'deepseek-flash' or obj.get('stream') is not True or not first:
            self.send_error(400)
            return
        if second:
            if not any(m['role']=='assistant' and '紫色' in json.dumps(m,ensure_ascii=False) for m in msgs):
                self.send_error(400)
                return
            self.server.seen.add('multi')
        else:
            self.server.seen.add('image-turn')
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream')
        self.end_headers()

        def event(kind, **fields):
            body = json.dumps({'type':kind, **fields})
            self.wfile.write(f'event: {kind}\ndata: {body}\n\n'.encode())
            self.wfile.flush()

        event('message_start', message={'id':'replay','model':'deepseek-flash','usage':{'input_tokens':2}})
        event('content_block_start', index=0, content_block={'type':'text','text':''})
        event('content_block_delta', index=0, delta={'type':'text_delta','text':'第二轮' if second else '紫'})
        if not second and self.server.advance.wait(12):
            self.server.seen.add('early')
        if not second:
            event('content_block_delta', index=0, delta={'type':'text_delta','text':'色'})
        event('content_block_stop', index=0)
        event('message_delta', delta={'stop_reason':'end_turn'},usage={'output_tokens':2})
        event('message_stop')


def run():
    abi, root = sys.argv[1], Path(sys.argv[2])
    layout = ROOT / 'runtime-cache' / abi
    sysroot = root / 'sys'
    for line in (layout/'assets/bindings.tsv').read_text().splitlines():
        name, path = line.split('\t')
        to = sysroot / path[1:]
        to.parent.mkdir(parents=True, exist_ok=True)
        if not to.exists():
            # PRoot bindings keep the guest filename visible to Node's module loader.
            # A filesystem symlink here would resolve .node into the installer name ending in .so.
            shutil.copyfile(layout/'jni'/abi/name, to)
            to.chmod(0o755)
    arch = (layout/'assets/runtime-arch').read_text().strip()
    binary = sysroot / ('opt/dsh/deepseek-harness-sdk-runtime-linux-'+arch)
    home = root/'dsh'
    home.mkdir(exist_ok=True)
    with ThreadingHTTPServer(('127.0.0.1',0),Server) as server:
        server.advance=threading.Event()
        server.grant=secrets.token_urlsafe(24)
        server.seen=set()
        origin='http://127.0.0.1:'+str(server.server_port)
        (home/'.credentials.yaml').write_text(json.dumps({'version':1,'records':{'deepseek-account-platform/default':
            {'kind':'grant','payload':{'version':1,'token':server.grant,'issuer':origin}}}}))
        patch = root/'fixture.yml'
        patch.write_text(json.dumps([
            {'id':'deepseek-account','config':{'platformOrigin':origin,'harnessOrigin':origin,
                'inferenceOrigin':origin,'allowLoopbackHttp':True}},
            {'id':'llm-deepseek-account','config':{'baseURL':origin}}]))
        threading.Thread(target=server.serve_forever,daemon=True).start()
        cmd = ['qemu-aarch64','-L',str(sysroot)] if arch=='arm64' else []
        cmd += [str(binary),'web','--patch',str(ROOT/'app/src/main/assets/chat.yml'),
                '--patch',str(patch),'--no-open','--host','127.0.0.1','--port','0']
        env={'HOME':str(root),'DSH_HOME':str(home),'PATH':'/usr/bin:/bin','TMPDIR':str(root),
             'DSH_TELEMETRY_DISABLED':'1','NARB_NATIVE_CACHE_DIR':str(sysroot/'opt/native-cache')}
        with subprocess.Popen(cmd, env=env,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                              stdin=subprocess.DEVNULL,start_new_session=True) as proc:
            try:
                def pipe_launch():
                    for line in proc.stdout:
                        if re.fullmatch(rb'dsh web: http://127\.0\.0\.1:\d+/\?token=\S+\s*',line):
                            # Inherited anonymous pipe directly to the test client; never a console or log.
                            sys.stdout.buffer.write(line)
                            sys.stdout.flush()
                    sys.stdout.close()
                threading.Thread(target=pipe_launch,daemon=True).start()
                for line in sys.stdin:
                    if line.strip()=='advance':
                        server.advance.set()
                    if line.strip()=='done':
                        return server.seen=={'early','multi','image-turn','photo'}
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid,signal.SIGTERM)
                    try: proc.wait(timeout=3)
                    except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL)


if __name__=='__main__':
    if not all(stat.S_ISFIFO(os.fstat(fd).st_mode) for fd in (0,1)) or len(sys.argv)!=3:
        raise SystemExit('Run the Gradle replay test.')
    os.umask(0o077)
    raise SystemExit(0 if run() else 1)
