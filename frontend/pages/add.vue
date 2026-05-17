<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import type { SearchResultItem } from '@/composables/useMovies'

definePageMeta({ middleware: 'auth' })

const { searchTmdb, saveMovie, getStatus } = useMovies()

const query = ref('')
const searching = ref(false)
const searchError = ref<string | null>(null)
const results = ref<SearchResultItem[]>([])

const pollingIntervals = new Map<string, ReturnType<typeof setInterval>>()

async function handleSearch() {
  if (!query.value.trim()) return
  searching.value = true
  searchError.value = null
  results.value = []
  try {
    const items = await searchTmdb(query.value.trim())
    results.value = items.map(item => ({ ...item, state: 'idle' as const }))
  } catch (e: any) {
    if (e?.status === 422) {
      searchError.value = 'No TMDB key configured. Add your key in Settings.'
    } else {
      searchError.value = 'Search failed. Please try again.'
    }
  } finally {
    searching.value = false
  }
}

async function handlePosterClick(item: SearchResultItem) {
  if (item.state !== 'idle') return
  item.state = 'pending'
  try {
    const { id } = await saveMovie(item.tmdbId)
    item.movieId = id
    startPolling(item, id)
  } catch {
    item.state = 'error'
    item.errorMessage = 'Could not save — check your TMDB key.'
  }
}

function startPolling(item: SearchResultItem, movieId: string) {
  const interval = setInterval(async () => {
    try {
      const response = await getStatus(movieId)
      if (response.status === 'SUCCESS') {
        item.state = 'success'
        clearInterval(interval)
        pollingIntervals.delete(movieId)
        setTimeout(() => {
          results.value = results.value.filter(r => r.tmdbId !== item.tmdbId)
        }, 1500)
      } else if (response.status === 'ERROR') {
        item.state = 'error'
        item.errorMessage = 'Could not save — check your TMDB key.'
        clearInterval(interval)
        pollingIntervals.delete(movieId)
      }
      // PENDING: continue polling
    } catch {
      item.state = 'error'
      item.errorMessage = 'Could not save — check your TMDB key.'
      clearInterval(interval)
      pollingIntervals.delete(movieId)
    }
  }, 2500)
  pollingIntervals.set(movieId, interval)
}

onUnmounted(() => {
  pollingIntervals.forEach(interval => clearInterval(interval))
  pollingIntervals.clear()
})

function posterUrl(posterPath: string | null): string {
  if (!posterPath) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}
</script>

<template>
  <main class="max-w-4xl mx-auto px-4 py-8">
    <h1 class="text-2xl font-semibold tracking-wide text-foreground mb-6">Add Film</h1>

    <form class="flex gap-3 mb-8" @submit.prevent="handleSearch">
      <InputText
        v-model="query"
        placeholder="Search for a film..."
        class="flex-1"
      />
      <ButtonPrimary type="submit" :disabled="searching">
        {{ searching ? 'Searching...' : 'Search' }}
      </ButtonPrimary>
    </form>

    <FormErrorBanner v-if="searchError" :message="searchError" />

    <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
      <div
        v-for="item in results"
        :key="item.tmdbId"
        class="relative cursor-pointer group"
        @click="handlePosterClick(item)"
      >
        <img
          :src="posterUrl(item.posterPath)"
          :alt="item.title ?? ''"
          class="w-full aspect-[2/3] object-cover bg-card border border-border"
        />

        <div
          v-if="item.state === 'pending'"
          class="absolute inset-0 bg-background/70 flex items-center justify-center"
        >
          <SpinnerIcon class="w-8 h-8 text-primary" />
        </div>

        <div
          v-if="item.state === 'success'"
          class="absolute inset-0 bg-background/70 flex items-center justify-center"
        >
          <CheckCircle2 class="w-10 h-10 text-primary" />
        </div>

        <div
          v-if="item.state === 'error'"
          class="absolute inset-0 bg-background/70 flex items-center justify-center flex-col gap-2 p-2"
        >
          <XCircle class="w-10 h-10 text-[#7A3520]" />
          <p class="text-xs text-[#7A3520] text-center">{{ item.errorMessage }}</p>
        </div>

        <div class="pt-2">
          <p class="text-sm font-medium text-foreground truncate">{{ item.title }}</p>
          <p class="text-xs text-muted-foreground">{{ item.year ?? 'Unknown year' }}</p>
        </div>
      </div>
    </div>
  </main>
</template>
