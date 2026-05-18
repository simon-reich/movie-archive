<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { TrashIcon } from 'lucide-vue-next'
import { useMovieDetail } from '@/composables/useMovieDetail'
import SpinnerIcon from '@/components/SpinnerIcon.vue'
import StarRating from '@/components/StarRating.vue'
import TrailerEmbed from '@/components/TrailerEmbed.vue'

useHead({ title: 'Film Detail — MovieArchive' })

const route = useRoute()
const id = route.params.id as string

const { movie, isLoading, error, updatePersonal, deleteMovie } = useMovieDetail(id)

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
function navigateToWriter(name: string) {
  router.push({ path: '/search', query: { q: name } })
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

      <!-- Hero (D-02): full-width cinematic backdrop -->
      <div class="relative w-full h-72 overflow-hidden">
        <img
          v-if="backdropUrl"
          :src="backdropUrl"
          alt=""
          class="absolute inset-0 w-full h-full object-cover"
        />
        <div v-else class="absolute inset-0 bg-card" />
        <!-- Gradient overlay -->
        <div class="absolute inset-0 bg-gradient-to-r from-black/80 via-black/50 to-transparent" />

        <!-- Delete button — top right -->
        <button
          class="absolute top-4 right-4 z-20 flex items-center gap-1 text-sm text-white/80 hover:text-white"
          @click="deleteModalOpen = true"
        >
          <TrashIcon class="w-4 h-4" />
          <span>Remove</span>
        </button>

        <!-- Hero content: poster + title/year/tagline -->
        <div class="relative z-10 flex items-end gap-6 h-full px-8 pb-6">
          <img
            v-if="posterUrl"
            :src="posterUrl"
            :alt="movie.title"
            class="w-32 aspect-[2/3] object-cover border border-white/20 flex-shrink-0"
          />
          <div
            v-else
            class="w-32 aspect-[2/3] bg-card flex-shrink-0 flex items-center justify-center border border-border"
          >
            <span class="text-2xl text-muted-foreground">{{ movie.title?.[0] }}</span>
          </div>
          <div class="flex-1">
            <h1 class="text-2xl font-semibold tracking-wide text-white">{{ movie.title }}</h1>
            <p class="text-sm text-white/70 mt-0.5">
              {{ movie.year }}
              <span v-if="movie.runtime"> · {{ movie.runtime }} min</span>
              <span
                v-if="movie.contentRating"
                class="ml-2 border border-white/40 px-1 text-xs"
              >{{ movie.contentRating }}</span>
            </p>
            <p v-if="movie.tagline" class="text-sm text-white/60 italic mt-1">{{ movie.tagline }}</p>
          </div>
        </div>
      </div>

      <!-- Two-column body (D-03): left facts+wiki, right sidebar -->
      <div class="max-w-7xl mx-auto px-8 py-8 grid grid-cols-3 gap-8">

        <!-- Left column: primary facts + Wikipedia (D-04) -->
        <div class="col-span-2 space-y-8">

          <!-- Primary facts section -->
          <section class="space-y-4">

            <!-- Genres — plain text -->
            <div v-if="movie.genreList.length">
              <span class="text-sm">{{ movie.genreList.join(', ') }}</span>
            </div>

            <!-- Directors — clickable (D-12) -->
            <div v-if="movie.directorList.length" class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Director</span>
              <span v-for="(dir, i) in movie.directorList" :key="dir">
                <button
                  class="text-sm hover:text-primary cursor-pointer"
                  @click="navigateToDirector(dir)"
                >{{ dir }}</button>
                <span v-if="i < movie.directorList.length - 1">, </span>
              </span>
            </div>

            <!-- Writers — clickable like directors -->
            <div v-if="movie.writerList.length" class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Writer</span>
              <span v-for="(writer, i) in movie.writerList" :key="writer">
                <button
                  class="text-sm hover:text-primary cursor-pointer"
                  @click="navigateToWriter(writer)"
                >{{ writer }}</button>
                <span v-if="i < movie.writerList.length - 1">, </span>
              </span>
            </div>

            <!-- Main cast — names only, clickable -->
            <div v-if="displayCast.length" class="flex items-center gap-2 flex-wrap">
              <span class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Cast</span>
              <span v-for="(member, i) in displayCast" :key="member.name ?? ''">
                <button
                  class="text-sm hover:text-primary cursor-pointer"
                  @click="member.name && navigateToActor(member.name)"
                >{{ member.name }}</button>
                <span v-if="i < displayCast.length - 1">, </span>
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
                <p class="mt-0.5">{{ movie.languageList.join(', ') }}</p>
              </div>
            </div>

            <!-- Ratings block: TMDB + OMDB (D-10: hide individually when null) -->
            <div class="flex flex-wrap gap-4 text-sm">
              <div v-if="movie.voteAverage !== null">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">TMDB</span>
                <p class="mt-0.5">{{ movie.voteAverage?.toFixed(1) }} / 10</p>
              </div>
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
              <div v-if="movie.boxOffice !== null">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">Box Office</span>
                <p class="mt-0.5">${{ movie.boxOffice?.toLocaleString() }}</p>
              </div>
            </div>

            <!-- RT / Metacritic from ratingList (D-10: hide when null/empty) -->
            <div v-if="movie.ratingList?.length" class="flex flex-wrap gap-4 text-sm">
              <div v-for="r in movie.ratingList" :key="r.source">
                <span class="font-semibold tracking-widest uppercase text-muted-foreground text-xs">{{ r.source }}</span>
                <p class="mt-0.5">{{ r.value }}</p>
              </div>
            </div>

          </section>

          <!-- Overview / Synopsis (D-04 order: facts → overview → wiki) -->
          <section v-if="movie.overview" class="space-y-2">
            <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Synopsis</h2>
            <p class="text-sm leading-relaxed">{{ movie.overview }}</p>
          </section>

          <!-- Wikipedia plot (D-02, DETAIL-02) -->
          <section v-if="movie.wikipediaPlot" class="space-y-2">
            <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Plot</h2>
            <p class="text-sm leading-relaxed">{{ movie.wikipediaPlot }}</p>
          </section>

          <!-- Wikipedia critics (DETAIL-02) -->
          <section v-if="movie.wikipediaCritics" class="space-y-2">
            <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Critical Response</h2>
            <p class="text-sm leading-relaxed">{{ movie.wikipediaCritics }}</p>
          </section>

        </div>

        <!-- Right sidebar: trailer + personal fields (D-03, D-06, D-07, D-11) -->
        <div class="col-span-1 space-y-6">

          <!-- Trailer embed (D-11) -->
          <TrailerEmbed v-if="movie.trailerKey" :trailer-key="movie.trailerKey" />

          <!-- Personal fields panel (D-06, D-07) -->
          <div class="bg-card border border-border p-4 space-y-4">
            <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground">Your Archive</h2>

            <!-- Watched toggle -->
            <label class="flex items-center gap-3 cursor-pointer">
              <input
                v-model="localWatched"
                type="checkbox"
                class="w-4 h-4 accent-primary"
                @change="onWatchedChange"
              />
              <span class="text-sm">Watched</span>
            </label>

            <!-- Star rating (StarRating component) -->
            <div>
              <p class="text-xs font-semibold tracking-widest uppercase text-muted-foreground mb-2">Rating</p>
              <StarRating :model-value="localRating" @update:model-value="onRatingChange" />
            </div>

            <!-- Notes textarea with debounce -->
            <div>
              <label class="text-xs font-semibold tracking-widest uppercase text-muted-foreground">Notes</label>
              <textarea
                v-model="localNotes"
                class="mt-1 w-full border border-input bg-background text-sm p-2 resize-none focus-visible:ring-2 ring-ring ring-offset-2 outline-none"
                rows="4"
                placeholder="Your thoughts..."
                @input="onNotesInput"
              />
            </div>
          </div>

        </div>
      </div>

      <!-- Full cast & crew — full width at page bottom (D-05) -->
      <section class="max-w-7xl mx-auto px-8 pb-16">
        <h2 class="text-sm font-semibold tracking-widest uppercase text-muted-foreground mb-6 border-t border-border pt-8">Cast & Crew</h2>
        <div class="grid grid-cols-3 gap-8">

          <!-- Full cast column -->
          <div>
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

          <!-- Crew by department (remaining columns) -->
          <div
            v-for="(members, dept) in crewByDepartment"
            :key="dept"
          >
            <h3 class="text-xs font-semibold tracking-widest uppercase text-muted-foreground mb-3">{{ dept }}</h3>
            <ul class="space-y-1">
              <li
                v-for="member in members"
                :key="(member.name ?? '') + (member.job ?? '')"
                class="text-sm"
              >
                {{ member.name }}<span v-if="member.job" class="text-muted-foreground"> — {{ member.job }}</span>
              </li>
            </ul>
          </div>

        </div>
      </section>

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
