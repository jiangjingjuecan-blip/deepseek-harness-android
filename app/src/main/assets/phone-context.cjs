// This preset supplies range selection, native DeepSeek web-tools wire bridging,
// and in-band/fallback chat titles. DSH owns the meter, summary call, journal
// transaction, image projection and provider serialization.
const { AsyncLocalStorage } = require('node:async_hooks')
const DOCUMENT_IMAGE_COST = 1024 + 8 + 8
const RECENT_TURNS = 4
const MARGIN = 16_384
const MARKER = 'phone-compact'
const RESTORE = 'phone-restore'
const WEB_HANDLER = 'phone-web'

function catalogModel(model) {
  return model === 'deepseek-flash' || model === 'deepseek-v4-pro'
}

function budget(model, info) {
  const known = catalogModel(model)
  const ceiling = known ? 1_048_576 : 65_536
  const window = Math.min(ceiling, info.context?.contextWindow ?? ceiling)
  const output = known ? Math.max(393_216, info.defaultMaxTokens ?? 256_000) : Math.min(8192, info.defaultMaxTokens ?? 8192)
  return { window, output, threshold: Math.min(Math.floor(window * 0.8), window - output - MARGIN) }
}

// After a replacement the checkpoint seq can be greater than every recent seq.
// Walk surface order. Keep the complete first exchange and four latest user turns.
function olderRange(session) {
  const nodes = session.surface.nodes
  const users = []
  let firstCheckpoint = -1
  for (let i = 0; i < nodes.length; i++) {
    const e = session.eventAt(nodes[i])
    if (e.type !== 'user/message') continue
    if (e.data.source.kind === 'user' || e.data.source.kind === 'phone-history') users.push(i)
    if (e.data.source.kind === 'compact-checkpoint' && firstCheckpoint < 0) firstCheckpoint = i
  }
  if (users.length <= RECENT_TURNS + 1) return null
  const start = firstCheckpoint > users[0] && firstCheckpoint < users[1] ? firstCheckpoint : users[1]
  return { start: nodes[start], end: nodes[users[users.length - RECENT_TURNS] - 1] }
}

// The original event and attachment stay in DSH. An already-described photo
// reaches later calls as DSH's text placeholder followed by the assistant's reply.
function offloadPhotos(session) {
  const targets = []
  for (const seq of session.surface.nodes) {
    const e = session.eventAt(seq)
    if (e.type !== 'user/message' || !e.data.content.some(b => b.type === 'image')) continue
    const imageIndexes = []
    let index = 0
    for (const block of session.deriveEventMessage(e).content) {
      if (block.type !== 'image') continue
      if (!block.offloaded) imageIndexes.push(index)
      index++
    }
    if (imageIndexes.length) targets.push({ seq, imageIndexes })
  }
  if (targets.length) session.append('image/offload', { targets })
  return targets.length > 0
}

function pressure(ctx, session) {
  const measurement = ctx.tokenMeter.measure(session)
  if (measurement.baseline.kind === 'usage') return measurement.totalTokens
  // UTF-8 byte count bounds ordinary text even for mixed CJK and emoji.
  // A cold chat has no request header yet. Allow for its fixed persona/framing.
  let estimate = 1024 + Buffer.byteLength(JSON.stringify(session.requestHeader()?.tools ?? []))
  for (const seq of session.surface.nodes) {
    const message = session.deriveEventMessage(session.eventAt(seq))
    if (!message) continue
    estimate += 8
    for (const block of message.content) {
      if (block.type === 'image' && !block.offloaded) estimate += 1024
      else estimate += Buffer.byteLength(block.text ?? JSON.stringify(block))
      estimate += 8
    }
  }
  return Math.max(measurement.totalTokens, estimate)
}

