<script setup lang="ts">
import { Search } from 'lucide-vue-next'

const props = defineProps<{ overlay?: boolean }>()

const { logout } = useAuth()
const authStore = useAuthStore()

const loading = ref(false)

async function handleLogout() {
  loading.value = true
  try {
    await logout()
  } finally {
    loading.value = false
  }
}

const textClass = computed(() =>
  props.overlay ? 'text-white/90 hover:text-white' : 'text-foreground hover:text-primary'
)
const mutedClass = computed(() =>
  props.overlay ? 'text-white/60' : 'text-muted-foreground'
)
</script>

<template>
  <nav class="fixed top-0 left-0 right-0 z-50">
    <div class="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
      <NuxtLink to="/" :class="['tracking-widest uppercase font-semibold text-sm', textClass]">
        MovieArchive
      </NuxtLink>
      <div class="flex items-center gap-4">
        <span :class="['text-sm hidden sm:inline', mutedClass]">{{ authStore.userEmail }}</span>
        <NuxtLink to="/add" :class="['text-sm font-medium', textClass]">
          Add Film
        </NuxtLink>
        <NuxtLink to="/search" :class="['text-sm font-medium flex items-center gap-1', textClass]">
          <Search class="w-4 h-4" />
          Search
        </NuxtLink>
        <NuxtLink to="/settings" :class="['text-sm font-medium', textClass]">
          Settings
        </NuxtLink>
        <button
          :disabled="loading"
          :class="['text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed', textClass]"
          @click="handleLogout"
        >
          {{ loading ? 'Signing out...' : 'Sign out' }}
        </button>
      </div>
    </div>
  </nav>
</template>
