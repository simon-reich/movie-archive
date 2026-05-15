import { useAuthStore } from '@/stores/auth'

export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  // Silent refresh on init (D-05): populate store from HttpOnly cookie.
  // On failure (expired/absent cookie), store stays empty — middleware handles redirect.
  try {
    await authStore.refresh()
  } catch {
    // Expected on first visit or after session expiry — do not rethrow
  }
})
