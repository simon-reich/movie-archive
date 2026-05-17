<script setup lang="ts">
import type { SearchResultItem } from '@/composables/useSearch'

defineProps<{
  movie: SearchResultItem
}>()

const router = useRouter()

function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w92${posterPath}`
}

function navigateToGenre(genre: string) {
  router.push({ path: '/search', query: { genre } })
}

function navigateToDirector(director: string) {
  router.push({ path: '/search', query: { director } })
}
</script>

<template>
  <div class="flex gap-4 py-3">
    <img
      :src="posterUrl(movie.posterPath)"
      :alt="movie.title"
      class="w-16 aspect-[2/3] object-cover bg-card border border-border flex-shrink-0"
    />
    <div class="flex flex-col min-w-0 gap-1">
      <p class="text-sm font-medium text-foreground truncate">{{ movie.title }}</p>
      <p class="text-xs text-muted-foreground">{{ movie.year }}</p>
      <div v-if="movie.directorList?.length" class="flex flex-wrap gap-1">
        <button
          type="button"
          class="text-xs text-muted-foreground hover:text-primary underline-offset-2 hover:underline"
          @click="navigateToDirector(movie.directorList[0])"
        >
          {{ movie.directorList[0] }}
        </button>
      </div>
      <div v-if="movie.genreList?.length" class="flex flex-wrap gap-1">
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
      <div class="flex items-center gap-3 mt-auto">
        <span v-if="movie.imdbRating !== null" class="text-xs text-muted-foreground">
          ★ {{ movie.imdbRating }}
        </span>
        <span v-if="movie.runtime !== null" class="text-xs text-muted-foreground">
          {{ movie.runtime }} min
        </span>
      </div>
    </div>
  </div>
</template>
