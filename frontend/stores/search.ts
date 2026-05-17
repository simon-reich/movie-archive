import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSearchStore = defineStore('search', () => {
  // Read viewMode from localStorage on client side only — SSR safe
  const stored = import.meta.client ? localStorage.getItem('viewMode') : null
  const viewMode = ref<'grid' | 'list'>((stored as 'grid' | 'list') ?? 'grid')

  function setViewMode(mode: 'grid' | 'list'): void {
    viewMode.value = mode
    if (import.meta.client) {
      localStorage.setItem('viewMode', mode)
    }
  }

  return { viewMode, setViewMode }
})
