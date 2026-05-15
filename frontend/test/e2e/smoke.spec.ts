import { expect, test } from '@playwright/test'

test.describe('Smoke test', () => {
  test('should load the home page and show the app heading', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('h1')).toHaveText('MovieArchive')
  })
})
