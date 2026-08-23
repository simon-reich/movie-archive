<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { TrashIcon, PlusCircleIcon } from 'lucide-vue-next'
import { useMovieDetail } from '@/composables/useMovieDetail'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import StarRating from '@/components/StarRating.vue'
import TrailerEmbed from '@/components/TrailerEmbed.vue'

useHead({
  title: 'Film Detail — MovieArchive',
  style: [{ innerHTML: 'html { background-color: #111 !important; }' }],
})

const route = useRoute()
const id = route.params.id as string

const { movie, isLoading, error, updatePersonal, deleteMovie, wikiRetrying, retryWiki } = useMovieDetail(id)

// ── Wikipedia retry (ENRICH-04/ENRICH-05) ───────────────────────────────────
const wikiRetryAttempted = ref(false)

async function onRetryWiki() {
  await retryWiki()
  wikiRetryAttempted.value = true
}

// ── Image URLs ─────────────────────────────────────────────────────────────
const backdropUrl = computed(() =>
  movie.value?.backdropPath
    ? `https://image.tmdb.org/t/p/w1280${movie.value.backdropPath}`
    : null
)
const posterUrl = computed(() =>
  movie.value?.posterPath
    ? `https://image.tmdb.org/t/p/w342${movie.value.posterPath}`
    : null
)

// ── Navigation ─────────────────────────────────────────────────────────────
const router = useRouter()

function navigateToActor(name: string) {
  router.push({ path: '/search', query: { actors: name } })
}
function navigateToDirector(name: string) {
  router.push({ path: '/search', query: { director: name } })
}
function navigateToGenre(genre: string) {
  router.push({ path: '/search', query: { genre } })
}
function navigateToCrew(name: string) {
  router.push({ path: '/search', query: { crew: name } })
}


// ── Personal fields — local state + auto-save ──────────────────────────────
const localWatched = ref(false)
const localRating = ref<number | null>(null)
const localNotes = ref('')
let notesDebounce: ReturnType<typeof setTimeout> | null = null

// Sync local state when movie loads
watch(movie, (m) => {
  if (m) {
    localWatched.value = m.watched
    localRating.value = m.personalRating
    localNotes.value = m.personalNotes ?? ''
  }
})

function onWatchedChange() {
  updatePersonal({ watched: localWatched.value })
}

function onRatingChange(rating: number | null) {
  localRating.value = rating
  updatePersonal({ personalRating: rating })
}

function onNotesInput() {
  if (notesDebounce) clearTimeout(notesDebounce)
  notesDebounce = setTimeout(() => {
    updatePersonal({ personalNotes: localNotes.value || null })
  }, 1000)
}

// ── Delete ─────────────────────────────────────────────────────────────────
const deleteModalOpen = ref(false)

async function confirmDelete() {
  deleteModalOpen.value = false
  await deleteMovie()
}

// ── Cast display (first 5 by order) ────────────────────────────────────────
const displayCast = computed(() =>
  movie.value?.fullCast
    .slice()
    .sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
    .slice(0, 5) ?? []
)

