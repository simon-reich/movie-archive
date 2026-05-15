<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AuthCard from '@/components/AuthCard.vue'
import ButtonPrimary from '@/components/ButtonPrimary.vue'

const route = useRoute()
const { verifyEmail } = useAuth()

type VerifyState = 'loading' | 'success' | 'error'
const state = ref<VerifyState>('loading')
const errorBody = ref<string>('Something went wrong. Please try again or contact support.')

onMounted(async () => {
  const token = route.query.token as string | undefined
  if (!token) {
    errorBody.value = 'Something went wrong. Please try again or contact support.'
    state.value = 'error'
    return
  }
  try {
    await verifyEmail(token)
    state.value = 'success'
  } catch (err: unknown) {
    const error = err as { data?: { message?: string } }
    const msg = error.data?.message ?? ''
    if (msg.toLowerCase().includes('expired')) {
      errorBody.value = 'This verification link has expired. Request a new one from the sign-in page.'
    } else if (msg.toLowerCase().includes('used') || msg.toLowerCase().includes('consumed')) {
      errorBody.value = 'This link has already been used. Your account may already be active.'
    } else {
      errorBody.value = 'Something went wrong. Please try again or contact support.'
    }
    state.value = 'error'
  }
})
</script>

<template>
  <AuthCard
    :heading="state === 'loading' ? 'Verifying your email...' : state === 'success' ? 'Email verified' : 'Verification failed'"
  >
    <div v-if="state === 'loading'" class="text-sm text-muted-foreground">
      Please wait...
    </div>

    <div v-else-if="state === 'success'" class="space-y-4">
      <p class="text-sm text-foreground">
        Your account is active. You can now sign in.
      </p>
      <NuxtLink to="/login">
        <ButtonPrimary type="button">Sign in</ButtonPrimary>
      </NuxtLink>
    </div>

    <div v-else class="space-y-4">
      <p class="text-sm text-foreground">{{ errorBody }}</p>
    </div>
  </AuthCard>
</template>
