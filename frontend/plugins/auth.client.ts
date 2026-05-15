import { useAuthStore } from '@/stores/auth'

const publicRoutes = [
  '/login',
  '/signup',
  '/verify-email',
  '/verify-email-sent',
  '/forgot-password',
  '/reset-password',
]

export default defineNuxtPlugin(async (nuxtApp) => {
  const authStore = useAuthStore()
  // Silent refresh on init (D-05): populate store from HttpOnly cookie.
  try {
    await authStore.refresh()
  } catch {
    // Refresh failed — if on a protected route, redirect to login now
    // to avoid flashing protected content with an expired session.
    const route = nuxtApp.$router.currentRoute.value
    if (!publicRoutes.includes(route.path)) {
      await navigateTo('/login')
    }
  }
})
