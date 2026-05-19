<script setup lang="ts">
import { ref } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

const { forgotPassword } = useAuth()

const email = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const submitted = ref(false)

function clearError() {
  errorMessage.value = null
}

async function handleSubmit() {
  errorMessage.value = null
  loading.value = true
  try {
    await forgotPassword(email.value)
    // Always show success after 200 (enumeration protection — AUTH-07)
    submitted.value = true
  } catch (err: unknown) {
    const error = err as { status?: number; response?: { headers?: { get: (h: string) => string | null } } }
    if (error.status === 429) {
      const retryAfter = error.response?.headers?.get('Retry-After') ?? '60'
      errorMessage.value = `Too many attempts. Try again in ${retryAfter} seconds.`
    } else {
      errorMessage.value = 'Something went wrong. Please try again.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthCard heading="Reset your password" subtext="Enter your email and we'll send you a reset link.">
    <div v-if="submitted" class="space-y-4">
      <p class="text-sm text-foreground">
        If an account exists for that email, a reset link is on its way.
      </p>
    </div>
    <form v-else novalidate @submit.prevent="handleSubmit">
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

        <FormErrorBanner :message="errorMessage" />

        <ButtonPrimary :loading="loading" :disabled="loading">
          {{ loading ? 'Sending...' : 'Send reset link' }}
        </ButtonPrimary>
      </div>
    </form>

    <div class="mt-6 text-center text-sm text-muted-foreground">
      <NuxtLink to="/login" class="text-foreground underline">Back to sign in</NuxtLink>
    </div>
  </AuthCard>
</template>
