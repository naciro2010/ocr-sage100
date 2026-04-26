import type { DossierListItem, DossierDetail, DocumentInfo, ValidationResult, PageResponse, DossierType, DashboardStats, AuditEntry, RuleCatalogEntry } from './dossierTypes'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/dossiers`

// Cache + request deduplication.
//
// Strategie "stale-while-revalidate":
//   - Si la donnee en cache est encore "fresh" (age < ttl) : on la rend
//     immediatement sans appel reseau.
//   - Si elle est "stale mais utilisable" (age < ttl + STALE_GRACE) : on la
//     rend immediatement ET on revalide en arriere-plan, ce qui garde l'UI
//     instantanee tout en gardant la donnee a jour.
//   - Si elle est trop vieille ou absente : appel reseau classique.
// La deduplication d'inflight reste indispensable : sans elle, monter un
// composant qui appelle 6 endpoints en parallele 2 fois (StrictMode dev,
// retry React) declenche 12 roundtrips au lieu de 6.
const apiCache = new Map<string, { data: unknown; ts: number }>()
const inflightRequests = new Map<string, Promise<unknown>>()
const CACHE_TTL = 5000 // 5s
const STALE_GRACE = 30_000 // 30s pendant lesquelles on peut servir le cache + revalider

async function cachedFetch<T>(url: string, ttl = CACHE_TTL): Promise<T> {
  const cached = apiCache.get(url)
  const age = cached ? Date.now() - cached.ts : Infinity

  if (cached && age < ttl) return cached.data as T

  if (cached && age < ttl + STALE_GRACE) {
    // Stale mais servable : on revalide en arriere-plan sans bloquer l'UI.
    if (!inflightRequests.has(url)) {
      const refresh = apiFetch(url)
        .then(res => handleResponse<T>(res))
        .then(data => { apiCache.set(url, { data, ts: Date.now() }); return data })
        .catch(() => cached.data as T)
        .finally(() => inflightRequests.delete(url))
      inflightRequests.set(url, refresh)
    }
    return cached.data as T
  }

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

/** Vide le cache d'une URL precise. Utile apres une mutation ciblee. */
export function invalidateUrl(url: string) {
  apiCache.delete(url)
}

/**
 * Prefetch idle : declenche les fetchs lents pendant que le navigateur est
 * inactif. On reuse les memes URL que les vrais appels -> hit cache lors de
 * la navigation. Pas de bloquage UI grace a requestIdleCallback.
 */
export function prefetchOnIdle(urls: string[], ttl = 60_000) {
  const run = () => urls.forEach(u => { void cachedFetch(u, ttl).catch(() => {}) })
  type RIC = (cb: () => void, opts?: { timeout: number }) => number
  const ric = (window as unknown as { requestIdleCallback?: RIC }).requestIdleCallback
  if (typeof ric === 'function') ric(run, { timeout: 2000 })
  else setTimeout(run, 200)
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
  // L'AbortSignal court-circuite la dedup/cache : un composant qui demonte
  // doit avoir le droit d'annuler son fetch sans rendre la donnee polluee
  // pour les autres consommateurs.
  if (signal) {
    const res = await apiFetch(`${BASE}?page=${page}&size=${size}`, { signal })
    return handleResponse(res)
  }
  return cachedFetch<PageResponse<DossierListItem>>(`${BASE}?page=${page}&size=${size}`, 3000)
}

export async function getDashboardStats(signal?: AbortSignal): Promise<DashboardStats> {
  if (signal) {
    const res = await apiFetch(`${BASE}/stats`, { signal })
    return handleResponse(res)
  }
  // Backend cache deja les stats 30s, mais SWR cote client supprime le
  // roundtrip pour les navigations <30s.
  return cachedFetch<DashboardStats>(`${BASE}/stats`, 15_000)
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

/**
 * Prefetch coordonne des donnees de la page Detail. Appelle les endpoints
 * atomiques en parallele (REST clean, 1 ressource = 1 endpoint). Les
 * resultats atterrissent dans le cache front + cache du Service Worker
 * -> au clic, DossierDetail trouve toutes ses donnees deja la.
 */
export function prefetchDossierDetail(id: string) {
  void getDossierSummary(id).catch(() => {})
  void getDocumentsWithData(id).catch(() => {})
  void getValidationResults(id).catch(() => {})
  void getRuleConfig(id).catch(() => {})
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
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      // body non JSON : on garde le message HTTP brut
    }
    throw new Error(message)
  }
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
  if (!res.ok) {
    let message = `HTTP ${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      // ignore
    }
    throw new Error(message)
  }
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
  // Audit n'evolue qu'apres une action utilisateur ; les writes appellent
  // invalidateCache(dossierId), donc le cache court reste correct.
  return cachedFetch<AuditEntry[]>(`${BASE}/${dossierId}/audit`, 5000)
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

export function getExportExcelUrl(dossierId: string): string {
  return `${API_URL}/api/dossiers/${dossierId}/export/excel`
}

export interface CompareRow {
  label: string
  values: Record<string, string | null>
  conflict: boolean
}

