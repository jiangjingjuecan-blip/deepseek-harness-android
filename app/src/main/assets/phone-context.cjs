// This preset supplies range selection. DSH owns the meter, summary call, journal
// transaction, image projection and provider serialization.
const DOCUMENT_IMAGE_COST = 1024 + 8 + 8
const RECENT_TURNS = 4
const MARGIN = 16_384
const MARKER = 'phone-compact'
const RESTORE = 'phone-restore'

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

exports.name = 'phone-context'
exports.inject = ['compaction', 'tokenMeter', 'commands', 'sessions', 'llm']
exports.apply = function apply(ctx, config) {
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
  ctx.on('agent/pre-step', async ({agent, messages, signal, turn, step}, next) => {
    const marker = messages.find(m => m.source.kind === MARKER || m.source.kind === RESTORE)
    if (!marker) return next()
    const decision = await next()
    if (decision.kind !== 'enter') return decision
    const job = pending.get(agent)
    if (job && marker.id === job.id && marker.source.kind === RESTORE) {
      const {role, content} = job.row
      // The normal driver admits the persona before the first message. Use the
      // same preset scalar and the driver's own runtime-context proposal.
      if (!agent.session.surface.nodes.length) {
        const system = {id: job.id + '-system', role: 'system', source: {kind: 'system-prompt'},
          content: [{type: 'text', text: config.persona}]}
        agent.session.append('system/message', {turn, step, message: system}, {surfaceOp: 'append'})
      }
      const source = role === 'user' ? {kind: 'phone-history'} : {kind: 'model', provider: 'phone-local', model: 'transcript'}
      const message = {id: job.id, role, content, source}
      const event = role === 'user'
        ? agent.session.append('user/message', message, {surfaceOp: 'append'})
        : agent.session.append([
          ['step/start', {turn, step}],
          ['assistant/message', {turn, step, message, stream: []}, {surfaceOp: 'append'}],
          ['step/end', {turn, step}],
        ])[2]
      job.seq = event.seq
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
    definitionId: 'phone-locate', name: 'phone-locate', recordInput: false, description: '定位存档',
    handler({agent, rawInput}) {
      const expected = JSON.parse(rawInput)
      return {kind: 'success', text: JSON.stringify(legacyCuts(agent.session, expected))}
    },
  }))

  // Import a visible SQLite row on a private session. The empty maintenance turn
  // gives it a real turn boundary without replaying a prompt against the model.
  ctx.effect(() => ctx.commands.register({
    definitionId: RESTORE, name: RESTORE, recordInput: false, description: '恢复分支',
    async handler({agent, rawInput, signal, commandId}) {
      if (pending.has(agent)) return {kind: 'error', text: '正在处理'}
      const row = JSON.parse(rawInput)
      if (!['user', 'assistant'].includes(row.role) || !Array.isArray(row.content) || !row.content.length ||
          !row.content.every(b => b.type === 'text' && typeof b.text === 'string')) return {kind: 'error', text: '参数错误'}
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
