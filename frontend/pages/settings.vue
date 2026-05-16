<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

const { saveApiKey, loadApiKeys, changePassword, changeEmail } = useSettings()
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

// Handle ?emailConfirmed=true / ?emailError=... from backend redirect
onMounted(() => {
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
})

// Load API keys as soon as accessToken is available.
// Using watch with immediate:true handles both cases:
// - token already in store (client-side navigation, store preserved)
// - token set after page load (F5 / direct URL — auth plugin sets it async)
watch(
  () => authStore.accessToken,
  async (token) => {
    if (!token) return
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
  },
  { immediate: true },
)

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
  emailChanging.value = true
  emailError.value = null
  emailChangeSuccess.value = false
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
        <form @submit.prevent="handleChangeEmail" novalidate>
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
        <form @submit.prevent="handleChangePassword" novalidate>
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

    <hr class="border-border my-8" />

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
              :disabled="keysLoading || tmdbSaving"
              class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleSaveTmdb"
            >
              {{ tmdbSaving ? 'Saving...' : 'Save' }}
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
              :disabled="keysLoading || omdbSaving"
              class="h-10 px-4 text-sm font-medium bg-primary text-primary-foreground rounded-none hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              @click="handleSaveOmdb"
            >
              {{ omdbSaving ? 'Saving...' : 'Save' }}
            </button>
          </div>
        </FormField>
        <p v-if="omdbSaved" class="text-sm text-foreground mt-1">Saved</p>
      </div>
    </section>

    <hr class="border-border my-8" />

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

  </div>
</template>
