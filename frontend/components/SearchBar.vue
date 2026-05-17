<script setup lang="ts">
import { X } from 'lucide-vue-next'
import InputText from '@/components/InputText.vue'

defineProps<{
  modelValue: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
</script>

<template>
  <div class="relative flex items-center">
    <InputText
      id="search-input"
      :model-value="modelValue"
      placeholder="Search your archive..."
      class="flex-1 pr-8"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <button
      v-show="modelValue"
      type="button"
      class="absolute right-2 text-muted-foreground hover:text-foreground"
      aria-label="Clear search"
      @click="emit('update:modelValue', '')"
    >
      <X class="w-4 h-4" />
    </button>
  </div>
</template>
