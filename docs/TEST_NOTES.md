# 安卓安装包验证

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
