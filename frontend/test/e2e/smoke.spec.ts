import { expect, test } from '@playwright/test'

test.describe('Smoke test', () => {
  test('should redirect unauthenticated users to login page', async ({ page }) => {
    await page.goto('/')
    await expect(page).toHaveURL('/login', { timeout: 10_000 })
    await expect(page.locator('h1')).toBeVisible()
  })
})
