<script setup lang="ts">
defineProps<{
  heading: string
  subtext?: string
}>()
</script>

<template>
  <div class="min-h-screen bg-background flex items-center justify-center px-4">
    <div class="w-full max-w-sm">
      <!-- App name above card (per UI-SPEC Form Anatomy Contract) -->
      <p class="tracking-widest uppercase font-semibold text-2xl text-foreground text-center mb-6">
        MovieArchive
      </p>
      <!-- Auth card -->
      <div class="bg-card border border-border rounded-none p-6">
        <div class="mb-6">
          <h1 class="text-xl font-semibold tracking-wide text-card-foreground">{{ heading }}</h1>
          <p v-if="subtext" class="text-sm text-muted-foreground mt-1">{{ subtext }}</p>
        </div>
        <slot />
      </div>
    </div>
  </div>
</template>
