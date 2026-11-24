// This preset supplies range selection. DSH owns the meter, summary call, journal
// transaction, image projection and provider serialization.
const RECENT_TURNS = 4
const MARGIN = 16_384
const MARKER = 'phone-compact'

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
    if (e.data.source.kind === 'user') users.push(i)
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

exports.name = 'phone-context'
exports.inject = ['compaction', 'tokenMeter', 'commands', 'sessions', 'llm']
exports.apply = function apply(ctx) {
  const pending = new WeakMap()
  const pictures = new WeakSet()

  ctx.on('agent/request', async ({agent, signal}, next) => {
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
    if (message.content.some(b => b.type === 'image')) pictures.add(agent)
  })
  ctx.on('agent/turn-stopping', ({agent}) => {
    if (pictures.delete(agent)) offloadPhotos(agent.session)
  })

  // compactRegion requires an open turn. A private, empty inbox item opens a
  // DSH maintenance turn. Returning no messages closes it before an LLM step.
  // An orphaned marker after recovery is consumed without invoking the model.
  ctx.on('agent/pre-step', async ({agent, messages, signal}, next) => {
    const marker = messages.find(m => m.source.kind === MARKER)
    if (!marker) return next()
    const job = pending.get(agent)
    if (job && marker.id === job.id) {
      const range = olderRange(agent.session)
      if (range) {
        const abort = AbortSignal.any([signal, job.signal])
        await ctx.compaction.compactRegion(range.start, range.end, agent, abort)
        job.changed = true
      }
      job.finished = true
    }
    const decision = await next()
    if (decision.kind !== 'enter') return decision
    return { ...decision, messages: decision.messages.filter(m => m.source.kind !== MARKER) }
  }, { prepend: true })

  ctx.effect(() => ctx.commands.register({
    definitionId: 'phone-context', name: 'phone-context', recordInput: false,
    description: '压缩上下文',
    async handler({agent, rawInput, signal, commandId}) {
      const input = rawInput.trim()
      const manual = input === 'manual'
      const draft = manual ? 0 : Number(input)
      const error = text => ({ kind: 'error', text })
      if (!manual && (!/^\d+$/.test(input) || !Number.isSafeInteger(draft))) return error('参数错误')
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
        if (!manual && draft >= threshold) return error('消息过长')
        if ((manual || used + draft >= threshold) && olderRange(session)) {
          signal.throwIfAborted()
          agent.followup({ id: job.id, role: 'user', content: [], source: {kind: MARKER} })
          await agent.whenIdle()
          if (!job.finished) return error('压缩失败')
          await ctx.sessions.flush(session)
          used = pressure(ctx, session)
        }
        if (!manual && used + draft >= threshold) return error('上下文过长')
        return { kind: 'success', text: [used, window, Number(job.changed)].join(' ') }
      } catch {
        return error('压缩失败')
      } finally {
        pending.delete(agent)
      }
    },
  }))
}
