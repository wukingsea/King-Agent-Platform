import { ApiError, authHeaders } from './http'
import type {
  ChatDegradedEvent,
  ChatRetryEvent,
  ChatStreamHandlers,
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
  let payload: any
  try {
    payload = JSON.parse(data)
  } catch {
    return
  }
  switch (event) {
    case 'meta':
      handlers.onMeta?.(payload)
      break
    case 'citation':
      handlers.onCitation?.(payload as CitationItem)
      break
    case 'reasoning':
      if (payload.delta) handlers.onReasoning?.(payload.delta)
      break
    case 'content':
      if (payload.delta) handlers.onContent?.(payload.delta)
      break
    case 'tool_call':
      handlers.onToolCall?.(payload as ToolCallEvent)
      break
    case 'tool_result':
      handlers.onToolResult?.(payload as ToolResultEvent)
      break
    case 'tool_approval':
      handlers.onToolApproval?.(payload as ToolApprovalEvent)
      break
    case 'retry':
      handlers.onRetry?.(payload as ChatRetryEvent)
      break
    case 'degraded':
      handlers.onDegraded?.(payload as ChatDegradedEvent)
      break
    case 'usage':
      handlers.onUsage?.(payload)
      break
    case 'error':
      handlers.onError?.(payload.message || '生成失败')
      break
    case 'done':
      handlers.onDone?.(payload)
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
