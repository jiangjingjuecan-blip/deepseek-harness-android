package com.sunwin.deepseekharness;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.SSLException;

/** Only fixed labels, known DSH codes and validated numbers may enter history or the exported log. */
final class ChatFailure extends IOException {
    enum Stage { CREATE_SESSION, SELECT_MODEL, READ_PHOTO, FOLLOW, PROMPT, REPLY, SAVE_REPLY }

    private static final Set<String> CODES = new HashSet<>(Arrays.asList((
            "ACCOUNT_QUOTA QUOTA ACCOUNT_SIGN_IN_REQUIRED ACCOUNT_TOKEN_INVALID AUTH INVALID_CREDENTIAL MISSING_CREDENTIAL " +
            "CLIENT_UPGRADE_REQUIRED UNKNOWN_MODEL MODEL_UNSUPPORTED RATE_LIMIT TIMEOUT TRANSPORT SERVER STREAM_CLOSED " +
            "EMPTY_RESPONSE INVALID_REQUEST INVALID_RESPONSE MALFORMED_RESPONSE UNSUPPORTED_CONTENT FILES_API " +
            "DISCOVERY_FAILED INVALID_CONFIG INVALID_MODEL_CONTEXT INVALID_MODEL_INFO NO_ADAPTER " +
            "CONTEXT_WINDOW_EXCEEDED IMAGE_OFFLOAD_REQUIRED ABORTED TOOL_ACTIVATION_FAILED UNKNOWN " +
            "gateway/bad-request gateway/internal gateway/forbidden gateway/not-found gateway/method-unavailable " +
            "gateway/arguments-invalid gateway/input-invalid gateway/result-invalid gateway/binding-invalid gateway/signature-invalid " +
            "gateway/peer-disposed gateway/cancelled gateway/context-not-found session/conflict session/agent-busy " +
            "session/not-found session/model-unavailable session/attachment-invalid session/workspace-attach-failed workspace/not-found " +
            "MODEL_DOES_NOT_SUPPORT_IMAGES").split(" ")));
    private static final Set<String> SYSTEM_CODES = new LinkedHashSet<>(Arrays.asList(
            "ENOSYS", "EACCES", "EPERM", "ENOENT", "ENOSPC", "ENOTFOUND", "EAI_AGAIN", "ECONNREFUSED",
            "ECONNRESET", "ENETUNREACH", "EHOSTUNREACH", "ETIMEDOUT", "UND_ERR_CONNECT_TIMEOUT",
            "CERTIFICATE_VERIFY_FAILED", "CERT_HAS_EXPIRED", "UNABLE_TO_VERIFY_LEAF_SIGNATURE",
            "UNABLE_TO_GET_ISSUER_CERT_LOCALLY", "DEPTH_ZERO_SELF_SIGNED_CERT", "ERR_TLS_CERT_ALTNAME_INVALID",
            "ERR_DLOPEN_FAILED"));
    final String code;

    ChatFailure(String fixedLabel) { this(fixedLabel, ""); }
    private ChatFailure(String label, String code) { super(label); this.code = code; }
    String diagnostic() { return code.isEmpty() ? getMessage() : getMessage() + " [" + code + "]"; }

