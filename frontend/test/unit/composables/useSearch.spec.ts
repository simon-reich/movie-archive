import { describe, it } from 'vitest'

describe('useSearch composable', () => {
  it.todo('reads q param from URL on mount')
  it.todo('reads genre param (single and array) from URL and normalizes to array')
  it.todo('reads director param from URL')
  it.todo('debounces query changes by ~300ms before updating URL')
  it.todo('appends results on load-more (page > 0), replaces on page 0')
  it.todo('re-executes search when route query changes (clickable attribute navigation)')
})
