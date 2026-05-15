<script setup lang="ts">
import { ref } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import FormField from '@/components/FormField.vue'
import InputText from '@/components/InputText.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'
import FormErrorBanner from '@/components/FormErrorBanner.vue'

const route = useRoute()
const { resetPassword } = useAuth()

const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const errorMessage = ref<string | null>(null)
const passwordError = ref<string | null>(null)
const confirmError = ref<string | null>(null)
const success = ref(false)

function clearFieldError(field: 'password' | 'confirm') {
  if (field === 'password') passwordError.value = null
  if (field === 'confirm') confirmError.value = null
  errorMessage.value = null
}

function validate(): boolean {
  let valid = true
  passwordError.value = null
  confirmError.value = null
  if (!newPassword.value || newPassword.value.length < 8) {
    passwordError.value = 'Password must be at least 8 characters.'
    valid = false
  }
  if (newPassword.value !== confirmPassword.value) {
    confirmError.value = 'Passwords do not match.'
    valid = false
  }
  return valid
}

async function handleSubmit() {
  if (!validate()) return
  const token = route.query.token as string | undefined
  if (!token) {
    errorMessage.value = 'Invalid reset link.'
    return
  }
  errorMessage.value = null
  loading.value = true
  try {
    await resetPassword(token, newPassword.value)
    success.value = true
  } catch (err: unknown) {
    const error = err as { data?: { message?: string } }
    const msg = error.data?.message ?? ''
    if (msg.toLowerCase().includes('expired')) {
      errorMessage.value = 'This reset link has expired. Request a new one.'
    } else if (msg.toLowerCase().includes('used') || msg.toLowerCase().includes('consumed')) {
      errorMessage.value = 'This link has already been used.'
    } else {
      errorMessage.value = 'Something went wrong. Please try again.'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthCard heading="Set new password" subtext="Choose a strong password for your account.">
    <div v-if="success" class="space-y-4">
      <p class="text-sm text-foreground">
        Password updated. You can now sign in with your new password.
      </p>
      <NuxtLink to="/login">
        <ButtonPrimary type="button">Sign in</ButtonPrimary>
      </NuxtLink>
    </div>

    <form v-else @submit.prevent="handleSubmit" novalidate>
      <div class="space-y-4">
        <FormField id="new-password" label="New password" :error="passwordError ?? undefined">
          <InputText
            id="new-password"
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            :has-error="!!passwordError"
            :disabled="loading"
            :aria-describedby="passwordError ? 'new-password-error' : undefined"
            @input="clearFieldError('password')"
          />
        </FormField>

        <FormField id="confirm-password" label="Confirm password" :error="confirmError ?? undefined">
          <InputText
            id="confirm-password"
            v-model="confirmPassword"
            type="password"
            autocomplete="new-password"
            :has-error="!!confirmError"
            :disabled="loading"
            :aria-describedby="confirmError ? 'confirm-password-error' : undefined"
            @input="clearFieldError('confirm')"
          />
        </FormField>

        <FormErrorBanner :message="errorMessage" />

        <ButtonPrimary :loading="loading" :disabled="loading">
          {{ loading ? 'Resetting...' : 'Reset password' }}
        </ButtonPrimary>
      </div>
    </form>
  </AuthCard>
</template>
