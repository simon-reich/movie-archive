<script setup lang="ts">
defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <select
    :value="modelValue"
    class="h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary rounded-none"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
  >
    <option value="title_asc">Title A–Z</option>
    <option value="year_desc">Year (newest)</option>
    <option value="rating_desc">Personal rating</option>
    <option value="imdb_desc">IMDB rating</option>
  </select>
</template>
