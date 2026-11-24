# 手机对话的上下文预算

本次核对的可执行文件来自随 APK 锁定的 DSH 0.2.2-rc.2 wheel。下列源码链接固定在同版提交 `ddac9676`。2026-11-24 在已登录的盒子上读取了 `session/modelCatalog`。账号组返回模型 ID、显示名和推理选项，容量需要从适配器取得。[运行时锁文件](../tools/runtime.lock.json) · [官方模型定义](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/models.ts#L4-L23)

## 模型与预算

| 账号模型 ID | 账号显示名 | 输入 | 输入与输出共用窗口 | 平台最大输出 | 本版输出预留 | 安全余量 | 发送前触发值 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `deepseek-flash` | DeepSeek-V4.1-Flash | 文字、图片 | 1,048,576 | 393,216 | 393,216 | 16,384 | 638,976 |
| `deepseek-v4-pro` | DeepSeek-V4-Pro | 文字 | 1,048,576 | 393,216 | 393,216 | 16,384 | 638,976 |

单位均为 token。平台列出 1M 上下文、384K 最大输出，官方目录测试保存了两个精确整数。账号适配器把目录里的最大输出与路由上限取小，本版 DSH 默认一次请求最多输出 256,000。手机按平台最大输出预留，两项预留合计 409,600，触发值为总窗口的 60.9375%。官方给出的是合计窗口，没有单列独立的最大输入值。[平台模型表](https://api-docs.deepseek.com/quick_start/pricing/) · [官方目录数据](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/tests/listed-catalog.spec.ts#L8-L18) · [账号路由](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek-account/src/index.ts#L56-L68) · [输出上限](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/defaults.ts#L5-L9) · [适配器合并目录](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/listed-catalog.ts#L58-L70)

每次发送前解析当前路由，窗口取表中数值与适配器容量的较小值。触发值取窗口的 80% 向下取整，以及「窗口减输出预留再减安全余量」两者中的较小值。当前会话占用加本轮草稿估算**达到**触发值，就先压缩。压缩后重新计量，只有低于触发值才提交草稿。若草稿本身太大，或首轮及近期原文已占满预算，返回短错误并在本机保留这次输入。[手机策略](../app/src/main/assets/phone-context.cjs) · [发送顺序](../app/src/main/java/com/sunwin/deepseekharness/ChatEngine.java)

今后目录新增的 ID 先使用应用保守上限。窗口至多 65,536，单次生成也实际限到 8,192，安全余量 16,384，满窗口时触发值为 40,960。适配器报告更小容量或输出上限时再取小。65,536 和 8,192 是本应用选定的回退值，未经核实的新模型规格以账号目录后续公告为准。切回已知模型时恢复其 DSH 输出设置。[手机策略](../app/src/main/assets/phone-context.cjs)

## 原生压缩与手机保留范围

- 此版没有 `session/compact` RPC。官方 Web 使用 `commands/execute` 执行 `/compact`，由 `ctx.compaction.compactNow` 在空闲时压缩。默认自动策略在下一步请求前检查压力，也处理模型切换和溢出。默认触发比例为 80%，额外余量 65,536，尾部按扣除输出后的消息预算保留 16%。手动命令从第一条非系统消息开始选择区段，最近保留量按 0 传入，算法仍留下最后一个节点。它无法指定保留首问或最近几轮。[会话 RPC](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/api/session-controller/src/index.ts#L281-L633) · [官方手动命令](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/command-compact/src/index.ts#L59-L87) · [默认值](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/config.ts#L64-L98) · [原生预算公式](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/config.ts#L185-L210) · [区段选择](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/region.ts#L104-L159)
- Web 把计量器挂在宿主，把压缩后端交给各个预设。0.1.2 的 `phone-chat` 只有 persona。0.1.3 为它挂载官方 `compaction-basic`，由手机策略选择区段，调用原生 `compactRegion`。保留顺序为**原系统及工具声明 → 完整首轮 → 一份滚动摘要 → 最近 4 轮原文**。最后一次摘要和这次新变旧的轮次一起进入下一份摘要。至少有六个尚未被摘要替代的用户轮次时，才会有新的区段可压缩。范围按当前模型消息的顺序选择，因为被替换后的日志序号可能比后面的消息更大。[Web 预设分工](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/bundle/web-app/cordis.patch.yml#L569-L580) · [原生范围接口](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction/src/index.ts#L168-L193) · [手机预设](../app/src/main/assets/chat.yml)
- 原生后端要求 `compactRegion` 在已打开的轮次内运行。手机的 `/phone-context` 命令只传草稿预算或手动标记。有可压缩区段时，用内部空消息开启一个维护轮，进入第一步后消耗该标记，调用原生摘要，等待会话空闲和日志落盘。这个轮次只产生摘要模型调用。摘要生成上限为 **2,048 token**，实际长度随材料而定。DSH 记录 `compaction/start`、`compaction/summary`、替换消息和 `compaction/end`，检查工具边界、并发变化和摘要是否确实缩短。失败时保留已有正文，发送流程不会自动追加另一条聊天请求。[空轮的官方行为](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/core/agent-loop/src/agent.ts#L345-L371) · [原生事务](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/region.ts#L175-L263) · [摘要调用](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/summarizer.ts#L137-L169)
- 顶栏的百分比可以点开「压缩上下文」。手动操作可以在阈值以下运行。原文还没有可压缩区段时显示「无需压缩」。手机 SQLite 版本 2 升至 3 时只新增按对话保存模型、占用和窗口的表，升级自版本 1 时先补原有错误字段。摘要和维护轮不会插入聊天列表，DSH 会话 ID、原文、已保存照片及其路径都保留。[SQLite 迁移](../app/src/main/java/com/sunwin/deepseekharness/History.java) · [顶栏](../app/src/main/java/com/sunwin/deepseekharness/MainActivity.java)

## 用量、缓存与照片

`session/follow` 打开时继续传 `assistantStream: true`。首帧的 `projections.values.contextPressure` 可以含 `contextWindow`、最近已发送的输入 `pressureTokens` 和纳入消息变化的 `projectedTokens`。生成中的 `assistant-stream.frame.chunk` 在 `type=usage` 时提供用量，落盘的 `assistant/message.data.usage` 再次确认。Android 按请求、轮次及尝试 ID 处理同一条 WebSocket，保留正文增量和原有结束、错误解析。一次请求取未缓存输入、缓存读、缓存写和输出之和，推理 token 已含在输出中。重复事件覆盖上一读数，累计账单 `tokenUsage` 不参与百分比。[订阅协议](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/api/session-controller/src/types.ts#L553-L569) · [官方计量字段](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/token-meter/src/projection.ts#L10-L53) · [适配器换算](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/translate.ts#L150-L162) · [Android 流解析](../app/src/main/java/com/sunwin/deepseekharness/Reply.java)

发送前优先用 DSH 的 `tokenMeter.measure`。原生计量器会把最近一次请求的实测用量与同一请求的估值比较。采用实测基准时，再加减后续模型消息的估值。缺少可用的实测基准时，手机预设按 UTF-8 字节数、消息封装、固定系统开销和图片上界保守估算，并与原生估值取大。Android 断开时可用 SQLite 中的上次读数，老库没有读数时用本机原文估算。顶栏分母是总窗口，输入很少时显示 `<1%`。[官方计量](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/token-meter/src/index.ts#L151-L194) · [官方估算法](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/token-meter/src/estimate.ts#L12-L21) · [Android 估算](../app/src/main/java/com/sunwin/deepseekharness/ContextUsage.java)

DeepSeek 对已持久化且完整匹配的输入前缀按缓存命中价收费。命中的 token 仍占窗口。保持开头的 persona、系统内容、工具声明及排序、首轮文字与顺序稳定，可以复用共同前缀。修改较早的文字、工具或图片会从首个差异处缩短可匹配前缀。缓存也可能过期。手机不把时间、百分比或滚动摘要放到首问前。普通问答始终复用固定首轮。原生辅助摘要复用系统和工具，随后发送选定的旧区段。[DeepSeek 缓存匹配规则](https://api-docs.deepseek.com/guides/kv_cache/) · [命中和未命中价格](https://api-docs.deepseek.com/quick_start/pricing/) · [原生摘要输入](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-basic/src/region.ts#L533-L563)

Flash 支持图片，V4-Pro 当前是文字模型。官方视觉计量先把不足 544 × 544 总像素的小图放大，对齐 14 像素网格，每轴按 3 比 1 降采样，按网格行乘以「列数加 1」再加 2，调整尺寸后单图最多 **1,024 个视觉 token**。手机按上界预算，最终用量以服务回报为准。照片导入沿用长边至多 1,600 像素、JPEG 质量 88。手机历史保留该 JPEG，系统相册中的源照片也不会被改写。[官方视觉公式](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/image-tokens.ts#L1-L44) · [照片导入](../app/src/main/java/com/sunwin/deepseekharness/Photos.java)

DSH 原有 Files 引用和内联回退有各自的传输预算。超过预算的 `IMAGE_OFFLOAD_REQUIRED` 可以触发原生 `image/offload` 事件，后续发送时按事件把指定图片换成带标识的文字占位。手机在获得看图答复后主动记录同一种事件，保留问题和助手已经给出的文字描述。无需额外调用视觉模型生成图注。升级前留下的旧图、异常中断留下的旧图会在下一次发送或手动压缩前补处理。第一次移除图片会改变该处的缓存前缀，以后沿用同一占位。[原生图片选择与持久化](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/compaction/compaction-image-offload/src/image-offload.ts#L16-L46) · [模型端文字占位](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm/src/content.ts#L109-L118) · [适配器预算](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/llm/llm-deepseek/src/images.ts#L39-L88)

## 0.1.3-test 验证记录

**主机。** 分别以 `HARNESS_REPLAY=x86_64`、`HARNESS_REPLAY=arm64-v8a` 运行 `:app:testReleaseUnitTest`，每次 17 项通过、0 项跳过。第二次通过 qemu-user 执行锁定的 ARM64 DSH。Robolectric 使用原生 SQLite 和生产 `ChatEngine`、OkHttp，模型服务使用回环夹具。测试检查两个账号模型的阈值前一位和等号、缓存读写与输出的去重、未知模型回退与切回、版本 1/2 升级、流式失败及恢复。夹具在接收端比对 persona、工具、首轮、其余四轮的角色与逐字内容，以及两次滚动摘要的顺序。照片首次以原字节上传，续聊只收到文字占位和「紫色」。原文和本机 JPEG 在压缩后逐条、逐字节核对。[主机回放](../app/src/test/java/com/sunwin/deepseekharness/HarnessReplayTest.java) · [模型端断言](../tools/replay.py) · [数据库测试](../app/src/test/java/com/sunwin/deepseekharness/HistoryTest.java)

**Android ARM64 模拟器。** 在 Android 10（API 29）的 `harnessarm` 上，先用已安装的 0.1.2 保存一段对话，覆盖安装 0.1.3 后从历史列表重新打开并核对原文。使用最终签名包运行 `python3 tools/replay.py --device emulator-5562`，真实界面发送八轮消息，在第六轮后打开顶栏百分比菜单并选择「压缩上下文」。第七轮由夹具报告高用量，顶栏显示 61%，第八轮发送前自动压缩，回复后回到 `<1%`。本机仍有完整的 16 条消息。仪器测试返回 `passed=context`，模型端也分别收到了预期的两次摘要和两组逐字保留的上下文。[模拟器界面测试](../app/src/androidTest/java/com/sunwin/deepseekharness/DeviceContext.java)

同时在整机模拟器中运行图形界面和 PRoot 内 DSH 时，宿主 QEMU 两次因 `SIGILL` 退出。上述八轮回放通过 `adb reverse` 连接宿主上以 qemu-user 运行的同版官方 ARM64 DSH 和本地模型夹具。这八轮的模型调用全部在夹具内完成。另外单独在模拟器 PRoot 中启动了 APK 自带的 DSH，在未登录的账号路由中没有可选模型。测试通过本地参数校验调用了手机预设的命令，返回 `passed=context-guest`。此次没有 iQOO 真机结果。[客机自检](../app/src/androidTest/java/com/sunwin/deepseekharness/DeviceSmoke.java)

**盒子真实账号。** 使用原有 DSH 进程，在全新的 `phone-chat` 会话发送一次「只回好」，收到 1 个非空正文增量。日志同时记录了官方预设自动发起的 `session/title-llm-request` 与模型生成的标题。共 **2 次模型调用**（正文 1，标题 1），没有发送图片或执行真实账号摘要。正文的流式与持久用量一致，未缓存输入 245、缓存读 128、缓存写 0、输出 2，合计 375。用正确的 `sessionId` 读取 `session/projections` 后得到输入 373、下一次输入估计 386、窗口 1,048,576。首次额外读取该投影时参数形状有误，通过只读 RPC 复核，没有重发提问。[官方自动标题](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/session/session-title-first-prompt-llm/src/index.ts#L145-L165) · [投影请求参数](https://github.com/deepseek-ai/deepseek-harness/blob/ddac9676/packages/api/session-controller/src/types.ts#L417-L424)

钱包合计从 **¥20.31433410** 到 **¥20.31352298**，观察到的差额约 **¥0.00081112**。刚收到回复时余额仍是旧值，稍后重读才反映扣款。无法从该合计拆分正文和标题各自的费用。
