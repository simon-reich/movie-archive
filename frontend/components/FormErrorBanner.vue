<script setup lang="ts">
defineProps<{
  message: string | null
}>()
</script>

<template>
  <div
    v-if="message"
    role="alert"
    class="border-l-2 border-[#7A3520] pl-3 py-2 text-sm text-[#7A3520] bg-transparent"
  >
    {{ message }}
  </div>
</template>