// V6 stored the DSH ID but no seqs. Match original append events (including
// ones later compacted off the surface) in order. Stop at any disagreement.
function legacyCuts(session, expected) {
  const hash = text => require('node:crypto').createHash('sha256').update(text).digest('hex')
  const cuts = []
  let user = false, chunks = []
  for (let seq = 0; seq < session.seq; seq++) {
    const e = session.eventAt(seq)
    if (e.type === 'user/message' && e.surfaceOp === 'append') {
      const kind = e.data.source.kind
      if (kind === 'runtime-context' && user && !chunks.length) cuts[cuts.length - 1] = seq
      if (kind !== 'user') continue
      const want = expected[cuts.length]
      const first = e.data.content[0]
      if (user || !want || want[0] !== 'user' || !first) break
      if (want[1] === null ? !(first.type === 'image' || first.text?.startsWith('[文件：')) :
          first.type !== 'text' || hash(first.text) !== want[1]) break
      cuts.push(seq)
      user = true
      chunks = []
    } else if (user && e.type === 'assistant/message' && e.surfaceOp === 'append') {
      const text = e.data.message.content.filter(b => b.type === 'text').map(b => b.text).join('')
      if (text) chunks.push(text)
    } else if (user && e.type === 'turn/end') {
      const want = expected[cuts.length]
      if (!want || want[0] !== 'assistant' || e.data.reason?.kind !== 'completed' || hash(chunks.join('\n\n')) !== want[1]) break
      cuts.push(seq)
      user = false
      if (cuts.length === expected.length) break
    }
  }
  return cuts
}

// Record phone-web metadata using DSH's built-in non-surface `hook/result` event
// so the JSONL v4 journal stays 100% canonical across reloads, forks and follow streams.
function emitWebEvent(session, turn, point, payload) {
  try {
    return session.append('hook/result', {
      turn: Number.isSafeInteger(turn) && turn >= 0 ? turn : 0,
      point: 'phone-web:' + point,
      handlerId: WEB_HANDLER,
      decision: JSON.stringify(payload),
      durationMs: 0,
    })
  } catch {
    return null
  }
}

// Collect saved server tool blocks per visible assistant turn on the session surface.
// Walk surface order with the same non-empty/coalescing rule as DeepSeekAdapter.serialize.
function surfaceWebBlocks(session) {
  const bySeq = new Map()
  const byTurnStep = new Map()
  for (let seq = 0; seq < session.seq; seq++) {
    const e = session.eventAt(seq)
    if (e.type !== 'hook/result' || e.data?.handlerId !== WEB_HANDLER || e.data?.point !== 'phone-web:blocks') continue
    try {
      const payload = JSON.parse(e.data.decision)
      if (!Array.isArray(payload.blocks) || !payload.blocks.length) continue
      if (Number.isSafeInteger(payload.assistantSeq)) bySeq.set(payload.assistantSeq, payload.blocks)
      else byTurnStep.set(`${e.data.turn}:${payload.step ?? 0}`, payload.blocks)
    } catch {}
  }
  if (!bySeq.size && !byTurnStep.size) return []
  const assistantTurns = []
  let lastWireRole = null
  for (const seq of session.surface.nodes) {
    const e = session.eventAt(seq)
    const msg = session.deriveEventMessage(e)
    if (!msg) continue
    if (msg.role === 'system' || msg.role === 'developer') continue
    if (msg.role === 'assistant') {
      const hasContent = msg.content.some(b => (b.type === 'text' && b.text.length > 0) || b.type === 'reasoning' || b.type === 'tool-call')
      if (!hasContent) continue
      const saved = bySeq.get(e.seq) ?? (msg.source?.provider !== 'phone-local' ? byTurnStep.get(`${e.data.turn}:${e.data.step ?? 0}`) : null)
      if (lastWireRole === 'assistant' && assistantTurns.length) {
        if (saved?.length) {
          const prev = assistantTurns[assistantTurns.length - 1]
          assistantTurns[assistantTurns.length - 1] = prev ? prev.concat(saved) : saved
        }
      } else {
        assistantTurns.push(saved ?? null)
      }
      lastWireRole = 'assistant'
    } else if (msg.role === 'user' || msg.role === 'tool') {
      if (msg.role === 'user' && !msg.content.length) continue
      lastWireRole = 'user'
    }
  }
  return assistantTurns
}

