export default defineNuxtRouteMiddleware((to) => {
  const publicRoutes = [
    '/login',
    '/signup',
    '/verify-email',
    '/verify-email-sent',
    '/forgot-password',
    '/reset-password',
  ]

  // Allow all public auth routes without cookie check (D-03)
  if (publicRoutes.includes(to.path)) return

  // Cookie presence check only — no backend call server-side (D-02)
  const refreshCookie = useCookie('refresh_token')
  if (!refreshCookie.value) {
    return navigateTo('/login')
  }
})
