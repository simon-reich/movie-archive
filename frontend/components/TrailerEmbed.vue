<script setup lang="ts">
import { ref, computed } from 'vue'
import { PlayIcon } from 'lucide-vue-next'

const props = defineProps<{
  trailerKey: string | null | undefined
}>()

const trailerActive = ref(false)

const thumbnailUrl = computed(() =>
  props.trailerKey
    ? `https://img.youtube.com/vi/${props.trailerKey}/hqdefault.jpg`
    : null
)

const embedUrl = computed(() =>
  props.trailerKey
    ? `https://www.youtube.com/embed/${props.trailerKey}?autoplay=1`
    : null
)
</script>

<template>
  <div v-if="trailerKey" class="w-full">
    <!-- Thumbnail with play overlay — no YouTube iframe until click -->
    <div
      v-if="!trailerActive"
      class="relative cursor-pointer"
      @click="trailerActive = true"
    >
      <img
        :src="thumbnailUrl!"
        alt="Trailer thumbnail"
        class="w-full aspect-video object-cover"
      >
      <!-- Play button overlay (terracotta square — no rounded corners) -->
      <div class="absolute inset-0 flex items-center justify-center">
        <div class="w-12 h-12 bg-primary flex items-center justify-center">
          <PlayIcon class="w-6 h-6 text-primary-foreground ml-0.5" />
        </div>
      </div>
    </div>
    <!-- Iframe loads only after user clicks — autoplay=1 (D-11) -->
    <iframe
      v-else
      :src="embedUrl!"
      class="w-full aspect-video border-0"
      allow="autoplay; encrypted-media; picture-in-picture"
      allowfullscreen
      title="Film trailer"
    />
  </div>
</template>
