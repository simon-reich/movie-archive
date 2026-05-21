<script setup lang="ts">
import { StarIcon } from 'lucide-vue-next'

const props = defineProps<{
  modelValue: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number | null]
}>()

function handleStarClick(star: number) {
  // Toggle off if same star clicked (deselect → null)
  const newRating = props.modelValue === star ? null : star
  emit('update:modelValue', newRating)
}

function isFilled(star: number): boolean {
  return props.modelValue !== null && star <= props.modelValue
}
</script>

<template>
  <div class="grid grid-cols-5 sm:grid-cols-10 md:grid-cols-5 gap-0.5" role="group" aria-label="Rating">
    <button
      v-for="star in 10"
      :key="star"
      type="button"
      class="p-0.5 cursor-pointer focus-visible:ring-2 ring-ring outline-none"
      :aria-label="`Rate ${star} out of 10`"
      @click="handleStarClick(star)"
    >
      <StarIcon
        class="w-5 h-5"
        :class="isFilled(star) ? 'text-primary fill-primary' : 'text-muted-foreground'"
      />
    </button>
  </div>
</template>
