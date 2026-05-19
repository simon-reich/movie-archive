<script setup lang="ts">
import SearchBar from '@/components/SearchBar.vue'
import FilterPanel from '@/components/FilterPanel.vue'
import SortSelect from '@/components/SortSelect.vue'
import ViewToggle from '@/components/ViewToggle.vue'
import MovieGrid from '@/components/MovieGrid.vue'
import MovieList from '@/components/MovieList.vue'
import SpinnerIcon from '@/components/SpinnerIcon.vue'

useHead({ title: 'Search — MovieArchive' })

const { searchQuery, sort, results, total, hasMore, isLoading, updateFilter, loadMore } = useSearch()
const searchStore = useSearchStore()

const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

function setupObserver() {
  observer?.disconnect()
  if (!sentinel.value) return
  observer = new IntersectionObserver(([entry]) => {
    if (entry?.isIntersecting && hasMore.value && !isLoading.value) {
      loadMore()
    }
  }, { rootMargin: '200px' })
  observer.observe(sentinel.value)
}

onMounted(setupObserver)
onUnmounted(() => observer?.disconnect())
watch(sentinel, setupObserver)
</script>

<template>
  <main class="max-w-7xl mx-auto px-4 py-8">
    <h1 class="text-2xl font-semibold tracking-wide text-foreground mb-6">Search Archive</h1>

    <!-- Search bar -->
    <div class="mb-4">
      <SearchBar v-model="searchQuery" />
    </div>

    <!-- Controls row: filter trigger + sort + view toggle -->
    <div class="flex items-start justify-between gap-4 mb-4">
      <FilterPanel />
      <div class="flex items-center gap-2 flex-shrink-0">
        <SortSelect
          :model-value="sort"
          @update:model-value="updateFilter('sort', $event)"
        />
        <ViewToggle
          :model-value="searchStore.viewMode"
          @update:model-value="searchStore.setViewMode($event)"
        />
      </div>
    </div>

    <!-- Result count -->
    <p class="text-sm text-muted-foreground mb-4">{{ total }} films</p>

    <!-- Loading state -->
    <div v-if="isLoading && results.length === 0" class="flex justify-center py-16">
      <SpinnerIcon class="w-8 h-8 text-foreground" />
    </div>

    <!-- Results -->
    <template v-else>
      <MovieGrid v-if="searchStore.viewMode === 'grid'" :movies="results" />
      <MovieList v-else :movies="results" />

      <!-- Empty state -->
      <div v-if="!isLoading && results.length === 0" class="py-16 text-center">
        <p class="text-muted-foreground">No films found. Try adjusting your search or filters.</p>
      </div>

      <!-- Infinite scroll sentinel -->
      <div ref="sentinel" class="mt-8 flex justify-center h-8">
        <SpinnerIcon v-if="isLoading && results.length > 0" class="w-6 h-6 text-muted-foreground" />
      </div>
    </template>
  </main>
</template>
