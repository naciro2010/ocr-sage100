import { memo, useState } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { getActiveRules, RULE_GROUPS } from '../../config/validationRules'
import { ShieldCheck, Loader2, ChevronDown, ChevronUp, ClipboardCheck, Zap } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  validating: boolean
  onValidate: () => void
}

export default memo(function ChecklistSection({ dossier, validating, onValidate }: Props) {
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set())
  const activeRules = getActiveRules(dossier.type as 'BC' | 'CONTRACTUEL')
  const systemRules = activeRules.filter(r => r.category === 'system')
  const defaultChecklist = activeRules.filter(r => r.category === 'checklist')

  const checklistData = dossier.checklistAutocontrole
  const extractedPoints = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []
  const extractedSignataires = (checklistData?.signataires as Array<Record<string, unknown>> | undefined) || []
  const hasExtracted = extractedPoints.length > 0
  const checklistPrestataire = (checklistData?.prestataire as string) || dossier.fournisseur || '\u2014'
  const checklistRef = (checklistData?.referenceFacture as string) || ''

  const toggleGroup = (key: string) => {
    setCollapsedGroups(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })
  }

  // Group system rules
  const groupedRules = RULE_GROUPS.map(g => ({
    ...g,
    rules: systemRules.filter(r => (r as { group?: string }).group === g.key),
  })).filter(g => g.rules.length > 0)

  return (
    <>
      {/* Checklist autocontrole */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}>
            <ClipboardCheck size={14} /> Check-list d'autocontrole
          </h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {hasExtracted && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Extrait du document</span>}
            <span style={{ fontSize: 9, fontFamily: 'var(--font-mono)', color: 'var(--ink-30)' }}>CCF-EN-04-V02</span>
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginBottom: 14, fontSize: 12 }}>
          <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Prestataire : </span>{checklistPrestataire}</div>
          <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Reference facture : </span>{checklistRef}</div>
          <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Dossier : </span>{dossier.reference}</div>
        </div>

        <table className="data-table">
          <thead>
            <tr>
              <th style={{ width: 40 }}>#</th>
              <th>Points de controle</th>
              <th style={{ width: 50 }}>OK *</th>
              <th style={{ width: 120 }}>Observation</th>
            </tr>
          </thead>
          <tbody>
            {hasExtracted ? (
              extractedPoints.map((pt, i) => {
                const isOk = pt.estValide === true
                const isFail = pt.estValide === false
                return (
                  <tr key={i}>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)' }}>
                      {pt.numero != null ? String(pt.numero) : i + 1}
                    </td>
                    <td>
                      <div style={{ fontSize: 12, lineHeight: 1.5 }}>
                        {String(pt.description || defaultChecklist[i]?.desc || '')}
                      </div>
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <span className={`check-point-icon ${isOk ? 'pass' : isFail ? 'fail' : 'na'}`}
                        style={{ width: 18, height: 18, fontSize: 10, display: 'inline-flex' }}>
                        {isOk ? '\u2713' : isFail ? '\u2717' : '\u2014'}
                      </span>
                    </td>
                    <td style={{ fontSize: 11, color: 'var(--ink-40)' }}>
                      {pt.observation != null && pt.observation !== '\\u2014' ? String(pt.observation).replace(/\\u2014/g, '\u2014') : ''}
                    </td>
                  </tr>
                )
              })
            ) : (
              defaultChecklist.map((rule, i) => (
                <tr key={rule.code}>
                  <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)' }}>{i + 1}</td>
                  <td><div style={{ fontSize: 12, lineHeight: 1.5 }}>{rule.desc}</div></td>
                  <td style={{ textAlign: 'center' }}>
                    <span className="check-point-icon na" style={{ width: 18, height: 18, fontSize: 10, display: 'inline-flex' }}>{'\u2014'}</span>
                  </td>
                  <td></td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {extractedSignataires.length > 0 && (
          <div style={{ marginTop: 12, padding: '10px 12px', background: 'var(--ink-02)', borderRadius: 6 }}>
            <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 6, fontFamily: 'var(--font-mono)' }}>Signataires</div>
            {extractedSignataires.map((sig, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '3px 0', fontSize: 12 }}>
                <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 14, height: 14, fontSize: 9 }}>
                  {sig.aSignature ? '\u2713' : '\u2014'}
                </span>
                <span style={{ fontWeight: 600 }}>{String(sig.nom || '')}</span>
                {sig.date != null && <span style={{ fontSize: 10, color: 'var(--ink-30)', fontFamily: 'var(--font-mono)' }}>{String(sig.date)}</span>}
              </div>
            ))}
          </div>
        )}

        <div style={{ fontSize: 10, color: 'var(--ink-30)', marginTop: 8, fontStyle: 'italic' }}>
          *Les controles doivent etre obligatoirement exhaustifs
        </div>
      </div>

      {/* System rules — grouped by category */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h2 style={{ marginBottom: 0 }}>
            <Zap size={14} /> Verifications automatiques ({systemRules.length})
          </h2>
          {dossier.resultatsValidation.length === 0 && (
            <button className="btn btn-primary btn-sm" onClick={onValidate} disabled={validating}>
              {validating ? <><Loader2 size={12} className="spin" /> Verification...</> : <><ShieldCheck size={12} /> Lancer</>}
            </button>
          )}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {groupedRules.map(group => {
            const isCollapsed = collapsedGroups.has(group.key)
            return (
              <div key={group.key} style={{
                border: '1px solid var(--ink-05)', borderRadius: 6,
                overflow: 'hidden',
              }}>
                <div
                  onClick={() => toggleGroup(group.key)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 8,
                    padding: '6px 10px', cursor: 'pointer',
                    background: 'var(--ink-02)',
                    fontSize: 10, fontWeight: 700, color: 'var(--ink-50)',
                    textTransform: 'uppercase', letterSpacing: 1,
                    fontFamily: 'var(--font-mono)',
                    userSelect: 'none',
                  }}
                >
                  {isCollapsed ? <ChevronDown size={12} /> : <ChevronUp size={12} />}
                  {group.label}
                  <span style={{ fontSize: 9, color: 'var(--ink-30)', fontWeight: 500 }}>({group.rules.length})</span>
                </div>
                {!isCollapsed && (
                  <div style={{ padding: '4px 0' }}>
                    {group.rules.map(rule => (
                      <div key={rule.code} style={{
                        display: 'flex', alignItems: 'center', gap: 8,
                        padding: '4px 12px', fontSize: 11,
                      }}>
                        <span style={{
                          fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700,
                          color: 'var(--accent-deep)', width: 28, flexShrink: 0,
                        }}>{rule.code}</span>
                        <span style={{ color: 'var(--ink-50)', flex: 1 }}>{rule.label}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </>
  )
})
