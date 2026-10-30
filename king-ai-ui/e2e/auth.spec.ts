import { expect, test } from '@playwright/test'
import { installApiMocks } from './support/mock-api'

test('logs in and lands on the dashboard', async ({ page }) => {
  const mock = await installApiMocks(page)

  await page.goto('/#/login')
  await page.getByPlaceholder('请输入用户名').fill('admin')
  await page.getByPlaceholder('请输入登录密码').fill('secret-pass-1')
  await page.getByRole('button', { name: '进入工作台' }).click()

  await expect(page).toHaveURL(/#\/dashboard/)
  await expect(page.locator('.sidebar .user-name')).toHaveText('管理员')
  expect(mock.loginCalls).toBe(1)
  await expect
    .poll(() => page.evaluate(() => localStorage.getItem('king_ai_space')))
    .toBe('1')
})

test('switches space and reloads into it', async ({ page }) => {
  await installApiMocks(page, { authenticated: true })

  await page.goto('/#/dashboard')
  await expect(page.locator('.space-select')).toContainText('总部空间')

  await page.locator('.space-select').click()
  const option = page.locator('.el-select-dropdown__item:visible', { hasText: '研发空间' })
  await Promise.all([page.waitForEvent('load'), option.click()])

  await expect(page.locator('.space-select')).toContainText('研发空间')
  await expect(page.locator('.hero-eyebrow')).toContainText('研发空间')
  expect(await page.evaluate(() => localStorage.getItem('king_ai_space'))).toBe('2')
})
