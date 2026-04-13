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
      if (next.has(key)) next.delete(key); else next.add(key)
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

  const systemCollapsed = collapsedBlocks.has('system')
  const autoCollapsed = collapsedBlocks.has('autocontrole')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>

      {/* ===== BLOCK 1: Verifications automatiques ===== */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div
          className="vblock-header vblock-header-system"
          onClick={() => toggleBlock('system')}
          role="button"
          tabIndex={0}
          aria-expanded={!systemCollapsed}
          aria-controls="vblock-system-content"
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleBlock('system') } }}
        >
          <div className="vblock-title-group">
            <Zap size={14} aria-hidden="true" />
            <span className="vblock-title">Verifications automatiques</span>
            {hasResults && (
              <span className="vblock-stats">
                {sysOk} OK &middot; {sysKo} KO &middot; {systemResults.length} total
              </span>
            )}
          </div>
          <div className="vblock-actions">
            {!hasResults && (
              <button className="btn btn-sm vblock-btn-launch" onClick={e => { e.stopPropagation(); onValidate() }} disabled={validating}>
                {validating ? <Loader2 size={11} className="spin" /> : <ShieldCheck size={11} />}
                {validating ? ' Verification...' : ' Lancer'}
              </button>
            )}
            {hasResults && (
              <button className="btn btn-sm vblock-btn-rerun" onClick={e => { e.stopPropagation(); onValidate() }} disabled={validating} aria-label="Relancer la verification">
                {validating ? <Loader2 size={10} className="spin" /> : '\u21bb'}
              </button>
            )}
            {systemCollapsed ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronUp size={14} aria-hidden="true" />}
          </div>
        </div>

        <div id="vblock-system-content" className={`collapsible ${systemCollapsed ? 'collapsed' : 'expanded'}`} style={{ maxHeight: systemCollapsed ? 0 : 2000 }}>
          <div className="vblock-inner">
            {hasResults ? (
              groupedSystem.map(group => (
                <div key={group.key}>
                  <div className="vblock-group-header">{group.label}</div>
                  {group.items.map(item => {
                    const r = item.result
                    const status: ItemStatus = r ? statutToItemStatus(r.statut) : 'pending'
                    const sd = STATUS_DISPLAY[status]
                    return (
                      <div key={item.code} className="vblock-item">
                        <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                        <span className="vblock-code">{item.code}</span>
                        <div className="vblock-content">
                          <div className="vblock-label">{item.label}</div>
                          {r?.detail && <div className="vblock-detail">{r.detail}</div>}
                        </div>
                        {r?.id && (
                          <select
                            className="vblock-select"
                            value={r.statut}
                            onChange={e => handleCorrect(r.id!, e.target.value)}
                            disabled={saving === r.id}
                            style={{ background: sd.bg, color: sd.color }}
                            aria-label={`Corriger le statut de ${item.code}`}
                          >
                            {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                          </select>
                        )}
                        {r?.statutOriginal && r.statutOriginal !== r.statut && (
                          <span className="vblock-corrected">corrige</span>
                        )}
                      </div>
                    )
                  })}
                </div>
              ))
            ) : (
              groupedSystem.map(group => (
                <div key={group.key}>
                  <div className="vblock-group-header">{group.label}</div>
                  {group.items.map(item => (
                    <div key={item.code} className="vblock-item">
                      <span className="vblock-pill" style={{ background: 'var(--ink-05)', color: 'var(--ink-30)' }}>&middot;</span>
                      <span className="vblock-code">{item.code}</span>
                      <span style={{ fontSize: 12, color: 'var(--ink-50)' }}>{item.label}</span>
                    </div>
                  ))}
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      {/* ===== BLOCK 2: Verification autocontrole ===== */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div
          className="vblock-header vblock-header-autocontrole"
          onClick={() => toggleBlock('autocontrole')}
          role="button"
          tabIndex={0}
          aria-expanded={!autoCollapsed}
          aria-controls="vblock-auto-content"
          onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleBlock('autocontrole') } }}
        >
          <div className="vblock-title-group">
            <ClipboardCheck size={14} aria-hidden="true" />
            <span className="vblock-title">Verification autocontrole</span>
            {hasAutocontrole && (
              <span className="vblock-stats">
                {autoOk} OK &middot; {autoKo} KO &middot; {autocontroleItems.length} points
              </span>
            )}
            {hasAutocontrole && <span className="vblock-source-badge">Extrait du document</span>}
          </div>
          {autoCollapsed ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronUp size={14} aria-hidden="true" />}
        </div>

        <div id="vblock-auto-content" className={`collapsible ${autoCollapsed ? 'collapsed' : 'expanded'}`} style={{ maxHeight: autoCollapsed ? 0 : 2000 }}>
          <div>
            {hasAutocontrole && (
              <div className="vblock-info-row">
                <div>
                  <span className="vblock-info-label">Prestataire: </span>
                  {(dossier.checklistAutocontrole?.prestataire as string) || dossier.fournisseur || '\u2014'}
                </div>
                <div>
                  <span className="vblock-info-label">Ref: </span>
                  {(dossier.checklistAutocontrole?.referenceFacture as string) || '\u2014'}
                </div>
                <div style={{ marginLeft: 'auto', fontSize: 9, color: 'var(--ink-30)', fontFamily: 'var(--font-mono)' }}>CCF-EN-04-V02</div>
              </div>
            )}

            <div className="vblock-inner">
              {autocontroleItems.map((item, i) => {
                const sd = STATUS_DISPLAY[item.status]
                return (
                  <div key={i} className="vblock-item">
                    <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                    <span className="vblock-code" style={{ width: 22 }}>{item.num}</span>
                    <div className="vblock-content">
                      <div className="vblock-label">{item.desc}</div>
                      {item.observation && item.observation !== '\u2014' && (
                        <div className="vblock-detail">{item.observation}</div>
                      )}
                    </div>
                    <span className="vblock-status-badge" style={{ background: sd.bg, color: sd.color }}>
                      {sd.label}
                    </span>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
})
