import { memo } from 'react'
import type { AuditEntry } from '../../api/dossierTypes'
import { Clock } from 'lucide-react'

interface Props {
  audit: AuditEntry[]
}

export default memo(function AuditLog({ audit }: Props) {
  if (audit.length === 0) return null

  return (
    <div className="card">
      <h2><Clock size={14} /> Historique</h2>
      {audit.map((a, i) => (
        <div key={i} className="audit-row">
          <div>
            <span style={{ fontWeight: 700, color: 'var(--slate-700)' }}>{a.action}</span>
            {a.detail && <span style={{ color: 'var(--slate-500)', marginLeft: 8 }}>{a.detail}</span>}
          </div>
          <span style={{ color: 'var(--slate-400)', fontSize: 12, whiteSpace: 'nowrap' }}>
            {new Date(a.dateAction).toLocaleString('fr-FR')}
          </span>
        </div>
      ))}
    </div>
  )
})
