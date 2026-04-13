import { memo, useState, useMemo, useCallback } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { updateValidationResult } from '../../api/dossierApi'
import { getActiveRules, RULE_GROUPS } from '../../config/validationRules'
import { parseChecklistPoints, STATUS_DISPLAY, STATUT_OPTIONS, statutToItemStatus, estValideToItemStatus, type ItemStatus } from '../../config/checklistUtils'
import { useToast } from '../Toast'
import { Zap, ClipboardCheck, ShieldCheck, Loader2, ChevronDown, ChevronUp } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  validating: boolean
  onValidate: () => void
}

export default memo(function VerificationBlocks({ dossier, validating, onValidate }: Props) {
  const { toast } = useToast()
  const [saving, setSaving] = useState<string | null>(null)
  const [collapsedBlocks, setCollapsedBlocks] = useState<Set<string>>(new Set())

  const hasResults = dossier.resultatsValidation.length > 0
  const activeRules = useMemo(() => getActiveRules(dossier.type as 'BC' | 'CONTRACTUEL'), [dossier.type])
  const systemRuleDefs = useMemo(() => activeRules.filter(r => r.category === 'system'), [activeRules])
  const systemResults = dossier.resultatsValidation

  const groupedSystem = useMemo(() => RULE_GROUPS.map(g => {
    const groupRuleCodes = systemRuleDefs
      .filter(r => (r as { group?: string }).group === g.key)
      .map(r => r.code)
    const items = groupRuleCodes.map(code => {
      const ruleDef = systemRuleDefs.find(r => r.code === code)
      const result = systemResults.find(r => r.regle === code || r.regle.startsWith(code))
      return { code, label: ruleDef?.label || code, result }
    })
    return { ...g, items }
  }).filter(g => g.items.length > 0), [systemRuleDefs, systemResults])

  const parsedPoints = useMemo(() => parseChecklistPoints(dossier), [dossier.checklistAutocontrole])
  const hasAutocontrole = parsedPoints.length > 0

  const autocontroleItems = useMemo(() => {
    if (hasAutocontrole) {
      return parsedPoints.map(pt => ({
        num: pt.num, desc: pt.desc,
        status: estValideToItemStatus(pt.estValide, hasResults),
        observation: pt.observation,
      }))
    }
    return activeRules.filter(r => r.category === 'checklist').map((r, i) => ({
      num: i + 1, desc: r.desc, status: 'pending' as ItemStatus, observation: null as string | null,
    }))
  }, [parsedPoints, hasAutocontrole, hasResults, activeRules])

  const toggleBlock = useCallback((key: string) => {
    setCollapsedBlocks(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })
  }, [])

  const handleCorrect = useCallback(async (resultId: string, newStatut: string) => {
    setSaving(resultId)
    try {
      await updateValidationResult(dossier.id, resultId, { statut: newStatut })
      toast('success', 'Corrige')
      onValidate()
    } catch { toast('error', 'Erreur') }
    finally { setSaving(null) }
  }, [dossier.id, onValidate, toast])

  const { sysOk, sysKo, autoOk, autoKo } = useMemo(() => ({
    sysOk: systemResults.filter(r => r.statut === 'CONFORME').length,
    sysKo: systemResults.filter(r => r.statut === 'NON_CONFORME').length,
    autoOk: autocontroleItems.filter(i => i.status === 'ok').length,
    autoKo: autocontroleItems.filter(i => i.status === 'ko').length,
  }), [systemResults, autocontroleItems])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>

      {/* ===== BLOCK 1: Verifications automatiques ===== */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div
          onClick={() => toggleBlock('system')}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '12px 16px', cursor: 'pointer', userSelect: 'none',
            background: 'linear-gradient(135deg, var(--ink) 0%, var(--ink-90) 100%)',
            color: '#fff',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Zap size={14} />
            <span style={{ fontWeight: 700, fontSize: 13 }}>Verifications automatiques</span>
            {hasResults && (
              <span style={{ fontSize: 10, opacity: 0.7, fontFamily: 'var(--font-mono)' }}>
                {sysOk} OK \u00b7 {sysKo} KO \u00b7 {systemResults.length} total
              </span>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {!hasResults && (
              <button className="btn btn-sm" onClick={e => { e.stopPropagation(); onValidate() }} disabled={validating}
                style={{ background: 'var(--accent)', color: '#fff', border: 'none', fontSize: 10, padding: '4px 10px' }}>
                {validating ? <Loader2 size={11} className="spin" /> : <ShieldCheck size={11} />}
                {validating ? ' Verification...' : ' Lancer'}
              </button>
            )}
            {hasResults && (
              <button className="btn btn-sm" onClick={e => { e.stopPropagation(); onValidate() }} disabled={validating}
                style={{ background: 'rgba(255,255,255,0.15)', color: '#fff', border: 'none', fontSize: 10, padding: '3px 8px' }}>
                {validating ? <Loader2 size={10} className="spin" /> : '\u21bb'}
              </button>
            )}
            {collapsedBlocks.has('system') ? <ChevronDown size={14} /> : <ChevronUp size={14} />}
          </div>
        </div>

        {!collapsedBlocks.has('system') && (
          <div style={{ padding: '4px 0' }}>
            {hasResults ? (
              /* Show grouped results */
              groupedSystem.map(group => (
                <div key={group.key}>
                  <div style={{
                    padding: '6px 16px', fontSize: 9, fontWeight: 700,
                    color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1.5,
                    fontFamily: 'var(--font-mono)', background: 'var(--ink-02)',
                    borderBottom: '1px solid var(--ink-05)',
                  }}>
                    {group.label}
                  </div>
                  {group.items.map(item => {
                    const r = item.result
                    const status: ItemStatus = r ? statutToItemStatus(r.statut) : 'pending'
                    const sd = STATUS_DISPLAY[status]
                    return (
                      <div key={item.code} style={{
                        display: 'flex', alignItems: 'center', gap: 8,
                        padding: '7px 16px', borderBottom: '1px solid var(--ink-02)',
                        transition: 'background 0.1s',
                      }}
                        onMouseOver={e => (e.currentTarget.style.background = 'var(--ink-02)')}
                        onMouseOut={e => (e.currentTarget.style.background = 'transparent')}
                      >
                        {/* Status pill */}
                        <span style={{
                          width: 22, height: 22, borderRadius: '50%',
                          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                          fontSize: 11, fontWeight: 800, flexShrink: 0,
                          background: sd.bg, color: sd.color,
                        }}>{sd.icon}</span>

                        {/* Code */}
                        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', width: 28, flexShrink: 0 }}>
                          {item.code}
                        </span>

                        {/* Label + detail */}
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink)' }}>{item.label}</div>
                          {r?.detail && (
                            <div style={{ fontSize: 10, color: 'var(--ink-40)', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                              {r.detail}
                            </div>
                          )}
                        </div>

                        {/* Inline correction */}
                        {r?.id && (
                          <select
                            value={r.statut}
                            onChange={e => handleCorrect(r.id!, e.target.value)}
                            disabled={saving === r.id}
                            style={{
                              fontSize: 9, padding: '2px 4px', border: '1px solid var(--ink-10)',
                              borderRadius: 4, background: sd.bg, color: sd.color,
                              fontWeight: 700, cursor: 'pointer', width: 'auto',
                            }}
                          >
                            {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                          </select>
                        )}

                        {/* Corrected badge */}
                        {r?.statutOriginal && r.statutOriginal !== r.statut && (
                          <span style={{ fontSize: 8, color: 'var(--warning)', fontFamily: 'var(--font-mono)' }}>
                            corrige
                          </span>
                        )}
                      </div>
                    )
                  })}
                </div>
              ))
            ) : (
              /* Pre-validation: show rule list */
              groupedSystem.map(group => (
                <div key={group.key}>
                  <div style={{
                    padding: '6px 16px', fontSize: 9, fontWeight: 700,
                    color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1.5,
                    fontFamily: 'var(--font-mono)', background: 'var(--ink-02)',
                  }}>
                    {group.label}
                  </div>
                  {group.items.map(item => (
                    <div key={item.code} style={{
                      display: 'flex', alignItems: 'center', gap: 8,
                      padding: '6px 16px', borderBottom: '1px solid var(--ink-02)',
                    }}>
                      <span style={{
                        width: 22, height: 22, borderRadius: '50%',
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: 11, background: '#f8fafc', color: '#94a3b8',
                      }}>{'\u00b7'}</span>
                      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', width: 28 }}>{item.code}</span>
                      <span style={{ fontSize: 12, color: 'var(--ink-50)' }}>{item.label}</span>
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>
        )}
      </div>

      {/* ===== BLOCK 2: Verification autocontrole ===== */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div
          onClick={() => toggleBlock('autocontrole')}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '12px 16px', cursor: 'pointer', userSelect: 'none',
            background: 'linear-gradient(135deg, var(--accent-deep) 0%, var(--accent) 100%)',
            color: '#fff',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <ClipboardCheck size={14} />
            <span style={{ fontWeight: 700, fontSize: 13 }}>Verification autocontrole</span>
            {hasAutocontrole && (
              <span style={{ fontSize: 10, opacity: 0.7, fontFamily: 'var(--font-mono)' }}>
                {autoOk} OK \u00b7 {autoKo} KO \u00b7 {autocontroleItems.length} points
              </span>
            )}
            {hasAutocontrole && <span style={{ fontSize: 8, background: 'rgba(255,255,255,0.2)', padding: '1px 6px', borderRadius: 3 }}>Extrait du document</span>}
          </div>
          {collapsedBlocks.has('autocontrole') ? <ChevronDown size={14} style={{ color: '#fff' }} /> : <ChevronUp size={14} style={{ color: '#fff' }} />}
        </div>

        {!collapsedBlocks.has('autocontrole') && (
          <div>
            {/* Header info */}
            {hasAutocontrole && (
              <div style={{
                display: 'flex', gap: 16, padding: '8px 16px',
                background: 'var(--ink-02)', borderBottom: '1px solid var(--ink-05)',
                fontSize: 11,
              }}>
                <div>
                  <span style={{ fontWeight: 700, fontSize: 9, color: 'var(--ink-30)', textTransform: 'uppercase', fontFamily: 'var(--font-mono)' }}>Prestataire: </span>
                  {(dossier.checklistAutocontrole?.prestataire as string) || dossier.fournisseur || '\u2014'}
                </div>
                <div>
                  <span style={{ fontWeight: 700, fontSize: 9, color: 'var(--ink-30)', textTransform: 'uppercase', fontFamily: 'var(--font-mono)' }}>Ref: </span>
                  {(dossier.checklistAutocontrole?.referenceFacture as string) || '\u2014'}
                </div>
                <div style={{ marginLeft: 'auto', fontSize: 9, color: 'var(--ink-30)', fontFamily: 'var(--font-mono)' }}>CCF-EN-04-V02</div>
              </div>
            )}

            {/* Points */}
            <div style={{ padding: '4px 0' }}>
              {autocontroleItems.map((item, i) => {
                const sd = STATUS_DISPLAY[item.status]
                return (
                  <div key={i} style={{
                    display: 'flex', alignItems: 'center', gap: 8,
                    padding: '7px 16px', borderBottom: '1px solid var(--ink-02)',
                    transition: 'background 0.1s',
                  }}
                    onMouseOver={e => (e.currentTarget.style.background = 'var(--ink-02)')}
                    onMouseOut={e => (e.currentTarget.style.background = 'transparent')}
                  >
                    <span style={{
                      width: 22, height: 22, borderRadius: '50%',
                      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 11, fontWeight: 800, flexShrink: 0,
                      background: sd.bg, color: sd.color,
                    }}>{sd.icon}</span>

                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', width: 22, flexShrink: 0 }}>
                      {item.num}
                    </span>

                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--ink)' }}>{item.desc}</div>
                      {item.observation && item.observation !== '\\u2014' && (
                        <div style={{ fontSize: 10, color: 'var(--ink-40)', marginTop: 1 }}>{item.observation}</div>
                      )}
                    </div>

                    <span style={{
                      fontSize: 9, fontWeight: 700, padding: '2px 8px',
                      borderRadius: 4, background: sd.bg, color: sd.color,
                    }}>
                      {sd.label}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
})
