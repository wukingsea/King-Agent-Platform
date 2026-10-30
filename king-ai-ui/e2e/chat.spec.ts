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
  await expect(input).toBeVisible()
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
