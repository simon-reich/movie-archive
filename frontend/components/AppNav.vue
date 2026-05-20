<script setup lang="ts">
import { Search, Settings, LogOut, Menu, X, PlusCircle } from 'lucide-vue-next'

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

const drawerOpen = ref(false)
</script>

<template>
  <nav class="fixed top-0 left-0 right-0 z-50">
    <div class="max-w-7xl mx-auto px-4 py-3 flex items-center justify-between">
      <NuxtLink to="/" :class="['tracking-widest uppercase font-semibold text-sm', textClass]">
        MovieArchive
      </NuxtLink>
      <div class="hidden md:flex items-center gap-4">
        <NuxtLink to="/add" :class="['text-sm font-medium flex items-center gap-1', textClass]">
          <PlusCircle class="w-4 h-4" />
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
      <!-- Mobile hamburger: visible only below md -->
      <button
        class="md:hidden"
        :class="textClass"
        aria-label="Open navigation"
        @click="drawerOpen = true"
      >
        <Menu class="w-5 h-5" />
      </button>
    </div>
  </nav>

  <!-- Backdrop: blocks interaction with page behind the drawer -->
  <Transition name="fade">
    <div
      v-if="drawerOpen"
      class="fixed inset-0 z-[59] md:hidden"
      @click="drawerOpen = false"
    />
  </Transition>

  <!-- Slide-in drawer: only on mobile, solid off-white panel, no rounded corners -->
  <Transition name="slide">
    <div
      v-if="drawerOpen"
      class="fixed top-0 right-0 h-full w-64 bg-background border-l border-border z-[60] flex flex-col pt-16 px-6 gap-6 md:hidden"
    >
      <!-- Close button -->
      <button
        class="absolute top-4 right-4 text-foreground"
        aria-label="Close navigation"
        @click="drawerOpen = false"
      >
        <X class="w-5 h-5" />
      </button>

      <!-- Nav links: uppercase tracking-widest, terracotta on active route -->
      <NuxtLink
        to="/add"
        class="text-sm font-medium tracking-widest uppercase text-foreground hover:text-primary flex items-center gap-2"
        active-class="text-primary"
        @click="drawerOpen = false"
      >
        <PlusCircle class="w-4 h-4" />
        Add Film
      </NuxtLink>

      <NuxtLink
        to="/search"
        class="text-sm font-medium tracking-widest uppercase text-foreground hover:text-primary flex items-center gap-2"
        active-class="text-primary"
        @click="drawerOpen = false"
      >
        <Search class="w-4 h-4" />
        Search
      </NuxtLink>

      <NuxtLink
        to="/settings"
        class="text-sm font-medium tracking-widest uppercase text-foreground hover:text-primary flex items-center gap-2"
        active-class="text-primary"
        @click="drawerOpen = false"
      >
        <Settings class="w-4 h-4" />
        Settings
      </NuxtLink>

      <button
        :disabled="loading"
        class="text-sm font-medium tracking-widest uppercase text-foreground hover:text-primary flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed text-left"
        @click="handleLogout(); drawerOpen = false"
      >
        <LogOut class="w-4 h-4" />
        Sign out
      </button>
    </div>
  </Transition>
</template>

<style scoped>
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.3s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
