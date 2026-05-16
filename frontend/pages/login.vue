<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

definePageMeta({ layout: 'default' })

const authStore = useAuthStore()
// The middleware already redirects authenticated users away from /login on every navigation.
// This watch handles the edge case where the middleware runs before the cookie is evaluated
// (e.g. during the very first hydration tick). Since isAuthenticated is now cookie-backed,
// it is synchronously true on both SSR and client — immediate:true fires with the correct value.
watch(
  () => authStore.isAuthenticated,
  (authenticated) => { if (authenticated) navigateTo('/') },
  { immediate: true },
)

const { login } = useAuth()
const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)

// Handle ?emailError=... from backend email-confirm redirect when user is logged out.
// Error redirects go to /login because /settings is protected and the query param
// would be lost if the middleware redirected an unauthenticated user from /settings → /login.
onMounted(() => {
  if (route.query.emailError) {
    const code = route.query.emailError as string
    errorMessage.value =
      code === 'token-used' ? 'This confirmation link has already been used.' :
      code === 'token-expired' ? 'This confirmation link has expired. Request a new one.' :
      code === 'email-unavailable' ? 'This email address is no longer available. Please request a new change.' :
      'Invalid confirmation link.'
    router.replace({ query: {} })
  }
})

// Clear error on user input (D-08)
function clearError() {
  errorMessage.value = null
}

async function handleSubmit() {
  errorMessage.value = null
  loading.value = true
  try {
    await login(email.value, password.value)
  } catch (err: unknown) {
    const error = err as { status?: number; data?: { message?: string }; response?: { headers?: { get: (h: string) => string | null } } }
    if (error.status === 429) {
      const retryAfter = error.response?.headers?.get('Retry-After') ?? '60'
      errorMessage.value = `Too many attempts. Try again in ${retryAfter} seconds.`
    } else if (error.status === 403) {
      errorMessage.value = 'Please verify your email before signing in.'
    } else if (error.status === 401) {
      errorMessage.value = error.data?.message ?? 'Invalid email or password.'
    } else {
      errorMessage.value = 'Something went wrong. Please try again.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthCard heading="Sign in" subtext="Welcome back">
    <form @submit.prevent="handleSubmit" novalidate>
      <div class="space-y-4">
        <FormField id="email" label="Email">
          <InputText
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            :disabled="loading"
            @input="clearError"
          />
        </FormField>

        <FormField id="password" label="Password">
          <InputText
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            :disabled="loading"
            @input="clearError"
          />
          <div class="text-right mt-1">
            <NuxtLink to="/forgot-password" class="text-sm text-muted-foreground hover:underline">
              Forgot password?
            </NuxtLink>
          </div>
        </FormField>

        <FormErrorBanner :message="errorMessage" />

        <ButtonPrimary :loading="loading" :disabled="loading">
          {{ loading ? 'Signing in...' : 'Sign in' }}
        </ButtonPrimary>
      </div>
    </form>

    <div class="mt-4 text-center text-sm text-muted-foreground">
      Don't have an account?
      <NuxtLink to="/signup" class="text-foreground underline">Sign up</NuxtLink>
    </div>
  </AuthCard>
</template>
