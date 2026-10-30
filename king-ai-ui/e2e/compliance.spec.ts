import { expect, test } from '@playwright/test'
import { auditorUser, installApiMocks } from './support/mock-api'

test('space auditors can review governance data but cannot change it', async ({ page }) => {
  await installApiMocks(page, {
    authenticated: true,
    user: auditorUser,
    spaces: [
      {
        id: 1,
        name: '总部空间',
        code: 'hq',
        status: 0,
        role: 'AUDITOR',
        createdAt: '2026-10-01T08:00:00',
      },
    ],
  })

  await page.goto('/#/compliance')

  await expect(page.getByRole('tab', { name: '留存策略' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '访问复核' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '合规设置' })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: '数据导出' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '新增策略' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '刷新配置' })).toHaveCount(0)

  await page.getByRole('tab', { name: '访问复核' }).click()
  await expect(page.getByText('还没有访问复核')).toBeVisible()
})
