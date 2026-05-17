<script setup lang="ts">
import type { SearchResultItem } from '@/composables/useSearch'
import MovieListItem from '@/components/MovieListItem.vue'

defineProps<{
  movies: SearchResultItem[]
}>()
</script>

<template>
  <div class="flex flex-col divide-y divide-border">
    <MovieListItem
      v-for="item in movies"
      :key="item.id"
      :movie="item"
    />
  </div>
</template>