export async function compareDocuments(dossierId: string): Promise<{ dossierId: string; rows: CompareRow[] }> {
  const res = await apiFetch(`${BASE}/${dossierId}/compare`)
  return handleResponse(res)
}

export interface DocumentSearchHit {
  documentId: string
  dossierId: string
  dossierReference: string
  nomFichier: string
  typeDocument: string
  dateUpload: string
  rank: number
}

export async function searchDocuments(q: string, limit = 50): Promise<DocumentSearchHit[]> {
  if (!q.trim()) return []
  const url = `${BASE}/search-documents?q=${encodeURIComponent(q)}&limit=${limit}`
  return cachedFetch<DocumentSearchHit[]>(url, 30_000)
}

export async function uploadZip(dossierId: string, file: File, type?: string): Promise<{
  documents: DocumentInfo[]
  stats: { accepted: number; deduped: number; skipped: number }
}> {
  const fd = new FormData()
  fd.append('file', file)
  if (type) fd.append('type', type)
  const res = await apiFetch(`${BASE}/${dossierId}/documents/zip`, { method: 'POST', body: fd })
  invalidateCache(`/${dossierId}`)
  return handleResponse(res)
}

export async function bulkChangeStatut(
  ids: string[],
  statut: string,
  motifRejet?: string,
  validePar?: string
): Promise<Array<{ id: string; ok: boolean; error?: string }>> {
  const res = await apiFetch(`${BASE}/bulk/statut`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, statut, motifRejet, validePar })
  })
  invalidateCache('/api/dossiers')
  return handleResponse(res)
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

export async function updateValidationResult(dossierId: string, resultId: string, updates: { statut?: string; commentaire?: string; corrigePar?: string; valeurTrouvee?: string; valeurAttendue?: string; detail?: string; documentIds?: string }): Promise<ValidationResult> {
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

/**
 * Safe for <iframe src> / <embed src> PDF previews: the backend issues a 307
 * redirect to a presigned S3 URL when the bucket is enabled, so the PDF is
 * streamed by the object store (no backend bandwidth). Falls back to byte
 * streaming for filesystem storage. Don't use this for fetch().blob() calls —
 * stick with getDocumentFileUrl() there until the bucket has CORS configured.
 */
export function getDocumentPreviewUrl(dossierId: string, docId: string): string {
  return `${API_URL}/api/dossiers/${dossierId}/documents/${docId}/file?redirect=true`
}

/**
 * If S3 storage is enabled on the backend, resolves to a short-lived presigned
 * URL that the browser can hit directly against the bucket (no backend
 * bandwidth). For filesystem storage, the /file-url endpoint returns 204 and
 * we fall back to the byte-stream endpoint.
 */
export async function resolveDocumentUrl(dossierId: string, docId: string): Promise<string> {
  try {
    const res = await apiFetch(`${API_URL}/api/dossiers/${dossierId}/documents/${docId}/file-url`)
    if (res.status === 204) return getDocumentFileUrl(dossierId, docId)
    if (!res.ok) return getDocumentFileUrl(dossierId, docId)
    const data = (await res.json()) as { url?: string }
    return data.url ?? getDocumentFileUrl(dossierId, docId)
  } catch {
    return getDocumentFileUrl(dossierId, docId)
  }
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

/**
 * Persiste une correction humaine sur un champ extrait. Le backend met a jour
 * `Document.donneesExtraites` ET l'entite typee correspondante (Facture / BC /
 * OP / ...), donc une relance ulterieure des regles utilise la valeur corrigee.
 * Seule une re-extraction (reprocess document) ecrase une correction.
 */
export async function updateExtractedField(
  dossierId: string,
  docId: string,
  field: string,
  value: string | number | boolean | null
): Promise<Record<string, unknown>> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/documents/${docId}/extraction`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ field, value }),
  })
  return handleResponse(res)
}

export async function rerunValidationRule(dossierId: string, regle: string): Promise<ValidationResult[]> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/validation/rerun/${regle}`, { method: 'POST' })
  return handleResponse(res)
}

export async function correctAndRerun(
  dossierId: string, resultId: string,
  updates: { statut?: string; commentaire?: string; corrigePar?: string; valeurTrouvee?: string; valeurAttendue?: string; detail?: string; documentIds?: string }
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

export interface RequiredDocumentEntry { type: string; label: string }
export interface RequiredDocumentsResponse {
  defaults: RequiredDocumentEntry[]
  selected: string[]
  isCustom: boolean
}

export async function getRequiredDocuments(dossierId: string): Promise<RequiredDocumentsResponse> {
  return cachedFetch(`${BASE}/${dossierId}/required-documents`, 5000)
}

export async function updateRequiredDocuments(
  dossierId: string, selected: string[] | null
): Promise<RequiredDocumentsResponse> {
  invalidateCache(dossierId)
  const res = await apiFetch(`${BASE}/${dossierId}/required-documents`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ selected }),
  })
  return handleResponse(res)
}

export async function updateGlobalRuleConfig(rules: Record<string, boolean>): Promise<Array<{ regle: string; enabled: boolean }>> {
  const res = await apiFetch(`${BASE}/global-rule-config`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(rules),
  })
  return handleResponse(res)
}
