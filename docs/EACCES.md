# iQOO 11 首条消息的 EACCES

手机只留下了 v0.1.1-test 的一个错误码。它说明 DSH 已经启动并把失败传回应用，旧日志没有保留手机内核拒绝的具体调用。固定版本 DSH 首次保存会话时会加载原生模块、核对锁文件，并发布正式日志。

| 首次保存会话的步骤 | 进程、系统调用和路径 |
| --- | --- |
| 加载文件锁模块 | DSH 内嵌的 Node 打开 `session.lock`，然后按需加载 `system.node`。v0.1.1 只预置了 NARB 的 `require-builtin` 模块。另一个加载器 pkg 会把锁模块写到 `/home/dsh/.cache/pkg/<模块 SHA-256>/@deepseek-ai/node-addon-system-linux-arm64/bin/glibc/system.node`。glibc 的 `dlopen` 需要对这个文件执行 `mmap(PROT_EXEC)`。 |
| 核对锁文件 | Node 的原生模块对打开的 `session.lock` 描述符加 `flock`。随后 libuv 分别按描述符和路径取得设备号和 inode。两者必须相同，避免另一进程替换锁文件。API 29 的普通应用不能直接调用 `statx`，旧 PRoot 在模拟这个调用时报告了错误的 inode。 |
| 发布首份会话日志 | DSH 的 JSONL 存储先写临时文件并同步，再用 Node 的 `fs.link` 发布正式文件，最后删除临时名。ARM64 Linux 对应 `linkat`。两个文件都在 `/home/dsh/.dsh/sessions/--home-dsh-chat--/<会话>/`，目标文件名形如 `session.vN.jsonl`，压缩时另带 `.zstd`。Android 的普通应用受 SELinux 硬链接限制。 |

