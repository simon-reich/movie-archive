import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { BulkImportBatchDetail, BulkImportProgress } from '@/composables/useBulkImport'

// ── MOCK useBulkImport ──────────────────────────────────────────────────────
const mockSubscribeToProgress = vi.fn()
const mockGetBatchDetail = vi.fn()
const mockUnsubscribe = vi.fn()
const mockResolveLine = vi.fn()

let capturedOnProgress: ((p: BulkImportProgress) => void | Promise<void>) | undefined

vi.mock('@/composables/useBulkImport', () => ({
  useBulkImport: () => ({
    subscribeToProgress: mockSubscribeToProgress,
    getBatchDetail: mockGetBatchDetail,
    getBatches: vi.fn(),
    resolveLine: mockResolveLine,
  }),
}))

// ── MOCK useMovies ───────────────────────────────────────────────────────────
const mockSearchTmdb = vi.fn()

vi.mock('@/composables/useMovies', () => ({
  useMovies: () => ({
    searchTmdb: mockSearchTmdb,
  }),
}))

const NUXT_LINK_STUB = {
  template: '<a :href="to"><slot /></a>',
  props: ['to'],
}

const MOCK_DETAIL: BulkImportBatchDetail = {
  batchId: 'batch-1',
  createdAt: '2026-08-24T10:00:00Z',
  totalLines: 2,
  lines: [
    { id: 'line-1', title: 'Inception', originalTitle: null, year: 2010, status: 'SAVED', posterPath: '/poster.jpg', movieId: 'movie-1', rawLine: 'Inception;;2010' },
    { id: 'line-2', title: 'Unknown Film', originalTitle: null, year: null, status: 'NOT_FOUND', posterPath: null, movieId: null, rawLine: 'Unknown Film;;9999' },
  ],
}

const MOCK_DETAIL_WITH_PARSE_ERROR: BulkImportBatchDetail = {
  batchId: 'batch-2',
  createdAt: '2026-08-24T10:00:00Z',
  totalLines: 1,
  lines: [
    { id: 'line-3', title: 'BadLine', originalTitle: null, year: null, status: 'PARSE_ERROR', posterPath: null, movieId: null, rawLine: 'BadLine;;notayear' },
  ],
}

const MOCK_DETAIL_AMBIGUOUS: BulkImportBatchDetail = {
  batchId: 'batch-3',
  createdAt: '2026-08-28T10:00:00Z',
  totalLines: 1,
  lines: [
    { id: 'line-4', title: 'Robin Hood', originalTitle: null, year: 2010, status: 'AMBIGUOUS', posterPath: null, movieId: null, rawLine: 'Robin Hood;;2010' },
  ],
}

// happy-dom's test environment does not provide a real localStorage global — stub an
// in-memory implementation so the page's onMounted() read/write calls don't throw.
function createLocalStorageStub(): Storage {
  const store = new Map<string, string>()
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, value)
    },
    removeItem: (key: string) => {
      store.delete(key)
    },
    clear: () => store.clear(),
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    get length() {
      return store.size
    },
  } as Storage
}

