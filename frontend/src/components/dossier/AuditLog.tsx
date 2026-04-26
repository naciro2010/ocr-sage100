import { memo, useMemo, useState } from 'react'
import type { AuditEntry } from '../../api/dossierTypes'
import { Clock, ChevronDown, AlertOctagon } from 'lucide-react'

interface Props {
  audit: AuditEntry[]
}

// Actions critiques (changement de verdict, validation, rejet, correction
// manuelle) : audit reglementaire, jamais repliees. Le reste (upload,
// reclassement, re-extraction routine) reste collapsable pour ne pas
// noyer la timeline d'un dossier actif.
const CRITICAL_ACTION_RE = /^(VALIDE|REJET|REOUVRIR|CORRECTION|FORCE|OVERRIDE|VALIDATION|FINALIZE|FINALISATION|MODIFIE|DELETE)/i

const ROUTINE_LIMIT = 5

function isCritical(a: AuditEntry): boolean {
  return CRITICAL_ACTION_RE.test(a.action)
}

export default memo(function AuditLog({ audit }: Props) {
  const [expanded, setExpanded] = useState(false)

  const { critical, routine } = useMemo(() => {
    const crit: AuditEntry[] = []
    const rout: AuditEntry[] = []
    for (const a of audit) {
      if (isCritical(a)) crit.push(a); else rout.push(a)
    }
    return { critical: crit, routine: rout }
  }, [audit])

  if (audit.length === 0) return null

  const visibleRoutine = expanded ? routine : routine.slice(0, ROUTINE_LIMIT)
  const hiddenRoutine = routine.length - visibleRoutine.length

  const renderRow = (a: AuditEntry, i: number, important: boolean) => (
    <div key={`${important ? 'c' : 'r'}-${i}`} className="audit-row" style={important ? {
      borderLeft: '3px solid var(--danger)',
      paddingLeft: 8,
      background: 'rgba(239,68,68,0.04)',
    } : undefined}>
      <div>
        <span className="audit-action" style={important ? { fontWeight: 700, color: 'var(--danger)' } : undefined}>
          {important && <AlertOctagon size={11} style={{ display: 'inline-block', verticalAlign: 'text-bottom', marginRight: 4 }} aria-hidden="true" />}
          {a.action}
        </span>
        {a.detail && <span className="audit-detail">{a.detail}</span>}
        {a.utilisateur && <span className="audit-detail" style={{ fontStyle: 'italic' }}>par {a.utilisateur}</span>}
      </div>
      <span className="audit-date">{new Date(a.dateAction).toLocaleString('fr-FR')}</span>
    </div>
  )

  return (
    <div className="card">
      <h2><Clock size={14} /> Historique</h2>
      {critical.length > 0 && (
        <>
          {critical.map((a, i) => renderRow(a, i, true))}
          {routine.length > 0 && <div style={{ height: 1, background: 'var(--ink-10)', margin: '8px 0' }} aria-hidden="true" />}
        </>
      )}
      {visibleRoutine.map((a, i) => renderRow(a, i, false))}
      {hiddenRoutine > 0 && !expanded && (
        <button className="btn btn-secondary btn-sm" style={{ marginTop: 8, width: '100%', justifyContent: 'center' }}
          onClick={() => setExpanded(true)}>
          <ChevronDown size={14} /> Voir les {hiddenRoutine} entree{hiddenRoutine > 1 ? 's' : ''} routine{hiddenRoutine > 1 ? 's' : ''}
        </button>
      )}
    </div>
  )
})
