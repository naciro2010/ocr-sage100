import type { DossierDetail } from '../api/dossierTypes'

export interface ParsedChecklistPoint {
  num: number
  desc: string
  estValide: boolean | null
  observation: string | null
}

function parseBooleanish(v: unknown): boolean | null {
  if (v === true) return true
  if (v === false) return false
  if (typeof v === 'string') {
    const s = v.toLowerCase().trim()
    if (s === 'true' || s === 'oui' || s === 'conforme' || s === 'o' || s === 'yes') return true
    if (s === 'false' || s === 'non' || s === 'non conforme' || s === 'n' || s === 'no') return false
  }
  if (typeof v === 'number') return v !== 0
  return null
}

export function parseChecklistPoints(dossier: DossierDetail): ParsedChecklistPoint[] {
  const checklistData = dossier.checklistAutocontrole
  const extracted = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []

  return extracted.map((pt, i) => ({
    num: pt.numero != null ? Number(pt.numero) : i + 1,
    desc: String(pt.description || `Point ${pt.numero || i + 1}`),
    estValide: parseBooleanish(pt.estValide),
    observation: pt.observation != null && String(pt.observation) !== '\\u2014' ? String(pt.observation) : null,
  }))
}

export function hasAutocontrole(dossier: DossierDetail): boolean {
  const checklistData = dossier.checklistAutocontrole
  const extracted = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []
  return extracted.length > 0
}

export type ItemStatus = 'ok' | 'ko' | 'warn' | 'na' | 'pending'

export const STATUS_DISPLAY: Record<ItemStatus, { label: string; color: string; bg: string; icon: string }> = {
  ok:      { label: 'Conforme',       color: '#059669', bg: '#ecfdf5', icon: '\u2713' },
  ko:      { label: 'Non conforme',   color: '#dc2626', bg: '#fef2f2', icon: '\u2717' },
  warn:    { label: 'Avertissement',  color: '#d97706', bg: '#fffbeb', icon: '!' },
  na:      { label: 'N/A',           color: '#6b7280', bg: '#f3f4f6', icon: '\u2014' },
  pending: { label: 'En attente',    color: '#94a3b8', bg: '#f8fafc', icon: '\u00b7' },
}

export const STATUT_OPTIONS = [
  { value: 'CONFORME', label: 'Conforme' },
  { value: 'NON_CONFORME', label: 'Non conforme' },
  { value: 'AVERTISSEMENT', label: 'Avertissement' },
  { value: 'NON_APPLICABLE', label: 'N/A' },
] as const

export function statutToItemStatus(statut: string): ItemStatus {
  if (statut === 'CONFORME') return 'ok'
  if (statut === 'NON_CONFORME') return 'ko'
  if (statut === 'AVERTISSEMENT') return 'warn'
  return 'na'
}

export function estValideToItemStatus(estValide: boolean | null, hasResults: boolean): ItemStatus {
  if (!hasResults) return 'pending'
  if (estValide === true) return 'ok'
  if (estValide === false) return 'ko'
  return 'na'
}
