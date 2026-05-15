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
        // Overall gate: ≥ 70% (composables/stores target 80% — enforced in CI per-glob)
        lines: 70,
        branches: 70,
        functions: 70,
        statements: 70,
      },
    },
  },
})
