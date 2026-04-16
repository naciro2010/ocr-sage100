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

export default memo(function EvidenceList({ evidences, statut, onOpenDocument }: Props) {
  if (!evidences || evidences.length === 0) return null

  const ordered = [...evidences].sort((a, b) => {
    const order: Record<string, number> = { attendu: 1, trouve: 2, calcule: 3, source: 4 }
    return (order[a.role] || 9) - (order[b.role] || 9)
  })

  return (
    <div className="evidence-list">
      <div className="evidence-title">Preuves ({evidences.length})</div>
      {ordered.map((e, i) => {
        const cfg = ROLE_CONFIG[e.role] || ROLE_CONFIG.source
        const Icon = cfg.icon
        const isMissing = !e.valeur || e.valeur.trim().length === 0
        const highlight = statut === 'NON_CONFORME' && (e.role === 'trouve' || e.role === 'attendu')
        return (
          <div key={i}
            className={`evidence-item ${highlight ? 'evidence-item--highlight' : ''}`}
            style={{ '--role-color': cfg.color, '--role-bg': cfg.bg } as React.CSSProperties}
          >
            <span className="evidence-role-tag">
              <Icon size={9} /> {cfg.label}
            </span>
            <div style={{ minWidth: 0 }}>
              <div className="evidence-label">{e.libelle || e.champ}</div>
              <div className={`evidence-value ${isMissing ? 'evidence-value--missing' : ''}`}>
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
              <span className="evidence-doc-type">
                {TYPE_DOCUMENT_LABELS[e.documentType as TypeDocument] || e.documentType}
              </span>
            ) : <span />}
          </div>
        )
      })}
    </div>
  )
})
