"""Private pipe fixture for HarnessReplayTest. The only network peer is this loopback listener."""
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import queue
import signal
import stat
import subprocess
import sys
import threading
import tempfile

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
        if self.path == '/fixture-connect':
            connection = self.server.__dict__.pop('handoff', None)
            if connection is None:
                self.send_error(404)
            else:
                self.json(connection)
            return
        if not self.authorized():
            return
        if self.path == '/models':
            self.json({'data': [{'id': model, 'context_window': 1048576, 'max_output_tokens': 393216,
                       'client_requirements': {'deepseek-harness': {'min_version': '0.2.2-rc.2'}},
                       'input_modalities': modalities, 'output_modalities': ['text'], 'api_capabilities': {
                           'anthropic_messages': {}}} for model, modalities in
                           [('deepseek-flash',['text','image']), ('deepseek-v4-pro',['text']),
                            ('fixture-future',['text'])]]})
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
            image = next((p.get_payload(decode=True) for p in mime.iter_parts() if p.get_content_type() == 'image/jpeg'), b'')
            if image == PHOTO:
                file_id = 'file-pixel'
            elif len(image) == len(PHOTO)+5 and image[:6] == bytes.fromhex('ffd8fffe0003') and image[7:] == PHOTO[2:]:
                file_id = 'file-pixel-'+str(image[6])
            elif image.startswith(bytes.fromhex('ffd8')) and 0 < len(image) < 65536:
                file_id = 'file-'+hashlib.sha256(image).hexdigest()[:16]
            else:
                self.send_error(400)
                return
            self.server.seen.add('photo')
            self.json({'id':file_id,'type':'file','mime_type':'image/jpeg','size_bytes':len(image),
                       'created_at':'2026-11-23T00:00:00Z','filename':'test.jpg'})
            return
        if self.path != '/v1/messages':
            self.send_error(404)
            return
        obj = json.loads(data)
        msgs = obj['messages']
        contents = json.dumps(msgs, ensure_ascii=False)
        last = json.dumps(msgs[-1], ensure_ascii=False)
        if self.server.device_attachments and '附件回放' in last:
            blocks=msgs[-1]['content']
            images=[b.get('source',{}).get('file_id') for b in blocks if b.get('type')=='image']
            if (len(images)!=5 or len(set(images))!=5 or not all(images) or
                    '[文件：便签.txt]' not in last or '设备文件正文' not in last or
                    '[文件：报告.docx]' not in last or '|[图 1]' not in last or '|[图 2]' not in last):
                self.send_error(400)
                return
            self.server.seen.add('device-attachments')
            self.stream('附件已收到')
            return
        if self.is_title(obj):
            self.stream('回放')
            return
        if self.server.device_adventure:
            texts=[''.join(b.get('text','') for b in m['content'] if not b.get('text','').startswith('<system-reminder>')) for m in msgs]
            roll=self.server.device_roll
            if len(texts)==1 and texts==['设备开场']:
                label,answer='entrance','门前'
            elif len(texts)==3 and texts[:2]==['设备开场','门前'] and re.fullmatch(r'\[骰子 d20 \| (\d+) \| \1\]\n查门',texts[-1]):
                if roll is not None and roll!=texts[-1]:
                    self.send_error(400)
                    return
                self.server.device_roll=texts[-1]
                label,answer=('regen','金钥匙') if 'silver' in self.server.seen else ('silver','银钥匙')
            elif roll and texts==['设备开场','门前',roll,'铜钥匙','用钥匙']:
                label,answer='edited-assistant','打开'
            elif roll and texts==['设备开场','门前',roll.replace('查门','回营')]:
                label,answer='edited-user','归来'
            elif texts==['设备开场','门前','向北']:
                label,answer='fork','山路'
            else:
                self.send_error(400)
                return
            if [m['role'] for m in msgs]!=['user' if i%2==0 else 'assistant' for i in range(len(texts))]:
                self.send_error(400)
                return
            self.server.seen.add(label)
            self.stream(answer)
            return
        if self.server.adventure:
            # Expectations arrive over the test process's private pipe. Inspect the
            # actual provider envelope after DSH has forked, restored or compacted.
            try:
                want=self.server.expected.get(timeout=6)
            except queue.Empty:
                self.server.mismatches+=1
                self.send_error(400)
                return
            rendered=[b.get('text','') for m in msgs for b in m['content']]
            reminders=[t for t in rendered if t.startswith('<system-reminder>')]
            texts=[[m['role'], ''.join(b.get('text','') for b in m['content'] if b.get('text','') not in reminders)] for m in msgs]
            prefix=(obj.get('system'),obj.get('tools'),reminders)
            if 'Condense the conversation ABOVE' not in last and texts:
                # When the first question is unchanged, its complete block sequence must match,
                # including runtime context, images and positions.
                known=self.server.first_inputs.setdefault(texts[0][1],msgs[0])
                same_first=known==msgs[0]
            else:
                same_first=True
            summary='Condense the conversation ABOVE' in last
            if self.server.prefix is None and not summary:
                self.server.prefix=prefix
            images=[b.get('source',{}).get('file_id') for m in msgs for b in m['content'] if b.get('type')=='image']
            photo_question=want.get('photoQuestion')
            photo_ok=photo_question is None or (len(msgs)==1 and msgs[0]['role']=='user' and
                    any(b.get('text')==photo_question for b in msgs[0]['content']))
            match=(same_first and photo_ok and texts==want.get('messages',texts) and images==want.get('images',images) and
                   all(t in contents for t in want.get('include',[])) and
                   all(t not in contents for t in want.get('exclude',[])) and
                   (summary if want.get('summary') else not summary and prefix==self.server.prefix))
            if not match:
                self.server.mismatches+=1
                self.send_error(400)
                return
            self.server.matched+=1
            self.stream(want['answer'])
            return
        if '测试新模型' in last or '核对恢复' in last:
            future='测试新模型' in last
            if (obj.get('model'),obj.get('max_tokens')) != (('fixture-future',8192) if future else ('deepseek-v4-pro',256000)):
                self.send_error(400)
                return
            self.server.seen.add('future-cap' if future else 'restored-cap')
            self.stream('收到')
            return
        if 'Condense the conversation ABOVE' in last:
            old_a, old_b = '甲' * 300 in contents, '乙' * 300 in contents
            if obj.get('max_tokens') != 2048 or old_a == old_b or '近三' in contents:
                self.send_error(400)
                return
            if old_a and 'file-turn' in self.server.seen and ('旧文件正文' not in contents or '[图 2]' not in contents or '"type": "image"' in contents):
                self.send_error(400)
                return
            if old_b and '摘要甲' not in contents:
                self.send_error(400)
                return
            self.server.summaries.append('a' if old_a else 'b')
            self.server.seen.add('summary-a' if old_a else 'summary-b')
            self.stream('摘要甲' if old_a else '摘要乙')
            return
        if '固定首问' in contents and obj.get('stream'):
            # Compare the actual provider envelope, including serialized roles and tools.
            prefix = [obj.get('system'),obj.get('tools'),msgs[0]]
            if self.server.prefix is None:
                self.server.prefix = prefix
            if prefix != self.server.prefix:
                self.send_error(400)
                return
            if len(msgs)>1:
                if self.server.first_reply is None:
                    self.server.first_reply=msgs[1]
                if msgs[1]!=self.server.first_reply:
                    self.send_error(400)
                    return
            answers={'固定首问':'首答', '旧甲':'旧图是紫色', '旧乙':'乙答', '近三':'三答',
                     '近四':'四答', '近五':'五答', '抬高用量':'六答', '核对自动':'七答'}
            if '旧甲' in last and '[文件：归档.docx]' in last and '旧文件正文' in last and '[图 2]' in last:
                self.server.seen.add('file-turn')
            if '抬高用量' in last or '核对自动' in last:
                auto = '核对自动' in last
                recent = ['近三','近四','近五','抬高用量'] if auto else ['旧乙'+'乙'*600,'近三','近四','近五']
                summary = '摘要乙' if auto else '摘要甲'
                texts=[(m['role'],b['text']) for m in msgs for b in m['content']
                       if isinstance(b,dict) and b.get('type')=='text']
                tail=[]
                for question in recent:
                    tail.extend([('user',question),('assistant',answers['旧乙' if question.startswith('旧乙') else question])])
                if (summary not in contents or '旧文件正文' in contents or '甲' * 300 in contents or (auto and '乙' * 300 in contents)
                        or texts[-9:-1]!=tail or '首答' not in contents):
                    self.send_error(400)
                    return
                self.server.seen.add('auto-tail' if auto else 'manual-tail')
            self.stream(next((answer for label,answer in answers.items() if label in last),'收到'),
                        638976 if '抬高用量' in last else 240)
            return
        if '额度回放' in json.dumps(msgs[-1], ensure_ascii=False):
            self.server.seen.add('quota')
            self.send_response(402)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"error":{"type":"billing_error","message":"Insufficient Balance"}}')
            return
        first = any(m['role'] == 'user' and isinstance(m['content'], list)
                    and any(x.get('type')=='text' and '看图' in x['text'] for x in m['content'])
                    and any(x.get('source',{}).get('file_id')=='file-pixel' for x in m['content'])
                    for m in msgs)
        second = '继续' in json.dumps(msgs[-1], ensure_ascii=False)
        if obj.get('model') != 'deepseek-flash' or obj.get('stream') is not True or (not second and not first):
            self.send_error(400)
            return
        if second:
            if (first or contents.count('image omitted')!=20 or '[图 10]' not in contents or '中段|[图 2]' in contents or '"type": "image"' in contents or
                    not any(m['role']=='assistant' and '紫色' in json.dumps(m,ensure_ascii=False) for m in msgs)):
                self.send_error(400)
                return
            self.server.seen.add('multi')
        else:
            images=[b.get('source',{}).get('file_id') for b in msgs[-1]['content'] if b.get('type')=='image']
            if (len(images)!=20 or images[:9]!=['file-pixel']+['file-pixel-'+str(i) for i in range(1,9)]
                    or images[9]==images[10] or images[9:]!=images[9:11]*5+[images[9]]):
                self.send_error(400)
                return
            # The actual provider envelope must keep each marker and its image together.
            pending=''
            index=0
            for block in msgs[-1]['content']:
                if block.get('type')=='text':
                    pending+=block['text']
                elif block.get('type')=='image':
                    if index>=9 and ('[图 '+str((index-9)%10+1)+']') not in pending:
                        self.send_error(400)
                        return
                    index+=1
                    pending=''
            if '段尾' not in pending or '[图 2]' in pending:
                self.send_error(400)
                return
            self.server.seen.add('twenty-images')
            self.server.seen.add('image-turn')
        self.stream('第二轮' if second else '紫', split=not second)

    @staticmethod
    def is_title(obj):
        return 'title' in str(obj.get('system','')).lower()

    def stream(self, answer, input_tokens=2, split=False):
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream')
        self.end_headers()

        def event(kind, **fields):
            body = json.dumps({'type':kind, **fields})
            self.wfile.write(f'event: {kind}\ndata: {body}\n\n'.encode())
            self.wfile.flush()

        event('message_start', message={'id':'replay','model':'deepseek-flash','usage':{'input_tokens':input_tokens}})
        event('content_block_start', index=0, content_block={'type':'text','text':''})
        event('content_block_delta', index=0, delta={'type':'text_delta','text':answer})
        if split and self.server.advance.wait(12):
            self.server.seen.add('early')
        if split:
            event('content_block_delta', index=0, delta={'type':'text_delta','text':'色'})
        event('content_block_stop', index=0)
        event('message_delta', delta={'stop_reason':'end_turn'},usage={'output_tokens':2})
        event('message_stop')