// ── Crew grouped by department ─────────────────────────────────────────────
const crewByDepartment = computed(() => {
  if (!movie.value?.fullCrew) return {} as Record<string, NonNullable<typeof movie.value>['fullCrew']>
  const groups: Record<string, NonNullable<typeof movie.value>['fullCrew']> = {}
  for (const member of movie.value.fullCrew) {
    const dept = member.department ?? 'Other'
    if (!groups[dept]) groups[dept] = []
    groups[dept]!.push(member)
  }
  return groups
})
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">

    <!-- Loading state -->
    <div v-if="isLoading" class="flex items-center justify-center py-24">
      <SpinnerIcon />
    </div>

    <!-- Error state -->
    <div v-else-if="error" class="flex items-center justify-center py-24">
      <p class="text-[#7A3520]">{{ error }}</p>
    </div>

    <template v-else-if="movie">

      <!-- Hero (D-02): full-width cinematic backdrop — -mt-12 pulls it under the fixed nav -->
      <div class="relative w-full h-72 overflow-hidden -mt-12">
        <img
          v-if="backdropUrl"
          :src="backdropUrl"
          alt=""
          class="absolute inset-0 w-full h-full object-cover"
        >
        <div v-else class="absolute inset-0 bg-card" />
        <!-- Gradient overlay -->
        <div class="absolute inset-0 bg-gradient-to-r from-black/80 via-black/50 to-transparent" />

        <!-- Hero content: poster + title/year/tagline on left, Delete button bottom-right -->
        <div class="relative z-10 max-w-7xl mx-auto px-4 h-full flex items-end justify-between pb-6">
          <div class="flex items-end gap-3 sm:gap-6">
            <img
              v-if="posterUrl"
              :src="posterUrl"
              :alt="movie.title"
              class="w-20 sm:w-32 aspect-[2/3] object-cover border border-white/20 flex-shrink-0"
            >
            <div
              v-else
              class="w-20 sm:w-32 aspect-[2/3] bg-card flex-shrink-0 flex items-center justify-center border border-border"
            >
              <span class="text-2xl text-muted-foreground">{{ movie.title?.[0] }}</span>
            </div>
            <div class="flex-1">
              <h1 data-testid="movie-title" class="text-2xl font-semibold tracking-wide text-white">{{ movie.title }}</h1>
              <p class="text-sm text-white/70 mt-0.5">
                {{ movie.year }}
                <span v-if="movie.runtime"> · {{ movie.runtime }} min</span>
                <span
                  v-if="movie.contentRating"
                  class="ml-2 border border-white/40 px-1 text-xs"
                >{{ movie.contentRating }}</span>
              </p>
              <p v-if="movie.tagline" class="text-sm text-white/60 italic mt-1">{{ movie.tagline }}</p>
              <div v-if="movie.genreList.length" class="flex flex-wrap gap-2 mt-3">
                <button
                  v-for="genre in movie.genreList"
                  :key="genre"
                  class="text-xs text-white/70 hover:text-white cursor-pointer"
                  @click="navigateToGenre(genre)"
                >{{ genre }}</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Two-column body (D-03): left facts+wiki, right sidebar -->
      <div class="max-w-7xl mx-auto px-4 py-8 grid grid-cols-1 md:grid-cols-3 gap-8">

        <!-- Left column: primary facts + Wikipedia (D-04) -->
        <div class="col-span-1 md:col-span-2 space-y-8">

          <!-- Primary facts section -->
          <section class="space-y-4">

            <!-- Directors — clickable (D-12) -->
            <div v-if="movie.directorList.length" class="flex items-baseline gap-2">
              <span class="shrink-0 text-sm font-semibold tracking-widest uppercase text-muted-foreground">Director</span>
              <span class="text-sm leading-snug">
                <span v-for="(dir, i) in movie.directorList" :key="dir">
                  <button
                    class="hover:text-primary cursor-pointer"
                    @click="navigateToDirector(dir)"
                  >{{ dir }}</button>
                  <span v-if="i < movie.directorList.length - 1">, </span>
                </span>
              </span>
            </div>

            <!-- Writers — clickable like directors -->
            <div v-if="movie.writerList.length" class="flex items-baseline gap-2">
              <span class="shrink-0 text-sm font-semibold tracking-widest uppercase text-muted-foreground">Writer</span>
              <span class="text-sm leading-snug">
                <span v-for="(writer, i) in movie.writerList" :key="writer">
                  <button
                    class="hover:text-primary cursor-pointer"
                    @click="navigateToCrew(writer)"
                  >{{ writer }}</button>
                  <span v-if="i < movie.writerList.length - 1">, </span>
                </span>
              </span>
            </div>

            <!-- Main cast — names only, clickable -->
            <div v-if="displayCast.length" class="flex items-baseline gap-2">
              <span class="shrink-0 text-sm font-semibold tracking-widest uppercase text-muted-foreground">Cast</span>
              <span class="text-sm leading-snug">
                <span v-for="(member, i) in displayCast" :key="member.name ?? ''">
                  <button
                    class="hover:text-primary cursor-pointer"
                    @click="member.name && navigateToActor(member.name)"
                  >{{ member.name }}</button>
                  <span v-if="i < displayCast.length - 1">, </span>
                </span>
              </span>
            </div>

            <!-- Country & Language -->
            <div class="flex gap-6 text-sm">
              <div v-if="movie.countryList.length">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">Country</span>
                <p class="mt-0.5">{{ movie.countryList.join(', ') }}</p>
              </div>
              <div v-if="movie.languageList.length">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">Language</span>
                <p class="mt-0.5">{{ movie.languageList.map(l => l.toUpperCase()).join(', ') }}</p>
              </div>
            </div>

            <!-- Ratings block: all sources in one row (D-10) -->
            <div class="flex flex-wrap gap-4 text-sm">
              <div v-if="movie.imdbRating !== null">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">IMDB</span>
                <p class="mt-0.5">
                  <a
                    v-if="movie.imdbLink"
                    :href="movie.imdbLink"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="hover:text-primary"
                  >{{ movie.imdbRating }} / 10</a>
                  <span v-else>{{ movie.imdbRating }} / 10</span>
                </p>
              </div>
              <!-- OMDB ratings: skip "Internet Movie Database" if TMDB already provides imdbRating -->
              <template v-if="movie.ratingList?.length">
                <div
                  v-for="r in movie.ratingList.filter(r => movie?.imdbRating !== null ? r.source !== 'Internet Movie Database' : true)"
                  :key="r.source"
                >
                  <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">
                    {{ r.source === 'Internet Movie Database' ? 'IMDB' : r.source === 'Rotten Tomatoes' ? 'RT' : r.source }}
                  </span>
                  <p class="mt-0.5">{{ r.value }}</p>
                </div>
              </template>
              <div v-if="movie.voteAverage !== null">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">TMDB</span>
                <p class="mt-0.5">{{ movie.voteAverage?.toFixed(1) }} / 10</p>
              </div>
            </div>
            <div v-if="movie.boxOffice !== null" class="flex flex-wrap gap-4 text-sm">
              <div>
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">Box Office</span>
                <p class="mt-0.5">${{ movie.boxOffice?.toLocaleString() }}</p>
              </div>
            </div>

          </section>

          <!-- Overview / Synopsis -->
          <section v-if="movie.overview" class="space-y-2">
            <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Synopsis</h2>
            <p class="text-sm leading-relaxed">{{ movie.overview }}</p>
          </section>

        </div>

        <!-- Right sidebar: trailer + personal fields (D-03, D-06, D-07, D-11) -->
        <div class="col-span-1 space-y-6">

          <!-- Trailer embed (D-11) -->
          <TrailerEmbed v-if="movie.trailerKey" :trailer-key="movie.trailerKey" />

          <!-- Personal fields panel (D-06, D-07) -->
          <div class="bg-card border border-border p-3 space-y-3">

            <!-- Watched + Rating on one row, label above control -->
            <div class="flex items-start gap-6">
              <label class="flex flex-col gap-1 cursor-pointer">
                <span class="text-xs font-semibold tracking-widest uppercase text-muted-foreground">Watched</span>
                <input
                  v-model="localWatched"
                  type="checkbox"
                  class="w-4 h-4 accent-primary"
                  @change="onWatchedChange"
                >
              </label>
              <div class="flex flex-col gap-1">
                <span class="text-xs font-semibold tracking-widest uppercase text-muted-foreground">Rating</span>
                <StarRating :model-value="localRating" @update:model-value="onRatingChange" />
              </div>
            </div>

            <!-- Notes textarea with debounce -->
            <div>
              <label class="text-xs font-semibold tracking-widest uppercase text-muted-foreground">Notes</label>
              <textarea
                v-model="localNotes"
                class="mt-1 w-full border border-input bg-background text-sm p-2 resize-none focus-visible:ring-2 ring-ring ring-offset-2 outline-none"
                rows="3"
                placeholder="Your thoughts..."
                @input="onNotesInput"
              />
            </div>
          </div>

        </div>
      </div>

      <!-- Wikipedia — full width below the two-column block -->
      <div v-if="movie.wikipediaPlot || movie.wikipediaCritics" class="max-w-7xl mx-auto px-4 pb-8 space-y-8 border-t border-border pt-8">
        <section v-if="movie.wikipediaPlot" class="space-y-2">
          <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Plot</h2>
          <p class="text-sm leading-relaxed">{{ movie.wikipediaPlot }}</p>
        </section>
        <section v-if="movie.wikipediaCritics" class="space-y-2">
          <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Critical Response</h2>
          <p class="text-sm leading-relaxed">{{ movie.wikipediaCritics }}</p>
        </section>
      </div>
      <!-- No Wikipedia data — manual retry prompt (ENRICH-04/ENRICH-05) -->
      <div v-else class="max-w-7xl mx-auto px-4 pb-8 space-y-4 border-t border-border pt-8">
        <p class="text-sm text-muted-foreground">
          No Wikipedia data found.
          <span v-if="wikiRetryAttempted"> Still no page found.</span>
        </p>
        <button
          type="button"
          :disabled="wikiRetrying"
          class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-2"
          @click="onRetryWiki"
        >
          <SpinnerIcon v-if="wikiRetrying" class="w-4 h-4" />
          {{ wikiRetrying ? 'Retrying...' : 'Retry' }}
        </button>
      </div>

      <!-- Full cast & crew — full width at page bottom (D-05) -->
      <section class="max-w-7xl mx-auto px-4 pb-16">
        <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground mb-6 border-t border-border pt-8">Cast & Crew</h2>
        <div class="columns-1 md:columns-3 gap-8">

          <!-- Full cast -->
          <div class="break-inside-avoid mb-6">
            <h3 class="text-xs font-semibold tracking-widest uppercase text-muted-foreground mb-3">Cast</h3>
            <ul class="space-y-1">
              <li
                v-for="member in movie.fullCast.slice().sort((a, b) => (a.order ?? 999) - (b.order ?? 999))"
                :key="(member.name ?? '') + (member.character ?? '')"
                class="text-sm"
              >
                <button class="hover:text-primary" @click="member.name && navigateToActor(member.name)">{{ member.name }}</button>
              </li>
            </ul>
          </div>

          <!-- Crew by department -->
          <div
            v-for="(members, dept) in crewByDepartment"
            :key="dept"
            class="break-inside-avoid mb-6"
          >
            <h3 class="text-xs font-semibold tracking-widest uppercase text-muted-foreground mb-3">{{ dept }}</h3>
            <ul class="space-y-1">
              <li
                v-for="member in members"
                :key="(member.name ?? '') + (member.job ?? '')"
                class="text-sm"
              >
                <button class="hover:text-primary" @click="member.name && navigateToCrew(member.name)">{{ member.name }}</button><span v-if="member.job" class="text-muted-foreground"> — {{ member.job }}</span>
              </li>
            </ul>
          </div>

        </div>
      </section>

      <!-- Remove button at bottom -->
      <div class="max-w-7xl mx-auto px-4 py-8 border-t border-border flex justify-center">
        <button
          class="flex items-center gap-1.5 text-sm text-foreground hover:opacity-70"
          @click="deleteModalOpen = true"
        >
          <TrashIcon class="w-4 h-4" />
          <span>Remove from archive</span>
        </button>
      </div>

    </template>

    <!-- Delete confirmation modal (D-13) -->
    <div
      v-if="deleteModalOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      @click.self="deleteModalOpen = false"
    >
      <div class="bg-card border border-border p-6 w-full max-w-sm space-y-4">
        <h2 class="text-lg font-semibold tracking-wide">Remove from archive?</h2>
        <p class="text-sm text-muted-foreground">This cannot be undone.</p>
        <div class="flex gap-2 justify-end">
          <button
            class="px-4 py-2 text-sm border border-border hover:bg-muted"
            @click="deleteModalOpen = false"
          >Cancel</button>
          <button
            class="px-4 py-2 text-sm bg-primary text-primary-foreground hover:opacity-90"
            @click="confirmDelete"
          >Confirm</button>
        </div>
      </div>
    </div>

  </div>
</template>
