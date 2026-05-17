<script setup lang="ts">
import type { SearchResultItem } from '@/composables/useSearch'
import MovieCard from '@/components/MovieCard.vue'

defineProps<{
  movies: SearchResultItem[]
}>()
</script>

<template>
  <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
    <MovieCard
      v-for="item in movies"
      :key="item.id"
      :movie="item"
    />
  </div>
</template>
