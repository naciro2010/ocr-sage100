import type { DossierListItem, DossierDetail, DocumentInfo, ValidationResult, PageResponse, DossierType, DashboardStats } from './dossierTypes'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/dossiers`

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
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type, fournisseur, description }),
  })
  return handleResponse(res)
}

export async function listDossiers(page = 0, size = 20): Promise<PageResponse<DossierListItem>> {
  const res = await fetch(`${BASE}?page=${page}&size=${size}`)
  return handleResponse(res)
}

export async function getDashboardStats(signal?: AbortSignal): Promise<DashboardStats> {
  const res = await fetch(`${BASE}/stats`, { signal })
  return handleResponse(res)
}

export async function getDossier(id: string, signal?: AbortSignal): Promise<DossierDetail> {
  const res = await fetch(`${BASE}/${id}`, { signal })
  return handleResponse(res)
}

export async function updateDossier(id: string, data: Record<string, unknown>): Promise<DossierDetail> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return handleResponse(res)
}

export async function deleteDossier(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function changeStatut(id: string, statut: string, motifRejet?: string, validePar?: string): Promise<DossierDetail> {
  const res = await fetch(`${BASE}/${id}/statut`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ statut, motifRejet, validePar }),
  })
  return handleResponse(res)
}

export async function uploadDocuments(dossierId: string, files: File[]): Promise<DocumentInfo[]> {
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  const res = await fetch(`${BASE}/${dossierId}/documents`, { method: 'POST', body: form })
  return handleResponse(res)
}

export async function validateDossier(id: string): Promise<ValidationResult[]> {
  const res = await fetch(`${BASE}/${id}/valider`, { method: 'POST' })
  return handleResponse(res)
}

export async function getValidationResults(id: string): Promise<ValidationResult[]> {
  const res = await fetch(`${BASE}/${id}/resultats-validation`)
  return handleResponse(res)
}
