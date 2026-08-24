<script setup lang="ts">
import { ref, onMounted } from 'vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import type { BulkImportBatchSummary } from '@/composables/useBulkImport'

const { getBatches } = useBulkImport()

const batches = ref<BulkImportBatchSummary[]>([])
const isLoading = ref(true)
const error = ref<string | null>(null)

const STATUS_LABELS: Record<string, string> = {
  SAVED: 'saved',
  AMBIGUOUS: 'ambiguous',
  NOT_FOUND: 'not found',
  PARSE_ERROR: 'parse error',
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

function statusSummary(statusCounts: Record<string, number>): string {
  return Object.entries(statusCounts)
    .filter(([, count]) => count > 0)
    .map(([status, count]) => `${count} ${STATUS_LABELS[status] ?? status.toLowerCase()}`)
    .join(' · ')
}

onMounted(async () => {
  isLoading.value = true
  error.value = null
  try {
    batches.value = await getBatches()
  } catch {
    error.value = 'Failed to load import history. Please refresh.'
  } finally {
    isLoading.value = false
  }
})
</script>

<template>
  <div>
  <Head><Title>Bulk Import History — MovieArchive</Title></Head>

  <div class="min-h-screen bg-background">
    <main class="max-w-4xl mx-auto px-4 py-8">
      <h1 class="text-2xl font-semibold tracking-wide text-foreground mb-6">Bulk Import History</h1>

      <!-- Loading state -->
      <div v-if="isLoading" class="flex items-center justify-center py-24">
        <SpinnerIcon />
      </div>

      <!-- Error state -->
      <div v-else-if="error" class="flex flex-col items-center justify-center py-24 gap-4">
        <p class="text-destructive">{{ error }}</p>
      </div>

      <!-- Empty state -->
      <div v-else-if="batches.length === 0" class="flex flex-col items-center justify-center py-24 gap-4">
        <p class="text-muted-foreground">No bulk imports yet.</p>
        <NuxtLink
          to="/add"
          class="px-5 py-2 bg-primary text-primary-foreground text-sm font-semibold hover:opacity-90"
        >
          Add films
        </NuxtLink>
      </div>

      <!-- Batch list -->
      <div v-else class="flex flex-col divide-y divide-border border border-border" data-testid="batch-list">
        <NuxtLink
          v-for="b in batches"
          :key="b.batchId"
          :to="`/imports/${b.batchId}`"
          data-testid="batch-row"
          class="flex items-center justify-between gap-4 px-4 py-4 hover:bg-card"
        >
          <div class="flex flex-col gap-1">
            <p class="text-sm font-medium text-foreground">{{ formatDate(b.createdAt) }}</p>
            <p class="text-xs text-muted-foreground">{{ statusSummary(b.statusCounts) }}</p>
          </div>
          <p class="text-sm text-muted-foreground">{{ b.totalLines }} lines</p>
        </NuxtLink>
      </div>
    </main>
  </div>
  </div>
</template>
