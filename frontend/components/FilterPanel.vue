<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { SlidersHorizontal } from 'lucide-vue-next'
import { CollapsibleRoot, CollapsibleTrigger, CollapsibleContent } from 'radix-vue'

const { updateFilter, genres, director, actors, yearFrom, yearTo, imdbFrom, imdbTo,
  contentRatings, runtimeMax, watched, languages, countries, facets, fetchFacets } = useSearch()

const isOpen = ref(false)

// Local input state for autocomplete fields
const directorInput = ref(director.value)
const actorsInput = ref('')
const directorSuggestions = ref<string[]>([])
const actorSuggestions = ref<string[]>([])

// Debounce timers for autocomplete
let directorDebounce: ReturnType<typeof setTimeout> | null = null
let actorsDebounce: ReturnType<typeof setTimeout> | null = null

// Load dynamic filter options once on mount
onMounted(() => fetchFacets())

async function fetchDirectorSuggestions(prefix: string) {
  if (!prefix || prefix.length < 2) {
    directorSuggestions.value = []
    return
  }
  try {
    const data = await $fetch<{ suggestions: string[] }>(
      `/api/search/autocomplete?field=director&prefix=${encodeURIComponent(prefix)}`,
      { credentials: 'include' }
    )
    directorSuggestions.value = data.suggestions
  } catch {
    directorSuggestions.value = []
  }
}

async function fetchActorSuggestions(prefix: string) {
  if (!prefix || prefix.length < 2) {
    actorSuggestions.value = []
    return
  }
  try {
    const data = await $fetch<{ suggestions: string[] }>(
      `/api/search/autocomplete?field=actors&prefix=${encodeURIComponent(prefix)}`,
      { credentials: 'include' }
    )
    actorSuggestions.value = data.suggestions
  } catch {
    actorSuggestions.value = []
  }
}

function onDirectorInput(e: Event) {
  const val = (e.target as HTMLInputElement).value
  directorInput.value = val
  if (directorDebounce) clearTimeout(directorDebounce)
  directorDebounce = setTimeout(() => fetchDirectorSuggestions(val), 300)
}

function onDirectorChange() {
  updateFilter('director', directorInput.value || null)
}

function onActorsInput(e: Event) {
  const val = (e.target as HTMLInputElement).value
  actorsInput.value = val
  if (actorsDebounce) clearTimeout(actorsDebounce)
  actorsDebounce = setTimeout(() => fetchActorSuggestions(val), 300)
}

function toggleArrayFilter(key: string, current: string[], value: string) {
  const next = current.includes(value)
    ? current.filter(v => v !== value)
    : [...current, value]
  updateFilter(key, next.length > 0 ? next : null)
}

function clearAllFilters() {
  updateFilter('genre', null)
  updateFilter('director', null)
  updateFilter('actors', null)
  updateFilter('year_from', null)
  updateFilter('year_to', null)
  updateFilter('imdb_from', null)
  updateFilter('imdb_to', null)
  updateFilter('content_rating', null)
  updateFilter('runtime_max', null)
  updateFilter('watched', null)
  updateFilter('language', null)
  updateFilter('country', null)
  directorInput.value = ''
  actorsInput.value = ''
}

const hasActiveFilters = computed(() =>
  genres.value.length > 0 || director.value || actors.value.length > 0 ||
  yearFrom.value !== undefined || yearTo.value !== undefined ||
  imdbFrom.value !== undefined || imdbTo.value !== undefined ||
  contentRatings.value.length > 0 || runtimeMax.value !== undefined ||
  watched.value !== undefined || languages.value.length > 0 || countries.value.length > 0
)
</script>

