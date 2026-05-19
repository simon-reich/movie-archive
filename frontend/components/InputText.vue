<script setup lang="ts">
defineProps<{
  id: string
  modelValue: string
  type?: string
  placeholder?: string
  disabled?: boolean
  hasError?: boolean
  autocomplete?: string
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <input
    :id="id"
    :type="type ?? 'text'"
    :value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    :autocomplete="autocomplete"
    :aria-invalid="hasError ? 'true' : undefined"
    class="w-full border bg-background text-sm rounded-none h-10 px-3 outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
    :class="hasError ? 'border-[#7A3520]' : 'border-input'"
    @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
  >
</template>
