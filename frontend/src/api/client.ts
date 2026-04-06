import type { Invoice, InvoiceUpdateRequest, Page, DashboardStats, BatchResult, BatchSyncResult, ValidationResult, AiSettingsResponse, ErpSettingsResponse } from './types'

const API_URL = import.meta.env.VITE_API_URL || ''
const BASE = `${API_URL}/api/invoices`

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return res.json()
}

export async function uploadInvoice(file: File): Promise<Invoice> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(BASE, { method: 'POST', body: form })
  return handleResponse(res)
}

export async function listInvoices(page = 0, size = 20): Promise<Page<Invoice>> {
  const res = await fetch(`${BASE}?page=${page}&size=${size}`)
  return handleResponse(res)
}

export async function getInvoice(id: number): Promise<Invoice> {
  const res = await fetch(`${BASE}/${id}`)
  return handleResponse(res)
}

export async function updateInvoice(id: number, data: InvoiceUpdateRequest): Promise<Invoice> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  return handleResponse(res)
}

export async function syncToSage(id: number): Promise<Invoice> {
  const res = await fetch(`${BASE}/${id}/sync`, { method: 'POST' })
  return handleResponse(res)
}

export async function getDashboard(): Promise<DashboardStats> {
  const res = await fetch(`${BASE}/dashboard`)
  return handleResponse(res)
}

async function handleBlobResponse(res: Response): Promise<Blob> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ message: res.statusText }))
    throw new Error(body.message || `HTTP ${res.status}`)
  }
  return res.blob()
}

export async function exportCsv(ids: number[]): Promise<Blob> {
  const res = await fetch(`${API_URL}/api/export/csv`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  })
  return handleBlobResponse(res)
}

export async function exportJson(ids: number[]): Promise<Blob> {
  const res = await fetch(`${API_URL}/api/export/json`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids }),
  })
  return handleBlobResponse(res)
}

export async function exportUbl(id: number): Promise<Blob> {
  const res = await fetch(`${API_URL}/api/export/ubl/${id}`)
  return handleBlobResponse(res)
}

export async function exportEdi(id: number): Promise<Blob> {
  const res = await fetch(`${API_URL}/api/export/edi/${id}`)
  return handleBlobResponse(res)
}

export async function batchUpload(files: File[]): Promise<BatchResult> {
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  const res = await fetch(`${BASE}/batch`, { method: 'POST', body: form })
  return handleResponse(res)
}

export async function batchSync(ids: number[], erpType: string): Promise<BatchSyncResult> {
  const res = await fetch(`${BASE}/batch-sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ids, erpType }),
  })
  return handleResponse(res)
}

export async function validateInvoice(id: number): Promise<ValidationResult> {
  const res = await fetch(`${BASE}/${id}/validate`)
  return handleResponse(res)
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

// --- ERP Settings ---

export async function getErpSettings(): Promise<ErpSettingsResponse> {
  const res = await fetch(`${API_URL}/api/settings/erp`)
  return handleResponse(res)
}

export async function saveErpSettings(settings: Record<string, unknown>): Promise<ErpSettingsResponse> {
  const res = await fetch(`${API_URL}/api/settings/erp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  })
  return handleResponse(res)
}

export async function testErpConnection(erpType: string): Promise<{ success: boolean; message: string }> {
  const res = await fetch(`${API_URL}/api/settings/erp/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ erpType }),
  })
  return handleResponse(res)
}
