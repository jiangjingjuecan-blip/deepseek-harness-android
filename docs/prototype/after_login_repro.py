"""在已启动的官方 DSH Web 实例上复现 Android 的两轮对话。"""
import argparse
import asyncio
import base64
import hmac
import io
import json
import os
import re
import sys
import tempfile
import time
import uuid
from contextlib import suppress
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

import aiohttp

ROOT = Path("/tmp/dsh-repro")
READY = ROOT / "LOGIN_READY.txt"
CLIENT = {"version": "0.2.2-rc.2", "locale": "zh-CN",
          "timezoneOffsetSeconds": int(datetime.now().astimezone().utcoffset().total_seconds())}
PHASES = {"initializing", "waiting-browser", "exchanging", "committing", "expired", "failed", "cancelled"}
REDACTED = "[已遮盖]"
ERROR_CODES = set("""AUTH QUOTA ACCOUNT_QUOTA ACCOUNT_TOKEN_INVALID ACCOUNT_SIGN_IN_REQUIRED RATE_LIMIT
    INVALID_CREDENTIAL MISSING_CREDENTIAL TIMEOUT TRANSPORT SERVER UNKNOWN EMPTY_RESPONSE ABORTED STREAM_CLOSED
    INVALID_REQUEST MALFORMED_RESPONSE INVALID_RESPONSE MODEL_UNSUPPORTED UNKNOWN_MODEL REQUEST_HEADERS
    CLIENT_UPGRADE_REQUIRED CONTEXT_WINDOW_EXCEEDED UNSUPPORTED_CONTENT FILES_API IMAGE_OFFLOAD_REQUIRED
    gateway/internal gateway/bad-request gateway/method-unavailable gateway/arguments-invalid gateway/binding-invalid
    gateway/input-invalid gateway/result-invalid gateway/signature-invalid gateway/peer-disposed gateway/cancelled
    session/model-unavailable session/agent-busy session/conflict session/not-found session/attachment-invalid
    session/workspace-attach-failed workspace/not-found MODEL_DOES_NOT_SUPPORT_IMAGES""".split())


class Failure(Exception):
    """异常消息可以安全显示。"""


TRACE_FIELDS = set("""body frame method status type rpcId payload args request sessionId agentPreset cwd
    requestId mode content text data mediaType name streamId endpoint address kind assistantStream maxMessages
    result ok value accepted error code message details reason failure groups models id provider model
    failures records projections cursor header revision event seq time surfaceOp target inserted source turn
    step attemptId index chunk startedAfterSeq outcome role stream usage inputTokens outputTokens
    cacheReadTokens cacheWriteTokens totalTokens label assistantText textDeltas closeCode""".split())
TRACE_VALUES = set("""client-request client-response item open end error reply session/follow session/prompt
    session/create session/selectModel session/modelCatalog session/cancel account/getProfile account/getBalance
    session queue snapshot event assistant-stream start chunk finish text-delta text image image/png image/jpeg
    assistant/message assistant/attempt turn/start turn/end step/start step/end user/message agent/inbox/spliced
    next-turn next-step user assistant append completed aborted interrupted blocked forked max-tokens
    deepseek-account deepseek-flash phone-chat 纯文字 文字和图片""".split())
TRACE_NUMBERS = set("""status closeCode cursor revision seq time turn step startedAfterSeq index maxMessages
    inputTokens outputTokens cacheReadTokens cacheWriteTokens totalTokens textDeltas""".split())
SYSTEM_CODES = ("ENOSYS", "EACCES", "ENOSPC", "ENOTFOUND", "EAI_AGAIN", "ECONNREFUSED", "ECONNRESET",
                "ETIMEDOUT", "ENETUNREACH", "EHOSTUNREACH", "CERT_HAS_EXPIRED", "UNABLE_TO_VERIFY_LEAF_SIGNATURE")
TRACE_KEY = os.urandom(32)


