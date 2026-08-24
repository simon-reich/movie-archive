<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import type { BulkImportBatchDetail, BulkImportProgress } from '@/composables/useBulkImport'

const route = useRoute()
const batchId = route.params.batchId as string

const { subscribeToProgress, getBatchDetail } = useBulkImport()

const progress = ref<BulkImportProgress | null>(null)
const batch = ref<BulkImportBatchDetail | null>(null)
const loadingDetail = ref(false)
const error = ref<string | null>(null)

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
      <h2 class="text-xl font-semibold tracking-wide mb-4">Results</h2>
      <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
        <div
          v-for="line in batch.lines"
          :key="`${line.title}-${line.year}`"
          data-testid="result-card"
          class="relative overflow-hidden"
        >
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
        </div>
      </div>
    </div>
  </main>
</template>
