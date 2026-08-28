import { fetchEventSource } from '@microsoft/fetch-event-source'

// Types
export interface BulkImportLineResult {
  id: string
  title: string
  originalTitle: string | null
  year: number | null
  status: 'SAVED' | 'AMBIGUOUS' | 'NOT_FOUND' | 'PARSE_ERROR'
  posterPath: string | null
  movieId: string | null
  rawLine: string
}

export interface BulkImportBatchDetail {
  batchId: string
  createdAt: string
  totalLines: number
  lines: BulkImportLineResult[]
}

export interface BulkImportBatchSummary {
  batchId: string
  createdAt: string
  totalLines: number
  statusCounts: Record<string, number>
}

export interface BulkImportProgress {
  processed: number
  total: number
  complete: boolean
}

export function useBulkImport() {
  const accessTokenCookie = useCookie<string | null>('access_token')

  function authHeaders(): Record<string, string> {
    return accessTokenCookie.value
      ? { Authorization: `Bearer ${accessTokenCookie.value}` }
      : {}
  }

  async function getBatches(): Promise<BulkImportBatchSummary[]> {
    return await $fetch<BulkImportBatchSummary[]>('/api/movies/bulk-import/batches', {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function getBatchDetail(batchId: string): Promise<BulkImportBatchDetail> {
    return await $fetch<BulkImportBatchDetail>(`/api/movies/bulk-import/batches/${batchId}`, {
      credentials: 'include',
      headers: authHeaders(),
    })
  }

  async function resolveLine(
    batchId: string,
    lineId: string,
    tmdbId: number,
    posterPath: string | null,
  ): Promise<{ movieId: string }> {
    return await $fetch<{ movieId: string }>(
      `/api/movies/bulk-import/batches/${batchId}/lines/${lineId}/resolve`,
      {
        method: 'POST',
        body: { tmdbId, posterPath },
        credentials: 'include',
        headers: authHeaders(),
      },
    )
  }

  function subscribeToProgress(batchId: string, onProgress: (p: BulkImportProgress) => void): () => void {
    const ctrl = new AbortController()
    fetchEventSource(`/api/movies/bulk-import/${batchId}/progress`, {
      headers: authHeaders(),
      signal: ctrl.signal,
      async onopen() {
        // no-op: default fetch-event-source behavior already validates content-type on open
      },
      onmessage(ev) {
        if (ev.event === 'progress' || ev.event === 'complete') {
          onProgress(JSON.parse(ev.data) as BulkImportProgress)
        }
      },
      onerror(err) {
        // Stop the library's default retry-forever behavior on a fatal error (e.g. 403/404)
        throw err
      },
    })
    return () => ctrl.abort()
  }

  return { getBatches, getBatchDetail, subscribeToProgress, resolveLine }
}
