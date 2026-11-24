# DeepSeek Harness 安卓接入验证

文内所有来源的访问日期均为 **2026-11-23**。目标手机是 vivo iQOO 11，验证以[用户需求原文](REQUIREMENTS_user_v2.md)为准。

**按原要求，建议暂不开发。** DeepSeek Harness 有官方账号模式，用的是开放平台余额。官方没有发布可直接集成到安卓 App 的 Harness 运行时，也没公开让第三方 App 自动拉取网页旧聊天并写回原会话的接口。要接着同一条云端记录聊，可以用官方客户端。要自己做原生界面，可以用官方 API，在手机里导入一份旧记录。

## 1. 有官方 DeepSeek Harness 吗？iQOO 11 能本地运行吗？

**产品有。官方 SDK 在 Linux 上已跑通，iQOO 11 本地运行尚未通过验证。** DeepSeek Harness（简称 DSH）由杭州深度求索人工智能基础技术研究有限公司提供，是开源预览版。它在本机运行会话、工具和插件，把需要推理的内容发给选定的模型服务。官网链接到 `deepseek-ai/deepseek-harness`。仓库代码使用 MIT 许可证，依赖各有许可证，调用在线服务还受用户协议约束。[产品官网](https://www.deepseek.com/harness/) · [官方仓库与许可证](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.3-alpha.2/LICENSE) · [Harness 用户协议](https://www.deepseek.com/harness/terms-of-use/)

| 官方发布物 | 11 月 23 日查到的版本 | 实际运行方式 |
| --- | --- | --- |
| npm 命令行 `@deepseek-ai/dsh` | `latest` 和 `next` 都是 **0.2.2-rc.5**（11-18）。更新的 `alpha` 是 **0.2.3-alpha.2**（11-22）。两者都是预发布版。 | 需要 Node.js 24.9.0 或更高版本。用 `dsh web` 启动本机服务和网页界面。[npm 元数据](https://registry.npmjs.org/@deepseek-ai%2fdsh) · [GitHub 发布记录](https://github.com/deepseek-ai/deepseek-harness/releases) |
| 官网桌面安装包 | 本次读取 Windows 安装包的版本字段为 **0.2.2-rc.5**，下载服务器标记的文件修改日期为 11-19。 | 官网提供 Windows 和 macOS 桌面版。本轮只读取 Windows 安装包元数据。[官方下载页](https://www.deepseek.com/download/) · [Windows 安装包](https://download.deepseek.com/desktop/dsh-latest-windows-x64.exe) |
| TypeScript 客户端 `@deepseek-ai/dsh-sdk-client` | `next` 为 0.2.2-rc.5（11-18），`alpha` 为 0.2.3-alpha.2（11-22）。这个包自己的 `latest` 仍指向 0.0.1-rc.1。 | 用 Node 的 `child_process` 启动 DSH，通过标准输入输出收发 JSON-RPC。普通 WebView 或 React Native 的 JS 环境不能直接代替这个子进程。[npm 元数据](https://registry.npmjs.org/@deepseek-ai%2fdsh-sdk-client) · [客户端实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.3-alpha.2/packages/sdk/client/src/client.ts) |
| PyPI 的 `deepseek-harness-sdk` 与 `deepseek-harness-runtime-bin` | 两个包最新都是 **0.2.2rc2**（11-11），SDK 固定依赖同版本运行时。 | Python ≥3.10。Python 包本身标为通用，真正执行 DSH 的二进制只发给 Linux x64/arm64、macOS x64/arm64 和 Windows x64。Linux arm64 包依赖 glibc，安卓使用 Bionic。[Python SDK](https://pypi.org/project/deepseek-harness-sdk/0.2.2rc2/) · [运行时文件清单](https://pypi.org/project/deepseek-harness-runtime-bin/0.2.2rc2/#files) |

安卓和 iOS 的官方下载是 **DeepSeek 聊天 App**。同一下载页把 **Harness 桌面端**列在 macOS 和 Windows 下。查过官方发布目录和包注册表，没找到官方的 Kotlin/JVM 库、AAR、Android NDK 包、React Native 模块或 iOS SDK。Maven Central 按 `deepseek-harness` 查到 0 项，Swift Package Index 同名查询也为 0。官方另有实验性 Web Worker，可以在浏览器里跑预览，但它的本地 HTTP 通道不监听真实端口，不能据此认定手机浏览器能完成 Harness 的登录回调。[官方下载页](https://www.deepseek.com/download/) · [运行时目标清单](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.3-alpha.2/python/sdk-runtime/platforms.json) · [Maven 查询](https://search.maven.org/solrsearch/select?q=deepseek-harness&rows=20&wt=json) · [Swift 查询](https://swiftpackageindex.com/search?query=deepseek-harness) · [Web Worker 限制](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.3-alpha.2/packages/experimental/webworker-runtime/README.md)

**如果必须保留 Harness 核心，最接近的做法是在手机上运行完整 DSH。** 有社区开发者报告在 Termux 里补齐原生依赖后启动了 DSH。另一个社区项目 DSHA 在安卓上带了一套 ARM64 Linux 环境，并接入 DSH 登录回调。两种做法的适配和更新都由社区维护。我们没有在 iQOO 11 上安装这些包，也没有可证明网页历史能随之导入的材料。[开发者的实机记录](https://github.com/deepseek-ai/deepseek-harness/discussions/136) · [DSHA 项目自述](https://github.com/DSH-APP/DSHA) · [DSHA 的回调说明](https://github.com/DSH-APP/DSHA/blob/main/docs/ports-and-networking.md)

## 2. 第三方安卓 App 能用 DeepSeek 账号授权登录吗？

**没有查到向独立第三方 App 开放的通用 OAuth。Harness 自己有官方账号授权。** 开放平台公开的接入步骤是由用户创建 API Key。官方 Harness 提供 `@deepseek-ai/dsh-deepseek-account` 管登录、退出和余额，`@deepseek-ai/dsh-llm-deepseek-account` 管账号模型调用。能运行完整 DSH 的客户端可复用这些组件。没找到普通第三方 App 可申请的 `client_id`、可选的授权范围（scope）、自定义回调登记或“读取网页聊天”权限。11 月 24 日，本机 Linux 版已完成真实账号授权和图文实测。[实测结果](INTERRUPT.md) · [开放平台首次调用](https://api-docs.deepseek.com/zh-cn/) · [账号组件说明](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/README.zh.md) · [账号模型组件说明](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/llm/llm-deepseek-account/README.zh.md)

账号组件要求本机 DSH 已启动 Web 服务。默认的无界面 SDK 配置只开标准输入输出，不会替你启动接收浏览器回调的服务。[SDK 默认配置的限制](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/bundle/sdk-app/README.zh.md)

1. 本机 DSH 先监听 `http://127.0.0.1:<端口>/oauth/callback`。也可用带显式端口的 `localhost` 或 `[::1]`。它向 `https://platform.deepseek.com/auth-api/v0/dsh/auth_init` 发起 POST，带上回调地址、随机 `state`、PKCE S256 校验信息和来源（`web` 或 `desktop`）。
2. 系统浏览器打开平台返回的 `/dsh/authorize`，由用户在官方页面登录并确认。浏览器回到本机后，DSH 校验 `state`，向 `POST /auth-api/v0/dsh/auth_exchange` 提交一次性授权码、校验值和相同的回调地址，再跳到官方完成页 `/dsh/authorized`。授权凭据留在本机 DSH，由组件用于查询资料、余额和请求模型。这套流程不要求自建云服务器，但手机上必须有能接收回调的 DSH 进程。[登录实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/src/index.ts#L721-L754) · [接口和来源限制](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/src/protocol.ts)
3. 授权初始化完成后，取消登录会调用 `POST /auth-api/v0/dsh/auth_cancel`。退出用 `signOut`，先删除本机授权，再在后台向平台的 `POST /auth-api/v0/users/logout` 请求撤销会话。组件区分“已存凭据”和“平台已确认身份”，显示登录状态时还要看资料刷新是否成功。[账号状态与退出](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/README.zh.md)

独立 App 若改走 API，让用户在官方平台创建本人专用 Key，再在手机上手动填入。界面可标“API 已连接”。断开时清除本机 Key，要让 Key 在其他地方也失效，需到平台官网撤销。若用浏览器或内嵌 WebView 打开官网，登录、退出和历史由官网处理。内嵌登录兼容性待真机验证。没有官方说明允许宿主拿网页 Cookie 或登录令牌换取 API Key、模型权限或同步历史。[创建及管理 Key](https://platform.deepseek.com/api_keys) · [开放平台服务协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html) · [官方聊天入口](https://chat.deepseek.com/)

## 3. 聊一次扣哪笔钱？怎么核对？

**想花本人开放平台的可用余额，Harness 默认账号模型和本人 API Key 都有官方依据。想用网页免费聊天的待遇，应在官方聊天服务里聊。** DeepSeek 的聊天 App、开放平台和 Harness 使用统一账号，用户协议另行约定了 Harness 默认模型的计费方式。[统一账号说明](https://www.deepseek.com/harness/privacy/) · [Harness 用户协议第二部分第 1 条第（3）项](https://www.deepseek.com/harness/terms-of-use/)

| 在哪里聊 | 额度和查看方式 |
| --- | --- |
| 官方网页或官方聊天 App | 官方仍提供免费聊天体验。没查到公开的网页次数查询接口，也没找到第三方可以使用网页免费次数或会员权益的说明。用户当日是否遇到限额、有什么账号权益，要看本人网页或 App 的提示。[当前官方下载说明](https://www.deepseek.com/download/) · [聊天入口](https://chat.deepseek.com/) |
| Harness 选择账号模型 `deepseek-account` | 协议写明默认模型走开放平台预充值和计费。Harness 的 `getBalance` 通过平台的 `GET /api/v0/users/get_user_summary` 读取充值和赠金钱包。`GET /models` 用当前账号授权查询模型，再按客户端版本筛选。这条模型调用路径不会自动改用 API Key。没有发现一个单独计量的通用“Harness 免费额度池”。是否有活动赠金，以本人钱包为准。[钱包实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/README.zh.md) · [账号模型列表](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/llm/llm-deepseek-account/README.zh.md) |
| 直接调用官方 API，或在 Harness 的 `deepseek-official` 路径使用本人 DeepSeek API Key | 扣 Key 所属开放平台账号的钱。充值和有效赠金都有时，官方优先扣赠金。用 `GET https://api.deepseek.com/user/balance` 读取 `is_available` 和 `balance_infos[]`。每个币种单独看 `total_balance`（合计）、`granted_balance`（赠金）、`topped_up_balance`（充值）。Harness 若配置其他厂商的模型，费用按对应厂商算。[余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/) · [当前计费规则](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) · [自定义模型条款](https://www.deepseek.com/harness/terms-of-use/) |

**充值和用量。** Harness 账号组件给出的正式入口是 `https://platform.deepseek.com/top_up` 和 `https://platform.deepseek.com/usage`。手机可以跳到官方页面给开放平台钱包充值。没有查到购买网页聊天次数的官方入口。此次匿名访问聊天、平台、Key 管理、用量和充值页面均返回 HTTP 403。未带凭据请求官方余额和模型接口均返回 HTTP 401。没有读取到任何账户数据，也没有发起支付。[入口生成代码](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/credentials/deepseek-account/src/index.ts#L206-L218) · [充值](https://platform.deepseek.com/top_up) · [用量](https://platform.deepseek.com/usage)

**本人以后怎样验证扣费**

1. 用同一个 DeepSeek 账号，暂停其他 API 调用和 Harness 任务。先在平台记下时间、币种、充值余额、赠金余额和最近用量。如已有本人 Key，可用余额接口取原始精度。全程只记时间、模型、金额和 token 数。
2. 选同一个短问题，分三次发。第一次在官方网页发，第二次在官方 Harness 里明确选账号模型，第三次用本人 Key 调 `POST /chat/completions`。关掉工具，每次单独记下时间和用量，分别对比发送前后同币种的总余额和两种分项。用 Key 的流式请求可设 `stream_options.include_usage=true` 核对 token 数。Harness 一轮可能触发额外模型请求，要等平台账单更新后核对该时段的全部用量。[对话接口及用量字段](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)
3. 预计平台用量中能找到 Harness 和 API 的消费，网页体验要结合网页提示单独判断。若余额看似没动，先查赠金、显示精度和账单延迟。只有时间、用量和钱包变化能对上，才算验证了本人这笔扣费。**本轮没有真实扣费结果。**

## 4. 流式回复、本地历史和网页旧对话能做到什么程度？

**官方 API 可以流式回复，手机可以自己保存历史，无需中转服务器。** 例如手机直接向 `https://api.deepseek.com/chat/completions` 发送 `stream: true`，按 SSE（服务器逐段推送）拼接 `choices[].delta.content`，以 `[DONE]` 结束。Chat Completions 需要客户端在下一轮带回所选分支的消息。安卓可以用 Room/SQLite 保存消息、分支和完成状态，重启后读回。中断的回答应标成未完成。发送的上下文和图片仍会到 DeepSeek 服务器上处理。[流式协议](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) · [多轮对话](https://api-docs.deepseek.com/zh-cn/guides/multi_round_chat/) · [官方本地存储示例](https://github.com/deepseek-ai/chat-demo/blob/8ad7b56e196b87d8a3215dc09476d6d146ad3f0e/README.zh-CN.md)

**图片和模型。** 11 月 13 日的官方公告已确认图片及文件输入。当前 `deepseek-flash`（V4.1-Flash-1017）能理解图片，可传 Base64 图片或官方 Files API 返回的文件 ID。价格表里的 `deepseek-v4-pro` 不支持图像理解。手机应通过本人凭据调用 `GET https://api.deepseek.com/models` 显示可用模型和输入能力。Harness SDK 也接受由文本块和图片块组成的消息，需要选择支持图片的模型。此次没有发送真实图片。[11 月公告](https://api-docs.deepseek.com/zh-cn/news/news261113/) · [图像输入](https://api-docs.deepseek.com/zh-cn/guides/vision/) · [模型列表](https://api-docs.deepseek.com/zh-cn/api/list-models/) · [Python SDK 的图片输入](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/python/sdk/README.zh.md)

**Harness 自己的会话。** 官方 Web 界面用 `assistant/live-chunk` 显示正在生成的文字，完整消息进入本地会话记录。SDK 可以用相同的 `dsh_home` 和会话 ID 调 `resume_session`，接着原来的记录聊。下方实测中，Python SDK 的通知回调交出了完整回复，但没有在两段之间交出第一段。Harness 调官方模型时，服务方也会处理对话、上传内容及工具调用日志。[官方实时界面说明](https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/conversation) · [SDK 通知实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/sdk/server/src/server.ts#L164-L180) · [Harness 隐私政策](https://www.deepseek.com/harness/privacy/)

**网页旧对话有两种“继续聊”。**

- 要继续**同一条云端对话**，用本人账号登录官方网页或官方 DeepSeek App，在历史列表点开即可。官方提供跨端历史同步。第三方 App 即便用 WebView 打开这个页面，也只是显示官网里的会话。公开 API 和 Harness 账号组件都没提供按网页账号列出、下载聊天并向原网页线程追加消息的接口。[官方跨端同步说明](https://api-docs.deepseek.com/news/news250115/) · [网页历史操作说明](https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html) · [公开 API 目录](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)
- 要**把旧上下文带到本机继续聊**，已有官方办法。用户在官网点头像 → 设置 → 数据管理 → 导出所有历史对话，再选取导出的 ZIP。官方 11 月发布的 `deepseek-ai/chat-demo` 能读这个文件并用本人 Key 续聊。11 月 20 日的 v0.1.3 支持第 3 版 ZIP，能还原消息分支。缺少原件的附件只保留文件名和大小。演示版确认导入后会替换已有本地会话和附件。此后新消息存在本地副本，不回写网页原线程。**手动导出和选文件是必需的用户操作，不能算自动导入。** [官方演示指南](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/) · [演示客户端](https://cdn.deepseek.com/chat-demo) · [导入格式与覆盖行为](https://github.com/deepseek-ai/chat-demo/blob/8ad7b56e196b87d8a3215dc09476d6d146ad3f0e/src/services/chat/history/README.md)

### 本机原型和复现方法

原型只用 Linux x86_64、Python 3.13.5 和 PyPI 发布的官方运行时。测试程序在 `127.0.0.1` 回放两段固定文字，不向公网发送聊天请求。安装后分别用默认 `sdk` 和精简 `sdk-minimal` 运行。以下命令从本仓库根目录执行。[原型脚本](prototype/harness_smoke.py)

```sh
gate_tmp=$(mktemp -d)
python3 -m venv "$gate_tmp/venv"
"$gate_tmp/venv/bin/python" -m pip --isolated install --index-url https://pypi.org/simple --only-binary=:all: deepseek-harness-sdk==0.2.2rc2
env -i PATH=/usr/bin:/bin "$gate_tmp/venv/bin/python" -I docs/prototype/harness_smoke.py --profile sdk
env -i PATH=/usr/bin:/bin "$gate_tmp/venv/bin/python" -I docs/prototype/harness_smoke.py --profile sdk-minimal
```

两次运行均正常退出，结果相同。

```text
loopback_sse_parts=2
sdk_first_text_before_second_frame=false
sdk_turns=2 restart_history=pass
```

回放端发完第一段，最多等 2 秒才发第二段。直接读同一 Messages SSE 接口的对照程序在此期间收到了第一段。SDK 的 `on_notification` 在此期间没收到第一段正文，回合结束时得到了完整的两段文字。原型还检查了本地会话文件，关闭第一个 DSH 进程，再启动第二个进程续聊。回放端收到的第二轮上下文里确实有第一轮的问题和回答。测试用了隔离的临时目录和固定的无效占位串，只输出上述结果，不记录凭据或请求内容。

还用 pip 按 Android 13 / ARM64 的包标签检查了已发布的运行时。

```sh
"$gate_tmp/venv/bin/python" -m pip --isolated download --index-url https://pypi.org/simple --only-binary=:all: --no-deps --platform android_33_arm64_v8a --dest "$gate_tmp/android" deepseek-harness-runtime-bin==0.2.2rc2
```

结果为 `No matching distribution found`。本机未配置 Android SDK，找不到 `adb` 和 `emulator`，当前用户也无权读写 `/dev/kvm`。本轮没有运行安卓模拟器，Linux 测试覆盖不到 iQOO 11 上的进程、回调和后台恢复。

### 给协调人的建议

**原要求暂不通过。** 按本人最看重的功能，有三种选择。

| 优先保住什么 | 可选方案 | 需要接受的限制 |
| --- | --- | --- |
| 本人官网登录、网页权益、原线程续聊 | 直接使用官方 Android App 或在系统浏览器打开官网。有内嵌入口需求时，WebView 只展示官网。 | 记录由官方云端管理，自己的 App 不能把官网历史自动存进手机数据库。 |
| 原生聊天、图片、流式、本地历史、可核对和充值的余额 | **建议的替代架构**是手机用本人 Key 直连 DeepSeek，Room/SQLite 存聊天。用 Android Keystore 管理加密密钥，API Key 加密后存到 App 私有目录。模型、余额从官方 API 读取，充值跳官方页面，旧记录由用户选 ZIP 后导入。全程无需自建服务器。 | 用户要接受开放平台计费、手动配置 Key、手动导入，以及导入后在本地副本续聊。 |
| 必须以 Harness 为核心，使用它的账号组件 | 先在 iQOO 11 上单独验证社区 Linux 兼容环境里的官方 DSH，使用本机回调和官方账号模型。 | 需验证升级、系统切后台和实时文字。即使通过，网页旧记录仍缺官方自动同步接口。 |

**风险。** 只使用本人的账号和额度。提取 WebView 的 Cookie、令牌，或调用网页内部接口抓取聊天，容易随网站更新失效。DeepSeek 用户协议对自动抓取和复制服务内容设有限制，本人持有账号也不能替代接口授权。任何后续方案都应让 Key、令牌、Cookie 和授权码远离日志、崩溃报告及导出文件。[DeepSeek 用户协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-terms-of-use.html) · [开放平台服务协议](https://cdn.deepseek.com/policies/zh-CN/deepseek-open-platform-terms-of-service.html)

**仍待本人和真机验证。** 这台机器没有 DeepSeek 登录。本轮未验证 iQOO 11 上的 Harness 启动、浏览器同意并回到手机、真实退出和失效、本人可用模型及余额、实际扣款与充值、官方服务器的流式首段、图片识别，以及真实网页会话和导出包的续聊结果。真机验收时，可先确认登录后能刷新余额和模型，再用一张无隐私的测试图发问。观察正文是否分段出现，关闭进程、重开同一会话追问图中细节，最后退出并检查账号模型是否要求重新登录。开发决定留给协调人和用户。

## 5. 在手机上发起 Codex CLI 的办法，Harness 也能用吗？

**能解决一部分。最接近原需求的是把官方 DSH 和一套精简 Linux 环境装进同一个 APK。** 这次已经用官方 Linux ARM64 程序拿到了 DeepSeek 平台返回的授权页地址，也验证了图片能经 Harness 的账号模型组件发出去。前者停在用户确认前，后者用本机模拟模型接收。iQOO 11 上完整登录和实际扣费仍待验证。以下资料访问日期为 **2026-11-23（UTC）**。

### 手机上发起 Codex，任务实际在哪里跑？

| 手机上做什么 | 程序、文件和额度在哪里 |
| --- | --- |
| **在 Termux 里敲 `codex`** | CLI 可以直接在 Android 上跑。11 月 23 日 Termux 的 aarch64 仓库有 `codex 0.171.0`，可用 `pkg install codex` 安装。Termux 用 OpenAI 的 Rust 源码加安卓补丁构建。命令和项目文件都在手机上，无需电脑，模型推理仍要联网。CLI 用 ChatGPT 登录走账号的 Codex 套餐额度，浏览器回调不方便且账号允许时可用 `codex login --device-auth`。选 API Key 则按 API 用量计费。[Termux 构建配方](https://github.com/termux/termux-packages/blob/90bab96741a878831e9e1ed328c7351083152be7/packages/codex/build.sh) · [Termux 发布索引](https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz) · [OpenAI 官方登录说明](https://learn.chatgpt.com/docs/auth) |
| **在 ChatGPT 手机 App 里选 Cloud** | 手机发任务、看进度和结果，OpenAI 的云端工作区运行命令、保存工作文件。自己的电脑可以关机，手机也可以离开。要用有 Codex 权限的 ChatGPT 账号，按套餐或工作区额度使用。入口随版本、工作区和开放范围而异。[手机入口](https://learn.chatgpt.com/docs/mobile) · [Codex Cloud](https://learn.chatgpt.com/docs/cloud) · [额度说明](https://learn.chatgpt.com/docs/pricing) |
| **在 ChatGPT 手机 App 里选自己的电脑** | iOS 和 Android 可与 Mac 或 Windows 桌面端配对。两端登录同一 ChatGPT 账号和工作区，从桌面设置扫码。手机发指令、看差异、点批准，电脑用自己的项目、工具和凭据执行，按账号或工作区已有的 Codex 权益用量结算。电脑须保持唤醒、在线。若用手机终端 SSH 到电脑上敲 `codex`，CLI 和登录也都在电脑上。[官方远程连接说明](https://learn.chatgpt.com/docs/remote-connections) |

OpenAI 官方提供 `npm install -g @openai/codex`。npm 包里的 Node 脚本负责启动，真正执行的是 Rust 原生程序。0.171.0 的启动器看到 Android ARM64 会选 `aarch64-unknown-linux-musl`，但对应 npm 二进制包声明 `os: linux`。官方发了 Linux ARM64 包，没找到单列的 Android/Bionic 包。上游 issue 也记录了 Termux 下 npm 跳过该依赖的问题。远程连接则有开发者接口 `codex app-server`（双向 JSON-RPC）和终端选项 `codex --remote <URL>`，实验性的 `codex remote-control start` 和 `pair` 分别启动后台服务和生成配对码。用官方手机 App 时，首次配对仍要从桌面 App 的设置发起。[官方启动器源码](https://github.com/openai/codex/blob/rust-v0.171.0/codex-cli/bin/codex.js) · [ARM64 包元数据](https://registry.npmjs.org/@openai%2fcodex/0.171.0-linux-arm64) · [上游问题 #37262](https://github.com/openai/codex/issues/37262) · [CLI 命令](https://learn.chatgpt.com/docs/cli/reference) · [App Server 协议](https://learn.chatgpt.com/docs/app-server)

按 Sunwin 特别提到的“CLI”，**我更倾向于他记得的是在安卓终端里启动命令**。如果记得的是在 ChatGPT 里点一下就开工，选 Cloud 或扫码连电脑也都说得通。单凭“发起”两个字，判断不了执行位置。

### 把 DSH 装进一个 APK，要带什么？

**优先试官方 Linux ARM64 版，给它随包带一套 glibc 小系统，用 Android 版 PRoot 启动。** iQOO 11 本身就是 ARM64，真机不需要 QEMU。APK 需要 glibc 加载器、运行库、DSH 和它的辅助程序，账号和会话放在 App 私有的 `DSH_HOME`。本次测试的 wheel 已把 Node 和 DSH 依赖打进可执行文件，直接启动它无需另装 Node 或 Python。若改走 npm，则要带 Node ≥24.9.0 和匹配的原生模块。只嵌一个 Python 解释器或只换成 musl 小系统都不能加载这份 glibc 二进制。社区 DSHA 已有 Ubuntu ARM64 + Node 24 + PRoot 的单 APK 实现，其发布包约 302 MiB，可用来估量体积。[官方 wheel 说明](https://pypi.org/project/deepseek-harness-runtime-bin/0.2.2rc2/) · [官方平台清单](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.3-alpha.2/python/sdk-runtime/platforms.json) · [DSHA 的实现和包体](https://github.com/DSH-APP/DSHA/blob/36d89b72294348d0482d509109cf8e76d51fc1cb/README.md)

**直接移植到 Bionic 也是一条路，工作量更大。** 官方平台清单里没有 Android 目标。可以从 MIT 源码入手，用 NDK 的 `aarch64-linux-android` 目标编 Node 和扩展，还要适配终端、子进程、文件和沙箱。Node 24 有 Android 构建脚本，上游未把 Android 列为受支持平台。社区 Termux 适配已有 `node-pty`、`koffi`、图片处理和会话文件的补丁。目标 API ≥29 的 APK 还有执行位置限制，把二进制解压进可写私有目录再直接 `execve` 会受阻。需验证随 APK 安装的原生启动器、加载器和文件布局，也要实测 iQOO 的 SELinux 和系统调用限制是否允许 PRoot 工作。[Node 24 的 Android 构建说明](https://github.com/nodejs/node/blob/v24.x/BUILDING.md#android) · [NDK 工具链](https://developer.android.com/ndk/guides/other_build_systems) · [Termux 适配记录](https://github.com/Vengisk/deepseek-harness-termux/blob/4e56ebf3440e1eaa4ac61c611bcab085b089776e/README.md) · [Android 执行限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

**原生界面可以连 DSH 自带的本机服务。** 用户打开 App 时，由 Android 服务启动 `dsh web --no-open --host 127.0.0.1 --port 0`，让系统分配端口。宿主从子进程管道拿到本次启动地址，在内存里换取 DSH 的本机会话 Cookie。账号、余额、模型和聊天走已鉴权的本机 HTTP，实时事件走 WebSocket。开放平台的账号凭据留在 DSH 内。另一个可用通道是 SDK 的标准输入输出 JSON-RPC，默认 `sdk` 配置本身没有浏览器回调监听器。[Web 启动与鉴权](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/bundle/web-app/README.zh.md) · [HTTP 和 WebSocket](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/client/connection/README.zh.md) · [SDK 配置](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/bundle/sdk-app/README.zh.md)

### 官方登录能走到哪一步？

在这台 x86_64 Linux 上用 QEMU 跑官方 `manylinux_2_28_aarch64` wheel，DSH 报版本 `0.2.2-rc.2`。全新目录启动后，本机账号接口显示已退出。调用 `account/startSignIn` 后，正式平台接受了这次 Web 来源的 `auth_init`，DSH 进入 `waiting-browser`，给出 `https://platform.deepseek.com/dsh/authorize`（省略查询串）。访问空的 `/oauth/callback`，登录前是 **404**，等待授权时是 **400**，取消后又是 **404**。这验证了 ARM64 进程、正式授权初始化，以及回调路由的注册、校验和撤除。没有填写账号、兑换授权码或产生扣费。[复现命令和脱敏输出](prototype/harness_arm64_notes.md) · [对应版本的账号实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/credentials/deepseek-account/src/index.ts#L683-L754)

手机上应由系统 Chrome 或普通 Custom Tab 打开正式授权页。在回环回调流程中，确认后浏览器跳到 `http://127.0.0.1:<本次端口>/oauth/callback`，应由仍在运行的 DSH 校验 `state` 并用 PKCE S256 兑换。DSH 也接受带显式端口的 `localhost`、`[::1]`。这个请求通过 TCP 送到监听器，无需给 Activity 配 intent filter。用户回到 App 时读账号状态即可。普通 Custom Tab 使用浏览器的网络栈。专门的 Auth Tab 以自定义 scheme 或已验证的 HTTPS 回传结果，与 DSH 固定的 HTTP 回调不同。[RFC 8252 §7.3](https://datatracker.ietf.org/doc/html/rfc8252#section-7.3) · [Chrome Custom Tabs](https://developer.chrome.com/docs/android/custom-tabs) · [Auth Tab 回传方式](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab)

HTTPS 页面整页跳去 HTTP 回环地址在协议上可行。浏览器把顶层跳转和页面内的 `fetch`、iframe 分开处理，后两者还可能触发 Chrome 的本地网络权限。APK 的 `android:usesCleartextTraffic` 和网络安全配置约束本 App 的出站请求，不能替 Chrome 放行跳转。原生界面自己访问 HTTP 时，target API ≥28 的 App 默认限制明文，可按需只放行回环。Android 17（API 37）起，未单列 localhost 时系统默认允许回环明文。官方 DSH 只声明 `web`、`desktop` 两种登录来源，未找到 Android 来源、第三方回调登记或设备码登录。随包运行 Web 配置可沿用它自己的 `web` 流程。如果平台拒绝手机上的流程，目前没有文档给出可替换的来源。[混合内容规范 §4.4](https://www.w3.org/TR/mixed-content/#should-block-fetch) · [Chrome 对主框架跳转的说明](https://developer.chrome.com/release-notes/147#local_network_access_restrictions_on_service_worker_windowclientnavigate) · [Android 回环明文规则](https://developer.android.com/privacy-and-security/security-config#Localhost) · [官方账号接口](https://deepseek-harness.github.io/deepseek-harness/reference/subsystems/credentials)

**补正第 4 项的环境记录。** 这台机器其实装有 Android SDK，`adb` 和模拟器在 `~/Android/sdk`，上次没在 PATH 里找到。桌面 Chrome 156 已从公开 HTTPS 页跳进本机 HTTP 监听器。Android 11 模拟器也启动了，设备内的回环监听器能响应请求，但系统服务反复中断，进入 Chrome 后又出现系统界面无响应，没收到浏览器回调。社区 DSHA 有同一端口接回调的实现。iQOO 11 上切浏览器、本人确认、换取凭据和返回 App 这一整段还没有实测。[浏览器对照与模拟器记录](prototype/harness_arm64_notes.md#浏览器跳回-127001) · [DSHA 回调与端口说明](https://github.com/DSH-APP/DSHA/blob/36d89b72294348d0482d509109cf8e76d51fc1cb/docs/ports-and-networking.md)

### 图片也能走同一条会话吗？

**图片已在本地回放和真实账号上走通。** 11 月 24 日，真实账号的图文轮在同一会话收到 528 字符正文。[实测结果](INTERRUPT.md)

官方 `session/prompt` 接受 `contentBlocks`，Python SDK 可在同一轮发 `{type: "text", text}` 和 `{type: "image", data, mimeType}`。实测明确选了 `deepseek-account / deepseek-flash`，用本机模拟账号和服务。接收端核对了账号鉴权头、上传的 69 字节 PNG 原件，以及随后 Messages 请求中同一条用户消息里的问题和图片文件 ID，SDK 最后收到固定回复。说明图片确实从账号模型通道发出。正式账号要看 `/models` 给出的可用模型和输入能力。当前 Flash 支持图片，V4-Pro 只收文字。这条路无需额外 Key。`x-dsh-auth-token` 由账号组件发往获准的服务地址。`getPlatformSession` 只供宿主嵌入官方平台使用，没找到用它从原生界面直接调通用视觉 API 的公开授权。官方视觉 API 的直连示例使用本人的 API Key。[SDK 消息类型](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/sdk/protocol/src/types.ts#L70-L90) · [账号模型与鉴权](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/llm/llm-deepseek-account/README.zh.md) · [账号凭据的适用范围](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/credentials/deepseek-account/README.zh.md) · [模型能力](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) · [视觉 API](https://api-docs.deepseek.com/zh-cn/guides/vision/) · [图片实测](prototype/harness_account_image.py)

### 另两条路和开发决定

- **Termux 可作真机对照。** 用户先装 Termux，在 PRoot 的 Debian/Ubuntu 里跑官方 Linux DSH，或用社区适配版。登录时保持 Termux 进程存活，用同一手机的浏览器接回调。社区已有启动报告，完整账号授权仍待实测。两个 App 共享本机回环网络，但私有文件分开。要让我们的 App 接入，可让用户把本次启动地址仅交给自己的 App，由 App 校验回环地址、换取本机会话。DSH 重启后要重新配对。只知道默认端口 3080 无法鉴权。用户还要管安装、升级、两个进程和省电设置，适合排查问题，日常使用仍需照看两个 App。[Termux 实机报告](https://github.com/deepseek-ai/deepseek-harness/discussions/136) · [PRoot 环境](https://github.com/termux/proot-distro) · [DSH 本机连接](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/client/connection/README.zh.md)
- **远程也能做。** 自己的电脑运行 `dsh web`，手机经 SSH 本地端口转发访问。账号可在电脑浏览器登录。若从手机登录，浏览器里的回环端口必须经隧道转到电脑上的同一个 DSH。执行、凭据和记录都在电脑，电脑须常开联网，这和只靠手机使用的要求冲突。租云主机还要自行部署。没查到 DeepSeek 提供类似 Codex Cloud 的托管 Harness 任务入口。官方聊天 App 提供的是聊天服务。仓库另有实验性 LiveShare，需单独部署分享服务。[DSH Web 与 SSH 转发](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/bundle/web-app/README.zh.md) · [LiveShare](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.5/packages/experimental/liveshare/README.zh.md) · [官方产品入口](https://www.deepseek.com/download/)

**按 Sunwin 选的方向，优先做单 APK 内置 glibc 运行时的真机验证。** 如果真机完成官方账号授权，就能复用 Harness 的登录、退出、余额、模型列表和账号模型调用，按本人**开放平台的充值余额和有效赠金**计费。网页免费聊天次数不能拿来抵扣。运行时和会话文件可放在手机，模型推理所需的文字、图片会发给 DeepSeek，全程无需自建云服务器。充值可跳官方页面。网页旧聊天目前只能让用户从官网导出 ZIP，App 再导入选定分支，在本机副本续聊。自动拉取或回写原网页线程仍缺官方接口。[计费条款第二部分](https://www.deepseek.com/harness/terms-of-use/) · [官方充值页](https://platform.deepseek.com/top_up) · [官方导入实例](https://github.com/deepseek-ai/chat-demo/blob/8ad7b56e196b87d8a3215dc09476d6d146ad3f0e/src/services/chat/history/README.md)

按一名熟悉 Android 和 Linux 的工程师估算，有 iQOO 11、本人账号和可用余额后，先用 **3–5 个工作日**验能否在 App 身份下启动、切到浏览器完成授权、返回后查余额和模型。再用一张测试图核对识图、实际账单、重启续聊和退出。流式文字应在回答结束前看到第一段，上次 Python SDK 回放没达到这项，要单独验 Web 的实时事件。通过后约 **2–4 周**做出可重复安装的单 APK 运行时，处理安装体积、请求进行时的前台服务通知、锁屏断流、耗电和系统回收。若必须改走 Bionic，核心扩展和系统调用需逐项移植，按**数周以上**另估。以上只估运行时接入。这是第三方打包。分发前要保留 MIT 署名，核对二进制内各依赖的再分发条件，以及 Harness 在线服务和商标的使用条款。上游仍在预发布，升级 Node、DSH 或原生库后要重跑登录和图片测试。[Android 前台服务](https://developer.android.com/develop/background-work/services/fgs) · [开源许可证](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/LICENSE) · [Harness 用户协议](https://www.deepseek.com/harness/terms-of-use/)

**部分能。** 借用手机本地跑 CLI 的办法，有希望在一个 App 里用自己的 DeepSeek 账号聊天、把记录存在手机上，也不用架服务器。完整登录和真实消费还差 iQOO 11 验证。网页旧对话要自动同步回原线程，仍缺 DeepSeek 官方接口。