async function mountPage() {
  const { default: BatchDetailPage } = await import('@/pages/imports/[batchId].vue')
  return mount(BatchDetailPage, {
    global: {
      stubs: {
        SpinnerIcon: { template: '<div data-testid="spinner"></div>' },
        NuxtLink: NUXT_LINK_STUB,
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
    vi.stubGlobal('localStorage', createLocalStorageStub())
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

  it('wraps a SAVED line in a whole-card link to /movies/{movieId}', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    const links = wrapper.findAll('a')
    const savedLink = links.find(a => (a.attributes('href') ?? '').includes('/movies/movie-1'))
    expect(savedLink).toBeDefined()
  })

  it('does not render a movie link for an AMBIGUOUS/NOT_FOUND line', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    const links = wrapper.findAll('a')
    const movieLinks = links.filter(a => (a.attributes('href') ?? '').includes('/movies/'))
    expect(movieLinks).toHaveLength(1)
    expect(movieLinks[0]!.attributes('href')).toContain('/movies/movie-1')
  })

  it('renders a PARSE_ERROR line with its distinct testid and raw line text', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL_WITH_PARSE_ERROR)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    const parseErrorCard = wrapper.find('[data-testid="parse-error-card"]')
    expect(parseErrorCard.exists()).toBe(true)

    const rawLineText = wrapper.find('[data-testid="raw-line-text"]')
    expect(rawLineText.exists()).toBe(true)
    expect(rawLineText.text()).toBe('BadLine;;notayear')
  })

  it('renders grid view by default when no localStorage entry is present', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    expect(wrapper.findAll('[data-testid="result-card"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="view-list-row"]')).toHaveLength(0)
  })

  it('switches to list view when the ViewToggle list button is clicked', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    const listButton = wrapper.find('[aria-label="List view"]')
    expect(listButton.exists()).toBe(true)
    await listButton.trigger('click')
    await nextTick()

    const rows = wrapper.findAll('[data-testid="view-list-row"]')
    expect(rows).toHaveLength(MOCK_DETAIL.lines.length)
    expect(rows[0]!.text()).toContain('Saved')
    expect(rows[1]!.text()).toContain('Not found')
  })

  it('renders list view immediately when localStorage has bulk-import-view-mode=list', async () => {
    localStorage.setItem('bulk-import-view-mode', 'list')
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 2, total: 2, complete: true })
    await nextTick()
    await nextTick()

    expect(wrapper.findAll('[data-testid="view-list-row"]')).toHaveLength(MOCK_DETAIL.lines.length)
    expect(wrapper.findAll('[data-testid="result-card"]')).toHaveLength(0)
  })

  // ── D-08/D-11: inline resolve widget ────────────────────────────────────

  it('renders a resolve-toggle on an AMBIGUOUS line but not on a PARSE_ERROR line', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL_AMBIGUOUS)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    expect(wrapper.find('[data-testid="resolve-toggle"]').exists()).toBe(true)
  })

  it('does not render a resolve-toggle on a PARSE_ERROR line', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL_WITH_PARSE_ERROR)
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    expect(wrapper.find('[data-testid="resolve-toggle"]').exists()).toBe(false)
  })

  it('expanding the resolve widget runs a fresh TMDB search prefilled with the line title and renders candidates', async () => {
    mockGetBatchDetail.mockResolvedValueOnce(MOCK_DETAIL_AMBIGUOUS)
    mockSearchTmdb.mockResolvedValueOnce([
      { tmdbId: 1002, title: 'Robin Hood', year: 2010, posterPath: '/robinhood2.jpg' },
    ])
    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    await wrapper.find('[data-testid="resolve-toggle"]').trigger('click')
    await nextTick()
    await nextTick()

    expect(mockSearchTmdb).toHaveBeenCalledWith('Robin Hood')
    expect(wrapper.findAll('[data-testid="resolve-candidate"]')).toHaveLength(1)
  })

  it('clicking a candidate calls resolveLine with the picked tmdbId/posterPath, then refetches the batch', async () => {
    mockGetBatchDetail
      .mockResolvedValueOnce(MOCK_DETAIL_AMBIGUOUS)
      .mockResolvedValueOnce({
        ...MOCK_DETAIL_AMBIGUOUS,
        lines: [{ ...MOCK_DETAIL_AMBIGUOUS.lines[0]!, status: 'SAVED', movieId: 'movie-9' }],
      })
    mockSearchTmdb.mockResolvedValueOnce([
      { tmdbId: 1002, title: 'Robin Hood', year: 2010, posterPath: '/robinhood2.jpg' },
    ])
    mockResolveLine.mockResolvedValueOnce({ movieId: 'movie-9' })

    const wrapper = await mountPage()
    await capturedOnProgress?.({ processed: 1, total: 1, complete: true })
    await nextTick()
    await nextTick()

    await wrapper.find('[data-testid="resolve-toggle"]').trigger('click')
    await nextTick()
    await nextTick()

    await wrapper.find('[data-testid="resolve-candidate"]').trigger('click')
    await nextTick()
    await nextTick()
    await nextTick()

    expect(mockResolveLine).toHaveBeenCalledTimes(1)
    const [, calledLineId, calledTmdbId, calledPosterPath] = mockResolveLine.mock.calls[0]!
    expect(calledLineId).toBe('line-4')
    expect(calledTmdbId).toBe(1002)
    expect(calledPosterPath).toBe('/robinhood2.jpg')
    // D-09: the refetch is distinguishable from the initial mount-time getBatchDetail call.
    expect(mockGetBatchDetail).toHaveBeenCalledTimes(2)
  })
})
