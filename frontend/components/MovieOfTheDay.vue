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
    <NuxtLink :to="`/movies/${props.movie.id}`" class="block overflow-hidden mb-3 shadow-md hover:shadow-2xl transition-shadow duration-300">
      <img
        :src="posterUrl(props.movie.posterPath)"
        :alt="props.movie.title"
        class="w-full object-cover hover:scale-105 transition-transform duration-300"
      />
    </NuxtLink>
    <NuxtLink :to="`/movies/${props.movie.id}`" class="text-xl font-bold text-foreground hover:text-primary">{{ props.movie.title }}</NuxtLink>
    <p class="text-sm text-muted-foreground">{{ props.movie.year }}</p>
  </div>
</template>
