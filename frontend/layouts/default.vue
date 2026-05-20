<script setup lang="ts">
// The middleware already enforces auth — if you can reach a protected route,
// you are logged in. Gate AppNav on the route, not the in-memory store
// (store is empty during SSR and hydration, causing persistent nav flicker).
const publicRoutes = [
  '/login', '/signup', '/verify-email', '/verify-email-sent',
  '/forgot-password', '/reset-password',
]
const route = useRoute()
const showNav = computed(() => !publicRoutes.includes(route.path))

// Hero height (h-72 = 288px) minus nav height (48px) = threshold where
// the hero ends and dark content begins.
const HERO_THRESHOLD = 240
const scrollY = ref(0)

function onScroll() {
  scrollY.value = window.scrollY
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))
onUnmounted(() => window.removeEventListener('scroll', onScroll))

// Reset scroll when navigating to a new page
watch(() => route.path, () => { scrollY.value = 0 })

const navOverlay = computed(() =>
  route.path.startsWith('/movies/') && scrollY.value < HERO_THRESHOLD
)
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <AppNav v-if="showNav" :overlay="navOverlay" />
    <div :class="showNav ? 'pt-12' : ''">
      <slot />
    </div>
  </div>
</template>
