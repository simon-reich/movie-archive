<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, resolveComponent } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import ViewToggle from '@/components/ViewToggle.vue'
import type { BulkImportBatchDetail, BulkImportLineResult, BulkImportProgress } from '@/composables/useBulkImport'
import type { TmdbSearchResult } from '@/composables/useMovies'

const BULK_IMPORT_VIEW_MODE_KEY = 'bulk-import-view-mode'

const route = useRoute()
const batchId = route.params.batchId as string

// G-15-2 fix: Nuxt's build-time component scan only auto-registers built-ins like
// NuxtLink into a file's compiled output when it detects a LITERAL <NuxtLink> tag in
// that file's template AST. This file never uses <NuxtLink> as a literal tag, so a
// bare-string `:is="'NuxtLink'"` ternary silently falls back to an inert unresolved
// custom element at runtime. Capturing the component reference via resolveComponent()
// with a literal string argument IS statically detectable by Nuxt's compiler, and
// binding `:is` to the captured reference (not the bare string) resolves correctly.
const NuxtLink = resolveComponent('NuxtLink')

const { subscribeToProgress, getBatchDetail, resolveLine } = useBulkImport()
const { searchTmdb } = useMovies()

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

// D-08/D-11: AMBIGUOUS/NOT_FOUND lines get an inline search-and-pick resolve widget;
// PARSE_ERROR/SAVED lines never do.
function isResolvable(line: BulkImportLineResult): boolean {
  return line.status === 'AMBIGUOUS' || line.status === 'NOT_FOUND'
}

// G-15-2: results are grouped into four fixed, ordered sections — Saved, Ambiguous,
// Not found, Parse error. PARSE_ERROR is rendered separately (see parseErrorLines /
// the always-row section below) and is never part of orderedCards.
const savedLines = computed(() => batch.value?.lines.filter(l => l.status === 'SAVED') ?? [])
const ambiguousLines = computed(() => batch.value?.lines.filter(l => l.status === 'AMBIGUOUS') ?? [])
const notFoundLines = computed(() => batch.value?.lines.filter(l => l.status === 'NOT_FOUND') ?? [])
const parseErrorLines = computed(() => batch.value?.lines.filter(l => l.status === 'PARSE_ERROR') ?? [])

// Fixed concatenation order encodes the required Saved -> Ambiguous -> Not found sequence.
const orderedCards = computed(() => [...savedLines.value, ...ambiguousLines.value, ...notFoundLines.value])

// Flags the first item of each status run in orderedCards, so a section heading is
// inserted exactly once per status group.
function isGroupStart(index: number): boolean {
  if (index === 0) return true
  return orderedCards.value[index - 1]!.status !== orderedCards.value[index]!.status
}

interface ResolveWidgetState {
  expanded: boolean
  searching: boolean
  results: TmdbSearchResult[]
  resolvingTmdbId: number | null
  error: string | null
}

// Per-line widget state, keyed by line.id — persists across a D-09 refetch since the
// resolved line keeps the same id (updated in place, never a new row).
const resolveState = reactive<Record<string, ResolveWidgetState>>({})

function getResolveState(line: BulkImportLineResult): ResolveWidgetState {
  if (!resolveState[line.id]) {
    resolveState[line.id] = {
      expanded: false,
      searching: false,
      results: [],
      resolvingTmdbId: null,
      error: null,
    }
  }
  return resolveState[line.id]!
}

// D-08: a FRESH TMDB search prefilled with the line's title every time the widget opens —
// this codebase never persists AMBIGUOUS candidates server-side, so there is nothing stale
// to reuse.
async function toggleResolve(line: BulkImportLineResult) {
  const state = getResolveState(line)
  state.expanded = !state.expanded
  if (!state.expanded) return

  state.error = null
  state.searching = true
  state.results = []
  try {
    state.results = await searchTmdb(line.title)
  } catch {
    state.error = 'Search failed. Please try again.'
  } finally {
    state.searching = false
  }
}