// Re-inject saved server_tool_use / web_*_tool_result blocks into serialized assistant messages.
function injectHistoryWebBlocks(session, wireMessages) {
  const savedTurns = surfaceWebBlocks(session)
  if (!savedTurns.length) return
  const wireAssistants = wireMessages.filter(m => m.role === 'assistant')
  if (wireAssistants.length !== savedTurns.length) return
  for (let i = 0; i < wireAssistants.length; i++) {
    const entries = savedTurns[i]
    if (!entries?.length) continue
    const content = Array.isArray(wireAssistants[i].content) ? wireAssistants[i].content : []
    const hasThinking = content.some(b => b.type === 'thinking')
    if (!hasThinking) {
      // Restored from SQLite (text only): place server tool blocks before answer text.
      const blocks = entries.map(item => item.block ?? item).filter(b => b && typeof b === 'object')
      wireAssistants[i].content = blocks.concat(content)
    } else {
      // Live DSH session: splice each block back at its exact recorded slot among thinking/text blocks.
      const out = []
      let cursor = 0
      const sorted = entries.map((item, idx) => ({
        slot: Number.isSafeInteger(item.slot) ? Math.max(0, Math.min(item.slot, content.length)) : 0,
        idx,
        block: item.block ?? item,
      })).filter(x => x.block && typeof x.block === 'object')
        .sort((a, b) => a.slot - b.slot || a.idx - b.idx)
      for (const item of sorted) {
        while (cursor < item.slot) out.push(content[cursor++])
        out.push(item.block)
      }
      while (cursor < content.length) out.push(content[cursor++])
      wireAssistants[i].content = out
    }
  }
}

function isServerToolBlock(type) {
  return type === 'server_tool_use' || type === 'web_search_tool_result' || type === 'web_fetch_tool_result'
}

