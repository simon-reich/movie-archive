import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    include: ['test/unit/**/*.spec.ts'],
    setupFiles: ['./test/setup.ts'],
    coverage: {
      reporter: ['text', 'json', 'html'],
      include: ['composables/**', 'stores/**', 'pages/**', 'components/**'],
      thresholds: {
        // Skeleton phase: 0% — bump to 70% (components) / 80% (composables+stores) once features land
        lines: 0,
        branches: 0,
        functions: 0,
        statements: 0,
      },
    },
  },
})
