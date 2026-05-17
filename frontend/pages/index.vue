<script setup lang="ts">
import { onMounted } from 'vue'
import DashboardStats from '@/components/DashboardStats.vue'
import MovieOfTheDay from '@/components/MovieOfTheDay.vue'
import ImdbHistogram from '@/components/ImdbHistogram.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'

definePageMeta({ middleware: ['auth'] })

const { data, isLoading, fetchDashboard } = useDashboard()

function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}

onMounted(async () => {
  await fetchDashboard()
})
</script>

<template>
  <Head><Title>Dashboard — MovieArchive</Title></Head>

  <div class="min-h-screen bg-background">
    <main class="max-w-7xl mx-auto px-4 py-8">
      <!-- Loading state -->
      <div v-if="isLoading" class="flex items-center justify-center py-24">
        <SpinnerIcon />
      </div>

      <!-- Dashboard content -->
      <div v-else-if="data">
        <h1 class="text-2xl font-bold tracking-widest uppercase text-foreground mb-8">Your Archive</h1>

        <!-- Stats section -->
        <section class="mb-12">
          <DashboardStats
            :totalFilms="data.totalFilms"
            :topGenres="data.topGenres"
            :languageBreakdown="data.languageBreakdown"
          />
        </section>

        <!-- Movie of the day + histogram -->
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-8 mb-12">
          <div class="lg:col-span-1">
            <section>
              <h2 class="text-xs tracking-widest uppercase text-muted-foreground mb-4">Film of the Day</h2>
              <MovieOfTheDay :movie="data.movieOfTheDay" />
            </section>
          </div>
          <div class="lg:col-span-2">
            <section>
              <h2 class="text-xs tracking-widest uppercase text-muted-foreground mb-4">IMDB Rating Distribution</h2>
              <ImdbHistogram :buckets="data.imdbHistogram" />
            </section>
          </div>
        </div>

        <!-- Recently added -->
        <section>
          <h2 class="text-xs tracking-widest uppercase text-muted-foreground mb-4">Recently Added</h2>
          <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
            <div v-for="film in data.recentlyAdded" :key="film.id" class="flex flex-col gap-1">
              <img
                :src="posterUrl(film.posterPath)"
                :alt="film.title"
                class="w-full object-cover"
              />
              <p class="text-sm font-medium text-foreground truncate">{{ film.title }}</p>
              <p class="text-xs text-muted-foreground">{{ film.year }}</p>
            </div>
          </div>
        </section>
      </div>

      <!-- Empty state (no data and not loading) -->
      <div v-else class="flex flex-col items-center justify-center py-24 gap-4">
        <p class="text-muted-foreground">Your archive is empty.</p>
        <NuxtLink
          to="/add"
          class="px-5 py-2 bg-primary text-primary-foreground text-sm font-semibold hover:opacity-90"
        >
          Add your first film
        </NuxtLink>
      </div>
    </main>
  </div>
</template>
