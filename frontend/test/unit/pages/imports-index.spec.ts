import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { BulkImportBatchSummary } from '@/composables/useBulkImport'

const NUXT_LINK_STUB = {
  template: '<a :href="to"><slot /></a>',
  props: ['to'],
}

// ── MOCK useBulkImport ──────────────────────────────────────────────────────
const mockGetBatches = vi.fn()

vi.mock('@/composables/useBulkImport', () => ({
  useBulkImport: () => ({
    getBatches: mockGetBatches,
    getBatchDetail: vi.fn(),
    subscribeToProgress: vi.fn(),
  }),
}))

const MOCK_BATCHES: BulkImportBatchSummary[] = [
  {
    batchId: 'batch-2',
    createdAt: '2026-08-24T12:00:00Z',
    totalLines: 15,
    statusCounts: { SAVED: 12, AMBIGUOUS: 2, NOT_FOUND: 1, PARSE_ERROR: 0 },
  },
  {
    batchId: 'batch-1',
    createdAt: '2026-08-20T09:00:00Z',
    totalLines: 3,
    statusCounts: { SAVED: 3, AMBIGUOUS: 0, NOT_FOUND: 0, PARSE_ERROR: 0 },
  },
]

async function mountPage() {
  const { default: BatchListPage } = await import('@/pages/imports/index.vue')
  return mount(BatchListPage, {
    global: {
      stubs: {
        SpinnerIcon: { template: '<div data-testid="spinner"></div>' },
        NuxtLink: NUXT_LINK_STUB,
      },
    },
  })
}

describe('/imports batch list page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a loading state before batches resolve', async () => {
    mockGetBatches.mockReturnValueOnce(new Promise(() => {}))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="spinner"]').exists()).toBe(true)
  })

  it('renders each batch row linking to its own detail page, newest first as returned', async () => {
    mockGetBatches.mockResolvedValueOnce(MOCK_BATCHES)
    const wrapper = await mountPage()
    await nextTick()
    await nextTick()

    const rows = wrapper.findAll('[data-testid="batch-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.attributes('href')).toBe('/imports/batch-2')
    expect(rows[1]!.attributes('href')).toBe('/imports/batch-1')
  })

  it('renders a compact status summary per batch, omitting zero counts', async () => {
    mockGetBatches.mockResolvedValueOnce(MOCK_BATCHES)
    const wrapper = await mountPage()
    await nextTick()
    await nextTick()

    const text = wrapper.text()
    expect(text).toContain('12 saved')
    expect(text).toContain('2 ambiguous')
    expect(text).toContain('1 not found')
    expect(text).not.toContain('0 parse error')
  })

  it('shows an error state when loading batches fails', async () => {
    mockGetBatches.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage()
    await nextTick()
    await nextTick()

    expect(wrapper.text()).toContain('Failed to load import history. Please refresh.')
  })

  it('shows an empty state with a link to Add Film when there are no batches', async () => {
    mockGetBatches.mockResolvedValueOnce([])
    const wrapper = await mountPage()
    await nextTick()
    await nextTick()

    expect(wrapper.text()).toContain('No bulk imports yet.')
    const link = wrapper.find('a[href="/add"]')
    expect(link.exists()).toBe(true)
  })
})
