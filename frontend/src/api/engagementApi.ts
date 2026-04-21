import { API_URL, apiFetch, handleResponse } from './http'
import type {
  EngagementListItem,
  EngagementResponse,
  EngagementStats,
  CreateEngagementRequest,
  StatutEngagement,
} from './engagementTypes'

interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export async function listEngagements(params: {
  statut?: StatutEngagement
  fournisseur?: string
  reference?: string
  page?: number
  size?: number
} = {}, signal?: AbortSignal): Promise<Page<EngagementListItem>> {
  const q = new URLSearchParams()
  if (params.statut) q.set('statut', params.statut)
  if (params.fournisseur) q.set('fournisseur', params.fournisseur)
  if (params.reference) q.set('reference', params.reference)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 50))
  q.set('sort', 'dateCreation,desc')
  const res = await apiFetch(`${API_URL}/api/engagements?${q.toString()}`, { signal })
  return handleResponse(res)
}

export async function getEngagementStats(signal?: AbortSignal): Promise<EngagementStats> {
  const res = await apiFetch(`${API_URL}/api/engagements/stats`, { signal })
  return handleResponse(res)
}

export async function getEngagement(id: string, signal?: AbortSignal): Promise<EngagementResponse> {
  const res = await apiFetch(`${API_URL}/api/engagements/${id}`, { signal })
  return handleResponse(res)
}

export async function createEngagement(req: CreateEngagementRequest): Promise<EngagementResponse> {
  const res = await apiFetch(`${API_URL}/api/engagements`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  return handleResponse(res)
}

export async function updateEngagement(id: string, req: Partial<CreateEngagementRequest>): Promise<EngagementResponse> {
  const res = await apiFetch(`${API_URL}/api/engagements/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  return handleResponse(res)
}

export async function deleteEngagement(id: string): Promise<void> {
  const res = await apiFetch(`${API_URL}/api/engagements/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function attachDossierToEngagement(engagementId: string, dossierId: string): Promise<void> {
  const res = await apiFetch(`${API_URL}/api/engagements/${engagementId}/dossiers`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ dossierId }),
  })
  if (!res.ok) {
    let msg = `HTTP ${res.status}`
    try { const b = await res.json(); msg = b.error || msg } catch { /* ignore */ }
    throw new Error(msg)
  }
}

export async function detachDossier(dossierId: string): Promise<void> {
  const res = await apiFetch(`${API_URL}/api/engagements/dossiers/${dossierId}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}
