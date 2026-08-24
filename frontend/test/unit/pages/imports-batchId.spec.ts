import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { BulkImportBatchDetail, BulkImportProgress } from '@/composables/useBulkImport'

// ── MOCK useBulkImport ──────────────────────────────────────────────────────
const mockSubscribeToProgress = vi.fn()
const mockGetBatchDetail = vi.fn()
const mockUnsubscribe = vi.fn()

let capturedOnProgress: ((p: BulkImportProgress) => void | Promise<void>) | undefined

vi.mock('@/composables/useBulkImport', () => ({
  useBulkImport: () => ({
    subscribeToProgress: mockSubscribeToProgress,
    getBatchDetail: mockGetBatchDetail,
    getBatches: vi.fn(),
  }),
}))

const MOCK_DETAIL: BulkImportBatchDetail = {
  batchId: 'batch-1',
  createdAt: '2026-08-24T10:00:00Z',
  totalLines: 2,
  lines: [
    { title: 'Inception', originalTitle: null, year: 2010, status: 'SAVED', posterPath: '/poster.jpg' },
    { title: 'Unknown Film', originalTitle: null, year: null, status: 'NOT_FOUND', posterPath: null },
  ],
}

async function mountPage() {
  const { default: BatchDetailPage } = await import('@/pages/imports/[batchId].vue')
  return mount(BatchDetailPage, {
    global: {
      stubs: {
        SpinnerIcon: { template: '<div data-testid="spinner"></div>' },
      },
    },
  })
}

describe('/imports/[batchId] page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    capturedOnProgress = undefined
    mockSubscribeToProgress.mockImplementation((_batchId: string, onProgress: (p: BulkImportProgress) => void | Promise<void>) => {
      capturedOnProgress = onProgress
      return mockUnsubscribe
    })
  })

  it('shows a connecting state before the first progress event arrives', async () => {
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('Connecting...')
  })

  it('shows processed/total text while progress is incomplete', async () => {
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 5, complete: false })
    await nextTick()
    expect(wrapper.text()).toContain('2 / 5 processed')
  })

  it('renders each line title and status once progress completes and detail has loaded', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    expect(mockGetBatchDetail).toHaveBeenCalled()
    expect(mockUnsubscribe).toHaveBeenCalled()
    expect(wrapper.text()).toContain('Inception')
    expect(wrapper.text()).toContain('Saved')
    expect(wrapper.text()).toContain('Unknown Film')
    expect(wrapper.text()).toContain('Not found')
  })

  it('renders a text-only fallback card (no broken img) for a line with posterPath null', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    const fallbacks = wrapper.findAll('[data-testid="poster-fallback"]')
    expect(fallbacks).toHaveLength(1)
    expect(fallbacks[0]!.text()).toContain('Unknown Film')

    const cards = wrapper.findAll('[data-testid="result-card"]')
    const notFoundCard = cards.find(c => c.text().includes('Unknown Film'))
    expect(notFoundCard?.find('img').exists()).toBe(false)
  })

  it('shows an error message when loading batch detail fails', async () => {
    mockGetBatchDetail.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    expect(wrapper.text()).toContain('Failed to load import report.')
  })

  it('unsubscribes from progress on unmount', async () => {
    const wrapper = await mountPage()
    wrapper.unmount()
    expect(mockUnsubscribe).toHaveBeenCalled()
  })
})
