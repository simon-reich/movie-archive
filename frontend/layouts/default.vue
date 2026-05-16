<script setup lang="ts">
const authStore = useAuthStore()
const sessionEmail = useCookie('session_email')

// Seed the store from the non-httpOnly session_email cookie so that:
// 1. SSR renders AppNav correctly (auth.client.ts doesn't run server-side)
// 2. No hydration mismatch between SSR output and client mount
// The client plugin overwrites these with fresh values from /api/auth/refresh.
if (sessionEmail.value && !authStore.userEmail) {
  authStore.userEmail = sessionEmail.value
}

// isLoggedIn: truthy if either the store (client, post-plugin) or the
// session cookie (SSR, pre-plugin) signals an active session.
const isLoggedIn = computed(() => !!(authStore.isAuthenticated || sessionEmail.value))
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <AppNav v-if="isLoggedIn" />
    <slot />
  </div>
</template>