// Wrap a DeepSeek Messages SSE stream: intercept server tool calls/results/citations/title
// for Android while handing DSH a clean remapped stream it natively understands.
function transformWebSse(bodyStream, session, turn, step) {
  const decoder = new TextDecoder()
  const encoder = new TextEncoder()
  const indexMap = new Map()
  const suppressed = new Set()
  const collectedBlocks = []
  const sourcesByUrl = new Map()
  const citations = []
  const citationUrls = new Set()
  const toolErrors = []
  let nextMappedIndex = 0
  let buffer = ''
  let flushedBlocks = false
  let refused = false

  function flushCollected() {
    if (flushedBlocks) return
    flushedBlocks = true
    if (collectedBlocks.length || citations.length || toolErrors.length) {
      emitWebEvent(session, turn, 'blocks', {
        step,
        blocks: collectedBlocks,
        citations,
        errors: toolErrors,
      })
    }
  }

  function recordSourcesFromBlock(cb) {
    if (cb.type === 'web_search_tool_result') {
      const items = Array.isArray(cb.content) ? cb.content : (cb.content ? [cb.content] : [])
      let count = 0
      let errorCode = ''
      for (const item of items) {
        if (!item || typeof item !== 'object') continue
        if (item.type === 'web_search_tool_result_error') {
          errorCode = typeof item.error_code === 'string' ? item.error_code : 'unavailable'
        } else if (item.type === 'web_search_result' && typeof item.url === 'string' && item.url) {
          count++
          if (!sourcesByUrl.has(item.url)) {
            sourcesByUrl.set(item.url, {
              url: item.url,
              title: typeof item.title === 'string' ? item.title : '',
              site: typeof item.site_name === 'string' ? item.site_name : '',
            })
          }
        }
      }
      if (errorCode) toolErrors.push({ toolUseId: cb.tool_use_id || '', kind: cb.kind || 'search', errorCode })
      emitWebEvent(session, turn, 'result', {
        toolUseId: cb.tool_use_id || '',
        kind: cb.kind || 'search',
        count,
        errorCode,
      })
    } else if (cb.type === 'web_fetch_tool_result') {
      const c = cb.content
      let errorCode = ''
      let url = ''
      if (c && typeof c === 'object') {
        if (c.type === 'web_fetch_tool_result_error') {
          errorCode = typeof c.error_code === 'string' ? c.error_code : 'unavailable'
        } else if (c.type === 'web_fetch_result' && typeof c.url === 'string' && c.url) {
          url = c.url
          const title = typeof c.content?.title === 'string' ? c.content.title : ''
          if (!sourcesByUrl.has(url)) sourcesByUrl.set(url, { url, title, site: '' })
        }
      }
      if (errorCode) toolErrors.push({ toolUseId: cb.tool_use_id || '', kind: cb.kind || 'read_link', errorCode })
      emitWebEvent(session, turn, 'result', {
        toolUseId: cb.tool_use_id || '',
        kind: cb.kind || 'read_link',
        count: url ? 1 : 0,
        errorCode,
      })
    }
  }

  function processFrame(rawFrame, controller) {
    const lines = rawFrame.split(/\r?\n/)
    let eventName = null
    const dataLines = []
    const commentLines = []
    for (const line of lines) {
      if (line.startsWith(':')) commentLines.push(line)
      else if (line.startsWith('event:')) eventName = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    for (const c of commentLines) controller.enqueue(encoder.encode(c + '\n\n'))
    if (!dataLines.length) return
    const rawData = dataLines.join('\n')
    let ev
    try {
      ev = JSON.parse(rawData)
    } catch {
      controller.enqueue(encoder.encode(rawFrame + '\n\n'))
      return
    }
    if (!ev || typeof ev !== 'object') {
      controller.enqueue(encoder.encode(rawFrame + '\n\n'))
      return
    }
    const emit = (kind, obj) => {
      controller.enqueue(encoder.encode(`event: ${kind}\ndata: ${JSON.stringify(obj)}\n\n`))
    }
    if (ev.type === 'content_block_start' && ev.content_block && isServerToolBlock(ev.content_block.type)) {
      const cb = ev.content_block
      suppressed.add(ev.index)
      collectedBlocks.push({ slot: nextMappedIndex, block: cb })
      if (cb.type === 'server_tool_use') {
        const input = cb.input && typeof cb.input === 'object' ? cb.input : {}
        emitWebEvent(session, turn, 'activity', {
          id: typeof cb.id === 'string' ? cb.id : '',
          action: typeof input.action === 'string' ? input.action : '',
          kind: typeof cb.kind === 'string' ? cb.kind : '',
          queries: Array.isArray(input.queries) ? input.queries.filter(q => typeof q === 'string') : [],
          url: typeof input.url === 'string' ? input.url : '',
          pattern: typeof input.pattern === 'string' ? input.pattern : '',
        })
      } else {
        recordSourcesFromBlock(cb)
      }
      return
    }
    if (ev.type === 'content_block_start') {
      const mapped = nextMappedIndex++
      indexMap.set(ev.index, mapped)
      emit('content_block_start', { ...ev, index: mapped })
      return
    }
    if (ev.type === 'content_block_delta') {
      if (suppressed.has(ev.index)) return
      if (ev.delta && ev.delta.type === 'citations_delta') {
        const c = ev.delta.citation
        if (c && typeof c.url === 'string' && c.url && !citationUrls.has(c.url)) {
          citationUrls.add(c.url)
          const known = sourcesByUrl.get(c.url)
          const title = (typeof c.title === 'string' && c.title.trim()) ? c.title.trim() : (known?.title || '')
          const site = known?.site || ''
          const entry = { url: c.url, title, site }
          citations.push(entry)
          emitWebEvent(session, turn, 'citation', entry)
        }
        return
      }
      const mapped = indexMap.get(ev.index)
      if (mapped !== undefined) emit('content_block_delta', { ...ev, index: mapped })
      return
    }
    if (ev.type === 'content_block_stop') {
      if (suppressed.has(ev.index)) return
      const mapped = indexMap.get(ev.index)
      if (mapped !== undefined) emit('content_block_stop', { ...ev, index: mapped })
      return
    }
    if (ev.type === 'message_delta') {
      const delta = ev.delta && typeof ev.delta === 'object' ? { ...ev.delta } : {}
      if (typeof delta.title === 'string' && delta.title.trim()) {
        emitWebEvent(session, turn, 'title', { title: delta.title.trim() })
        delete delta.title
      }
      if (delta.stop_reason === 'refusal') {
        flushCollected()
        refused = true
        emit('error', {
          type: 'error',
          error: { type: 'content_refusal', code: 'CONTENT_REFUSAL', message: 'Content review refused the response' },
        })
        return
      }
      emit('message_delta', { ...ev, delta })
      return
    }
    if (ev.type === 'message_stop') {
      if (refused) return
      flushCollected()
      emit('message_stop', ev)
      return
    }
    if (ev.type === 'error') {
      flushCollected()
      emit('error', ev)
      return
    }
    emit(eventName || ev.type, ev)
  }

  return bodyStream.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      buffer += decoder.decode(chunk, { stream: true })
      const sep = /\r?\n\r?\n/g
      let last = 0
      let match
      while ((match = sep.exec(buffer)) !== null) {
        const frame = buffer.slice(last, match.index)
        last = sep.lastIndex
        if (frame.length) processFrame(frame, controller)
      }
      if (last > 0) buffer = buffer.slice(last)
    },
    flush(controller) {
      buffer += decoder.decode()
      if (buffer.trim().length) processFrame(buffer, controller)
      flushCollected()
    },
    cancel() {
      flushCollected()
    },
  }))
}

