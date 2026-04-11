import type { DossierListItem, DossierDetail, DocumentInfo, ValidationResult, PageResponse, DossierType, DashboardStats, AuditEntry } from './dossierTypes'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/dossiers`

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

export async function createDossier(type: DossierType, fournisseur?: string, description?: string): Promise<DossierDetail> {
  const res = await apiFetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, fournisseur, description }),
  })
  return handleResponse(res)
}

export async function listDossiers(page = 0, size = 20): Promise<PageResponse<DossierListItem>> {
  const res = await apiFetch(`${BASE}?page=${page}&size=${size}`)
  return handleResponse(res)
}

export async function getDashboardStats(signal?: AbortSignal): Promise<DashboardStats> {
  const res = await apiFetch(`${BASE}/stats`, { signal })
  return handleResponse(res)
}

export async function getDossier(id: string, signal?: AbortSignal): Promise<DossierDetail> {
  const res = await apiFetch(`${BASE}/${id}`, { signal })
  return handleResponse(res)
}

export async function updateDossier(id: string, data: Record<string, unknown>): Promise<DossierDetail> {
  const res = await apiFetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return handleResponse(res)
}

export async function deleteDossier(id: string): Promise<void> {
  const res = await apiFetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function changeStatut(id: string, statut: string, motifRejet?: string, validePar?: string): Promise<DossierDetail> {
  const res = await apiFetch(`${BASE}/${id}/statut`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ statut, motifRejet, validePar }),
  })
  return handleResponse(res)
}

export async function uploadDocuments(dossierId: string, files: File[]): Promise<DocumentInfo[]> {
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  const res = await apiFetch(`${BASE}/${dossierId}/documents`, { method: 'POST', body: form })
  return handleResponse(res)
}

export async function validateDossier(id: string): Promise<ValidationResult[]> {
  const res = await apiFetch(`${BASE}/${id}/valider`, { method: 'POST' })
  return handleResponse(res)
}

export async function getValidationResults(id: string): Promise<ValidationResult[]> {
  const res = await apiFetch(`${BASE}/${id}/resultats-validation`)
  return handleResponse(res)
}

export async function reprocessDocument(dossierId: string, docId: string): Promise<DocumentInfo> {
  const res = await apiFetch(`${BASE}/${dossierId}/documents/${docId}/reprocess`, { method: 'POST' })
  return handleResponse(res)
}

export async function deleteDocument(dossierId: string, docId: string): Promise<void> {
  const res = await apiFetch(`${BASE}/${dossierId}/documents/${docId}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function changeDocumentType(dossierId: string, docId: string, typeDocument: string): Promise<DocumentInfo> {
  const res = await apiFetch(`${BASE}/${dossierId}/documents/${docId}/type`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ typeDocument }),
  })
  return handleResponse(res)
}

export async function getAuditLog(dossierId: string): Promise<AuditEntry[]> {
  const res = await apiFetch(`${BASE}/${dossierId}/audit`)
  return handleResponse(res)
}

export async function finalizeDossier(dossierId: string, data: {
  points: Array<{ description: string; observation: string; commentaire?: string }>
  signataire: string
  signatureBase64?: string
  commentaireGeneral?: string
}): Promise<{ tcDocId: string; opDocId: string; reference: string }> {
  const res = await apiFetch(`${BASE}/${dossierId}/finalize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return handleResponse(res)
}

export function getExportTCUrl(dossierId: string): string {
  return `${API_URL}/api/dossiers/${dossierId}/export/tc`
}

export function getExportOPUrl(dossierId: string): string {
  return `${API_URL}/api/dossiers/${dossierId}/export/op`
}

export function getDocumentFileUrl(dossierId: string, docId: string): string {
  return `${API_URL}/api/dossiers/${dossierId}/documents/${docId}/file`
}

export async function searchDossiers(params: {
  page?: number, size?: number, statut?: string, type?: string, fournisseur?: string
}): Promise<PageResponse<DossierListItem>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  if (params.statut) q.set('statut', params.statut)
  if (params.type) q.set('type', params.type)
  if (params.fournisseur) q.set('fournisseur', params.fournisseur)
  const res = await apiFetch(`${BASE}/search?${q}`)
  return handleResponse(res)
}