def run(abi, root, serial=None, scenario='context'):
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
        server.device_adventure=scenario=='adventure-ui'
        server.device_roll=None
        server.adventure=scenario=='adventure'
        server.expected=queue.Queue()
        server.matched=server.mismatches=0
        server.device_attachments=scenario=='attachments'
        server.advance=threading.Event()
        server.grant=secrets.token_urlsafe(24)
        server.seen=set()
        server.first_inputs={}
        server.prefix=None
        server.first_reply=None
        server.summaries=[]
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
        assets = ROOT/'app/src/main/assets'
        preset = root/'chat.yml'
        preset.write_text((assets/'chat.yml').read_text().replace('/etc/phone-context.cjs', str(assets/'phone-context.cjs')))
        cmd += [str(binary),'web','--patch',str(preset),
                '--patch',str(patch),'--no-open','--host','127.0.0.1','--port','0']
        env={'HOME':str(root),'DSH_HOME':str(home),'PATH':'/usr/bin:/bin','TMPDIR':str(root),
             'DSH_TELEMETRY_DISABLED':'1','NARB_NATIVE_CACHE_DIR':str(sysroot/'opt/native-cache'),
             'PKG_NATIVE_CACHE_PATH':str(sysroot/'opt/pkg-cache'),'NARB_DISABLE_LOCAL_BUILD':'1'}
        with subprocess.Popen(cmd, env=env,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                              stdin=subprocess.DEVNULL,start_new_session=True) as proc:
            try:
                if serial:
                    for line in proc.stdout:
                        launch=re.fullmatch(rb'dsh web: http://127\.0\.0\.1:(\d+)(/\?token=\S+)\s*', line)
                        if launch:
                            return device(serial, server, root, int(launch[1]), launch[2].decode(), scenario)
                    return False

                def pipe_launch():
                    for line in proc.stdout:
                        if re.fullmatch(rb'dsh web: http://127\.0\.0\.1:\d+/\?token=\S+\s*',line):
                            # Inherited anonymous pipe directly to the test client; never a console or log.
                            sys.stdout.buffer.write(line)
                            sys.stdout.flush()
                    sys.stdout.close()
                threading.Thread(target=pipe_launch,daemon=True).start()
                for line in sys.stdin:
                    if server.adventure and line.startswith('{'):
                        server.expected.put(json.loads(line))
                    if line.strip()=='advance':
                        server.advance.set()
                    if line.strip()=='done':
                        if server.adventure:
                            return server.matched>0 and not server.mismatches and server.expected.empty()
                        return (server.summaries == ['a','b'] and
                                server.seen=={'early','multi','image-turn','photo','quota','future-cap','restored-cap',
                                              'summary-a','summary-b','manual-tail','auto-tail','twenty-images','file-turn'})
            finally:
                if proc.poll() is None:
                    os.killpg(proc.pid,signal.SIGTERM)
                    try: proc.wait(timeout=3)
                    except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL)


