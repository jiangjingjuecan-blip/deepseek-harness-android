# iQOO 11 的零正文与「回复已中断」

旧版把本机请求失败、订阅断开、模型拒绝请求等情况都存成同一个「已中断」状态。尤其是 DSH 已接受消息、随后在第一段文字之前发出 `turn/end` 时，旧版丢掉了 `data.reason.error`。这解释了为什么文字和图片可以出现完全相同的空气泡。手机当时缺少失败阶段和错误码，无法从截图还原触发那两次失败的原因。

标题「你好」来自本地 SQLite，在创建远程会话之前就已写入。副标题 `deepseek-flash` 来自模型目录。固定版本 DSH 的账号列表请求失败时，仍会返回内置模型或上次成功的目录。浏览器完成授权、DSH 保存凭据、账号资料可读和推理请求成功，是四次不同的观察。[本地历史](../app/src/main/java/com/sunwin/deepseekharness/History.java) · [DSH 的目录回退](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/llm/llm-deepseek/src/listing-cache.ts#L139-L164)

## 真实账号与协议对照

2026-11-24 的 Linux x86_64 实测使用了与 APK 同版的官方 DSH 0.2.2-rc.2 和同一份聊天预设。浏览器停在官方授权完成页，本机随后读到 `credential-stored`。账号资料返回 `ready`，钱包有余额，`deepseek-account / deepseek-flash` 实际完成了以下两轮。

| 同一会话中的请求 | prompt 回执 | 首段文字耗时 | 非空文字增量 | 最终正文 | 回合结束 |
| --- | --- | ---: | ---: | ---: | --- |
| 「你好」 | HTTP 200，accepted | 0.88 秒 | 8 帧 | 14 字符 | completed |
| 文字和图片 | HTTP 200，accepted | 3.25 秒 | 305 帧 | 528 字符 | completed |

计时起点是发出 `session/prompt`。Android 与 Python 均通过回环凭据及相同的 Origin 连接 DSH，依次创建会话、选择账号模型、订阅 `/api/remote.mux` 的 `session/follow`、等待首个 `snapshot`，然后提交消息。两次真实握手都返回 101，两个 prompt 都由 HTTP 返回接受回执，正文和结束原因由 WebSocket 送达。手机端把导入的图片转成 JPEG，与问题一起放入同一条消息。[发送流程](../app/src/main/java/com/sunwin/deepseekharness/ChatEngine.java) · [复现脚本](prototype/after_login_repro.md)

0.1.1 复用了这两轮的脱敏逐帧记录。按记录中的长度填充正文，再交给新版 Java 解析器，两轮都得到表中的增量数、正文长度和结束状态。独立回放还让应用的 `Account`、`ChatEngine`、OkHttp 和 SQLite 连接打包用的官方 ARM64 DSH，由本地服务模拟推理。图片轮先落库「紫」、再完成「紫色」，续聊读回「第二轮」。模拟服务对纯文字和图文各返回一次 HTTP 402，两轮都复现了零正文，气泡对应的数据库行均记录「HTTP 402 · 账号额度不足」。本次验证新增真实推理 0 次，前后钱包差额为 0 元。早先两轮仅记录了钱包非零，缺少计算其实际扣款的起始余额。[ARM64 回放测试](../app/src/test/java/com/sunwin/deepseekharness/HarnessReplayTest.java)

## 0.1.1 在手机上如何处理

- 本机 RPC 拒绝、订阅握手失败、快照超时和模型返回的结束原因分别处理。DSH 若给出 HTTP 状态或可识别的 DNS、TLS、系统错误码，气泡会保留相应短原因。已经收到的正文和错误分栏存储。升级保留旧对话，旧版已结束的行没有可补回的原因。[错误提取](../app/src/main/java/com/sunwin/deepseekharness/ChatFailure.java)
- 每轮用本次请求 ID 匹配 DSH 的入队事件，再跟踪回合编号和流式尝试编号。上一轮迟到的文字或结束帧会被跳过。正式结束事件缺少具体错误时，会采用当前尝试已报告的原因。[回复解析](../app/src/main/java/com/sunwin/deepseekharness/Reply.java)
- 聊天页只有在账号资料就绪、DSH 已成功加载在线目录时，才把所选模型单独显示为副标题。目录回退时显示「内置模型目录」或「上次的模型目录」，来源未知时显示「模型目录待确认」。目录 RPC 失败会清除失效的选择。已存凭据且有候选模型时仍可发送，单独的钱包或资料读取失败不会拦截消息。[账号状态](../app/src/main/java/com/sunwin/deepseekharness/Account.java)
- 旧版在默认网络回调里又查询一次当前网络，启动线程也会写客机的解析器配置。两次读取可能对应不同的网络。新版直接采用回调携带的 DNS，串行更新 `resolv.conf`，启动时优先使用已收到的网络状态。客机继续使用 APK 内的 CA 证书包和 Node 的证书校验。手机 PRoot 的 DNS、TLS 与推理出口仍需该机的错误来区分。[客机网络配置](../app/src/main/java/com/sunwin/deepseekharness/Guest.java) · [Android 网络回调](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#onLinkPropertiesChanged(android.net.Network,%20android.net.LinkProperties))

手机再次出现中断时，可在设置中导出诊断日志。`CHAT_INTERRUPTED` 记录失败阶段和短原因，如 `FOLLOW WebSocket 握手失败 · HTTP 403` 或 `REPLY HTTP 402 · 账号额度不足 [ACCOUNT_QUOTA]`。模型单次尝试、目录来源、DSH 退出和应用重启恢复也有独立记录。[诊断日志](../app/src/main/java/com/sunwin/deepseekharness/Diagnostics.java)
