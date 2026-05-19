<script setup lang="ts">
import { Search, Settings, LogOut } from 'lucide-vue-next'

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
const avatarBg = 'bg-black text-white hover:bg-neutral-800'
const userInitial = computed(() => authStore.userEmail?.[0]?.toUpperCase() ?? '?')
</script>

<template>
  <nav class="fixed top-0 left-0 right-0 z-50">
    <div class="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
      <NuxtLink to="/" :class="['tracking-widest uppercase font-semibold text-sm', textClass]">
        MovieArchive
      </NuxtLink>
      <div class="flex items-center gap-4">
        <NuxtLink to="/add" :class="['text-sm font-medium', textClass]">
          Add Film
        </NuxtLink>
        <NuxtLink to="/search" :class="['text-sm font-medium flex items-center gap-1', textClass]">
          <Search class="w-4 h-4" />
          Search
        </NuxtLink>
        <NuxtLink to="/settings" :class="textClass" title="Settings">
          <Settings class="w-4 h-4" />
        </NuxtLink>
        <button
          :disabled="loading"
          :class="['disabled:opacity-50 disabled:cursor-not-allowed', textClass]"
          :title="loading ? 'Signing out…' : 'Sign out'"
          @click="handleLogout"
        >
          <LogOut class="w-4 h-4" />
        </button>
        <button
          :class="['w-4 h-4 rounded-full flex items-center justify-center text-[9px] font-semibold transition-colors', avatarBg]"
          :title="authStore.userEmail ?? ''"
        >
          {{ userInitial }}
        </button>
      </div>
    </div>
  </nav>
</template>
