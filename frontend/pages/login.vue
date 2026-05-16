<script setup lang="ts">
import { ref } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

definePageMeta({ layout: 'default' })

const authStore = useAuthStore()
// Watch for token — handles both immediate (store already populated)
// and deferred (auth plugin sets token after this setup runs on reload)
watch(
  () => authStore.accessToken,
  (token) => { if (token) navigateTo('/') },
  { immediate: true },
)

const { login } = useAuth()

const email = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)

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
