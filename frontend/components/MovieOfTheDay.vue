<script setup lang="ts">
interface DashboardMovieItem {
  id: string
  title: string
  year: number
  posterPath: string
}

const props = defineProps<{
  movie: DashboardMovieItem | null
}>()

function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w500${posterPath}`
}
</script>

<template>
  <div v-if="props.movie">
    <p class="text-xs tracking-widest uppercase text-muted-foreground mb-3">Film of the Day</p>
    <img
      :src="posterUrl(props.movie.posterPath)"
      :alt="props.movie.title"
      class="w-full object-cover mb-3"
    />
    <p class="text-xl font-bold text-foreground">{{ props.movie.title }}</p>
    <p class="text-sm text-muted-foreground">{{ props.movie.year }}</p>
  </div>
</template>
