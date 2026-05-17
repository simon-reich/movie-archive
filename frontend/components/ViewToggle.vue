<script setup lang="ts">
import { Grid, List } from 'lucide-vue-next'

defineProps<{
  modelValue: 'grid' | 'list'
}>()

const emit = defineEmits<{
  'update:modelValue': [value: 'grid' | 'list']
}>()
</script>

<template>
  <div class="flex border border-border">
    <button
      type="button"
      :class="[
        'px-3 py-2 flex items-center justify-center transition-colors',
        modelValue === 'grid'
          ? 'bg-primary text-primary-foreground'
          : 'bg-card text-muted-foreground hover:text-foreground',
      ]"
      aria-label="Grid view"
      @click="emit('update:modelValue', 'grid')"
    >
      <Grid class="w-4 h-4" />
    </button>
    <button
      type="button"
      :class="[
        'px-3 py-2 flex items-center justify-center transition-colors border-l border-border',
        modelValue === 'list'
          ? 'bg-primary text-primary-foreground'
          : 'bg-card text-muted-foreground hover:text-foreground',
      ]"
      aria-label="List view"
      @click="emit('update:modelValue', 'list')"
    >
      <List class="w-4 h-4" />
    </button>
  </div>
</template>
