import type { Invoice, Page, DashboardStats } from './types'

const BASE = '/api/invoices'

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

export async function syncToSage(id: number): Promise<Invoice> {
  const res = await fetch(`${BASE}/${id}/sync`, { method: 'POST' })
  return handleResponse(res)
}

export async function getDashboard(): Promise<DashboardStats> {
  const res = await fetch(`${BASE}/dashboard`)
  return handleResponse(res)
}
