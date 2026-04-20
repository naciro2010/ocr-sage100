import type { AiSettingsResponse, OcrSettingsResponse } from './types'

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

// --- AI Settings ---

export async function getAiSettings(): Promise<AiSettingsResponse> {
  const res = await fetch(`${API_URL}/api/settings/ai`, { headers: authHeaders() })
  return handleResponse(res)
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
  const res = await fetch(`${API_URL}/api/settings/ocr`, { headers: authHeaders() })
  return handleResponse(res)
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
