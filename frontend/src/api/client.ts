import type { AiSettingsResponse, OcrSettingsResponse, SystemHealthResponse } from './types'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

function authHeaders(): Record<string, string> {
  const auth = localStorage.getItem('recondoc_auth')
  return auth ? { 'Authorization': `Basic ${auth}` } : {}
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message: string
    try {
      const body = await res.json()
      message = body.message || body.error || `HTTP ${res.status}`
    } catch {
      message = `HTTP ${res.status} ${res.statusText}`
    }
    throw new Error(message)
  }
  return res.json()
}

// Deduplication d'inflight pour les GET de settings : Settings + Layout
// peuvent les demander en parallele au mount, on ne veut qu'un seul appel.
const settingsInflight = new Map<string, Promise<unknown>>()
function dedupedGet<T>(url: string): Promise<T> {
  const existing = settingsInflight.get(url)
  if (existing) return existing as Promise<T>
  const p = fetch(url, { headers: authHeaders() })
    .then(res => handleResponse<T>(res))
    .finally(() => settingsInflight.delete(url))
  settingsInflight.set(url, p)
  return p
}

// --- AI Settings ---

export async function getAiSettings(): Promise<AiSettingsResponse> {
  return dedupedGet<AiSettingsResponse>(`${API_URL}/api/settings/ai`)
}

export async function saveAiSettings(settings: {
  enabled: boolean
  apiKey?: string
  model?: string
  baseUrl?: string
}): Promise<AiSettingsResponse> {
  const res = await fetch(`${API_URL}/api/settings/ai`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(settings),
  })
  return handleResponse(res)
}

// --- OCR Settings ---

export async function getOcrSettings(): Promise<OcrSettingsResponse> {
  return dedupedGet<OcrSettingsResponse>(`${API_URL}/api/settings/ocr`)
}

export async function saveOcrSettings(settings: {
  mistralEnabled?: boolean
  mistralApiKey?: string
  mistralModel?: string
  mistralBaseUrl?: string
}): Promise<OcrSettingsResponse> {
  const res = await fetch(`${API_URL}/api/settings/ocr`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(settings),
  })
  return handleResponse(res)
}

// --- System Health ---

export async function getSystemHealth(): Promise<SystemHealthResponse> {
  return dedupedGet<SystemHealthResponse>(`${API_URL}/api/admin/system/health`)
}
