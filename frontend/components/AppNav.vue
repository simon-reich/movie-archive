<script setup lang="ts">
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
</script>

<template>
  <nav class="w-full border-b border-border bg-card px-4 py-3 flex items-center justify-between">
    <NuxtLink to="/" class="tracking-widest uppercase font-semibold text-sm text-foreground">
      MovieArchive
    </NuxtLink>
    <div class="flex items-center gap-4">
      <span class="text-sm text-muted-foreground hidden sm:inline">{{ authStore.userEmail }}</span>
      <NuxtLink
        to="/settings"
        class="text-sm font-medium text-foreground hover:text-primary"
      >
        Settings
      </NuxtLink>
      <button
        :disabled="loading"
        class="text-sm font-medium text-foreground hover:text-primary disabled:opacity-50 disabled:cursor-not-allowed"
        @click="handleLogout"
      >
        {{ loading ? 'Signing out...' : 'Sign out' }}
      </button>
    </div>
  </nav>
</template>
