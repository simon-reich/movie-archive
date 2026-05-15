<template>
  <AuthCard heading="Check your inbox">
    <div class="space-y-4">
      <p class="text-sm text-foreground">
        We sent a verification link to your email address. Click the link to activate your account.
      </p>
      <p class="text-sm text-muted-foreground">
        If you don't see it, check your spam folder.
      </p>
    </div>
    <div class="mt-6 text-center text-sm text-muted-foreground">
      <NuxtLink to="/login" class="text-foreground underline">Back to sign in</NuxtLink>
    </div>
  </AuthCard>
</template>
