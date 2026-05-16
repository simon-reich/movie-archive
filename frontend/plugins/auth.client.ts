// Auth state is now driven by the readable 'access_token' cookie (set by the backend on login/refresh).
// useCookie('access_token') in stores/auth.ts works on both SSR and client without a round-trip,
// so no async plugin is needed to restore auth state on page load.
//
// This file is intentionally left as a no-op. It can be removed entirely once confirmed stable.
export default defineNuxtPlugin(() => {})