def scrub(value, key=""):
    # Wire data can contain credentials even under unknown keys or across adjacent deltas.
    # Keep structural facts and correlate IDs only within this run.
    if isinstance(value, dict):
        return {k: scrub(v, k) for k, v in value.items() if k in TRACE_FIELDS}
    if isinstance(value, list):
        return [scrub(v, key) for v in value]
    if key == "code":
        return value if isinstance(value, str) and (value in ERROR_CODES or re.fullmatch(r"HTTP_[1-5]\d\d", value)) else REDACTED
    if isinstance(value, str):
        if key in {"text", "content", "data"}:
            return f"[{len(value)} 字符]"
        if key in {"sessionId", "requestId", "rpcId", "attemptId"}:
            return hmac.digest(TRACE_KEY, value.encode(), "sha256").hex()[:16]
        if key == "message":
            return next((c for c in SYSTEM_CODES if c in value), REDACTED)
        return value if value in TRACE_VALUES else REDACTED
    if type(value) in (float, int) and key not in TRACE_NUMBERS:
        return REDACTED
    return value


class Trace:
    def __init__(self, file):
        self.file = file
        self.start = time.monotonic()

    def write(self, event, **fields):
        if self.file:
            payload = scrub(fields)
            if event == "turn.result":
                # run_turn constructs this label from local stages and allowlisted error facts.
                payload["error"] = fields["error"]
            row = {"s": round(time.monotonic() - self.start, 3), "event": event, **payload}
            self.file.write(json.dumps(row, ensure_ascii=False) + "\n")
            self.file.flush()


def error_text(error):
    if not isinstance(error, dict):
        return "DSH 未提供结构化原因"
    parts = []
    if type(error.get("status")) is int and 100 <= error["status"] <= 599:
        parts.append(f"HTTP {error['status']}")
    for key in ("code", "message"):
        field = error.get(key)
        if isinstance(field, str) and field:
            safe = scrub(field, key)
            if safe != REDACTED:
                parts.append(safe)
    return " · ".join(parts) or "DSH 未提供原因"


def response_body(raw):
    try:
        return json.loads(raw)
    except (ValueError, UnicodeError):
        return raw.decode("utf-8", "replace")


def wallet_totals(value):
    if not isinstance(value, dict) or value.get("status") != "ready":
        return {}
    wallets = (value.get("value") or []) + (value.get("bonusWallets") or [])
    amounts = {}
    try:
        for wallet in wallets:
            currency = wallet.get("currency")
            if currency in {"CNY", "USD"}:
                amount = Decimal(wallet["balance"])
                if not amount.is_finite():
                    return {}
                amounts[currency] = amounts.get(currency, 0) + amount
        return amounts
    except (KeyError, TypeError, InvalidOperation):
        return {}


async def balance(api, label):
    try:
        amounts = wallet_totals(await api.rpc("account/getBalance", {"client": CLIENT}))
    except Failure:
        amounts = {}
    print(label, " · ".join(f"{c} {n:f}" for c, n in amounts.items()) or "未取得")
    return amounts


