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
    if (res.status === 401) {
      localStorage.removeItem('recondoc_user')
      localStorage.removeItem('recondoc_auth')
      window.location.href = '/'
      throw new Error('Session expiree — reconnexion requise')
    }
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

export interface DossierSummary {
  id: string; reference: string; type: DossierType; statut: 'BROUILLON' | 'EN_VERIFICATION' | 'VALIDE' | 'REJETE'
  fournisseur: string | null; description: string | null
  montantTtc: number | null; montantHt: number | null; montantTva: number | null; montantNetAPayer: number | null
  dateCreation: string; dateValidation: string | null; validePar: string | null; motifRejet: string | null
  nbDocuments: number; nbChecksConformes: number; nbChecksTotal: number
}

export async function getDossierSummary(id: string, signal?: AbortSignal): Promise<DossierSummary> {
  const res = await apiFetch(`${BASE}/${id}/summary`, { signal })
  return handleResponse(res)
}

export interface DocumentsWithData {
  documents: DocumentInfo[]
  factures: Record<string, unknown>[]
  bonCommande: Record<string, unknown> | null
  contratAvenant: Record<string, unknown> | null
  ordrePaiement: Record<string, unknown> | null
  checklistAutocontrole: Record<string, unknown> | null
  tableauControle: Record<string, unknown> | null
  pvReception: Record<string, unknown> | null
  attestationFiscale: Record<string, unknown> | null
}

export async function getDocumentsWithData(id: string, signal?: AbortSignal): Promise<DocumentsWithData> {
  const res = await apiFetch(`${BASE}/${id}/documents`, { signal })
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

// Download a file with auth headers (for links that open in new tab)
export async function downloadWithAuth(url: string, filename: string) {
  const res = await apiFetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const blob = await res.blob()
  const blobUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = blobUrl; a.download = filename; a.click()
  URL.revokeObjectURL(blobUrl)
}

export async function openWithAuth(url: string) {
  const res = await apiFetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const blob = await res.blob()
  const blobUrl = URL.createObjectURL(blob)
  window.open(blobUrl, '_blank')
}

export async function updateValidationResult(dossierId: string, resultId: string, updates: { statut?: string; commentaire?: string; corrigePar?: string }): Promise<ValidationResult> {
  const res = await apiFetch(`${BASE}/${dossierId}/validation/${resultId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(updates),
  })
  return handleResponse(res)
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
