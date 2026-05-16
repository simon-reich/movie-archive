<script setup lang="ts">
// The middleware already enforces auth — if you can reach a protected route,
// you are logged in. Gate AppNav on the route, not the in-memory store
// (store is empty during SSR and hydration, causing persistent nav flicker).
const publicRoutes = [
  '/login', '/signup', '/verify-email', '/verify-email-sent',
  '/forgot-password', '/reset-password',
]
const route = useRoute()
const showNav = computed(() => !publicRoutes.includes(route.path))
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <AppNav v-if="showNav" />
    <slot />
  </div>
</template>