class Local:
    def __init__(self, client, info, cookie):
        self.client = client
        self.info = info
        self.origin = f"http://127.0.0.1:{info['port']}"
        self.headers = {"Origin": self.origin, "Cookie": cookie}
        self.trace = Trace(None)

    async def rpc(self, method, args=None):
        rpc_id = str(uuid.uuid4())
        body = {"type": "client-request", "rpcId": rpc_id, "method": method, "payload": {"args": args or {}}}
        self.trace.write("http.send", method=method, body=body)
        try:
            async with self.client.post(self.origin + "/api/" + method, json=body, allow_redirects=False,
                                        headers={**self.headers, "Connection": "close",
                                                 "Content-Type": "application/json; charset=utf-8"}) as response:
                status = response.status
                raw = await response.read()
        except TimeoutError:
            raise Failure(f"{method} HTTP 超时") from None
        except aiohttp.ClientError as exc:
            raise Failure(f"{method} 连接失败（{type(exc).__name__}）") from None
        envelope = response_body(raw)
        # Account views can contain the browser URL, profile and wallet.
        result = envelope.get("result") if isinstance(envelope, dict) else None
        account_view = method.startswith("account/") and isinstance(result, dict) and result.get("ok") is True
        public = "[账号视图]" if account_view else envelope
        self.trace.write("http.recv", method=method, status=status, body=public)
        if not 200 <= status < 300:
            error = envelope.get("error", envelope) if isinstance(envelope, dict) else envelope
            raise Failure(f"{method} HTTP {status} · {error_text(error)}")
        if not isinstance(envelope, dict) or envelope.get("rpcId") != rpc_id:
            raise Failure(f"{method} RPC 响应无效")
        if not isinstance(result, dict):
            raise Failure(f"{method} 缺少 RPC 结果")
        if result.get("ok") is not True:
            raise Failure(f"{method} · {error_text(result.get('error'))}")
        return result.get("value")

    async def follow(self, session):
        try:
            socket = await self.client.ws_connect(self.origin + "/api/remote.mux", headers=self.headers,
                                                  heartbeat=20, compress=15, max_msg_size=0)
        except aiohttp.WSServerHandshakeError as exc:
            self.trace.write("ws.handshake", status=exc.status)
            raise Failure(f"WebSocket HTTP {exc.status}") from None
        self.trace.write("ws.handshake", status=101)
        frame = {"type": "open", "streamId": "reply", "endpoint": "session/follow", "payload": {"args": {
            "request": {"address": {"kind": "session", "sessionId": session}, "assistantStream": True, "maxMessages": 4}}}}
        self.trace.write("ws.send", frame=frame)
        try:
            await socket.send_json(frame)
        except BaseException:
            await socket.close()
            raise
        return socket

    async def item(self, ws):
        msg = await ws.receive()
        if msg.type != aiohttp.WSMsgType.TEXT:
            code = ws.close_code
            reason = ""
            if msg.type == aiohttp.WSMsgType.ERROR and ws.exception():
                reason = type(ws.exception()).__name__
            self.trace.write("ws.control", kind=msg.type.name, closeCode=code, reason=reason)
            raise Failure(f"WebSocket {msg.type.name} {code or ''} {reason[:80]}".strip())
        try:
            frame = json.loads(msg.data)
        except ValueError:
            self.trace.write("ws.invalid")
            raise Failure("WebSocket 帧不是 JSON") from None
        self.trace.write("ws.recv", frame=frame)
        if not isinstance(frame, dict) or frame.get("streamId") != "reply":
            raise Failure("WebSocket 流标识不匹配")
        if frame.get("type") == "error":
            raise Failure("session/follow · " + error_text(frame.get("error")))
        if frame.get("type") == "end":
            raise Failure("订阅提前结束")
        if frame.get("type") != "item" or not isinstance(frame.get("value"), dict):
            raise Failure("WebSocket 帧格式不符")
        return frame["value"]


def photo():
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (72, 20), "white")
    ImageDraw.Draw(im).text((6, 3), "TEST 123", fill="black")
    data = io.BytesIO()
    im.resize((216, 60), Image.Resampling.NEAREST).save(data, format="PNG")
    return {"type": "image", "mediaType": "image/png", "name": "test.png",
            "data": base64.b64encode(data.getvalue()).decode("ascii")}


def login_state(view):
    if view.get("status") == "credential-stored":
        return "credential-stored"
    attempt = view.get("attempt") or {}
    phase = attempt.get("phase")
    return "未登录" + (f"（{phase}）" if phase in PHASES else "")


