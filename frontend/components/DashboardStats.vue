<script setup lang="ts">
const props = defineProps<{
  totalFilms: number
  topGenres: { name: string; count: number }[]
  languageBreakdown: { code: string; count: number }[]
}>()
</script>

<template>
  <div v-if="props.totalFilms === 0" class="flex flex-col items-center justify-center py-12 gap-4 text-center">
    <p class="text-muted-foreground">No films yet</p>
    <NuxtLink
      to="/add"
      class="px-5 py-2 bg-primary text-primary-foreground text-sm font-semibold hover:opacity-90"
    >
      Add your first film
    </NuxtLink>
  </div>

  <div v-else class="flex flex-col sm:flex-row gap-4">
    <!-- Total Films -->
    <div class="flex-1 bg-card border border-border p-4">
      <p class="text-xs tracking-widest uppercase text-muted-foreground mb-2">Total Films</p>
      <p class="text-4xl font-bold text-foreground">{{ props.totalFilms }}</p>
    </div>

    <!-- Top Genres -->
    <div class="flex-1 bg-card border border-border p-4">
      <p class="text-xs tracking-widest uppercase text-muted-foreground mb-2">Top Genres</p>
      <ul class="space-y-1">
        <li
          v-for="genre in props.topGenres.slice(0, 5)"
          :key="genre.name"
          class="text-sm text-foreground"
        >
          {{ genre.name }} ({{ genre.count }})
        </li>
      </ul>
    </div>

    <!-- Languages -->
    <div class="flex-1 bg-card border border-border p-4">
      <p class="text-xs tracking-widest uppercase text-muted-foreground mb-2">Languages</p>
      <ul class="space-y-1">
        <li
          v-for="lang in props.languageBreakdown.slice(0, 5)"
          :key="lang.code"
          class="text-sm text-foreground"
        >
          {{ lang.code }} ({{ lang.count }})
        </li>
      </ul>
    </div>
  </div>
</template>
