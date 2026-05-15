import { describe, test } from 'vitest'

describe('/login page', () => {
  test.skip('renders email field, password field, and Sign in button', () => {})
  test.skip('shows inline error "Invalid email or password." on 401 response', () => {})
  test.skip('shows "Please verify your email before signing in." on 403 response', () => {})
  test.skip('shows "Too many attempts. Try again in X seconds." on 429 with Retry-After header', () => {})
  test.skip('redirects to / after successful login', () => {})
  test.skip('populates auth store after successful login', () => {})
  test.skip('shows spinner and disables button while request is in-flight', () => {})
})
