<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { CheckCircle2, XCircle } from 'lucide-vue-next'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'
import type { WikiReloadProgress } from '@/composables/useSettings'

const {
  saveApiKey, deleteApiKey, loadApiKeys, changePassword, changeEmail, triggerWikiReload,
  subscribeToWikiReloadProgress, stopWikiReload, getCurrentUserId,
} = useSettings()
const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

// API Keys section
const tmdbKey = ref<string>('')
const omdbKey = ref<string>('')
const tmdbSaving = ref(false)
const omdbSaving = ref(false)
const tmdbSaved = ref(false)
const omdbSaved = ref(false)
const tmdbDeleting = ref(false)
const omdbDeleting = ref(false)
const tmdbError = ref<string | null>(null)
const omdbError = ref<string | null>(null)
const keysLoading = ref(true)

// Email change section
const newEmail = ref('')
const emailChanging = ref(false)
const emailChangeSuccess = ref(false)
const emailConfirmedBanner = ref(false)
const emailError = ref<string | null>(null)

// Password change section
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordChanging = ref(false)
const passwordError = ref<string | null>(null)
const passwordFieldError = ref<string | null>(null)

// Wikipedia Data section
const wikiReloadTriggering = ref(false)
const wikiReloadMessage = ref<string | null>(null)
const wikiProgress = ref<WikiReloadProgress | null>(null)
const wikiMovieHistory = ref<{ title: string; status: string }[]>([])
const wikiStopping = ref(false)
let unsubscribeWikiProgress: (() => void) | null = null

const wikiProgressPercent = computed(() => {
  if (!wikiProgress.value || wikiProgress.value.total === 0) return 0
  return Math.round((wikiProgress.value.processed / wikiProgress.value.total) * 100)
})

// D-07/D-14-03: formats the backend's rolling-average etaSeconds — empty string renders
// nothing (gated by v-if below) when there's no active run or the run just started.
const wikiEtaLabel = computed(() => {
  const etaSeconds = wikiProgress.value?.etaSeconds
  if (!etaSeconds) return ''
  if (etaSeconds >= 60) return `~${Math.ceil(etaSeconds / 60)} min remaining`
  return `~${etaSeconds}s remaining`
})

// Reset inline success state when input value changes (D-06)
watch(tmdbKey, () => {
  tmdbSaved.value = false
  tmdbError.value = null
})
watch(omdbKey, () => {
  omdbSaved.value = false
  omdbError.value = null
})

// Reset email error when field changes — but NOT emailChangeSuccess (cleared on submit only)
watch(newEmail, () => {
  emailError.value = null
})

// Reset password error when any password field changes
watch([currentPassword, newPassword, confirmPassword], () => {
  passwordError.value = null
  passwordFieldError.value = null
})

// Handle ?emailConfirmed=true / ?emailError=... from backend redirect,
// then load API keys. Both happen once the component is mounted on the client
// (the access_token cookie is already available via the middleware — no async
// plugin round-trip needed before this point).
onMounted(async () => {
  if (route.query.emailConfirmed === 'true') {
    emailConfirmedBanner.value = true
    router.replace({ query: {} })
  } else if (route.query.emailError) {
    const code = route.query.emailError as string
    emailError.value =
      code === 'token-used' ? 'This confirmation link has already been used.' :
      code === 'token-expired' ? 'This confirmation link has expired. Request a new one.' :
      'Invalid confirmation link.'
    router.replace({ query: {} })
  }

  keysLoading.value = true
  try {
    const keys = await loadApiKeys()
    tmdbKey.value = keys.tmdb ?? ''
    omdbKey.value = keys.omdb ?? ''
  } catch {
    // Non-fatal — leave inputs empty
  } finally {
    keysLoading.value = false
  }

  try {
    const userId = await getCurrentUserId()
    unsubscribeWikiProgress = subscribeToWikiReloadProgress(userId, (p) => {
      wikiProgress.value = p
      if (p.lastMovieTitle) {
        wikiMovieHistory.value.push({ title: p.lastMovieTitle, status: p.lastMovieStatus ?? 'FAILED' })
      }
    })
  } catch {
    // Non-fatal — could not resolve the user id (e.g. /users/me failed); no live
    // progress stream, page still usable. Does NOT cover SSE connection errors,
    // which surface asynchronously via onerror (see useSettings.ts).
  }
})

