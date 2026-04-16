import { API_URL, apiFetch, handleResponse } from './http'
import type { FournisseurDetail, FournisseurSummary, FournisseursStats } from './fournisseurTypes'

const BASE = `${API_URL}/api/fournisseurs`

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
