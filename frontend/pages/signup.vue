<script setup lang="ts">
import { ref } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

const { signup } = useAuth()

const email = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const emailError = ref<string | null>(null)
const passwordError = ref<string | null>(null)

function clearFieldError(field: 'email' | 'password') {
  if (field === 'email') emailError.value = null
  if (field === 'password') passwordError.value = null
  errorMessage.value = null
}

function validate(): boolean {
  let valid = true
  emailError.value = null
  passwordError.value = null
  if (!email.value || !/.+@.+\..+/.test(email.value)) {
    emailError.value = 'Enter a valid email address.'
    valid = false
  }
  if (!password.value || password.value.length < 8) {
    passwordError.value = 'Password must be at least 8 characters.'
    valid = false
  }
  return valid
}

async function handleSubmit() {
  if (!validate()) return
  errorMessage.value = null
  loading.value = true
  try {
    await signup(email.value, password.value)
  } catch (err: unknown) {
    const error = err as { status?: number; data?: { message?: string } }
    if (error.status === 409) {
      errorMessage.value = error.data?.message ?? 'An account with this email already exists.'
    } else {
      errorMessage.value = 'Something went wrong. Please try again.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthCard heading="Create an account" subtext="Enter your details to get started">
    <form novalidate @submit.prevent="handleSubmit">
      <div class="space-y-4">
        <FormField id="email" label="Email" :error="emailError ?? undefined">
          <InputText
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            :has-error="!!emailError"
            :disabled="loading"
            :aria-describedby="emailError ? 'email-error' : undefined"
            @input="clearFieldError('email')"
          />
        </FormField>

        <FormField id="password" label="Password" :error="passwordError ?? undefined">
          <InputText
            id="password"
            v-model="password"
            type="password"
            autocomplete="new-password"
            :has-error="!!passwordError"
            :disabled="loading"
            :aria-describedby="passwordError ? 'password-error' : undefined"
            @input="clearFieldError('password')"
          />
        </FormField>

        <FormErrorBanner :message="errorMessage" />

        <ButtonPrimary :loading="loading" :disabled="loading">
          {{ loading ? 'Creating account...' : 'Create account' }}
        </ButtonPrimary>
      </div>
    </form>

    <div class="mt-4 text-center text-sm text-muted-foreground">
      Already have an account?
      <NuxtLink to="/login" class="text-foreground underline">Sign in</NuxtLink>
    </div>
  </AuthCard>
</template>
