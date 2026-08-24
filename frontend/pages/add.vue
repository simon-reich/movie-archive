<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import InputText from '@/components/InputText.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import type { SearchResultItem } from '@/composables/useMovies'

const { searchTmdb, saveMovie, getStatus, getSavedTmdbIds, uploadBulkImport } = useMovies()

const query = ref('')
const searching = ref(false)
const searchError = ref<string | null>(null)
const results = ref<SearchResultItem[]>([])

const selectedFile = ref<File | null>(null)
const bulkImporting = ref(false)
const bulkImportError = ref<string | null>(null)
const bulkImportMessage = ref<string | null>(null)
const lastBulkImportBatchId = ref<string | null>(null)

const pollingIntervals = new Map<string, ReturnType<typeof setInterval>>()
const savedTmdbIds = ref<Set<number>>(new Set())

onMounted(async () => {
  try {
    const ids = await getSavedTmdbIds()
    savedTmdbIds.value = new Set(ids)
  } catch {
    // Non-critical: if this fails, duplicate guard degrades to same-session only
  }
})

async function handleSearch() {
  if (!query.value.trim()) return
  searching.value = true
  searchError.value = null
  results.value = []
  try {
    const items = await searchTmdb(query.value.trim())
    results.value = items.map(item => ({
      ...item,
      state: savedTmdbIds.value.has(item.tmdbId) ? ('saved' as const) : ('idle' as const),
    }))
  } catch (e: unknown) {
    const err = e as { status?: number }
    if (err?.status === 422) {
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
      if (response.status === 'SUCCESS' && response.indexedAt !== null) {
        item.state = 'success'
        savedTmdbIds.value.add(item.tmdbId)
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
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}

function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  bulkImportError.value = null
  bulkImportMessage.value = null
}

async function handleBulkImport() {
  if (!selectedFile.value) return
  bulkImporting.value = true
  bulkImportError.value = null
  bulkImportMessage.value = null
  lastBulkImportBatchId.value = null
  try {
    const response = await uploadBulkImport(selectedFile.value)
    bulkImportMessage.value = 'Import started — this runs in the background.'
    lastBulkImportBatchId.value = response.batchId
    selectedFile.value = null
  } catch (e: unknown) {
    const err = e as { status?: number; data?: { message?: string } }
    if (err?.status === 422) {
      bulkImportError.value = 'No TMDB key configured. Add your key in Settings.'
    } else if (err?.status === 400 && err.data?.message) {
      bulkImportError.value = err.data.message
    } else {
      bulkImportError.value = 'Import failed. Please try again.'
    }
  } finally {
    bulkImporting.value = false
  }
}
</script>

<template>
  <main class="max-w-4xl mx-auto px-4 py-8">
    <h1 class="text-2xl font-semibold tracking-wide text-foreground mb-6">Add Film</h1>

    <form class="flex gap-3 mb-8" @submit.prevent="handleSearch">
      <InputText
        id="movie-search"
        v-model="query"
        placeholder="Search for a film..."
        class="flex-1 min-w-0"
      />
      <button
        type="submit"
        :disabled="searching"
        class="shrink-0 px-5 h-10 bg-primary text-primary-foreground text-sm font-semibold rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        {{ searching ? 'Searching...' : 'Search' }}
      </button>
    </form>

    <FormErrorBanner v-if="searchError" :message="searchError" />

    <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
      <div
        v-for="item in results"
        :key="item.tmdbId"
        data-testid="poster-card"
        class="relative cursor-pointer group overflow-hidden"
        @click="handlePosterClick(item)"
      >
        <img
          :src="posterUrl(item.posterPath)"
          :alt="item.title ?? ''"
          class="w-full aspect-[2/3] object-cover bg-card border border-border transition-transform duration-200 group-hover:scale-105"
        >

        <div
          v-if="item.state === 'pending'"
          data-testid="save-status"
          class="absolute inset-0 bg-background/70 flex items-center justify-center"
        >
          <SpinnerIcon class="w-8 h-8 text-foreground" />
        </div>

        <div
          v-if="item.state === 'success'"
          class="absolute inset-0 bg-background/70 flex items-center justify-center"
        >
          <CheckCircle2 class="w-10 h-10 text-foreground" />
        </div>

        <div
          v-if="item.state === 'error'"
          class="absolute inset-0 bg-background/70 flex items-center justify-center flex-col gap-2 p-2"
        >
          <XCircle class="w-10 h-10 text-foreground" />
          <p class="text-xs text-foreground/70 text-center">{{ item.errorMessage }}</p>
        </div>

        <div
          v-if="item.state === 'saved'"
          class="absolute bottom-0 right-0 p-2 bg-background/70 flex items-center justify-center"
        >
          <CheckCircle2 class="w-6 h-6 text-foreground" />
        </div>

        <div class="pt-2">
          <p class="text-sm font-medium text-foreground truncate">{{ item.title }}</p>
          <p class="text-xs text-muted-foreground">{{ item.year ?? 'Unknown year' }}</p>
        </div>
      </div>
    </div>

    <hr class="border-border my-8">

    <section id="bulk-import">
      <h1 class="text-xl font-semibold tracking-wide mb-6">Bulk Import</h1>
      <p class="text-sm text-muted-foreground mb-3">One film per line: Title;OriginalTitle;Year — leave Original Title empty if unknown, e.g. "Inception;;2010".</p>
      <form class="flex items-center gap-3" @submit.prevent="handleBulkImport">
        <input
          id="bulk-import-file"
          type="file"
          accept=".txt,.csv"
          class="text-sm text-foreground file:mr-3 file:h-10 file:px-4 file:border-0 file:bg-primary file:text-primary-foreground file:text-sm file:font-semibold file:rounded-none file:cursor-pointer file:hover:opacity-90"
          @change="handleFileSelect"
        >
        <button
          type="submit"
          :disabled="!selectedFile || bulkImporting"
          class="shrink-0 px-5 h-10 bg-primary text-primary-foreground text-sm font-semibold rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {{ bulkImporting ? 'Uploading...' : 'Import' }}
        </button>
      </form>
      <FormErrorBanner v-if="bulkImportError" :message="bulkImportError" />
      <p v-if="bulkImportMessage" class="text-sm text-foreground mt-2">{{ bulkImportMessage }}</p>
      <NuxtLink
        v-if="lastBulkImportBatchId"
        :to="`/imports/${lastBulkImportBatchId}`"
        class="text-sm text-primary hover:underline mt-1 inline-block"
      >Track progress →</NuxtLink>
    </section>
  </main>
</template>
