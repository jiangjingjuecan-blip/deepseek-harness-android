# DeepSeek 助手

DeepSeek 助手是一款 Android 聊天客户端。它通过 PRoot 在手机上运行 DeepSeek 官方的 ARM64 Linux 版 Harness（DSH 0.2.2-rc.2）。应用通过本机回环地址连接 Harness，由 Harness 管理登录凭据，并联网调用 DeepSeek 账号可用的模型。

你可以在系统浏览器登录 DeepSeek 账号，流式查看回复，把相册或相机里的图片和问题一起发送。对话历史保存在本机 SQLite 数据库，图片保存在应用私有目录。设置中可以切换模型、查看余额，并打开[官方充值页](https://platform.deepseek.com/top_up)。

## 安装测试版

适用于 Android 10 及以上、支持 `arm64-v8a` 的手机。

1. 在项目的 GitHub Release 中找到 `v0.1.1-test`，下载附件 `deepseek-harness-test.apk`。[发布页](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/tag/v0.1.1-test) · [下载 APK](https://github.com/jiangjingjuecan-blip/deepseek-harness-android/releases/download/v0.1.1-test/deepseek-harness-test.apk)
2. 核对文件大小 **108,247,320 字节（103.23 MiB）**。在电脑上可运行 `sha256sum deepseek-harness-test.apk`，预期 SHA-256 为 `0f9d4a94fb36df65ff0a7bba5ac7da7f0c55c616944f841d959d812907e2a61a`。
3. 在手机上点开 APK 安装。已安装 v0.1.0-test 的手机可直接覆盖升级，保留本地对话。如果系统询问，允许当前浏览器或文件管理器安装此应用。

## 在手机上试用

1. 打开「DeepSeek 助手」，按系统提示允许通知，等待「正在启动」变为「未登录」。启动失败时，在设置中点「重新启动」。
2. 点「登录 DeepSeek」，在浏览器完成授权后返回应用。在设置中查看登录状态、可用模型和余额。需要再次打开授权页时，可点「继续登录」或「备用登录」。
3. 发一个简短问题，观察回复是否逐段出现，再追问上一轮的内容。选一张相册图片或拍一张照片，把问题和图片一起发出。
4. 进入历史列表，新建对话。关闭再打开应用，从列表回到旧对话并续聊。在设置中点「充值」，确认打开 DeepSeek 官方页面。

模拟器和回放测试的结果，以及仍需在 iQOO 11 上验证的项目，见[安装包验证记录](docs/TEST_NOTES.md)。

## 从源码构建签名 APK

在 Debian 13 上准备 JDK 17 或 21、Python 3.11 或以上、`dpkg-deb`、`patchelf`、OpenSSL 和 Android SDK 命令行工具。将 `ANDROID_HOME` 设为自己的 SDK 目录，在仓库根目录运行以下命令。

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  'platforms;android-35' 'build-tools;34.0.0' 'build-tools;35.0.0'
python3 tools/runtime.py arm64-v8a
tools/release.sh
```

运行时准备脚本按锁文件中的地址下载官方 DSH wheel、Termux PRoot、Debian 运行库及原生扩展。它校验各包的 SHA-256，将 ARM64 可执行文件、库、证书和许可证放进 `runtime-cache/`。Gradle 的 `prepareRuntime` 任务也会调用该脚本，可以复用已准备好的运行时。[准备脚本](tools/runtime.py) · [运行时锁文件](tools/runtime.lock.json)

发布脚本先调用 `tools/signing.sh`。首次运行时，OpenSSL 生成随机密码，JDK 的 `keytool` 生成 PKCS#12 密钥库。请在本机妥善保存 `.signing/release.p12` 和 `.signing/password`，给自己的安装版本发更新时需使用同一签名。[发布脚本](tools/release.sh) · [签名脚本](tools/signing.sh)

脚本随后用 Gradle Wrapper 运行 `:app:testReleaseUnitTest` 和 `:app:assembleRelease`，并用 `apksigner` 验证成品。输出文件为 `dist-apk/deepseek-harness-test.apk`。若要在 x86_64 构建机上运行官方 ARM64 程序做图片和流式对话回放，安装 `qemu-user` 后可执行 `HARNESS_REPLAY=arm64-v8a ./gradlew :app:testReleaseUnitTest`。

## 贡献与许可

应用界面禁止添加免责声明、警告或解释性文字。[界面规则](docs/UI_RULE.md)

本项目的 Android 客户端采用 MIT 许可证。上游运行时和各依赖保留各自的许可与署名。[项目许可证](LICENSE) · [第三方声明](NOTICE) · [发布审计](docs/PUBLISH.md)