应用数据目录可写、账号已登录后，这些操作仍有各自的内核约束。[DSH 获取会话锁](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/session/session-persistence-jsonl/src/lease.ts) · [按需加载锁模块](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/native/system/packages/entry/src/flock.ts) · [DSH 发布日志](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/session/session-persistence-jsonl/src/index.ts#L1280-L1326) · [Android 硬链接策略](https://android.googlesource.com/platform/system/sepolicy/+/f58e6777b2fd7029ecc115efbf9dc452c2532b90/private/app_neverallows.te)

## v0.1.1 为什么只显示错误码

发送时，Java 依次调用建会话、选模型和提交消息的 HTTP 接口，并订阅 WebSocket。HTTP 错误体、RPC 拒绝、订阅错误帧、单次模型尝试和最终的 `turn/end` 都可能携带 Node 错误。旧版 `ChatFailure.remote` 从消息里挑出 `EACCES`，丢掉其余内容。上层错误码为空或为 `UNKNOWN` 时，这个词就成了整条气泡。Java 自己的文件、SQLite 或套接字异常另有本机标签。系统 shell、PRoot、加载器和 DSH 的标准错误经过筛选，旧版最多留下权限或加载失败的事件名。[错误提取](../app/src/main/java/com/sunwin/deepseekharness/ChatFailure.java) · [回合处理](../app/src/main/java/com/sunwin/deepseekharness/Reply.java)

## v0.1.2 改了什么

- 构建时从已校验的官方 wheel 的 SEA 资源中提取原始 ELF。锁模块以 `libdsh_flock.so` 安装，其他随包的 Node 模块及同包动态库也各有安装器文件。Java 为 NARB 和 pkg 分别绑定它们实际查找的路径。pkg 的主进程和 worker 加载代码各有一处 Android 适配，加载已绑定的 `system.node` 时跳过整份 `@deepseek-ai` 目录的复制。构建会检查这两处上游代码与预期一致。模块摘要、路径选择与 DSH 账号和会话逻辑沿用原版。[打包实现](../tools/runtime.py)
- 从固定版本 Termux 源码一起编译 PRoot 与 loader。系统限制 `statx` 时，PRoot 将该接口报告为不可用。Node 的 libuv 转用内核提供的 `fstat` 和按路径的普通 `stat`，两次查询都指向真实锁文件。支持 `statx` 的系统仍走原有路径。自检按 libuv 的查询顺序比较设备号和 inode，不符时显示「文件锁不符」。[PRoot 改动](../tools/proot-statx.patch) · [libuv 文件属性查询](https://github.com/libuv/libuv/blob/v1.51.0/src/unix/fs.c#L1424-L1526)
- PRoot 启用 `--link2symlink`，在客机数据目录内模拟硬链接。自检实际做一次独占文件锁和两次 `linkat`，要求重复发布返回 `EEXIST`，并在删掉临时名后读回原字节。这样保留 DSH 对独占发布和持久化的要求。[启动自检](../tools/guest_check.c)
- Java 先从 `nativeLibraryDir` 检查安装器文件和执行权限。随后 PRoot 直接从安装目录的绑定启动静态自检程序。在即将运行 DSH 的同一组绑定里，自检比较每个客机 ELF 与安装器文件的设备号和 inode，并实际映射一页可执行内存。它还试写 HOME、DSH_HOME、会话、聊天、临时和 pkg 缓存目录。旧版的空占位文件按路径清理。PRoot 的临时数据使用 `cache/proot`，其可执行 loader 固定为安装器的 `libproot_loader.so`。[客机启动](../app/src/main/java/com/sunwin/deepseekharness/Guest.java) · [Android 应用数据目录的执行限制](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
- 首次打开时，Android 13 及以上会询问通知权限。尚未豁免电池优化时，应用会打开一次系统确认页。相册使用系统文件选择器，拍照委托系统相机并临时授权输出 URI。应用的执行路径修复由 APK 安装布局和绑定完成。[系统电池优化设置](https://developer.android.com/training/monitoring-device-state/doze-standby) · [系统相机调用](https://developer.android.com/media/camera/camera-deprecated/photobasics)

## 手机上怎么看

气泡显示动作和目标，例如 `EACCES · 执行 /bin/busybox`。长路径会缩短。启动自检失败时，聊天页和设置页直接显示短原因，如 `绑定不符 · …/system.node`。在「设置 → 导出诊断日志」中查找以下记录。

| 记录 | 含义 |
| --- | --- |
| `SELF_CHECK_OK`、`RUNTIME_READY` | 前者表示绑定、可执行映射与数据操作已通过。后者表示 DSH 回环鉴权已完成。 |
| `PERMISSION_ERROR`、`RUNTIME_ERROR` | 自检、PRoot、加载器或其他子进程报告的失败。记录进程、操作、路径以及系统提供的 errno。 |
| `MODEL_ATTEMPT_ERROR`、`CHAT_INTERRUPTED` | 模型尝试或本轮最终错误。后一条同时保留阶段，如 `PROMPT`、`REPLY`。 |

例如 `process=GUEST_CHECK syscall=linkat` 表示客机自检确实调用了该系统接口。DSH 消息中的 `process=DSH_NODE` 标明报告边界。若上游仅返回错误码，气泡会显示 `EACCES · DSH 访问受限`，缺少的字段记作 `unknown`。导出保留安装库名、已知客机路径和链接目标，省略会话标识、用户文件名和请求参数。[诊断格式](../app/src/main/java/com/sunwin/deepseekharness/AccessFault.java)

## 2026-11-24 的对照实验

- **Linux x86_64，同版 DSH，空账号。** 把 pkg 原生缓存放在实际挂载为 `noexec` 的文件系统，提交「你好」。建会话、改标题、prompt 均收到成功回执。系统调用跟踪随后看到 glibc 对 `system.node` 的 `mmap(PROT_EXEC)` 返回 `EPERM`，没有生成会话日志。用已预置锁模块的缓存重试，映射和发布 JSONL 的硬链接均成功，磁盘出现非空日志。这个内核返回的数值是 1。
- **Android 10 / API 29，真实 ARM64 系统镜像，SELinux Enforcing。** 在应用自己的 UID 下，对缓存里有执行位的 BusyBox 直接 `execve` 返回 `EACCES`。同一系统允许对缓存里复制的锁模块做文件支持的可执行映射。省略 `--link2symlink` 后，客机在可写的 DSH 会话目录调用 `linkat`，得到 `EACCES(13)`。恢复选项，同样的发布、拒绝覆盖和读回检查通过。另将正常的 ARM64 ELF 留在客机数据目录，仅移除 ripgrep 的绑定，自检返回 `BIND_MISMATCH`。恢复安装器绑定后可执行 BusyBox 并以 0 退出。模拟器也调起了系统的电池优化确认页。
- **锁文件身份对照。** 在 ARM64 应用 UID 中，原版 PRoot 下的 `flock` 返回 0。同一打开文件的 `statx(fd, "", AT_EMPTY_PATH)` 报告 inode 6245，按路径的 `statx` 报告 133082。DSH 重试后报告会话已被其他写入者占用。普通 `fstat` 和 `stat` 都给出 133082。采用兼容修订后，受限的 `statx` 返回 `ENOSYS`，这两次普通查询得到相同设备号和 inode。该错误在 WebSocket 中表现为会话锁冲突。
- **版本对照。** 在 ARM64 模拟器安装公开的 v0.1.1-test，确认 DSH 可以启动并建立未登录会话。旧包的首次写入测试在回环请求处超时。应用数据里出现了 pkg 模块缓存和会话锁，当次没有观察到正式日志。早期修订仅绑定了模块，pkg 仍逐个复制整个作用域，也触发了回环超时。宿主机的 15 项测试包含 Android 客户端、官方 ARM64 运行时和本地模拟模型的图文与续聊回放。
- **最终签名 APK。** 在同一 ARM64 模拟器上覆盖安装，设备里的 APK 哈希与发布文件一致。官方 DSH 未登录启动，建会话、改标题和提交「你好」均返回 HTTP 200。最初只等文件出现的测试读到了 164 字节的日志头。最终测试等待回合结束和日志追加，再解压本次 `session.v4.jsonl.zstd`。文件共 1,944 字节，含 17 条 JSONL 记录，包含「你好」和 `turn/end`。
- `hebei_api30` 的镜像实际为 x86_64。本次用 KVM 启动该 AVD 后未取得 ADB 设备。上述 ARM64 结果来自另一台使用软件模拟的 API 29 AVD。没有连接 iQOO 11，也没有在模拟器完成正式账号登录或推理。就已观察到的 `EACCES(13)` 而言，首份会话日志的硬链接是最具体的手机假设。该手机是否还拒绝了文件的可执行映射，仍需其自己的诊断日志。

在已安装发布包的空白 ARM64 模拟器上，可以用同一签名构建并安装 `:app:assembleReleaseAndroidTest` 生成的测试 APK，再运行以下命令。第一个场景包含两个故意移除配置的对照，第二个场景通过本机接口创建会话并检查首份日志。[设备测试](../app/src/androidTest/java/com/sunwin/deepseekharness/DeviceSmoke.java)

```sh
adb shell am instrument -w -e scenario self-check com.sunwin.deepseekharness.test/com.sunwin.deepseekharness.DeviceSmoke
adb shell am instrument -w -e scenario session com.sunwin.deepseekharness.test/com.sunwin.deepseekharness.DeviceSmoke
```
