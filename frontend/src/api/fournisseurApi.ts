import type { FournisseurDetail, FournisseurSummary, FournisseursStats } from './fournisseurTypes'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/fournisseurs`

function authHeaders(): Record<string, string> {
  const auth = localStorage.getItem('recondoc_auth')
  return auth ? { 'Authorization': `Basic ${auth}` } : {}
}

function apiFetch(url: string, init?: RequestInit): Promise<Response> {
  const headers = { ...authHeaders(), ...(init?.headers || {}) }
  return fetch(url, { ...init, headers })
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

export async function listFournisseurs(q?: string, signal?: AbortSignal): Promise<FournisseurSummary[]> {
  const url = q ? `${BASE}?q=${encodeURIComponent(q)}` : BASE
  const res = await apiFetch(url, { signal })
  return handleResponse(res)
}

export async function getFournisseursStats(signal?: AbortSignal): Promise<FournisseursStats> {
  const res = await apiFetch(`${BASE}/stats`, { signal })
  return handleResponse(res)
}

export async function getFournisseurDetail(nom: string, signal?: AbortSignal): Promise<FournisseurDetail> {
  const res = await apiFetch(`${BASE}/${encodeURIComponent(nom)}`, { signal })
  return handleResponse(res)
}
