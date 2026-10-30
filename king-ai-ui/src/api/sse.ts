import { ApiError, authHeaders } from './http'
import type {
  ChatDegradedEvent,
  ChatDone,
  ChatMeta,
  ChatRetryEvent,
  ChatStreamHandlers,
  ChatUsage,
  CitationItem,
  ToolApprovalEvent,
  ToolCallEvent,
  ToolResultEvent,
} from './types'

interface StreamOptions {
  path: string
  body: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
  handlers: ChatStreamHandlers
}

function dispatch(
  event: string,
  data: string,
  handlers: ChatStreamHandlers,
) {
  if (!data) return
  let payload: Record<string, unknown>
  try {
    payload = JSON.parse(data)
  } catch {
    return
  }
  if (!payload || typeof payload !== 'object') return
  switch (event) {
    case 'meta':
      handlers.onMeta?.(payload as unknown as ChatMeta)
      break
    case 'citation':
      handlers.onCitation?.(payload as unknown as CitationItem)
      break
    case 'reasoning':
      if (typeof payload.delta === 'string' && payload.delta) {
        handlers.onReasoning?.(payload.delta)
      }
      break
    case 'content':
      if (typeof payload.delta === 'string' && payload.delta) {
        handlers.onContent?.(payload.delta)
      }
      break
    case 'tool_call':
      handlers.onToolCall?.(payload as unknown as ToolCallEvent)
      break
    case 'tool_result':
      handlers.onToolResult?.(payload as unknown as ToolResultEvent)
      break
    case 'tool_approval':
      handlers.onToolApproval?.(payload as unknown as ToolApprovalEvent)
      break
    case 'retry':
      handlers.onRetry?.(payload as unknown as ChatRetryEvent)
      break
    case 'degraded':
      handlers.onDegraded?.(payload as unknown as ChatDegradedEvent)
      break
    case 'usage':
      handlers.onUsage?.(payload as unknown as ChatUsage)
      break
    case 'error':
      handlers.onError?.(typeof payload.message === 'string' ? payload.message : '生成失败')
      break
    case 'done':
      handlers.onDone?.(payload as unknown as ChatDone)
      break
  }
}

export async function streamChat(options: StreamOptions): Promise<void> {
  const { path, body, signal, handlers } = options
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...authHeaders(),
      ...(options.headers || {}),
    },
    body: JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    let message = `请求失败（${response.status}）`
    try {
      const payload = await response.json()
      if (payload?.message) message = payload.message
    } catch {
      /* ignore */
    }
    throw new ApiError(message, response.status)
  }
  if (!response.body) {
    throw new ApiError('当前浏览器不支持流式响应', 0)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const consume = (flush = false) => {
    buffer = buffer.replace(/\r\n/g, '\n')
    let index = buffer.indexOf('\n\n')
    while (index >= 0) {
      const block = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      let event = 'message'
      const dataLines: string[] = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
      }
      if (dataLines.length) dispatch(event, dataLines.join('\n'), handlers)
      index = buffer.indexOf('\n\n')
    }
    if (flush && buffer.trim()) {
      const lines = buffer.trim().split('\n')
      const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message'
      const data = lines
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart())
        .join('\n')
      if (data) dispatch(event, data, handlers)
      buffer = ''
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      consume()
    }
    buffer += decoder.decode()
    consume(true)
  } finally {
    reader.releaseLock()
  }
}
