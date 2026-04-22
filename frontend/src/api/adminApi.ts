import { apiFetch, handleResponse, API_URL } from './http'

// Pricing ($ per 1M tokens). Fallback used when backend pricing endpoint is
// unavailable. The backend (AppSettingsService) is the source of truth and
// can be overridden via ai.price.<model>.in / .out in AppSetting.
export const CLAUDE_PRICING: Record<string, { input: number; output: number }> = {
  'claude-opus-4-7':  { input: 15.0, output: 75.0 },
  'claude-sonnet-4-6': { input: 3.0,  output: 15.0 },
  'claude-sonnet-4-5': { input: 3.0,  output: 15.0 },
  'claude-haiku-4-5-20251001': { input: 0.80, output: 4.0 },
  'default':          { input: 3.0,  output: 15.0 },
}

let pricingCache: Record<string, { input: number; output: number }> | null = null

export async function loadPricing(force = false): Promise<Record<string, { input: number; output: number }>> {
  if (pricingCache && !force) return pricingCache
  try {
    const res = await apiFetch(`${API_URL}/api/admin/claude-usage/pricing`)
    const data = (await handleResponse(res)) as Record<string, { input: number; output: number }>
    pricingCache = { ...CLAUDE_PRICING, ...data }
  } catch {
    pricingCache = { ...CLAUDE_PRICING }
  }
  return pricingCache
}

export async function savePricing(model: string, input: number, output: number): Promise<void> {
  const res = await apiFetch(`${API_URL}/api/admin/claude-usage/pricing`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, input, output }),
  })
  await handleResponse(res)
  pricingCache = null
}

export function estimateCostUsd(model: string, inputTokens: number, outputTokens: number): number {
  const table = pricingCache ?? CLAUDE_PRICING
  const p = table[model] ?? table.default ?? CLAUDE_PRICING.default
  return (inputTokens / 1_000_000) * p.input + (outputTokens / 1_000_000) * p.output
}

const BASE = `${API_URL}/api/admin`

export interface ClaudeUsageSummary {
  days: number
  since: string
  inputTokens: number
  outputTokens: number
  calls: number
  errors: number
  cacheCreationInputTokens: number
  cacheReadInputTokens: number
}

export interface ClaudeUsageDay {
  day: string
  inputTokens: number
  outputTokens: number
  calls: number
  errors: number
}

export interface ClaudeUsageTopDossier {
  dossierId: string
  reference: string | null
  fournisseur: string | null
  inputTokens: number
  outputTokens: number
  calls: number
}

export interface ClaudeUsageByModel {
  model: string
  inputTokens: number
  outputTokens: number
  calls: number
}

export async function getClaudeSummary(days = 30): Promise<ClaudeUsageSummary> {
  const res = await apiFetch(`${BASE}/claude-usage/summary?days=${days}`)
  return handleResponse(res)
}

export async function getClaudeDaily(days = 30): Promise<ClaudeUsageDay[]> {
  const res = await apiFetch(`${BASE}/claude-usage/daily?days=${days}`)
  return handleResponse(res)
}

export async function getClaudeTopDossiers(days = 30, limit = 10): Promise<ClaudeUsageTopDossier[]> {
  const res = await apiFetch(`${BASE}/claude-usage/top-dossiers?days=${days}&limit=${limit}`)
  return handleResponse(res)
}

export async function getClaudeByModel(days = 30): Promise<ClaudeUsageByModel[]> {
  const res = await apiFetch(`${BASE}/claude-usage/by-model?days=${days}`)
  return handleResponse(res)
}