async def login_link(api, view):
    attempt = view.get("attempt") or {}
    if view.get("status") == "credential-stored" or attempt.get("phase") in {"exchanging", "committing"}:
        print(login_state(view))
        return
    if attempt.get("phase") not in {"initializing", "waiting-browser"}:
        view = await api.rpc("account/startSignIn", {"client": CLIENT, "callbackOrigin": api.origin, "loginSource": "web"})
    async with asyncio.timeout(45):
        while (view.get("attempt") or {}).get("phase") == "initializing":
            await asyncio.sleep(0.2)
            view = await api.rpc("account/getState")
    attempt = view.get("attempt") or {}
    if attempt.get("phase") != "waiting-browser":
        raise Failure("官方授权入口未就绪 · " + login_state(view))
    url = attempt["authorizeUrl"]
    parsed = urlsplit(url)
    if ((parsed.scheme, parsed.netloc, parsed.path) != ("https", "platform.deepseek.com", "/dsh/authorize")
            or parsed.fragment or [k for k, _ in parse_qsl(parsed.query)] != ["authorize_id"]):
        raise Failure("授权 URL 格式异常")
    expires = datetime.fromtimestamp(attempt["expiresAt"] / 1000, timezone.utc).astimezone().isoformat(timespec="seconds")
    info = api.info
    content = (f"官方浏览器授权（DSH 0.2.2-rc.2，linux-x64）\n{url}\n本次授权截止时间 {expires}\n"
               f"回环服务 {api.origin}\n回调 {api.origin}/oauth/callback\nDSH 监听端口 {info['port']}\nDSH PID {info['pid']}\n"
               "请在系统浏览器完成官方授权。\n"
               "检查登录（仓库根目录） python3 docs/prototype/after_login_repro.py --status\n"
               "本机 POST /api/account/getState 返回 result.value.status=credential-stored 表示已保存登录。\n"
               "链接过期后运行 python3 docs/prototype/after_login_repro.py --login-link 更新本文件。\n")
    with tempfile.NamedTemporaryFile("w", dir=READY.parent, prefix=".login-ready-", encoding="utf-8", delete=False) as f:
        f.write(content)
    os.replace(f.name, READY)
    print("浏览器授权入口已写入本机私有文件", READY)


