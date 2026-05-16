export default defineNuxtRouteMiddleware((to) => {
  const publicRoutes = [
    '/login',
    '/signup',
    '/verify-email',
    '/verify-email-sent',
    '/forgot-password',
    '/reset-password',
  ]

  const authStore = useAuthStore()

  // Redirect authenticated users away from auth pages (works on both SSR and client
  // because isAuthenticated is now driven by the readable access_token cookie).
  if (publicRoutes.includes(to.path)) {
    if (authStore.isAuthenticated) {
      return navigateTo('/')
    }
    return
  }

  // Protected route: require a valid access_token cookie.
  // On SSR, useCookie reads from the incoming request — no async round-trip needed.
  // On client, useCookie reads from document.cookie.
  if (!authStore.isAuthenticated) {
    return navigateTo('/login')
  }
})
