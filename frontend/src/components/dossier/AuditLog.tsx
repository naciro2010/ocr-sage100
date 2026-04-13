import { memo, useState } from 'react'
import type { AuditEntry } from '../../api/dossierTypes'
import { Clock, ChevronDown } from 'lucide-react'

interface Props {
  audit: AuditEntry[]
}

const INITIAL_LIMIT = 5

export default memo(function AuditLog({ audit }: Props) {
  const [expanded, setExpanded] = useState(false)

  if (audit.length === 0) return null

  const visible = expanded ? audit : audit.slice(0, INITIAL_LIMIT)
  const hasMore = audit.length > INITIAL_LIMIT

  return (
    <div className="card">
      <h2><Clock size={14} /> Historique</h2>
      {visible.map((a, i) => (
        <div key={i} className="audit-row">
          <div>
            <span className="audit-action">{a.action}</span>
            {a.detail && <span className="audit-detail">{a.detail}</span>}
          </div>
          <span className="audit-date">
            {new Date(a.dateAction).toLocaleString('fr-FR')}
          </span>
        </div>
      ))}
      {hasMore && !expanded && (
        <button className="btn btn-secondary btn-sm" style={{ marginTop: 8, width: '100%', justifyContent: 'center' }}
          onClick={() => setExpanded(true)}>
          <ChevronDown size={14} /> Voir les {audit.length - INITIAL_LIMIT} entrees restantes
        </button>
      )}
    </div>
  )
})
