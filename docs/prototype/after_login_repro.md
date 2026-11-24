# 用官方账号复现两轮请求

从仓库根目录运行。脚本连接已启动的官方 Linux DSH 0.2.2-rc.2，使用本机私有目录 `/tmp/dsh-repro` 中的连接信息。Python 需要 3.11 或更高版本，以及 aiohttp 和 Pillow。

```sh
python3 docs/prototype/after_login_repro.py --status
python3 docs/prototype/after_login_repro.py --balance
```

`--status` 只读取账号状态。`credential-stored` 表示 DSH 已保存凭据。`--balance` 读取各币种钱包合计。需要取得浏览器授权入口时，`--login-link` 将入口写入本机的私有文件。

```sh
python3 docs/prototype/after_login_repro.py
```

默认命令会使用真实账号。在同一个新会话中先发一个简短文字问题，再发一个看图问题。图片由 Pillow 在内存中生成，是写有「TEST 123」的 216 × 60 PNG。每轮都会选模型、重开 WebSocket 订阅，收到快照才提交消息。终端显示非空文字增量数或短错误，并报告两轮前后的余额及差额。

每次运行单独写入权限为 0600 的 `/tmp/dsh-repro/turns-*.jsonl`。记录保留 HTTP 状态、WS 握手和事件顺序，正文及图像仅保留长度。请求与流标识只在同次记录内可关联，账号视图和鉴权数据不会进入记录。正式结束原因在 `turn/end → data.reason`。退出码 0 表示两轮均完成，1 表示有一轮失败，2 表示需要登录。[实测结果](../INTERRUPT.md)