async function* runInContext(storage, store, factory) {
  const iter = storage.run(store, factory)[Symbol.asyncIterator]()
  try {
    while (true) {
      const step = await storage.run(store, () => iter.next())
      if (step.done) return step.value
      yield step.value
    }
  } finally {
    if (typeof iter.return === 'function') await storage.run(store, () => iter.return())
  }
}

exports.name = 'phone-context'
exports.inject = ['compaction', 'tokenMeter', 'commands', 'sessions', 'llm']
exports.apply = function apply(ctx, config) {
  const pending = new WeakMap()
  const pictures = new WeakSet()
  const activeSessions = new Map()
  const webConfigs = new Map()
  const activeTurns = new Map()
  const llmContext = new AsyncLocalStorage()

  function remember(agent) {
    if (agent?.session?.id) activeSessions.set(agent.session.id, agent.session)
  }

  ctx.on('agent/disposed', ({ agent }) => {
    const id = agent?.session?.id
    if (!id) return
    activeSessions.delete(id)
    webConfigs.delete(id)
    activeTurns.delete(id)
  }, { global: true })

  ctx.on('agent/request', async ({agent, signal}, next) => {
    remember(agent)
    const config = await next()
    if (!catalogModel(config.model)) {
      const info = await ctx.llm.resolveModelInfo(config.provider, config.model, signal)
      return { ...config, maxTokens: budget(config.model, info).output }
    }
    const previous = agent.session.requestHeader()?.config
    if (previous && !catalogModel(previous.model)) {
      // Let DSH resolve the catalog model's default after leaving a capped route.
      const {maxTokens, ...defaults} = config
      return defaults
    }
    return config
  })
  ctx.on('agent/inbox/claimed', ({agent, message}) => {
    remember(agent)
    if (message.content.some(b => b.type === 'image')) pictures.add(agent)
  })
  ctx.on('agent/turn-stopping', ({agent}) => {
    if (pictures.delete(agent)) offloadPhotos(agent.session)
  })

  // Propagate exact LLM call metadata (sessionId, purpose) into our fetch interceptor so
  // compaction and auxiliary title calls are never confused with conversation turns.
  ctx.on('llm/stream', (options, next) => {
    if (options.provider !== 'deepseek-account' || options.purpose !== undefined) return next()
    const session = options.sessionId !== undefined ? activeSessions.get(String(options.sessionId)) : undefined
    if (!session) return next()
    return runInContext(llmContext, { sessionId: String(options.sessionId), session }, () => next())
  }, { global: true })

  // Intercept DeepSeek Messages requests only for active phone-chat conversation turns.
  ctx.effect(() => {
    const origFetch = globalThis.fetch
    if (typeof origFetch !== 'function') return
    const wrapped = async function phoneWebFetch(input, init) {
      const store = llmContext.getStore()
      const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : input?.url || '')
      if (!store || !init || init.method !== 'POST' || typeof init.body !== 'string' || !/\/messages(?:\?|$)/.test(url)) {
        return origFetch.call(this, input, init)
      }
      let body
      try {
        body = JSON.parse(init.body)
      } catch {
        return origFetch.call(this, input, init)
      }
      const { sessionId, session } = store
      const cfg = webConfigs.get(sessionId)
      let mutated = false
      if (Array.isArray(body.messages)) {
        const before = body.messages.map(m => m.role === 'assistant' ? (m.content?.length ?? 0) : 0)
        injectHistoryWebBlocks(session, body.messages)
        if (body.messages.some((m, i) => m.role === 'assistant' && (m.content?.length ?? 0) !== before[i])) mutated = true
      }
      if (body.model === 'deepseek-flash' && cfg) {
        const mode = cfg.mode
        // DeepSeek forbids mixing other tools (such as DSH's default working_directory)
        // with deepseek_web_search, deepseek_read_url, or title. Replace body.tools cleanly.
        if (mode === 'search') {
          body.tools = [
            { type: 'deepseek_web_search', name: 'deepseek_web_search' },
            { type: 'deepseek_read_url', name: 'deepseek_read_url' },
          ]
          mutated = true
        } else if (mode === 'read') {
          body.tools = [
            { type: 'deepseek_read_url', name: 'deepseek_read_url' },
          ]
          mutated = true
        } else if (cfg.wantTitle && Array.isArray(body.tools)) {
          delete body.tools
          mutated = true
        }
        // DeepSeek returns 400 if tool_choice names a web tool.
        if (body.tool_choice && typeof body.tool_choice === 'object' &&
            (body.tool_choice.name === 'deepseek_web_search' || body.tool_choice.name === 'deepseek_read_url')) {
          delete body.tool_choice
          mutated = true
        }
        if (cfg.wantTitle) {
          body.title = { enabled: true }
          mutated = true
        }
      }
      const nextInit = mutated ? { ...init, body: JSON.stringify(body) } : init
      const res = await origFetch.call(this, input, nextInit)
      const contentType = res.headers?.get?.('content-type') || ''
      if (!res.ok || !res.body || !contentType.includes('text/event-stream')) return res
      const turnInfo = activeTurns.get(sessionId) || { turn: 0, step: 0 }
      const transformed = transformWebSse(res.body, session, turnInfo.turn, turnInfo.step)
      return new Response(transformed, { status: res.status, statusText: res.statusText, headers: res.headers })
    }
    globalThis.fetch = wrapped
    return () => {
      if (globalThis.fetch === wrapped) globalThis.fetch = origFetch
    }
  })

  // compactRegion requires an open turn. A private, empty inbox item opens a
  // DSH maintenance turn. Returning no messages closes it before an LLM step.
  // An orphaned marker after recovery is consumed without invoking the model.
  ctx.on('agent/pre-step', async ({agent, messages, signal, turn, step}, next) => {
    remember(agent)
    const marker = messages.find(m => m.source.kind === MARKER || m.source.kind === RESTORE)
    if (!marker) {
      activeTurns.set(agent.session.id, { turn, step })
      return next()
    }
    const decision = await next()
    if (decision.kind !== 'enter') return decision
    const job = pending.get(agent)
    if (job && marker.id === job.id && marker.source.kind === RESTORE) {
      const {role, content, webBlocks} = job.row
      // The normal driver admits the persona before the first message. Use the
      // same preset scalar and the driver's own runtime-context proposal.
      if (!agent.session.surface.nodes.length) {
        const system = {id: job.id + '-system', role: 'system', source: {kind: 'system-prompt'},
          content: [{type: 'text', text: config.persona}]}
        agent.session.append('system/message', {turn, step, message: system}, {surfaceOp: 'append'})
      }
      const source = role === 'user' ? {kind: 'phone-history'} : {kind: 'model', provider: 'phone-local', model: 'transcript'}
      const message = {id: job.id, role, content, source}
      let assistantSeq = -1
      const event = role === 'user'
        ? agent.session.append('user/message', message, {surfaceOp: 'append'})
        : (() => {
          const batch = agent.session.append([
            ['step/start', {turn, step}],
            ['assistant/message', {turn, step, message, stream: []}, {surfaceOp: 'append'}],
            ['step/end', {turn, step}],
          ])
          assistantSeq = batch[1].seq
          return batch[2]
        })()
      job.seq = event.seq
      if (role === 'assistant' && assistantSeq >= 0 && Array.isArray(webBlocks) && webBlocks.length) {
        const saved = emitWebEvent(agent.session, turn, 'blocks', { assistantSeq, blocks: webBlocks })
        if (saved) job.seq = saved.seq
      }
      for (const reminder of decision.messages.filter(m => m.source.kind === 'runtime-context')) {
        job.seq = agent.session.append('user/message', reminder, {surfaceOp: 'append'}).seq
      }
    } else if (job && marker.id === job.id) {
      const range = olderRange(agent.session)
      if (range) {
        const abort = AbortSignal.any([signal, job.signal])
        await ctx.compaction.compactRegion(range.start, range.end, agent, abort)
        job.changed = true
      }
      job.finished = true
    }
    // Even an empty cold turn can propose a runtime reminder. It must not start a provider request.
    const ordinary = m => m.source.kind !== MARKER && m.source.kind !== RESTORE
    return { ...decision, messages: messages.some(ordinary) ? decision.messages.filter(ordinary) : [] }
  }, { prepend: true })

  ctx.effect(() => ctx.commands.register({
    definitionId: 'phone-web', name: 'phone-web', recordInput: false, description: '联网配置',
    handler({agent, rawInput}) {
      remember(agent)
      try {
        const arg = JSON.parse(rawInput)
        if (!['search', 'read', 'off'].includes(arg.mode)) return {kind: 'error', text: '参数错误'}
        webConfigs.set(agent.session.id, { mode: arg.mode, wantTitle: Boolean(arg.title) })
        return {kind: 'success', text: 'ok'}
      } catch {
        return {kind: 'error', text: '参数错误'}
      }
    },
  }))

  // Fallback title generator for models that do not support the top-level `title` parameter.
  ctx.effect(() => ctx.commands.register({
    definitionId: 'phone-title', name: 'phone-title', recordInput: false, description: '生成标题',
    async handler({agent, rawInput, signal}) {
      try {
        const arg = JSON.parse(rawInput)
        const transcript = typeof arg.transcript === 'string' ? arg.transcript.trim() : ''
        if (!transcript) return {kind: 'error', text: '参数错误'}
        const call = await ctx.llm.prepareCall(
          { provider: 'deepseek-account', model: 'deepseek-flash', maxTokens: 32, reasoningEffort: 'off' },
          signal,
        )
        const stream = call.stream({
          ...call.config,
          system: 'Generate a concise conversation title. Use Chinese if the chat is Chinese (at most 12 characters) or English (at most 6 words). Return only the title with no quotes or trailing punctuation.',
          messages: [{
            id: 'phone-title-' + Date.now(),
            role: 'user',
            content: [{ type: 'text', text: transcript }],
            source: { kind: 'phone-title' },
          }],
          sessionId: agent.session.id,
          purpose: 'phone-title',
          signal,
        })
        let text = ''
        for await (const chunk of stream) {
          if (chunk.type === 'text-delta') text += chunk.text
        }
        text = text.trim()
        return text ? {kind: 'success', text} : {kind: 'error', text: '空标题'}
      } catch {
        return {kind: 'error', text: '生成失败'}
      }
    },
  }))

  ctx.effect(() => ctx.commands.register({
    definitionId: 'phone-locate', name: 'phone-locate', recordInput: false, description: '定位存档',
    handler({agent, rawInput}) {
      remember(agent)
      const expected = JSON.parse(rawInput)
      return {kind: 'success', text: JSON.stringify(legacyCuts(agent.session, expected))}
    },
  }))

  // Import a visible SQLite row on a private session. The empty maintenance turn
  // gives it a real turn boundary without replaying a prompt against the model.
  ctx.effect(() => ctx.commands.register({
    definitionId: RESTORE, name: RESTORE, recordInput: false, description: '恢复分支',
    async handler({agent, rawInput, signal, commandId}) {
      remember(agent)
      if (pending.has(agent)) return {kind: 'error', text: '正在处理'}
      const row = JSON.parse(rawInput)
      if (!['user', 'assistant'].includes(row.role) || !Array.isArray(row.content) || !row.content.length ||
          !row.content.every(b => b.type === 'text' && typeof b.text === 'string')) return {kind: 'error', text: '参数错误'}
      if (row.webBlocks !== undefined && !Array.isArray(row.webBlocks)) return {kind: 'error', text: '参数错误'}
      const job = {id: commandId, row}
      pending.set(agent, job)
      try {
        await agent.whenIdle()
        signal.throwIfAborted()
        agent.followup({id: commandId, role: 'user', content: [], source: {kind: RESTORE}})
        await agent.whenIdle()
        if (!Number.isSafeInteger(job.seq)) return {kind: 'error', text: '恢复失败'}
        await ctx.sessions.flush(agent.session)
        return {kind: 'success', text: String(job.seq)}
      } finally {
        pending.delete(agent)
      }
    },
  }))

  ctx.effect(() => ctx.commands.register({
    definitionId: 'phone-context', name: 'phone-context', recordInput: false,
    description: '压缩上下文',
    async handler({agent, rawInput, signal, commandId}) {
      remember(agent)
      const input = rawInput.trim()
      const manual = input === 'manual'
      const files = /^files (\d+) (\d+) ([0-4])(?: (\d+))?$/.exec(input)
      const base = manual ? 0 : Number(files ? files[1] : input)
      const error = text => ({ kind: 'error', text })
      if (!manual && ((!files && !/^\d+$/.test(input)) || !Number.isSafeInteger(base))) return error('参数错误')
      const images = Number(files?.[4] ?? 0)
      if (files && (Number(files[2]) > 65536 || images > 20)) return error('参数错误')
      if (pending.has(agent)) return error('正在压缩')
      const job = { id: commandId, signal, finished: false, changed: false }
      pending.set(agent, job)
      try {
        await agent.whenIdle()
        signal.throwIfAborted()
        const session = agent.session
        const selection = agent.model.current()
        const info = await ctx.llm.resolveModelInfo(selection.provider, selection.model, signal)
        const {window, threshold} = budget(selection.model, info)
        // Also covers images left in v0.1.2 sessions or an interrupted turn.
        await agent.runMaintenance(async () => {
          if (offloadPhotos(session)) await ctx.sessions.flush(session)
        })
        let used = pressure(ctx, session)
        // Only the new file bodies can shrink. Preserve the user's question, labels,
        // picked photos and enough bytes for the first code point of each text attachment.
        const minimum = files ? Math.min(Number(files[2]), 4 * Number(files[3])) : 0
        const desired = files ? Math.min(Number(files[2]), Math.floor(threshold / 4)) : 0
        if (!manual && base + minimum >= threshold) return error('消息过长')
        if ((manual || used + base + desired + images * DOCUMENT_IMAGE_COST >= threshold) && olderRange(session)) {
          signal.throwIfAborted()
          agent.followup({ id: job.id, role: 'user', content: [], source: {kind: MARKER} })
          await agent.whenIdle()
          if (!job.finished) return error('压缩失败')
          await ctx.sessions.flush(session)
          used = pressure(ctx, session)
        }
        if (!manual && used + base + minimum >= threshold) return error('上下文过长')
        const reply = [used, window, Number(job.changed)]
        if (files) {
          const room = threshold - used - base - 1
          const slots = Math.min(images, Math.floor((room - minimum) / DOCUMENT_IMAGE_COST))
          reply.push(Math.min(desired, room - slots * DOCUMENT_IMAGE_COST))
          if (files[4] !== undefined) reply.push(slots)
        }
        return { kind: 'success', text: reply.join(' ') }
      } catch {
        return error('压缩失败')
      } finally {
        pending.delete(agent)
      }
    },
  }))
}
