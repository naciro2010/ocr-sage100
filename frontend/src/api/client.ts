import type { AiSettingsResponse } from './types'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

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
  const res = await fetch(`${API_URL}/api/settings/ai`)
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
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  return handleResponse(res)
}
