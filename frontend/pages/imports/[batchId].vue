<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import ViewToggle from '@/components/ViewToggle.vue'
import type { BulkImportBatchDetail, BulkImportLineResult, BulkImportProgress } from '@/composables/useBulkImport'

const BULK_IMPORT_VIEW_MODE_KEY = 'bulk-import-view-mode'

const route = useRoute()
const batchId = route.params.batchId as string

const { subscribeToProgress, getBatchDetail } = useBulkImport()

const progress = ref<BulkImportProgress | null>(null)
const batch = ref<BulkImportBatchDetail | null>(null)
const loadingDetail = ref(false)
const error = ref<string | null>(null)
// D-01: grid is the default view. D-02: locStorage read/write is guarded entirely inside
// onMounted()/a client-only watcher — never at top-level script scope (SSR-safety, see
// deviation_note in 15-01-PLAN.md for why this page uses localStorage instead of the
// useCookie-based fix already shipped in stores/search.ts).
const viewMode = ref<'grid' | 'list'>('grid')

let unsubscribe: (() => void) | null = null

const progressPercent = computed(() => {
  if (!progress.value || progress.value.total === 0) return 0
  return Math.round((progress.value.processed / progress.value.total) * 100)
})

async function loadDetail() {
  loadingDetail.value = true
  error.value = null
  try {
    batch.value = await getBatchDetail(batchId)
  } catch {
    error.value = 'Failed to load import report.'
  } finally {
    loadingDetail.value = false
  }
}

onMounted(() => {
  unsubscribe = subscribeToProgress(batchId, async (p) => {
    progress.value = p
    if (p.complete) {
      unsubscribe?.()
      await loadDetail()
    }
  })

  // D-02: read the persisted view mode only on the client, after mount — never at
  // top-level script scope (localStorage is unavailable during SSR).
  const stored = localStorage.getItem(BULK_IMPORT_VIEW_MODE_KEY)
  if (stored === 'grid' || stored === 'list') {
    viewMode.value = stored
  }

  watch(viewMode, (mode) => {
    localStorage.setItem(BULK_IMPORT_VIEW_MODE_KEY, mode)
  })
})

onUnmounted(() => {
  unsubscribe?.()
})

function posterUrl(posterPath: string | null): string {
  if (!posterPath || !posterPath.startsWith('/')) return '/placeholder-poster.svg'
  return `https://image.tmdb.org/t/p/w300${posterPath}`
}

function statusLabel(status: string): string {
  switch (status) {
    case 'SAVED':
      return 'Saved'
    case 'AMBIGUOUS':
      return 'Ambiguous'
    case 'NOT_FOUND':
      return 'Not found'
    case 'PARSE_ERROR':
      return 'Parse error'
    default:
      return status
  }
}

// D-05/D-07: only a SAVED line with a resolved movieId is ever a whole-card link —
// AMBIGUOUS/NOT_FOUND/PARSE_ERROR lines never link anywhere.
function movieLinkTarget(line: BulkImportLineResult): string | null {
  return line.status === 'SAVED' && line.movieId ? `/movies/${line.movieId}` : null
}
</script>