<template>
  <div class="border border-border flex-1 min-w-0">
    <CollapsibleRoot v-model:open="isOpen">
      <CollapsibleTrigger
        class="w-full flex items-center gap-2 px-4 py-3 text-sm font-medium text-foreground hover:bg-muted/30 transition-colors text-left"
      >
        <SlidersHorizontal class="w-4 h-4 flex-shrink-0" />
        <span>Filters</span>
        <span v-if="hasActiveFilters" class="ml-1 text-xs text-primary">(active)</span>
        <span class="ml-auto text-xs text-muted-foreground">{{ isOpen ? '▲' : '▼' }}</span>
      </CollapsibleTrigger>

      <CollapsibleContent>
        <div class="p-4 border-t border-border grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">

          <!-- Genre (dynamic from index) -->
          <div v-if="facets.genres.length > 0">
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Genre</label>
            <div class="flex flex-wrap gap-1">
              <button
                v-for="g in facets.genres"
                :key="g"
                type="button"
                :class="[
                  'px-2 py-1 text-xs border transition-colors',
                  genres.includes(g)
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-card text-foreground border-border hover:border-primary',
                ]"
                @click="toggleArrayFilter('genre', genres, g)"
              >
                {{ g }}
              </button>
            </div>
          </div>

          <!-- Director -->
          <div>
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Director</label>
            <input
              id="director-suggestions"
              :value="directorInput"
              list="director-suggestions-list"
              type="text"
              placeholder="e.g. Christopher Nolan"
              class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              @input="onDirectorInput"
              @change="onDirectorChange"
            />
            <datalist id="director-suggestions-list">
              <option v-for="s in directorSuggestions" :key="s" :value="s" />
            </datalist>
          </div>

          <!-- Actors -->
          <div>
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Actors</label>
            <input
              id="actors-suggestions"
              :value="actorsInput"
              list="actors-suggestions-list"
              type="text"
              placeholder="e.g. Cate Blanchett"
              class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              @input="onActorsInput"
              @change="updateFilter('actors', actorsInput || null)"
            />
            <datalist id="actors-suggestions-list">
              <option v-for="s in actorSuggestions" :key="s" :value="s" />
            </datalist>
          </div>

          <!-- Year range -->
          <div>
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Year</label>
            <div class="flex items-center gap-2">
              <input
                type="number"
                placeholder="From"
                :value="yearFrom"
                min="1888"
                max="2030"
                class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                @change="updateFilter('year_from', ($event.target as HTMLInputElement).value || null)"
              />
              <span class="text-muted-foreground text-xs">–</span>
              <input
                type="number"
                placeholder="To"
                :value="yearTo"
                min="1888"
                max="2030"
                class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                @change="updateFilter('year_to', ($event.target as HTMLInputElement).value || null)"
              />
            </div>
          </div>

          <!-- IMDB rating range -->
          <div>
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">IMDB Rating</label>
            <div class="flex items-center gap-2">
              <input
                type="number"
                placeholder="From"
                :value="imdbFrom"
                step="0.1"
                min="0"
                max="10"
                class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                @change="updateFilter('imdb_from', ($event.target as HTMLInputElement).value || null)"
              />
              <span class="text-muted-foreground text-xs">–</span>
              <input
                type="number"
                placeholder="To"
                :value="imdbTo"
                step="0.1"
                min="0"
                max="10"
                class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
                @change="updateFilter('imdb_to', ($event.target as HTMLInputElement).value || null)"
              />
            </div>
          </div>

          <!-- Runtime max -->
          <div>
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Max Runtime (min)</label>
            <input
              type="number"
              placeholder="e.g. 120"
              :value="runtimeMax"
              min="0"
              class="w-full h-10 px-3 text-sm bg-card border border-border text-foreground focus:outline-none focus:ring-1 focus:ring-primary"
              @change="updateFilter('runtime_max', ($event.target as HTMLInputElement).value || null)"
            />
          </div>

          <!-- Content rating (dynamic from index) -->
          <div v-if="facets.contentRatings.length > 0">
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Content Rating</label>
            <div class="flex flex-wrap gap-1">
              <button
                v-for="cr in facets.contentRatings"
                :key="cr"
                type="button"
                :class="[
                  'px-2 py-1 text-xs border transition-colors',
                  contentRatings.includes(cr)
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-card text-foreground border-border hover:border-primary',
                ]"
                @click="toggleArrayFilter('content_rating', contentRatings, cr)"
              >
                {{ cr }}
              </button>
            </div>
          </div>

          <!-- Language (dynamic from index — ISO-639-1 codes, displayed uppercase) -->
          <div v-if="facets.languages.length > 0">
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Language</label>
            <div class="flex flex-wrap gap-1">
              <button
                v-for="lang in facets.languages"
                :key="lang"
                type="button"
                :class="[
                  'px-2 py-1 text-xs border transition-colors',
                  languages.includes(lang)
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-card text-foreground border-border hover:border-primary',
                ]"
                @click="toggleArrayFilter('language', languages, lang)"
              >
                {{ lang.toUpperCase() }}
              </button>
            </div>
          </div>

          <!-- Production country (dynamic from index — ISO-3166-1 codes) -->
          <div v-if="facets.countries.length > 0">
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2 block">Production Country</label>
            <div class="flex flex-wrap gap-1">
              <button
                v-for="country in facets.countries"
                :key="country"
                type="button"
                :class="[
                  'px-2 py-1 text-xs border transition-colors',
                  countries.includes(country)
                    ? 'bg-primary text-primary-foreground border-primary'
                    : 'bg-card text-foreground border-border hover:border-primary',
                ]"
                @click="toggleArrayFilter('country', countries, country)"
              >
                {{ country }}
              </button>
            </div>
          </div>

          <!-- Not yet watched -->
          <div class="flex items-start gap-3">
            <label class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mt-1">Not Yet Watched</label>
            <div class="flex flex-col gap-1">
              <input
                type="checkbox"
                :checked="watched === false"
                class="w-4 h-4 mt-1"
                @change="updateFilter('watched', ($event.target as HTMLInputElement).checked ? 'false' : null)"
              />
              <p class="text-xs text-muted-foreground">Requires film status to be set (coming soon)</p>
            </div>
          </div>

        </div>

        <div class="px-4 pb-4 flex justify-end">
          <button
            type="button"
            class="text-xs text-muted-foreground hover:text-foreground underline underline-offset-2"
            @click="clearAllFilters"
          >
            Clear all filters
          </button>
        </div>
      </CollapsibleContent>
    </CollapsibleRoot>
  </div>
</template>