def device(serial, server, root, runtime_port, bootstrap, scenario):
    adb = [str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'),'-s',serial]
    ports = []
    try:
        for port in [server.server_port,runtime_port]:
            port_arg = 'tcp:'+str(port)
            subprocess.run([*adb,'reverse',port_arg,port_arg],check=True,stdout=subprocess.DEVNULL)
            ports.append(port_arg)
        # Single-use handoff over adb loopback. No credential goes in argv or instrumentation output.
        server.handoff={'port':runtime_port,'path':bootstrap,'cwd':str(root)}
        result=subprocess.run([*adb,'shell','am','instrument','-w','-e','scenario',scenario,
                               '-e','fixturePort',str(server.server_port),
                               'com.sunwin.deepseekharness.test/com.sunwin.deepseekharness.DeviceSmoke'],
                              stdout=subprocess.PIPE,text=True,check=True)
        print(result.stdout, end='')
        print('Provider observations:',', '.join(sorted(server.seen)))
        if scenario=='adventure-ui':
            return 'passed=adventure-ui' in result.stdout and server.seen=={'entrance','silver','regen','edited-assistant','edited-user','fork'}
        if scenario=='attachments':
            return 'passed=attachments' in result.stdout and 'device-attachments' in server.seen
        return ('passed=context' in result.stdout and server.summaries == ['a','b'] and
                {'summary-a','summary-b','manual-tail','auto-tail'} <= server.seen)
    finally:
        for port in ports:
            subprocess.run([*adb,'reverse','--remove',port],check=False)


if __name__=='__main__':
    os.umask(0o077)
    if len(sys.argv)==3 and sys.argv[1] in ('--device','--device-attachments','--device-adventure'):
        with tempfile.TemporaryDirectory(prefix='phone-context-') as root:
            raise SystemExit(0 if run('arm64-v8a',Path(root),sys.argv[2], {'--device':'context','--device-attachments':'attachments','--device-adventure':'adventure-ui'}[sys.argv[1]]) else 1)
    if not all(stat.S_ISFIFO(os.fstat(fd).st_mode) for fd in (0,1)) or (len(sys.argv)!=3 and sys.argv[3:]!=['adventure']):
        raise SystemExit('Run the Gradle replay test.')
    raise SystemExit(0 if run(sys.argv[1],Path(sys.argv[2]),scenario=sys.argv[3] if len(sys.argv)==4 else 'context') else 1)
