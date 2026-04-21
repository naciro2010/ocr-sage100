const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')
const BASE = `${API_URL}/api/custom-rules`

function authHeaders(): Record<string, string> {
  const auth = localStorage.getItem('recondoc_auth')
  return auth ? { 'Authorization': `Basic ${auth}` } : {}
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
  if (res.status === 204) return undefined as T
  return res.json()
}

export interface CustomRule {
  id: string
  code: string
  libelle: string
  description: string | null
  prompt: string
  enabled: boolean
  appliesToBC: boolean
  appliesToContractuel: boolean
  documentTypes: string[]
  severity: 'NON_CONFORME' | 'AVERTISSEMENT'
  requiredFields: string[]
  createdAt: string
  updatedAt: string
  createdBy: string | null
}

export interface CustomRuleRequest {
  libelle: string
  description?: string
  prompt: string
  enabled: boolean
  appliesToBC: boolean
  appliesToContractuel: boolean
  documentTypes?: string[]
  severity: 'NON_CONFORME' | 'AVERTISSEMENT'
  requiredFields?: string[]
}

export interface CustomRuleTestResult {
  regle: string
  libelle: string
  statut: 'CONFORME' | 'NON_CONFORME' | 'AVERTISSEMENT' | 'NON_APPLICABLE'
  detail: string | null
  evidences: Array<{
    role: string; champ: string; libelle: string | null
    documentId: string | null; documentType: string | null; valeur: string | null
  }> | null
  documentIds: string[] | null
}

export async function listCustomRules(): Promise<CustomRule[]> {
  const res = await fetch(BASE, { headers: authHeaders() })
  return handleResponse(res)
}

export async function createCustomRule(req: CustomRuleRequest): Promise<CustomRule> {
  const res = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
  })
  return handleResponse(res)
}

export async function updateCustomRule(id: string, req: CustomRuleRequest): Promise<CustomRule> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(req),
  })
  return handleResponse(res)
}

export async function deleteCustomRule(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${id}`, {
    method: 'DELETE',
    headers: authHeaders(),
  })
  await handleResponse<void>(res)
}

export async function toggleCustomRule(id: string, enabled: boolean): Promise<CustomRule> {
  const res = await fetch(`${BASE}/${id}/toggle`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ enabled }),
  })
  return handleResponse(res)
}

export async function testCustomRule(id: string, dossierId: string): Promise<CustomRuleTestResult> {
  const res = await fetch(`${BASE}/${id}/test`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ dossierId }),
  })
  return handleResponse(res)
}
