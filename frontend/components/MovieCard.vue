<script setup lang="ts">
import type { SearchResultItem } from '@/composables/useSearch'

defineProps<{
  movie: SearchResultItem
}>()

const router = useRouter()

function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}

function navigateToGenre(genre: string) {
  router.push({ path: '/search', query: { genre } })
}

function navigateToDirector(director: string) {
  router.push({ path: '/search', query: { director } })
}
</script>

<template>
  <div data-testid="movie-card" class="overflow-hidden">
    <NuxtLink :to="`/movies/${movie.id}`" class="block">
      <img
        :src="posterUrl(movie.posterPath)"
        :alt="movie.title"
        class="w-full aspect-[2/3] object-cover bg-card border border-border"
      >
    </NuxtLink>
    <div class="pt-2">
      <p class="text-sm font-medium text-foreground truncate">{{ movie.title }}</p>
      <p class="text-xs text-muted-foreground">{{ movie.year }}</p>
      <div v-if="movie.directorList?.length" class="mt-1 flex flex-wrap gap-1">
        <button
          type="button"
          class="text-xs text-muted-foreground hover:text-primary underline-offset-2 hover:underline"
          @click="navigateToDirector(movie.directorList[0])"
        >
          {{ movie.directorList[0] }}
        </button>
      </div>
      <div v-if="movie.genreList?.length" class="mt-1 flex flex-wrap gap-1">
        <button
          v-for="genre in movie.genreList"
          :key="genre"
          type="button"
          class="text-xs text-muted-foreground hover:text-primary underline-offset-2 hover:underline"
          @click="navigateToGenre(genre)"
        >
          {{ genre }}
        </button>
      </div>
    </div>
  </div>
</template>
