<script setup lang="ts">
interface HistogramBucket {
  label: string
  count: number
}

const props = defineProps<{
  buckets: HistogramBucket[]
}>()

const maxCount = computed(() => {
  if (!props.buckets.length) return 1
  return Math.max(...props.buckets.map(b => b.count), 1)
})

const hasData = computed(() =>
  props.buckets.length > 0 && props.buckets.some(b => b.count > 0)
)
</script>

<template>
  <div>
    <p v-if="!hasData" class="text-sm text-muted-foreground">No rating data yet</p>
    <div v-else class="flex items-end gap-2 h-24">
      <div
        v-for="bucket in props.buckets"
        :key="bucket.label"
        class="flex flex-col items-center gap-1 flex-1"
      >
        <div class="w-full flex items-end h-20">
          <div
            class="w-full bg-primary"
            :style="{ height: `${(bucket.count / maxCount) * 100}%` }"
          />
        </div>
        <span class="text-xs text-muted-foreground">{{ bucket.label }}</span>
      </div>
    </div>
  </div>
</template>