onUnmounted(() => {
  unsubscribeWikiProgress?.()
})

async function handleSaveTmdb() {
  tmdbSaving.value = true
  tmdbError.value = null
  tmdbSaved.value = false
  try {
    await saveApiKey('tmdb', tmdbKey.value)
    tmdbSaved.value = true
  } catch (err: unknown) {
    const e = err as { data?: { message?: string } }
    tmdbError.value = e?.data?.message ?? 'Something went wrong. Please try again.'
  } finally {
    tmdbSaving.value = false
  }
}

async function handleDeleteTmdb() {
  tmdbDeleting.value = true
  tmdbError.value = null
  try {
    await deleteApiKey('tmdb')
    tmdbKey.value = ''
    tmdbSaved.value = false
  } catch {
    tmdbError.value = 'Could not delete key. Please try again.'
  } finally {
    tmdbDeleting.value = false
  }
}

async function handleDeleteOmdb() {
  omdbDeleting.value = true
  omdbError.value = null
  try {
    await deleteApiKey('omdb')
    omdbKey.value = ''
    omdbSaved.value = false
  } catch {
    omdbError.value = 'Could not delete key. Please try again.'
  } finally {
    omdbDeleting.value = false
  }
}

async function handleSaveOmdb() {
  omdbSaving.value = true
  omdbError.value = null
  omdbSaved.value = false
  try {
    await saveApiKey('omdb', omdbKey.value)
    omdbSaved.value = true
  } catch (err: unknown) {
    const e = err as { data?: { message?: string } }
    omdbError.value = e?.data?.message ?? 'Something went wrong. Please try again.'
  } finally {
    omdbSaving.value = false
  }
}

async function handleChangeEmail() {
  emailError.value = null
  emailChangeSuccess.value = false

  // Client-side guard: reject if the entered email matches the current email
  if (newEmail.value.trim().toLowerCase() === (authStore.userEmail ?? '').toLowerCase()) {
    emailError.value = 'This is already your current email address.'
    return
  }

  emailChanging.value = true
  try {
    await changeEmail(newEmail.value)
    newEmail.value = ''
    // Set success AFTER clearing field so the watch(newEmail) doesn't wipe it
    emailChangeSuccess.value = true
  } catch (err: unknown) {
    const e = err as { data?: { message?: string } }
    emailError.value = e?.data?.message ?? 'Something went wrong. Please try again.'
  } finally {
    emailChanging.value = false
  }
}

async function onTriggerWikiReload() {
  wikiReloadTriggering.value = true
  wikiReloadMessage.value = null
  try {
    const result = await triggerWikiReload()
    // Only clear history when no run is currently active — the queue can accept a
    // second 'started' trigger while the first run is still in progress (queueCapacity=1),
    // and clearing here would wipe the still-running first run's visible history (WR-03).
    if (result === 'started' && (!wikiProgress.value || wikiProgress.value.complete)) {
      wikiMovieHistory.value = []
    }
    wikiReloadMessage.value = result === 'started'
      ? 'Reload started — this runs in the background and may take a few minutes.'
      : 'A reload is already in progress.'
  } catch {
    wikiReloadMessage.value = 'Something went wrong. Please try again.'
  } finally {
    wikiReloadTriggering.value = false
  }
}

async function onStopWikiReload() {
  wikiStopping.value = true
  try {
    await stopWikiReload()
  } catch {
    // Non-fatal — best-effort stop request
  } finally {
    wikiStopping.value = false
  }
}

async function handleChangePassword() {
  passwordError.value = null
  passwordFieldError.value = null

  // Client-side validation
  if (newPassword.value.length < 8) {
    passwordFieldError.value = 'Password must be at least 8 characters.'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    passwordFieldError.value = 'Passwords do not match.'
    return
  }

  passwordChanging.value = true
  try {
    // changePassword handles clearAuth() + navigateTo('/login') internally (D-05)
    await changePassword(currentPassword.value, newPassword.value)
  } catch (err: unknown) {
    const e = err as { data?: { message?: string } }
    passwordError.value = e?.data?.message ?? 'Something went wrong. Please try again.'
  } finally {
    passwordChanging.value = false
  }
}
</script>

