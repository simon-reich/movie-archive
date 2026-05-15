<script setup lang="ts">
defineProps<{
  id: string
  label: string
  error?: string
}>()
</script>

<template>
  <div class="space-y-1">
    <label
      :for="id"
      class="block text-sm font-semibold tracking-widest uppercase text-foreground"
    >
      {{ label }}
    </label>
    <slot />
    <p
      v-if="error"
      :id="`${id}-error`"
      class="text-sm text-[#7A3520] mt-1"
      role="alert"
    >
      {{ error }}
    </p>
  </div>
</template>
