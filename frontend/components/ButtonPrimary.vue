<script setup lang="ts">
import SpinnerIcon from './SpinnerIcon.vue'

defineProps<{
  loading?: boolean
  disabled?: boolean
  type?: 'submit' | 'button'
}>()
</script>

<template>
  <button
    :type="type ?? 'submit'"
    :disabled="disabled || loading"
    class="w-full h-11 bg-primary text-primary-foreground text-sm font-semibold rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
  >
    <SpinnerIcon v-if="loading" class="w-4 h-4" />
    <slot />
  </button>
</template>