// D-09: on success, refetch the full batch via the existing loadDetail() — never a local
// patch of line.status/movieId/tmdbId.
async function pickCandidate(line: BulkImportLineResult, candidate: TmdbSearchResult) {
  const state = getResolveState(line)
  state.resolvingTmdbId = candidate.tmdbId
  state.error = null
  try {
    await resolveLine(batchId, line.id, candidate.tmdbId, candidate.posterPath)
    await loadDetail()
    state.expanded = false
  } catch {
    state.error = 'Could not resolve — please try again.'
  } finally {
    state.resolvingTmdbId = null
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
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-xl font-semibold tracking-wide">Results</h2>
        <ViewToggle v-model="viewMode" />
      </div>

      <div v-if="viewMode === 'grid'" class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
        <template v-for="(line, idx) in orderedCards" :key="line.id">
          <h3
            v-if="isGroupStart(idx)"
            :data-testid="`section-heading-${line.status}`"
            class="col-span-full text-sm font-semibold tracking-wide text-foreground mt-4 first:mt-0"
          >
            {{ statusLabel(line.status) }}
          </h3>
          <component
            :is="movieLinkTarget(line) ? NuxtLink : 'div'"
            :to="movieLinkTarget(line) ?? undefined"
            data-testid="result-card"
            class="relative overflow-hidden block"
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

            <div v-if="isResolvable(line)" class="mt-2">
              <button
                type="button"
                data-testid="resolve-toggle"
                class="text-xs text-primary hover:underline"
                @click="toggleResolve(line)"
              >
                {{ getResolveState(line).expanded ? 'Cancel' : 'Resolve' }}
              </button>
            </div>
          </component>

          <!-- G-15-3: the expanded candidate picker is a sibling of result-card, not a
               descendant of it, so it can break out to col-span-full full-width instead
               of inheriting the single grid cell's narrow width. -->
          <div
            v-if="isResolvable(line) && getResolveState(line).expanded"
            data-testid="resolve-panel"
            class="col-span-full bg-card border border-border p-4 space-y-2"
          >
            <div
              v-if="getResolveState(line).searching"
              class="flex items-center gap-2 text-xs text-muted-foreground"
            >
              <SpinnerIcon class="w-4 h-4" />
              <span>Searching...</span>
            </div>
            <FormErrorBanner v-if="getResolveState(line).error" :message="getResolveState(line).error" />
            <div
              v-if="!getResolveState(line).searching && getResolveState(line).results.length"
              class="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-2"
            >
              <button
                v-for="candidate in getResolveState(line).results"
                :key="candidate.tmdbId"
                type="button"
                data-testid="resolve-candidate"
                class="relative"
                :disabled="getResolveState(line).resolvingTmdbId !== null"
                @click="pickCandidate(line, candidate)"
              >
                <img
                  :src="posterUrl(candidate.posterPath)"
                  :alt="candidate.title"
                  class="w-full aspect-[2/3] object-cover bg-card border border-border"
                >
                <div
                  v-if="getResolveState(line).resolvingTmdbId === candidate.tmdbId"
                  class="absolute inset-0 bg-background/70 flex items-center justify-center"
                >
                  <SpinnerIcon class="w-4 h-4" />
                </div>
              </button>
            </div>
          </div>
        </template>
      </div>

      <div v-else class="divide-y divide-border">
        <template v-for="(line, idx) in orderedCards" :key="line.id">
          <h3
            v-if="isGroupStart(idx)"
            :data-testid="`section-heading-${line.status}`"
            class="text-sm font-semibold tracking-wide text-foreground pt-4 pb-2 first:pt-0"
          >
            {{ statusLabel(line.status) }}
          </h3>
          <component
            :is="movieLinkTarget(line) ? NuxtLink : 'div'"
            :to="movieLinkTarget(line) ?? undefined"
            data-testid="view-list-row"
            class="flex gap-4 py-3"
          >
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

              <div v-if="isResolvable(line)">
                <button
                  type="button"
                  data-testid="resolve-toggle"
                  class="text-xs text-primary hover:underline"
                  @click="toggleResolve(line)"
                >
                  {{ getResolveState(line).expanded ? 'Cancel' : 'Resolve' }}
                </button>
              </div>
            </div>
          </component>

          <!-- G-15-3: the expanded candidate picker is a sibling of view-list-row, not a
               descendant of it, so it already spans the full row width (the divide-y list
               container is single-column) instead of being squeezed into the row's narrow
               text column. -->
          <div
            v-if="isResolvable(line) && getResolveState(line).expanded"
            data-testid="resolve-panel"
            class="bg-card border border-border p-4 space-y-2"
          >
            <div
              v-if="getResolveState(line).searching"
              class="flex items-center gap-2 text-xs text-muted-foreground"
            >
              <SpinnerIcon class="w-4 h-4" />
              <span>Searching...</span>
            </div>
            <FormErrorBanner v-if="getResolveState(line).error" :message="getResolveState(line).error" />
            <div
              v-if="!getResolveState(line).searching && getResolveState(line).results.length"
              class="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 gap-2"
            >
              <button
                v-for="candidate in getResolveState(line).results"
                :key="candidate.tmdbId"
                type="button"
                data-testid="resolve-candidate"
                class="relative"
                :disabled="getResolveState(line).resolvingTmdbId !== null"
                @click="pickCandidate(line, candidate)"
              >
                <img
                  :src="posterUrl(candidate.posterPath)"
                  :alt="candidate.title"
                  class="w-full aspect-[2/3] object-cover bg-card border border-border"
                >
                <div
                  v-if="getResolveState(line).resolvingTmdbId === candidate.tmdbId"
                  class="absolute inset-0 bg-background/70 flex items-center justify-center"
                >
                  <SpinnerIcon class="w-4 h-4" />
                </div>
              </button>
            </div>
          </div>
        </template>
      </div>

      <section v-if="parseErrorLines.length" data-testid="parse-error-section" class="mt-8">
        <h3 data-testid="section-heading-PARSE_ERROR" class="text-sm font-semibold tracking-wide text-foreground mb-2">
          {{ statusLabel('PARSE_ERROR') }}
        </h3>
        <div class="divide-y divide-border">
          <div
            v-for="line in parseErrorLines"
            :key="line.id"
            data-testid="parse-error-row"
            class="flex gap-4 py-3 border-l-2 border-[#7A3520] pl-3"
          >
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
          </div>
        </div>
      </section>
    </div>
  </main>
</template>
