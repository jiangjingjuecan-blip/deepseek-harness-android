# 安卓安装包验证

## v0.1.5-test

- ARM64 签名包大小为 **134,285,326 字节（128.06 MiB）**，SHA-256 为 `83953c98e9b28c22c7a00112d093aa8d6fd8df9a6bd000a48ec9652d148fede0`。包名为 `com.sunwin.deepseekharness`，versionCode 6，versionName 0.1.5，minSdk 29，targetSdk 34。APK v2 签名和 ZIP 对齐检查通过。本次逐一核对了 0.1.0 至 0.1.4 的五个公开 APK，证书 SHA-256 均为 `b849e51b4f5a8691ffca6eecbae2592fc28afe06acccc148c52b67f2abc2079e`。[下载 APK](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/download/v0.1.5-test/deepseek-harness-test.apk)
- 宿主机分别运行官方 x86_64 与 ARM64 DSH 0.2.2-rc.2，两组各通过 29 项测试，跳过 0 项。本地推理端验证了同一次模型请求中的 9 个有序图片引用，下一轮验证 9 个旧图占位符。旧文件正文进入原生压缩摘要，首轮前缀和最近四轮通过原有回归检查。另测了不同模型下的文件字节预算和剩余空间边界。[官方运行时回放](../app/src/test/java/com/sunwin/deepseekharness/HarnessReplayTest.java)
- SQLite v4 的单图行迁入 v5，关闭并重开后从原路径解码出像素。测试也核对了草稿、共享文件、独占扫描页、删除回滚、UTF-8 截断和本机较长的预览。文本、Markdown、CSV、JSON、代码、三种 Office 文档和 PDF 均有生成或合成样例。主机的扫描页测试用 PDFBox 绘制夹具，Android 测试调用系统 PdfRenderer。[附件回归测试](../app/src/test/java/com/sunwin/deepseekharness/AttachmentTest.java)
- 在独立的空白 Android 10 ARM64 模拟器 `upgrade015` 上先安装公开的 0.1.4。测试程序用 `attachment-seed-v4` 在 v4 数据库写入单图消息，再用 `adb install -r` 覆盖安装最终签名包。`attachment-upgrade` 打开旧对话并核对缩略图。设备内 APK 的 SHA-256 与下载文件一致。
- Android 10 ARM64 模拟器经 ADB 回环连接隔离的官方 ARM64 DSH。测试给系统选择器和相机注入合成结果，操作真实的草稿移除、发送和文件芯片。接收端在同一轮看到 3 张图及 TXT、DOCX 两份正文。全屏滑动切换图片后，用双指触摸事件放大，再检查原留白位置的像素。强制停止后重开，缩略图、文件正文和升级前的旧照片都能再次查看。[模拟器界面测试](../app/src/androidTest/java/com/sunwin/deepseekharness/DeviceAttachments.java)
- 一次合并运行在模拟器的 Android 10 ART JIT 编译线程中出现 SIGSEGV。将界面回放与 PDF 测试分进程后，两项均通过。系统渲染器在仅剩 2 个图片名额时将五页扫描夹具的前两页转为 JPEG，文字 PDF 在零图片名额下也读出了正文。测试使用合成数据，真实模型调用 0 次，费用 ¥0。未在 iQOO 真机上测试。[格式与限额](ATTACHMENTS.md)

## v0.1.4-test

