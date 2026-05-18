import { defineStore } from 'pinia'
import { computed } from 'vue'

export const useSearchStore = defineStore('search', () => {
  // useCookie is SSR-safe — unlike localStorage which is unavailable on the server.
  // On SSR the cookie value comes from the request headers; on client it's document.cookie.
  // This fixes the hydration mismatch that caused viewMode to reset on page reload.
  const viewModeCookie = useCookie<'grid' | 'list'>('viewMode', {
    default: () => 'grid',
    maxAge: 60 * 60 * 24 * 365,
  })

  const viewMode = computed(() => viewModeCookie.value)

  function setViewMode(mode: 'grid' | 'list'): void {
    viewModeCookie.value = mode
  }

  return { viewMode, setViewMode }
})
