import { memo } from 'react'
import { FileText, Eye, Calculator, Target, Search } from 'lucide-react'
import type { ValidationEvidence } from '../../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import type { TypeDocument } from '../../api/dossierTypes'

interface Props {
  evidences: ValidationEvidence[]
  statut?: 'CONFORME' | 'NON_CONFORME' | 'AVERTISSEMENT' | 'NON_APPLICABLE'
  onOpenDocument?: (docId: string, field?: string) => void
}

const ROLE_CONFIG: Record<string, { label: string; color: string; bg: string; icon: typeof Eye }> = {
  attendu:  { label: 'Attendu',  color: '#0369a1', bg: '#e0f2fe', icon: Target },
  trouve:   { label: 'Trouve',   color: '#6b21a8', bg: '#f3e8ff', icon: Search },
  source:   { label: 'Source',   color: '#475569', bg: '#f1f5f9', icon: FileText },
  calcule:  { label: 'Calcule',  color: '#0e7490', bg: '#cffafe', icon: Calculator },
}

function iconFor(role: string) {
  return ROLE_CONFIG[role]?.icon || Eye
}

export default memo(function EvidenceList({ evidences, statut, onOpenDocument }: Props) {
  if (!evidences || evidences.length === 0) return null

  const ordered = [...evidences].sort((a, b) => {
    const order: Record<string, number> = { attendu: 1, trouve: 2, calcule: 3, source: 4 }
    return (order[a.role] || 9) - (order[b.role] || 9)
  })

  return (
    <div style={{
      display: 'grid', gap: 4, marginTop: 6,
      background: 'var(--ink-02)', borderRadius: 6, padding: '8px 10px',
      border: '1px solid var(--ink-05)'
    }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-60)', textTransform: 'uppercase', letterSpacing: 0.3, marginBottom: 2 }}>
        Preuves ({evidences.length})
      </div>
      {ordered.map((e, i) => {
        const cfg = ROLE_CONFIG[e.role] || ROLE_CONFIG.source
        const Icon = iconFor(e.role)
        const isMissing = !e.valeur || e.valeur.trim().length === 0
        const highlight = statut === 'NON_CONFORME' && (e.role === 'trouve' || e.role === 'attendu')
        return (
          <div key={i} style={{
            display: 'grid', gridTemplateColumns: 'auto 1fr auto', gap: 8,
            alignItems: 'center', fontSize: 11, padding: '4px 6px',
            background: 'white', borderRadius: 4,
            border: highlight ? `1px solid ${cfg.color}33` : '1px solid transparent'
          }}>
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 4,
              background: cfg.bg, color: cfg.color,
              padding: '2px 6px', borderRadius: 3, fontSize: 9, fontWeight: 700,
              textTransform: 'uppercase', letterSpacing: 0.3, whiteSpace: 'nowrap'
            }}>
              <Icon size={9} /> {cfg.label}
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ color: 'var(--ink-60)', fontSize: 10 }}>
                {e.libelle || e.champ}
              </div>
              <div style={{
                fontWeight: 600, fontFamily: 'var(--font-mono)',
                color: isMissing ? 'var(--ink-30)' : 'var(--ink-90)',
                fontStyle: isMissing ? 'italic' : 'normal',
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'
              }}>
                {isMissing ? '(manquant)' : e.valeur}
              </div>
            </div>
            {e.documentId && onOpenDocument ? (
              <button
                className="btn btn-secondary btn-sm"
                onClick={ev => { ev.stopPropagation(); onOpenDocument(e.documentId!, e.champ) }}
                title={`Voir la source dans ${e.documentType ? TYPE_DOCUMENT_LABELS[e.documentType as TypeDocument] || e.documentType : 'le document'}`}
                style={{ padding: '3px 6px', fontSize: 10 }}
              >
                <FileText size={10} /> Voir
              </button>
            ) : e.documentType ? (
              <span style={{ fontSize: 9, color: 'var(--ink-40)', whiteSpace: 'nowrap' }}>
                {TYPE_DOCUMENT_LABELS[e.documentType as TypeDocument] || e.documentType}
              </span>
            ) : <span />}
          </div>
        )
      })}
    </div>
  )
})