<template>
  <div class="max-w-2xl mx-auto px-4 py-8">

    <!-- Section 1: Account -->
    <section id="account">
      <h1 class="text-xl font-semibold tracking-wide mb-6">Account</h1>

      <!-- Email confirmed banner (from ?emailConfirmed=true redirect) -->
      <div
        v-if="emailConfirmedBanner"
        class="mb-6 rounded-none border border-border bg-card px-4 py-3 text-sm text-foreground"
      >
        Your email address has been updated successfully.
      </div>

      <!-- Current email display -->
      <div class="mb-6">
        <p class="text-sm text-muted-foreground">Current email</p>
        <p class="text-sm font-medium text-foreground mt-1">{{ authStore.userEmail }}</p>
      </div>

      <!-- Email change form -->
      <div>
        <h2 class="text-base font-semibold tracking-widest uppercase mb-4">Change email</h2>
        <form novalidate @submit.prevent="handleChangeEmail">
          <div class="space-y-4">
            <FormField id="new-email" label="New email">
              <InputText
                id="new-email"
                v-model="newEmail"
                type="email"
                autocomplete="email"
                :disabled="emailChanging"
              />
            </FormField>
            <p
              v-if="emailChangeSuccess"
              class="text-sm text-foreground mt-2"
            >
              Check your inbox — click the link to confirm your new address.
            </p>
            <FormErrorBanner :message="emailError" />
            <ButtonPrimary :loading="emailChanging" :disabled="emailChanging">
              {{ emailChanging ? 'Updating...' : 'Update email' }}
            </ButtonPrimary>
          </div>
        </form>
      </div>

      <!-- Password change form -->
      <div class="mt-8">
        <h2 class="text-base font-semibold tracking-widest uppercase mb-4">Change password</h2>
        <form novalidate @submit.prevent="handleChangePassword">
          <div class="space-y-4">
            <FormField id="current-password" label="Current password">
              <InputText
                id="current-password"
                v-model="currentPassword"
                type="password"
                autocomplete="current-password"
                :disabled="passwordChanging"
              />
            </FormField>
            <FormField id="new-password" label="New password">
              <InputText
                id="new-password"
                v-model="newPassword"
                type="password"
                autocomplete="new-password"
                :disabled="passwordChanging"
              />
            </FormField>
            <FormField
              id="confirm-password"
              label="Confirm new password"
              :error="passwordFieldError ?? undefined"
            >
              <InputText
                id="confirm-password"
                v-model="confirmPassword"
                type="password"
                autocomplete="new-password"
                :disabled="passwordChanging"
              />
            </FormField>
            <FormErrorBanner :message="passwordError" />
            <ButtonPrimary :loading="passwordChanging" :disabled="passwordChanging">
              {{ passwordChanging ? 'Changing...' : 'Change password' }}
            </ButtonPrimary>
          </div>
        </form>
      </div>
    </section>

    <hr class="border-border my-8" >

    <!-- Section 2: API Keys -->
    <section id="api-keys">
      <h1 class="text-xl font-semibold tracking-wide mb-6">API Keys</h1>

      <!-- TMDB -->
      <div>
        <FormField id="tmdb-key" label="TMDB API key" :error="tmdbError ?? undefined">
          <div class="flex items-center gap-2">
            <InputText
              id="tmdb-key"
              v-model="tmdbKey"
              type="text"
              :disabled="keysLoading || tmdbSaving"
              :placeholder="keysLoading ? 'Loading...' : ''"
              class="flex-1"
            />
            <button
              type="button"
              :disabled="keysLoading || tmdbSaving || tmdbDeleting"
              class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleSaveTmdb"
            >
              {{ tmdbSaving ? 'Saving...' : 'Save' }}
            </button>
            <button
              v-if="tmdbKey"
              type="button"
              :disabled="keysLoading || tmdbSaving || tmdbDeleting"
              class="h-10 px-3 text-sm font-medium text-foreground border border-border rounded-none hover:bg-card disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleDeleteTmdb"
            >
              {{ tmdbDeleting ? '...' : 'Delete' }}
            </button>
          </div>
        </FormField>
        <p v-if="tmdbSaved" class="text-sm text-foreground mt-1">Saved</p>
      </div>

      <!-- OMDB -->
      <div class="mt-6">
        <FormField id="omdb-key" label="OMDB API key (optional)" :error="omdbError ?? undefined">
          <div class="flex items-center gap-2">
            <InputText
              id="omdb-key"
              v-model="omdbKey"
              type="text"
              :disabled="keysLoading || omdbSaving"
              :placeholder="keysLoading ? 'Loading...' : ''"
              class="flex-1"
            />
            <button
              type="button"
              :disabled="keysLoading || omdbSaving || omdbDeleting"
              class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleSaveOmdb"
            >
              {{ omdbSaving ? 'Saving...' : 'Save' }}
            </button>
            <button
              v-if="omdbKey"
              type="button"
              :disabled="keysLoading || omdbSaving || omdbDeleting"
              class="h-10 px-3 text-sm font-medium text-foreground border border-border rounded-none hover:bg-card disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleDeleteOmdb"
            >
              {{ omdbDeleting ? '...' : 'Delete' }}
            </button>
          </div>
        </FormField>
        <p v-if="omdbSaved" class="text-sm text-foreground mt-1">Saved</p>
      </div>
    </section>

    <hr class="border-border my-8" >

    <!-- Section 3: Import & Export -->
    <section id="import-export">
      <h1 class="text-xl font-semibold tracking-wide mb-6">Import &amp; Export</h1>
      <div class="flex gap-4">
        <ButtonPrimary type="button" :disabled="true">
          Export CSV
        </ButtonPrimary>
        <ButtonPrimary type="button" :disabled="true">
          Import CSV
        </ButtonPrimary>
      </div>
      <p class="text-sm text-muted-foreground mt-2">
        Coming soon — available after your first films are saved.
      </p>
    </section>

    <hr class="border-border my-8" >

    <!-- Section 4: Wikipedia Data -->
    <section id="wikipedia-data">
      <h1 class="text-xl font-semibold tracking-wide mb-6">Wikipedia Data</h1>
      <div class="flex items-center gap-2">
        <ButtonPrimary type="button" :loading="wikiReloadTriggering" :disabled="wikiReloadTriggering || (wikiProgress && !wikiProgress.complete)" @click="onTriggerWikiReload">
          {{ wikiReloadTriggering ? 'Starting...' : 'Reload missing Wikipedia data' }}
        </ButtonPrimary>
        <button
          v-if="wikiProgress && !wikiProgress.complete"
          type="button"
          data-testid="wiki-stop-button"
          :disabled="wikiStopping"
          class="h-10 px-4 text-sm font-medium text-foreground border border-border rounded-none hover:bg-card disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
          @click="onStopWikiReload"
        >
          {{ wikiStopping ? 'Stopping...' : 'Stop' }}
        </button>
      </div>
      <p v-if="wikiReloadMessage" class="text-sm text-foreground mt-2">{{ wikiReloadMessage }}</p>

      <div v-if="wikiProgress && !wikiProgress.complete" data-testid="wiki-reload-progress" class="mt-4 space-y-2">
        <p class="text-sm text-foreground">{{ wikiProgress.processed }} / {{ wikiProgress.total }} processed</p>
        <p v-if="wikiEtaLabel" class="text-sm text-muted-foreground">{{ wikiEtaLabel }}</p>
        <div class="w-full h-2 bg-card border border-border">
          <div class="h-full bg-primary" :style="{ width: `${wikiProgressPercent}%` }" />
        </div>
        <ul class="space-y-1">
          <li
            v-for="(entry, idx) in wikiMovieHistory"
            :key="idx"
            class="flex items-center gap-2 text-sm text-foreground"
          >
            <CheckCircle2 v-if="entry.status === 'SUCCESS'" class="w-4 h-4 shrink-0" />
            <XCircle v-else class="w-4 h-4 shrink-0" />
            <span>{{ entry.title }}</span>
          </li>
        </ul>
      </div>
    </section>

  </div>
</template>
