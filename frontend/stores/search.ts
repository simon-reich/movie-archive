import { defineStore } from 'pinia'
import { ref } from 'vue'

function safeLocalStorageGet(key: string): string | null {
  try {
    return typeof localStorage !== 'undefined' ? localStorage.getItem(key) : null
  } catch {
    return null
  }
}

function safeLocalStorageSet(key: string, value: string): void {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(key, value)
    }
  } catch {
    // ignore — localStorage not available (SSR or private browsing)
  }
}

export const useSearchStore = defineStore('search', () => {
  // Read viewMode from localStorage on client side only — SSR safe
  const stored = import.meta.client ? safeLocalStorageGet('viewMode') : null
  const viewMode = ref<'grid' | 'list'>((stored as 'grid' | 'list') ?? 'grid')

  function setViewMode(mode: 'grid' | 'list'): void {
    viewMode.value = mode
    if (import.meta.client) {
      safeLocalStorageSet('viewMode', mode)
    }
  }

  return { viewMode, setViewMode }
})