<template>
  <main class="max-w-4xl mx-auto px-4 py-8">
    <h1 class="text-2xl font-semibold tracking-wide text-foreground mb-6">Import Progress</h1>

    <div v-if="progress === null" class="flex items-center gap-2 text-sm text-muted-foreground">
      <SpinnerIcon class="w-4 h-4" />
      <span>Connecting...</span>
    </div>

    <div v-else-if="!progress.complete" class="space-y-2" data-testid="import-progress">
      <p class="text-sm text-foreground">{{ progress.processed }} / {{ progress.total }} processed</p>
      <div class="w-full h-2 bg-card border border-border">
        <div class="h-full bg-primary" :style="{ width: `${progressPercent}%` }" />
      </div>
    </div>

    <FormErrorBanner v-if="error" :message="error" />

    <div v-if="loadingDetail" class="flex items-center justify-center py-12">
      <SpinnerIcon />
    </div>

    <div v-if="batch" class="mt-8">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-xl font-semibold tracking-wide">Results</h2>
        <ViewToggle v-model="viewMode" />
      </div>

      <div v-if="viewMode === 'grid'" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
        <component
          :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'"
          v-for="line in batch.lines"
          :key="line.id"
          :to="movieLinkTarget(line) ?? undefined"
          data-testid="result-card"
          class="relative overflow-hidden block"
          :class="line.status === 'PARSE_ERROR' ? 'border-l-2 border-[#7A3520]' : ''"
        >
          <template v-if="line.status === 'PARSE_ERROR'">
            <div
              data-testid="parse-error-card"
              class="w-full aspect-[2/3] bg-card border border-[#7A3520] flex items-center justify-center p-2"
            >
              <p class="text-xs text-[#7A3520] text-center">{{ line.title || 'Unparseable line' }}</p>
            </div>
            <div class="absolute bottom-0 right-0 p-2 bg-background/70 flex items-center justify-center">
              <XCircle class="w-6 h-6 text-[#7A3520]" />
            </div>
            <p class="pt-2 text-sm font-medium text-[#7A3520] truncate">{{ statusLabel(line.status) }}</p>
            <p data-testid="raw-line-text" class="text-xs text-muted-foreground break-all">{{ line.rawLine }}</p>
          </template>
          <template v-else>
            <img
              v-if="line.posterPath"
              :src="posterUrl(line.posterPath)"
              :alt="line.title"
              class="w-full aspect-[2/3] object-cover bg-card border border-border"
            >
            <div
              v-else
              data-testid="poster-fallback"
              class="w-full aspect-[2/3] bg-card border border-border flex items-center justify-center p-2"
            >
              <p class="text-xs text-muted-foreground text-center">{{ line.title }}</p>
            </div>

            <div class="absolute bottom-0 right-0 p-2 bg-background/70 flex items-center justify-center">
              <CheckCircle2 v-if="line.status === 'SAVED'" class="w-6 h-6 text-foreground" />
              <XCircle v-else class="w-6 h-6 text-foreground" />
            </div>

            <p class="pt-2 text-sm font-medium text-foreground truncate">{{ line.title }}</p>
            <p class="text-xs text-muted-foreground">{{ statusLabel(line.status) }}</p>
          </template>
        </component>
      </div>

      <div v-else class="divide-y divide-border">
        <component
          :is="movieLinkTarget(line) ? 'NuxtLink' : 'div'"
          v-for="line in batch.lines"
          :key="line.id"
          :to="movieLinkTarget(line) ?? undefined"
          data-testid="view-list-row"
          class="flex gap-4 py-3"
          :class="line.status === 'PARSE_ERROR' ? 'border-l-2 border-[#7A3520] pl-3' : ''"
        >
          <template v-if="line.status === 'PARSE_ERROR'">
            <div
              data-testid="parse-error-card"
              class="w-16 aspect-[2/3] flex-shrink-0 bg-card border border-[#7A3520] flex items-center justify-center p-1"
            >
              <XCircle class="w-4 h-4 text-[#7A3520]" />
            </div>
            <div class="flex flex-col min-w-0 gap-1">
              <p class="text-sm font-medium text-[#7A3520] truncate">{{ statusLabel(line.status) }}</p>
              <p data-testid="raw-line-text" class="text-xs text-muted-foreground break-all">{{ line.rawLine }}</p>
            </div>
          </template>
          <template v-else>
            <img
              v-if="line.posterPath"
              :src="posterUrl(line.posterPath)"
              :alt="line.title"
              class="w-16 aspect-[2/3] object-cover bg-card border border-border flex-shrink-0"
            >
            <div
              v-else
              data-testid="poster-fallback"
              class="w-16 aspect-[2/3] bg-card border border-border flex items-center justify-center p-1 flex-shrink-0"
            >
              <p class="text-[10px] text-muted-foreground text-center leading-tight">{{ line.title }}</p>
            </div>
            <div class="flex flex-col min-w-0 justify-center gap-1">
              <p class="text-sm font-medium text-foreground truncate">{{ line.title }}</p>
              <div class="flex items-center gap-1.5">
                <CheckCircle2 v-if="line.status === 'SAVED'" class="w-4 h-4 text-foreground" />
                <XCircle v-else class="w-4 h-4 text-foreground" />
                <span class="text-xs text-muted-foreground">{{ statusLabel(line.status) }}</span>
              </div>
            </div>
          </template>
        </component>
      </div>
    </div>
  </main>
</template>
