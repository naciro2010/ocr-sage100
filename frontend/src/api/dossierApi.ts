import type { DossierListItem, DossierDetail, DocumentInfo, ValidationResult, PageResponse, DossierType, DashboardStats, AuditEntry, RuleCatalogEntry } from './dossierTypes'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/dossiers`

// Cache + request deduplication
const apiCache = new Map<string, { data: unknown; ts: number }>()
const inflightRequests = new Map<string, Promise<unknown>>()
const CACHE_TTL = 5000 // 5s

async function cachedFetch<T>(url: string, ttl = CACHE_TTL): Promise<T> {
  const cached = apiCache.get(url)
  if (cached && (Date.now() - cached.ts) < ttl) return cached.data as T

  // Deduplicate: if same URL is already in-flight, reuse its promise
  const inflight = inflightRequests.get(url)
  if (inflight) return inflight as Promise<T>

  const promise = apiFetch(url)
    .then(res => handleResponse<T>(res))
    .then(data => {
      apiCache.set(url, { data, ts: Date.now() })
      return data
    })
    .finally(() => inflightRequests.delete(url))

  inflightRequests.set(url, promise)
  return promise
}

function invalidateCache(prefix: string) {
  for (const key of apiCache.keys()) {
    if (key.includes(prefix)) apiCache.delete(key)
  }
}

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

export async function listDossiers(page = 0, size = 20, signal?: AbortSignal): Promise<PageResponse<DossierListItem>> {
  const res = await apiFetch(`${BASE}?page=${page}&size=${size}`, { signal })
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

export async function getDossierSummary(id: string): Promise<DossierSummary> {
  return cachedFetch(`${BASE}/${id}/summary`, 3000)
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

export async function getDocumentsWithData(id: string): Promise<DocumentsWithData> {
  return cachedFetch(`${BASE}/${id}/documents`, 5000)
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
  invalidateCache(id)
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
  invalidateCache(id)
  const res = await apiFetch(`${BASE}/${id}/valider`, { method: 'POST' })
  return handleResponse(res)
}

export async function getValidationResults(id: string): Promise<ValidationResult[]> {
  return cachedFetch(`${BASE}/${id}/resultats-validation`, 3000)
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

export async function updateValidationResult(dossierId: string, resultId: string, updates: { statut?: string; commentaire?: string; corrigePar?: string; valeurTrouvee?: string; valeurAttendue?: string; detail?: string }): Promise<ValidationResult> {
  invalidateCache(dossierId)
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
  page?: number, size?: number, statut?: string, type?: string, fournisseur?: string, signal?: AbortSignal
}): Promise<PageResponse<DossierListItem>> {
  const q = new URLSearchParams()
  if (params.page != null) q.set('page', String(params.page))
  if (params.size != null) q.set('size', String(params.size))
  if (params.statut) q.set('statut', params.statut)
  if (params.type) q.set('type', params.type)
  if (params.fournisseur) q.set('fournisseur', params.fournisseur)
  const res = await apiFetch(`${BASE}/search?${q}`, { signal: params.signal })
  return handleResponse(res)
}

export async function rerunValidationRule(dossierId: string, regle: string): Promise<ValidationResult[]> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/validation/rerun/${regle}`, { method: 'POST' })
  return handleResponse(res)
}

export async function correctAndRerun(
  dossierId: string, resultId: string,
  updates: { statut?: string; commentaire?: string; corrigePar?: string; valeurTrouvee?: string; valeurAttendue?: string; detail?: string }
): Promise<ValidationResult[]> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/validation/${resultId}/correct-and-rerun`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(updates),
  })
  return handleResponse(res)
}

export async function getCascadeScope(regle: string): Promise<{ regle: string; cascade: string[]; count: number }> {
  return cachedFetch(`${BASE}/validation/cascade/${regle}`, 60000)
}

export async function getRuleCatalog(): Promise<RuleCatalogEntry[]> {
  return cachedFetch(`${BASE}/rule-catalog`, 300000)
}

export async function getRuleConfig(dossierId: string): Promise<{ global: Record<string, boolean>; overrides: Record<string, boolean> }> {
  return cachedFetch(`${BASE}/${dossierId}/rule-config`, 5000)
}

export async function updateRuleConfig(dossierId: string, rules: Record<string, boolean>): Promise<{ global: Record<string, boolean>; overrides: Record<string, boolean> }> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/rule-config`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rules),
  })
  return handleResponse(res)
}

export async function getGlobalRuleConfig(): Promise<Array<{ regle: string; enabled: boolean }>> {
  return cachedFetch(`${BASE}/global-rule-config`, 5000)
}

export async function updateGlobalRuleConfig(rules: Record<string, boolean>): Promise<Array<{ regle: string; enabled: boolean }>> {
  const res = await apiFetch(`${BASE}/global-rule-config`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rules),
  })
  return handleResponse(res)
}
