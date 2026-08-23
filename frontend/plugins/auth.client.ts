// Auth state is driven by the readable 'access_token' cookie (set by the backend on
// login/refresh). useCookie('access_token') in stores/auth.ts works on both SSR and
// client without a round-trip, so no async plugin is needed to restore auth state on
// page load.
//
// This plugin's job is silent token rotation: the access token is intentionally
// short-lived (15 min, see CLAUDE.md JWT section) while the refresh token lasts 7 days.
// Nothing previously called store.refresh() proactively, so the access_token cookie
// simply expired after 15 min with no renewal — the user was silently logged out
// mid-session. Polling refresh() well inside the access-token lifetime keeps the
// session alive for as long as the refresh token is valid (up to 7 days), without
// widening the access token's own exposure window.

// 10 min — safely under the 15 min access-token lifetime, so a refresh always lands
// before the current access token expires even with normal timer jitter.
export const SILENT_REFRESH_INTERVAL_MS = 10 * 60 * 1000

export interface RefreshableAuthStore {
  isAuthenticated: boolean
  refresh: () => Promise<void>
}

/**
 * Attempts a silent token refresh. No-op if not currently authenticated (nothing to
 * renew). Swallows refresh failures (expired/revoked refresh token, or transient
 * network issues) — the next route navigation's auth middleware will redirect to
 * /login on its own once the access_token cookie is actually gone; this function has
 * nothing further to do on failure.
 */
export async function attemptSilentRefresh(authStore: RefreshableAuthStore): Promise<void> {
  if (!authStore.isAuthenticated) return
  try {
    await authStore.refresh()
  } catch {
    // Intentionally swallowed — see function doc above.
  }
}

export default defineNuxtPlugin(() => {
  const authStore = useAuthStore()
  const tick = () => attemptSilentRefresh(authStore)

  setInterval(tick, SILENT_REFRESH_INTERVAL_MS)

  // Also refresh immediately when the tab regains visibility, in case the interval was
  // suspended while backgrounded (laptop sleep, mobile Safari tab freeze, etc.) for
  // longer than the access-token lifetime.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') tick()
  })
})