- 签名 APK 的 versionCode 为 5，versionName 为 0.1.4，包名为 `com.sunwin.deepseekharness`，架构为 `arm64-v8a`。文件大小为 **131,181,049 字节（125.10 MiB）**，SHA-256 为 `6d8c2207abf7fde85532420135ce41b4f32d0f63aed7ac76e54471c4c184d0fc`。[下载 APK](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/download/v0.1.4-test/deepseek-harness-test.apk)
- `apksigner` 验证了 APK v2 签名，`zipalign` 检查通过。本版与 v0.1.0-test 至 v0.1.3-test 四个公开 APK 的签名证书 SHA-256 均为 `b849e51b4f5a8691ffca6eecbae2592fc28afe06acccc148c52b67f2abc2079e`。
- 主机分别以 `HARNESS_REPLAY=x86_64` 和 `HARNESS_REPLAY=arm64-v8a` 运行发布版单元测试，各通过 22 项，跳过 0 项。测试核对了消息、用量记录、独占和共享照片、删除当前对话后的空对话、事务回滚和进程重启后的照片清理。新建 DSH 会话的应答延迟至本地删除后送达时，即使 SQLite 已复用旧行号，新对话也保留原有状态。[删除回归测试](../app/src/test/java/com/sunwin/deepseekharness/HistoryDeletionTest.java)
- 隔离的官方 DSH 0.2.2-rc.2 在 `workspace/follow` 上发回归档事件，归档集合只有被删会话的 ID。Web 接口 `workspace/archiveSession` 支持停止活动，DSH 的原生会话日志仍保留。运行时离线和服务拒绝归档两条路径均验证了本地删除和短诊断。本版测试的模型请求全部由回环夹具响应。[归档回放](../app/src/test/java/com/sunwin/deepseekharness/HarnessReplayTest.java) · [DSH 归档接口](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/api/workspace-controller/src/commands.ts#L154-L183) · [DSH 日志存储说明](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/session/session-persistence-jsonl/README.zh.md#L167)
- Android 10 ARM64 模拟器 `harnessarm` 先在 0.1.3 中写入测试对话，再覆盖安装签名的 0.1.4，按原 ID 重新打开并核对正文。仪器测试注入触屏按下、持按和松开事件，验证轻点打开、长按后弹窗只有「删除对话」「取消」「删除」、取消保留原文、删除非当前对话保留当前选择，以及删除当前对话后进入用量为 0% 的空对话。强制停止并重开应用后，当前 ID 仍指向同一空对话，界面显示 0%。设备内 APK 的 SHA-256 与发布文件一致。模拟器使用本机 SQLite 样例，测试期间停用客机 DSH。实体振感与 iQOO 真机均未经本次测试。[模拟器删除测试](../app/src/androidTest/java/com/sunwin/deepseekharness/DeviceDeletion.java)

## v0.1.3-test

- `com.sunwin.deepseekharness` 的 versionCode 为 4，versionName 为 0.1.3，仅包含 arm64-v8a。APK 为 **131,176,105 字节（125.10 MiB）**，SHA-256 为 `0f78a9dd71a850842f4eba0f8850fb4ac527fe994751b5dd46e6f24c6a90d697`。
- APK 通过 v2 签名验证。签名证书 SHA-256 为 `b849e51b4f5a8691ffca6eecbae2592fc28afe06acccc148c52b67f2abc2079e`，与前三版一致。ARM64 模拟器在安装 0.1.2 时写入对话，随后覆盖安装 0.1.3 并重开该对话，正文通过核对。
- 主机两种架构各跑过 17 项测试。ARM64 模拟器上的最终签名客户端通过了顶栏百分比、手动压缩、下一轮前自动压缩及 16 条本地消息保留检查。八轮界面测试经 ADB 回环连接宿主上的官方 ARM64 DSH 和本地模型夹具。真实账号用了两次模型调用，分别是短回复与 DSH 自动标题。[上下文预算与分环境验证](CONTEXT.md)

## v0.1.2-test

- 发布文件为 `dist-apk/deepseek-harness-test.apk`，大小 **131,166,048 字节（125.09 MiB）**，SHA-256 为 `76f7e924d40eba2d1cb1ffbaa6c3fc183b6da90257d179775444cd3fcab3f531`。[下载 APK](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/download/v0.1.2-test/deepseek-harness-test.apk)
- 包名为 `com.sunwin.deepseekharness`，versionCode 3，versionName 0.1.2，minSdk 29，targetSdk 34。合并清单启用了原生库提取。APK 包含 30 个 AArch64 ELF，客机清单有 31 个绑定。已验证 APK v2 签名及 ZIP 对齐，签名证书 SHA-256 与前两个公开测试版一致，为 `b849e51b4f5a8691ffca6eecbae2592fc28afe06acccc148c52b67f2abc2079e`。
- 15 项宿主机测试全部通过，含官方 ARM64 运行时在 QEMU 下与本地模拟推理服务的图文、续聊、错误回放，以及权限错误脱敏、旧对话升级和错误绑定回归。ARM64 模拟器用最终签名包完成启动自检，实际拒绝了应用数据中的可执行文件。对可写会话目录关闭硬链接兼容时测得 `linkat = EACCES(13)`，最终配置下，解压实际会话日志得到 17 条记录，包含「你好」和回合结束事件。[首条消息错误记录](EACCES.md)

## v0.1.1-test

应用包名为 `com.sunwin.deepseekharness`，版本为 `0.1.1`，版本号从 1 增至 2。签名证书 SHA-256 与 v0.1.0-test 一致，旧版可以覆盖升级。SQLite 迁移测试已从旧表读回历史，再写入带短错误的新消息。

- 发布文件为 `dist-apk/deepseek-harness-test.apk`，大小 **108,247,320 字节（103.23 MiB）**，SHA-256 为 `0f9d4a94fb36df65ff0a7bba5ac7da7f0c55c616944f841d959d812907e2a61a`。[下载 APK](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/download/v0.1.1-test/deepseek-harness-test.apk)
- 包内源码版本为 `7782a97`。Android SDK 验证了 APK v2 签名与 ZIP 对齐。包内 18 个 ELF 均为 AArch64，合并清单的最低版本为 Android 10，目标版本为 Android 14。
- 13 项宿主机测试全部通过，包含正式 ARM64 DSH 与 Android 发送流程的回放、旧库升级、网络切换和模型目录回退。真实账号的文字与图文记录也都通过新版解析器。本次未连接 iQOO 11。[中断原因与实测数据](INTERRUPT.md)

## v0.1.0-test 的设备验证记录

以下记录对应上一版安装包（`850166c`）。

- 在 Android 10 / ARM64 模拟器上安装了该版发布包，设备内的 APK 哈希与交付文件一致。SELinux 处于强制执行模式。前台服务以应用自己的身份启动了 PRoot 和官方 ARM64 DSH。约 3 分 5 秒后，回环鉴权返回 HTTP 303，账号接口返回 HTTP 200，状态为「未登录」。停止服务后，PRoot 退出码为 0，回环端口已关闭。
- ARM64 模拟器使用纯软件模拟，一次冷启动达到应用的五分钟超时。模拟器稳定后重试，得到了上面的结果。设置、新建对话、打开 SQLite 样例消息，以及从新进程的历史列表读回同一条消息也已通过。
- 在 Linux x86_64 上用 QEMU 运行包内的官方 ARM64 Harness 0.2.2-rc.2。应用的 HTTP 客户端成功交换回环 Cookie、读取模拟账号状态与余额、选择账号提供的 `deepseek-flash` 并退出账号。
- 测试端核对了上传 JPEG 的原始字节和同一条消息中的问题与图片 ID。回放端等 Android 应用所用的 WebSocket 客户端拿到「紫」后才发送「色」。第二轮模型请求带有前一轮的文字、图片及回复，最终收到了「第二轮」。
- 6 项宿主机测试通过，覆盖上述回放、消息映射、模型与余额、日志过滤及 SQLite。SQLite 测试关闭并重开数据库，核对会话 ID、图片、已回复文本、中断标记及续聊记录。
- Android 13 / x86_64 空白模拟器安装了由同一份应用源码构建的 x86_64 测试包。前台服务启动、账号接口、停止服务后端口关闭均通过。设置、充值和日志导出入口、历史列表、新建对话、打开本地样例消息及重启后读回通过。系统界面一度无响应，启动完成后进行了上述测试。
- Android 13 / x86_64 上的 DSH 联系了 DeepSeek 正式平台，取得官方授权页地址。未带参数访问回调得到 HTTP 400，取消本次授权后该路径返回 HTTP 404。Android 10 / ARM64 上另做了两次授权入口测试，宿主 QEMU 两次均因非法指令退出，因此这个入口仍需在 iQOO 11 上确认。

## 在 iQOO 11 上验证

1. 将 APK 传到手机，点开安装。如系统询问，允许当前文件管理器安装未知应用。
2. 打开「DeepSeek 助手」，在系统确认页选择通知和电池优化设置。若启动失败，记下界面的短原因，可到设置导出诊断日志。
3. 点「登录 DeepSeek」，在系统浏览器完成授权后返回应用。需要重试时，到设置中点「继续登录」或「备用登录」。
4. 在设置查看登录状态、可用模型及余额。发送一个简短问题，留意文字是否逐段出现，再追问上一轮的内容。
5. 点相册选一张图，在输入框问图中内容后发送。也可点相机拍照，再发一轮。
6. 从左上角进入历史，新建一个对话。关闭并重开应用，再打开旧对话续聊。
7. 在设置点「充值」查看官方页面，可结合平台用量核对消费。最后可点「退出登录」。遇到问题，在设置点「导出诊断日志」，保存文件后发送。
