import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['test/unit/**/*.spec.ts'],
    coverage: {
      reporter: ['text', 'json', 'html'],
      include: ['composables/**', 'stores/**', 'pages/**', 'components/**'],
    },
  },
})
