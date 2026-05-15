<script setup lang="ts">
const authStore = useAuthStore()
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <AppNav v-if="authStore.isAuthenticated" />
    <slot />
  </div>
</template>
