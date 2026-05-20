import { expect, test } from '@playwright/test'

// Backend URL for direct API calls (setup endpoint bypasses Caddy)
const BACKEND_URL = process.env.BACKEND_URL ?? 'http://localhost:8080'

const TEST_EMAIL = process.env.TEST_USER_EMAIL ?? 'e2e@moviearchive.test'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD ?? 'E2ePassword1!'

// Serial mode prevents parallel beforeAll race on the test user
// (two projects share the same test user — idempotent setup handles it, but serial avoids races)
test.describe.configure({ mode: 'serial' })

test.describe('Happy path', () => {
  test.beforeAll(async ({ request }) => {
    // Seed test user and TMDB key via test-profile-only endpoint (D-12, D-14)
    const response = await request.post(`${BACKEND_URL}/test/setup`)
    expect(response.status()).toBe(200)
  })

  test('login → add film → search → view detail', async ({ page }) => {
    // ── Step 1: Log in ──────────────────────────────────────────────────
    await page.goto('/login')
    await page.fill('input[type="email"]', TEST_EMAIL)
    await page.fill('input[type="password"]', TEST_PASSWORD)
    await page.click('button[type="submit"]')
    // After successful login, app redirects to / (D-13)
    await expect(page).toHaveURL('/', { timeout: 15_000 })

    // ── Step 2: Add film ────────────────────────────────────────────────
    await page.goto('/add')
    // Type in search box (id="movie-search") and submit
    await page.fill('#movie-search', 'Inception')
    await page.click('button[type="submit"]')
    // Wait for poster cards to appear
    await expect(page.locator('[data-testid="poster-card"]').first()).toBeVisible({ timeout: 15_000 })
    // Click the first poster to save it
    await page.locator('[data-testid="poster-card"]').first().click()
    // Wait for save status to appear (pending spinner)
    await expect(page.locator('[data-testid="save-status"]').first()).toBeVisible({ timeout: 10_000 })

    // ── Step 3: Search for the film ─────────────────────────────────────
    // Navigate to /search — enrichment pipeline needs time (async, up to ~10s)
    await page.goto('/search')
    await page.fill('input[placeholder*="Search"]', 'Inception')
    await page.keyboard.press('Enter')
    // Wait up to 30s for enrichment to complete and film to appear in OpenSearch results
    await expect(page.locator('[data-testid="movie-card"]').first()).toBeVisible({ timeout: 30_000 })

    // ── Step 4: Open detail page ────────────────────────────────────────
    await page.locator('[data-testid="movie-card"]').first().click()
    // Detail page h1 should contain the film title
    await expect(page.locator('[data-testid="movie-title"]')).toContainText('Inception', { timeout: 15_000 })
  })
})
