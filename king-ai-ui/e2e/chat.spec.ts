import { expect, test, type Page } from '@playwright/test'
import { installApiMocks, installChatStream } from './support/mock-api'

declare global {
  interface Window {
    __chatStream: {
      emit: (event: string, data: unknown) => void
      close: () => void
      abort: () => void
    }
  }
}

async function openPlayground(page: Page) {
  const mock = await installApiMocks(page, { authenticated: true })
  await installChatStream(page)
  await page.goto('/#/playground')
  const input = page.locator('.composer-input textarea')
  await expect(input).toBeVisible({ timeout: 15_000 })
  return { input, mock }
}

test('streams assistant output until done', async ({ page }) => {
  const { input } = await openPlayground(page)

  await input.fill('你好')
  await input.press('Enter')
  await expect(page.getByRole('button', { name: '停止' })).toBeVisible()

  await page.evaluate(() =>
    window.__chatStream.emit('content', { delta: '你好，世界' }),
  )
  await expect(page.locator('.message-list')).toContainText('你好，世界')

  await page.evaluate(() =>
    window.__chatStream.emit('done', {
      messageId: 1,
      sessionId: 42,
      status: 'COMPLETED',
      durationMs: 12,
      stopped: false,
    }),
  )
  await page.evaluate(() => window.__chatStream.close())

  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0)
  await expect(page.locator('.message-list')).toContainText('已完成')
})

test('stops an in-flight generation through the API', async ({ page }) => {
  const { input, mock } = await openPlayground(page)

  await input.fill('请写一段很长的回答')
  await input.press('Enter')
  // The session id only appears once the stream meta arrives, which is also when
  // the stop button switches from a local abort to the server-side stop call.
  await expect(page.locator('.composer-foot .session-id')).toHaveText('#42')

  await page.getByRole('button', { name: '停止' }).click()
  await expect.poll(() => mock.stopCalls).toContain('/chat/requests/req-1/stop')

  await page.evaluate(() => window.__chatStream.close())
  await expect(page.locator('.message-list')).toContainText('已停止')
  await expect(page.getByRole('button', { name: '停止' })).toHaveCount(0)
})

test('renders knowledge citations streamed from the backend', async ({ page }) => {
  const { input } = await openPlayground(page)

  await input.fill('退款政策是什么')
  await input.press('Enter')

  await page.evaluate(() =>
    window.__chatStream.emit('citation', {
      index: 1,
      chunkId: 11,
      docId: 5,
      docName: '售后政策.pdf',
      knowledgeBaseId: 2,
      knowledgeBaseName: '产品库',
      chunkIndex: 0,
      score: 0.91,
      snippet: '退款需在 30 天内申请',
    }),
  )
  await page.evaluate(() =>
    window.__chatStream.emit('content', { delta: '退款政策说明' }),
  )
  await page.evaluate(() =>
    window.__chatStream.emit('done', {
      messageId: 9,
      sessionId: 42,
      status: 'COMPLETED',
      durationMs: 5,
      stopped: false,
    }),
  )
  await page.evaluate(() => window.__chatStream.close())

  const citations = page.locator('.citations')
  await expect(citations).toContainText('知识引用')
  await expect(citations).toContainText('售后政策.pdf')
  await expect(citations).toContainText('91%')
  await expect(citations).toContainText('退款需在 30 天内申请')
})

test('requires approval before executing a tool call', async ({ page }) => {
  const { input, mock } = await openPlayground(page)

  await input.fill('查询订单状态')
  await input.press('Enter')

  await page.evaluate(() =>
    window.__chatStream.emit('tool_approval', {
      requestId: 'req-1',
      callId: 'call-9',
      name: 'query_order',
      source: 'mcp',
      arguments: '{"id":1}',
      expiresInMs: 60_000,
    }),
  )

  const card = page.locator('.approval-card')
  await expect(card).toContainText('query_order')
  await expect(card).toContainText('需要确认')

  await card.getByRole('button', { name: '批准执行' }).click()
  await expect
    .poll(() => mock.approveCalls.join('\n'))
    .toContain('/chat/requests/req-1/tool-approvals/call-9')
  await expect(card).toHaveCount(0)

  await page.evaluate(() =>
    window.__chatStream.emit('done', {
      messageId: 10,
      sessionId: 42,
      status: 'COMPLETED',
      durationMs: 8,
      stopped: false,
    }),
  )
  await page.evaluate(() => window.__chatStream.close())
})
