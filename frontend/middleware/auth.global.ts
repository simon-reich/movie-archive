export default defineNuxtRouteMiddleware((to) => {
  const publicRoutes = [
    '/login',
    '/signup',
    '/verify-email',
    '/verify-email-sent',
    '/forgot-password',
    '/reset-password',
  ]

  // Redirect authenticated users away from auth pages (D-03)
  if (publicRoutes.includes(to.path)) {
    if (import.meta.client) {
      const nuxtApp = useNuxtApp()
      if (!nuxtApp.isHydrating) {
        const authStore = useAuthStore()
        if (authStore.isAuthenticated) {
          return navigateTo('/')
        }
      }
    }
    return
  }

  if (import.meta.server) {
    // Server-side: cookie is readable from the incoming request headers (D-02)
    const refreshCookie = useCookie('refresh_token')
    if (!refreshCookie.value) {
      return navigateTo('/login')
    }
  } else {
    // Client-side: auth.client plugin runs before navigation and populates the store.
    // If store is still empty after plugin ran, the cookie is absent/expired → redirect.
    // If nuxtApp.isHydrating, the plugin hasn't run yet — skip redirect and let plugin handle it.
    const nuxtApp = useNuxtApp()
    if (nuxtApp.isHydrating) return

    const authStore = useAuthStore()
    if (!authStore.isAuthenticated) {
      return navigateTo('/login')
    }
  }
})
