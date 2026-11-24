# 用官方 ARM64 Harness 验证登录入口、图片和浏览器回跳

实测及资料访问日期为 **2026-11-23（UTC）**。宿主是 Debian 13 x86_64。登录和图片两个探针都明确指定同一个 ARM64 可执行文件，分别使用全新的临时账号目录。只有登录初始化联系了 DeepSeek 正式平台。下方是筛选后的输出，包含凭据的启动地址、Cookie、授权查询串和进程原始输出都不写入日志。

## 准备运行时

在 Debian 13 上，从本仓库根目录开始，在同一个 shell 里依次执行以下各段。下载的软件包只解压到临时目录。宿主需具备 Python venv、unzip、binutils 和 ripgrep。[官方 wheel 及 SHA-256](https://pypi.org/pypi/deepseek-harness-runtime-bin/0.2.2rc2/json) · [运行时包内文件](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/python/sdk-runtime/README.zh.md)

```sh
set -eu
umask 077
repo_dir=$(pwd)
gate_dir=$(mktemp -d)
cd "$gate_dir"
apt-get -qq download qemu-user libc6-arm64-cross libstdc++6-arm64-cross libgcc-s1-arm64-cross
for deb in ./*.deb; do dpkg-deb -x "$deb" sys; done
python3 -m venv venv
venv/bin/python -m pip --isolated download --index-url https://pypi.org/simple \
    --only-binary=:all: --no-deps --platform manylinux_2_28_aarch64 \
    --dest . deepseek-harness-runtime-bin==0.2.2rc2
wheel=deepseek_harness_runtime_bin-0.2.2rc2-py3-none-manylinux_2_28_aarch64.whl
printf '%s  %s\n' dc8f6e2c463c9c174b8fbafa394ada62351ff16b9febde4b90dca6b57f1b4e84 "$wheel" | sha256sum -c -
unzip -q "$wheel" 'deepseek_harness_runtime/runtime/*'
mv deepseek_harness_runtime/runtime wheel-bin
chmod u+x wheel-bin/deepseek-harness-sdk-runtime-linux-arm64 wheel-bin/deepseek-harness-sdk-runtime-linux-arm64-rg
cat > run-dsh <<'SH'
#!/bin/sh
set -eu
gate_dir=$(dirname "$(realpath "$0")")
exec "$gate_dir/sys/usr/bin/qemu-aarch64" -L "$gate_dir/sys/usr/aarch64-linux-gnu" \
    "$gate_dir/wheel-bin/deepseek-harness-sdk-runtime-linux-arm64" "$@"
SH
chmod u+x run-dsh
mkdir empty-home
file wheel-bin/deepseek-harness-sdk-runtime-linux-arm64
readelf -d wheel-bin/deepseek-harness-sdk-runtime-linux-arm64 | rg NEEDED
env -i PATH=/usr/bin:/bin HOME="$gate_dir/empty-home" ./run-dsh --version
```

本次用 QEMU 10.0.13、ARM64 glibc 2.41 和 libstdc++/libgcc 14.2。wheel 为 99,239,965 字节，DSH 主程序为 181,603,456 字节。`file` 识别为 GNU/Linux AArch64 ELF，加载器是 `/lib/ld-linux-aarch64.so.1`。`readelf` 列出 libc、libdl、libpthread、libm、libstdc++、libgcc_s 和 glibc 加载器。`--version` 返回 **0.2.2-rc.2**。保留了同目录的 ripgrep 和下载清单，运行中无需系统 Node。这些结果说明 APK 内需要解决的加载器和运行库，也说明为什么直接拷到 Bionic 或纯 musl 环境不够。

## 让官方平台初始化一次登录

```sh
env -i PATH=/usr/bin:/bin python3 -I "$repo_dir/docs/prototype/harness_arm64_login.py" "$gate_dir/run-dsh"
```

```text
web_loopback=ready bootstrap_http=303 account=signed-out
auth_init=waiting-browser source=web authorize=https://platform.deepseek.com/dsh/authorize
empty_callback_http before=404 waiting=400 after_cancel=404
cancelled=signed-out
```

探针执行 `dsh web --no-open --host 127.0.0.1 --port 0`，从匿名管道接收启动地址，在内存换取 Cookie。它调用本机 `POST /api/account/startSignIn`，检查平台返回的授权页，再取消这次申请。在同一端口、同一路径、三次都无查询参数的情况下，404 → 400 → 404 对应回调路由的注册和撤除。探针没有提交用户身份，也没有兑换授权码。[登录探针](harness_arm64_login.py) · [官方账号实现](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/credentials/deepseek-account/src/index.ts#L683-L754)

## 让账号模型发送一张图片

```sh
venv/bin/python -m pip --isolated install --index-url https://pypi.org/simple \
    --only-binary=:all: deepseek-harness-sdk==0.2.2rc2
env -i PATH=/usr/bin:/bin "$gate_dir/venv/bin/python" -I \
    "$repo_dir/docs/prototype/harness_account_image.py" "$gate_dir/run-dsh"
```

```text
provider=deepseek-account model=deepseek-flash models_auth=pass
files_png_bytes=69 messages_user_text_and_file_id=pass replay_reply=pass
```

宿主 Python 只负责驱动。SDK 的 `dsh_bin` 指向上面的 QEMU 启动脚本，选的是完整 `sdk` 配置。测试把账号服务、模型目录、Files 和 Messages 都指到同一个本机模拟服务，临时生成的测试凭据只对这个服务有效。三处请求均核对账号鉴权头。服务端逐字节比较上传的 1×1 PNG，再核对 Messages 请求中同一条用户消息里的问题和图片 ID，最后回放固定文字。第一次准备的模型清单缺少 Messages 能力字段，DSH 返回 `MODEL_UNSUPPORTED`。补齐模拟清单后，同一份官方程序通过。这个测试验证了附件和账号调用路径，模型能否正确描述图片要用本人账号实测。[图片探针](harness_account_image.py) · [SDK 图片输入类型](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/sdk/protocol/src/types.ts#L70-L90) · [账号模型能力判定](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.2/packages/llm/llm-deepseek-account/README.zh.md)

## 浏览器跳回 127.0.0.1

单独用 Web Platform Tests 的公开 HTTPS 重定向页做对照。它返回 302，目标是当前浏览器所在设备上一个无授权参数的 HTTP 地址。桌面 Chrome 使用空白临时配置，回环监听器只记录是否收到 `GET /oauth/callback`。[测试页实现](https://github.com/web-platform-tests/wpt/blob/master/common/redirect.py) · [桌面探针](chrome_loopback_probe.py)

```sh
env -i PATH=/usr/bin:/bin python3 -I "$repo_dir/docs/prototype/chrome_loopback_probe.py" /usr/bin/google-chrome
```

```text
desktop_chrome_https_to_loopback=received
```

Linux 上的 Chrome 156.0.8078.47 完成了整页跳转。安卓测试用独立的空白 AVD、独立的 ADB 服务和设备内的回环监听器。两端浏览器都只用测试页。

模拟器 37.2.13 位于 `~/Android/sdk/emulator`，Google APIs 镜像为 Android 11 / API 30、x86_64、修订 16，预装 Chrome 83.0.4103.106。当前用户不能使用 KVM，启动参数用了 `-accel off -no-window -no-snapshot`。设备内用 `toybox nc` 监听 `127.0.0.1:38421`，只有收到 `GET /oauth/callback` 才写一个布尔标记。以下是安卓一侧的结果。

```text
$ adb shell getprop sys.boot_completed
1
$ adb shell toybox netstat -ltn
tcp    0    0    127.0.0.1:38421    0.0.0.0:*    LISTEN
GET /network-check（设备内独立的连通性检查）
HTTP/1.1 200 OK
android_browser_callback=unobserved
```

通过 `am start -a android.intent.action.VIEW -p com.android.chrome` 打开同一公开重定向页时，系统返回过 `unable to resolve Intent` 和 `Failure calling service activity: Broken pipe (32)`。重试后虽然进了 `ChromeTabbedActivity`，前台却显示系统界面无响应，监听器始终没收到浏览器的请求。系统日志记录了 `system_server` 的 `Lost network stack`。还尝试在这台空白模拟器安装 x86_64 Chromium 160 测试包，安装服务也返回 `Broken pipe`。设备内的 200 来自另一个路径的连通性检查。它能证明监听器可达，不能说明安卓 Chrome 已回跳或拒绝了 HTTP。这个 AVD 里也没有运行 ARM64 DSH。iQOO 11 上同一 APK 的运行时、系统浏览器和本人账号仍需放在一起实测。[Android Emulator 参数](https://developer.android.com/studio/run/emulator-commandline) · [Chromium 测试包目录](https://storage.googleapis.com/chromium-browser-snapshots/index.html?prefix=AndroidDesktop_x64/1752019/)
