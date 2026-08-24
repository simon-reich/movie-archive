import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockFetch = vi.fn()
vi.stubGlobal('$fetch', mockFetch)

const mockFetchEventSource = vi.fn()
vi.mock('@microsoft/fetch-event-source', () => ({
  fetchEventSource: (...args: unknown[]) => mockFetchEventSource(...args),
}))

const { useBulkImport } = await import('@/composables/useBulkImport')

describe('useBulkImport composable', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetchEventSource.mockReset()
  })

  it('getBatches returns array of BulkImportBatchSummary on success', async () => {
    mockFetch.mockResolvedValueOnce([
      {
        batchId: 'batch-1',
        createdAt: '2026-08-24T10:00:00Z',
        totalLines: 3,
        statusCounts: { SAVED: 2, AMBIGUOUS: 1 },
      },
    ])
    const { getBatches } = useBulkImport()
    const results = await getBatches()
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/bulk-import/batches',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(results).toHaveLength(1)
    expect(results[0].batchId).toBe('batch-1')
    expect(results[0].statusCounts.SAVED).toBe(2)
  })

  it('getBatchDetail returns BulkImportBatchDetail for given batchId', async () => {
    mockFetch.mockResolvedValueOnce({
      batchId: 'batch-1',
      createdAt: '2026-08-24T10:00:00Z',
      totalLines: 2,
      lines: [
        { title: 'Inception', originalTitle: null, year: 2010, status: 'SAVED', posterPath: '/poster.jpg' },
        { title: 'Unknown Film', originalTitle: null, year: null, status: 'NOT_FOUND', posterPath: null },
      ],
    })
    const { getBatchDetail } = useBulkImport()
    const detail = await getBatchDetail('batch-1')
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/movies/bulk-import/batches/batch-1',
      expect.objectContaining({ credentials: 'include' })
    )
    expect(detail.batchId).toBe('batch-1')
    expect(detail.lines).toHaveLength(2)
    expect(detail.lines[0].status).toBe('SAVED')
    expect(detail.lines[1].posterPath).toBeNull()
  })

  it('subscribeToProgress opens an SSE connection with auth headers and parses progress payloads', async () => {
    let capturedOnMessage: ((ev: { event: string, data: string }) => void) | undefined
    mockFetchEventSource.mockImplementation((url: string, opts: Record<string, unknown>) => {
      capturedOnMessage = opts.onmessage as typeof capturedOnMessage
    })

    const { subscribeToProgress } = useBulkImport()
    const onProgress = vi.fn()
    const unsubscribe = subscribeToProgress('batch-1', onProgress)

    expect(mockFetchEventSource).toHaveBeenCalledWith(
      '/api/movies/bulk-import/batch-1/progress',
      expect.objectContaining({ headers: expect.any(Object), signal: expect.any(Object) })
    )

    capturedOnMessage?.({ event: 'progress', data: JSON.stringify({ processed: 2, total: 5, complete: false }) })
    expect(onProgress).toHaveBeenCalledWith({ processed: 2, total: 5, complete: false })

    capturedOnMessage?.({ event: 'complete', data: JSON.stringify({ processed: 5, total: 5, complete: true }) })
    expect(onProgress).toHaveBeenCalledWith({ processed: 5, total: 5, complete: true })

    expect(typeof unsubscribe).toBe('function')
  })

  it('subscribeToProgress ignores messages with an unrecognized event name', async () => {
    let capturedOnMessage: ((ev: { event: string, data: string }) => void) | undefined
    mockFetchEventSource.mockImplementation((url: string, opts: Record<string, unknown>) => {
      capturedOnMessage = opts.onmessage as typeof capturedOnMessage
    })

    const { subscribeToProgress } = useBulkImport()
    const onProgress = vi.fn()
    subscribeToProgress('batch-1', onProgress)

    capturedOnMessage?.({ event: 'ping', data: '{}' })
    expect(onProgress).not.toHaveBeenCalled()
  })

  it('subscribeToProgress onerror rethrows to stop the library retry-forever behavior', async () => {
    let capturedOnError: ((err: unknown) => void) | undefined
    mockFetchEventSource.mockImplementation((url: string, opts: Record<string, unknown>) => {
      capturedOnError = opts.onerror as typeof capturedOnError
    })

    const { subscribeToProgress } = useBulkImport()
    subscribeToProgress('batch-1', vi.fn())

    expect(() => capturedOnError?.(new Error('fatal'))).toThrow('fatal')
  })
})