async def run_turn(api, session, model, content, label):
    ws = None
    submitted = False
    saw_text = False
    deltas = 0
    ended = False
    reason = ""
    partial = committed = ""
    stage = "session/selectModel"
    try:
        await api.rpc(stage, {"request": {"sessionId": session, "provider": "deepseek-account", "model": model}})
        stage = "session/follow"
        async with asyncio.timeout(25):
            ws = await api.follow(session)
            if (await api.item(ws)).get("type") != "snapshot":
                raise Failure("首帧缺少 snapshot")
        stage = "session/prompt"
        submitted = True
        result = await api.rpc(stage, {"request": {"sessionId": session, "requestId": str(uuid.uuid4()),
                                                  "mode": "queue", "content": content}})
        if result.get("accepted") is not True:
            raise Failure("DSH 未接受消息")
        stage = "等待回复事件"
        while not ended:
            item = await asyncio.wait_for(api.item(ws), 180)
            if item.get("type") == "assistant-stream":
                frame = item["frame"]
                chunk = frame.get("chunk", {})
                if frame["type"] == "start":
                    partial = ""
                if chunk.get("type") == "text-delta":
                    partial += chunk["text"]
                    saw_text |= bool(chunk["text"])
                    deltas += int(bool(chunk["text"]))
            elif item.get("type") == "event":
                event = item["event"]
                if event["type"] == "assistant/message":
                    parts = event["data"]["message"]["content"]
                    full = "".join(p["text"] for p in parts if p.get("type") == "text")
                    committed += full
                    partial = ""
                    saw_text |= bool(full)
                elif event["type"] == "turn/end":
                    ended = True
                    why = event["data"]["reason"]
                    if why.get("kind") == "error":
                        reason = error_text(why.get("error"))
                    elif why.get("kind") != "completed":
                        reason = "turn/end · " + scrub(why.get("kind"), "kind")
                    elif not (committed or partial):
                        reason = "回合结束但没有正文"
    except Failure as exc:
        reason = str(exc)
    except TimeoutError:
        reason = stage + "超时"
    except (aiohttp.ClientError, OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        reason = f"{stage} · {type(exc).__name__}"
    finally:
        if submitted and not ended:
            with suppress(Failure):
                await api.rpc("session/cancel", {"request": {"sessionId": session}})
        if ws is not None:
            await ws.close()
    api.trace.write("turn.result", label=label, assistantText=saw_text, textDeltas=deltas, error=reason)
    outcome = f"收到正文（{deltas} 个非空增量）" if saw_text else "零正文"
    print(f"{label}　{outcome}" + (f"　{reason}" if reason else ""))
    return not reason


async def repro(api):
    pic = photo()
    with tempfile.NamedTemporaryFile("w", dir=ROOT, prefix="turns-", suffix=".jsonl", encoding="utf-8", delete=False) as log:
        print("诊断记录", log.name, flush=True)
        api.trace = Trace(log)
        catalog = await api.rpc("session/modelCatalog")
        models = [m["id"] for g in catalog["groups"] if g["id"] == "deepseek-account" for m in g["models"]]
        if not models:
            raise Failure("账号模型列表为空，两轮均未发送")
        model = "deepseek-flash" if "deepseek-flash" in models else models[0]
        print("账号模型", model if model == "deepseek-flash" else "目录首项")
        profile = await api.rpc("account/getProfile", {"client": CLIENT})
        print("账号资料", "就绪" if (profile or {}).get("status") == "ready" else "未就绪")
        before = await balance(api, "发送前余额")
        session = await api.rpc("session/create", {"request": {"cwd": api.info["cwd"], "agentPreset": "phone-chat"}})
        sid = session["sessionId"]
        passed = True
        try:
            for label, question in (("纯文字", "只回一个好字"), ("文字和图片", "只回答图中的文字")):
                parts = [{"type": "text", "text": question}]
                if label == "文字和图片":
                    parts.append(pic)
                passed = await run_turn(api, sid, model, parts, label) and passed
        finally:
            after = await balance(api, "发送后余额")
            for currency in sorted(before.keys() & after.keys()):
                print("余额减少", currency, format(before[currency] - after[currency], "f"))
        return 0 if passed else 1


async def main(options):
    try:
        info = json.loads((ROOT / "instance.json").read_text())
        cookie = (ROOT / "loopback.cookie").read_text().strip()
    except FileNotFoundError:
        raise Failure("找不到本次 DSH 的本机会话") from None
    if type(info["port"]) is not int or not 0 < info["port"] < 65536 or not cookie:
        raise Failure("本机 DSH 连接信息无效")
    # aiohttp follows redirects for WS upgrades. Match OkHttp's disabled redirects.
    hooks = aiohttp.TraceConfig()
    async def handshake_headers(client, ctx, params):
        if params.url.path == "/api/remote.mux":
            params.headers["Sec-WebSocket-Extensions"] = "permessage-deflate"

    async def handshake_response(client, ctx, params):
        res = params.response
        if params.url.path == "/api/remote.mux" and res.status != 101:
            api.trace.write("ws.http-error", status=res.status, body=response_body(await res.read()))

    async def reject_redirect(client, ctx, params):
        await handshake_response(client, ctx, params)
        raise Failure(f"WebSocket HTTP {params.response.status}（重定向）")
    hooks.on_request_start.append(handshake_headers)
    hooks.on_request_end.append(handshake_response)
    hooks.on_request_redirect.append(reject_redirect)
    timeout = aiohttp.ClientTimeout(total=None, sock_connect=5, sock_read=45)
    async with aiohttp.ClientSession(timeout=timeout, cookie_jar=aiohttp.DummyCookieJar(),
                                     headers={"User-Agent": "okhttp/4.12.0", "Accept-Encoding": "gzip"},
                                     skip_auto_headers={"Accept"},
                                     trust_env=False, trace_configs=[hooks]) as client:
        api = Local(client, info, cookie)
        view = await api.rpc("account/getState")
        if options.login_link:
            await login_link(api, view)
            return 0
        if options.status or view.get("status") != "credential-stored":
            print(login_state(view))
            return 0 if view.get("status") == "credential-stored" else 2
        if options.balance:
            return 0 if await balance(api, "余额") else 1
        return await repro(api)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--status", action="store_true", help="只检查登录状态")
    group.add_argument("--balance", action="store_true", help="只读取钱包合计")
    group.add_argument("--login-link", action="store_true", help="取得有效的浏览器授权链接")
    try:
        sys.exit(asyncio.run(main(parser.parse_args())))
    except Failure as exc:
        sys.exit(str(exc))
    except (OSError, ValueError, KeyError, TypeError, AttributeError, ImportError, aiohttp.ClientError) as exc:
        sys.exit(f"复现失败（{type(exc).__name__}）")