    static ChatFailure remote(JSONObject error, int http, String fallback) {
        String rawCode = error == null ? "" : error.optString("code");
        String code = CODES.contains(rawCode) ? rawCode : "";
        if (http == 0 && rawCode.matches("HTTP_[1-5][0-9]{2}")) http = Integer.parseInt(rawCode.substring(5));
        JSONObject details = error == null ? null : error.optJSONObject("details");
        String reason = details == null ? "" : details.optString("reason");
        String note = CODES.contains(reason) ? reason : "";
        if (error != null) {
            int status = error.optInt("status", 0);
            if (http == 0 && status >= 100 && status <= 599) http = status;
            String message = rawCode + " " + error.optString("message") + " " + reason;
            for (String system : SYSTEM_CODES) {
                if (message.contains(system)) { note = system; break; }
            }
            if (note.isEmpty() && (code.isEmpty() || "UNKNOWN".equals(code) || "gateway/internal".equals(code))) {
                String detail = message.toLowerCase(Locale.ROOT);
                if (detail.contains("insufficient balance") || detail.contains("insufficient quota")) note = "账号额度不足";
                else if (detail.contains("permission denied")) note = "文件权限不足";
                else if (detail.contains("fetch failed")) note = "网络请求失败";
                else if (detail.contains("timed out")) note = "请求超时";
            }
        }
        String label;
        switch (code) {
            case "QUOTA": case "ACCOUNT_QUOTA": label = "账号额度不足"; break;
            case "AUTH": case "ACCOUNT_TOKEN_INVALID": case "INVALID_CREDENTIAL": label = "账号鉴权失败"; break;
            case "ACCOUNT_SIGN_IN_REQUIRED": case "MISSING_CREDENTIAL": label = "账号未登录"; break;
            case "MODEL_UNSUPPORTED": case "UNKNOWN_MODEL": case "session/model-unavailable": label = "模型不可用"; break;
            case "session/not-found": label = "DSH 会话不存在"; break;
            case "session/agent-busy": case "session/conflict": label = "会话正在处理其他请求"; break;
            case "session/attachment-invalid": label = "图片被拒绝"; break;
            case "EMPTY_RESPONSE": label = "模型返回空回复"; break;
            case "TIMEOUT": label = "模型请求超时"; break;
            case "TRANSPORT": label = "模型连接失败"; break;
            case "RATE_LIMIT": label = "请求过于频繁"; break;
            case "SERVER": label = "模型服务出错"; break;
            case "INVALID_REQUEST": label = "模型拒绝了请求"; break;
            case "INVALID_RESPONSE": case "MALFORMED_RESPONSE": label = "模型响应格式不符"; break;
            case "CONTEXT_WINDOW_EXCEEDED": label = "对话超出模型长度上限"; break;
            case "STREAM_CLOSED": label = "模型回复连接已关闭"; break;
            case "FILES_API": label = "图片服务请求失败"; break;
            case "CLIENT_UPGRADE_REQUIRED": label = "模型要求更新 DSH"; break;
            case "UNKNOWN": label = note.isEmpty() ? fallback : ""; break;
            default: label = code;
        }
        if (!note.isEmpty()) label = label.isEmpty() || "UNKNOWN".equals(label) ? note : label + " · " + note;
        if (http > 0) label = "HTTP " + http + (label.isEmpty() ? "" : " · " + label);
        return new ChatFailure(label.isEmpty() ? fallback : label, code);
    }

    static ChatFailure turn(JSONObject reason, ChatFailure attempt) {
        if (reason == null) return new ChatFailure("缺少回合结束原因");
        switch (reason.optString("kind")) {
            case "error":
                ChatFailure terminal = remote(reason.optJSONObject("error"), 0, "");
                if (!terminal.getMessage().isEmpty()) return terminal;
                return attempt != null ? attempt : new ChatFailure("DSH 回合出错");
            case "completed": return new ChatFailure("回合结束但没有正文");
            case "aborted":
                JSONObject cause = reason.optJSONObject("reason");
                return new ChatFailure(cause != null && "user".equals(cause.optString("kind")) ? "已取消回复" : "DSH 已取消回复");
            case "blocked": return new ChatFailure("DSH 已阻止回复");
            case "max-tokens": return new ChatFailure("达到输出上限");
            case "interrupted": return new ChatFailure("DSH 回合中断");
            case "forked": return new ChatFailure("DSH 会话已分叉");
            default: return new ChatFailure("回合结束原因无法识别");
        }
    }

    static ChatFailure wsClosed(int code, String reason) {
        String label = "WebSocket 已关闭（" + code + "）";
        switch (reason) {
            case "text messages required": return new ChatFailure(label + " 需要文本帧");
            case "invalid Remote stream request": return new ChatFailure(label + " 订阅请求无效");
            case "Remote stream failure could not be delivered": return new ChatFailure(label + " 订阅错误未送达");
            default: return new ChatFailure(label);
        }
    }

    static ChatFailure caught(Throwable e, Stage stage) {
        if (e instanceof ChatFailure) return (ChatFailure)e;
        if (e instanceof SocketTimeoutException) return new ChatFailure(
                stage == Stage.FOLLOW || stage == Stage.REPLY ? "WebSocket 超时" : "本机 HTTP 超时");
        if (e instanceof ConnectException) return new ChatFailure("无法连接本机 DSH");
        if (e instanceof InterruptedException) return new ChatFailure("回复线程已中断");
        if (e instanceof android.database.sqlite.SQLiteFullException) return new ChatFailure("本机存储空间不足");
        if (e instanceof android.database.sqlite.SQLiteException) return new ChatFailure("对话记录读写失败");
        if (e instanceof JSONException) return new ChatFailure("DSH 响应格式不符");
        if (stage == Stage.READ_PHOTO) return new ChatFailure("图片读取失败");
        if (e instanceof SSLException) return new ChatFailure("TLS 连接失败");
        if (e instanceof IOException) return new ChatFailure("连接已断开");
        return new ChatFailure("本机回复处理失败");
    }
}
