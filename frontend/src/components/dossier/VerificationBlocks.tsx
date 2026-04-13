import { memo, useState, useMemo, useCallback } from 'react'
import type { DossierDetail, ValidationResult } from '../../api/dossierTypes'
import { updateValidationResult } from '../../api/dossierApi'
import { getActiveRules, RULE_GROUPS, ALL_RULES } from '../../config/validationRules'
import { parseChecklistPoints, STATUS_DISPLAY, STATUT_OPTIONS, statutToItemStatus, estValideToItemStatus, type ItemStatus } from '../../config/checklistUtils'
import { useToast } from '../Toast'
import { Zap, ClipboardCheck, ShieldCheck, Loader2, ChevronDown, ChevronUp, AlertTriangle, User, FileText } from 'lucide-react'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'

interface Props {
  dossier: DossierDetail
  validating: boolean
  onValidate: () => void
  onNavigateDoc?: (docId: string) => void
}

function needsHumanReview(r: ValidationResult): boolean {
  if (r.statut === 'NON_CONFORME') return true
  if (r.statut === 'AVERTISSEMENT') return true
  if (r.source === 'llm' || r.source === 'ia') return true
  return false
}

function sourceLabel(source: string): string {
  if (source === 'deterministe' || source === 'regex' || source === 'DETERMINISTE') return 'Verifie'
  if (source === 'llm' || source === 'ia') return 'Extrait par IA'
  if (source === 'CHECKLIST') return 'Autocontrole'
  return 'Systeme'
}

function confidenceLevel(r: ValidationResult): { label: string; color: string; pct: number } {
  if (r.source === 'deterministe' || r.source === 'regex' || r.source === 'DETERMINISTE') return { label: 'Fiable', color: 'var(--info)', pct: 100 }
  if (r.source === 'llm' || r.source === 'ia') {
    if (r.statut === 'CONFORME') return { label: 'Fiable', color: 'var(--success)', pct: 85 }
    return { label: 'A verifier', color: 'var(--warning)', pct: 60 }
  }
  if (r.source === 'CHECKLIST') return { label: 'A verifier', color: 'var(--warning)', pct: 70 }
  return { label: 'Fiable', color: 'var(--ink-40)', pct: 90 }
}

function Legend() {
  return (
    <div className="vblock-legend">
      <span className="vblock-legend-title">Legende :</span>
      {[
        { icon: '\u2713', bg: '#ecfdf5', color: '#059669', label: 'Conforme' },
        { icon: '\u2717', bg: '#fef2f2', color: '#dc2626', label: 'Non conforme' },
        { icon: '!', bg: '#fffbeb', color: '#d97706', label: 'A verifier' },
        { icon: '\u2014', bg: '#f3f4f6', color: '#6b7280', label: 'Non applicable' },
      ].map(s => (
        <span key={s.label} className="vblock-legend-item">
          <span className="vblock-legend-pill" style={{ background: s.bg, color: s.color }}>{s.icon}</span>
          {s.label}
        </span>
      ))}
      <span className="vblock-legend-separator" />
      <span className="vblock-legend-item">
        <span className="vblock-source-tag deterministe" style={{ fontSize: 8 }}>Verifie</span>
        Comparaison exacte
      </span>
      <span className="vblock-legend-item">
        <span className="vblock-source-tag llm" style={{ fontSize: 8 }}>Extrait par IA</span>
        Donnee lue du PDF
      </span>
    </div>
  )
}

export default memo(function VerificationBlocks({ dossier, validating, onValidate, onNavigateDoc }: Props) {
  const { toast } = useToast()
  const [saving, setSaving] = useState<string | null>(null)
  const [collapsedBlocks, setCollapsedBlocks] = useState<Set<string>>(new Set(['system', 'autocontrole']))
  const [expandedItems, setExpandedItems] = useState<Set<string>>(new Set())

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
      const fullDef = ALL_RULES.find(r => r.code === code)
      const result = systemResults.find(r => r.regle === code || r.regle.startsWith(code))
      return { code, label: ruleDef?.label || code, desc: fullDef?.desc || '', result }
    })
    return { ...g, items }
  }).filter(g => g.items.length > 0), [systemRuleDefs, systemResults])

  const parsedPoints = useMemo(() => parseChecklistPoints(dossier), [dossier.checklistAutocontrole])
  const hasAutocontrole = parsedPoints.length > 0

  const autocontroleItems = useMemo(() => {
    if (hasAutocontrole) {
      return parsedPoints.map(pt => {
        const ckRule = ALL_RULES.find(r => r.category === 'checklist' && r.desc.toLowerCase().includes(pt.desc.substring(0, 20).toLowerCase()))
        return {
          num: pt.num, desc: pt.desc,
          status: estValideToItemStatus(pt.estValide, hasResults),
          observation: pt.observation,
          ruleCode: ckRule?.code || null,
          ruleDesc: ckRule?.desc || null,
        }
      })
    }
    return activeRules.filter(r => r.category === 'checklist').map((r, i) => ({
      num: i + 1, desc: r.label, status: 'pending' as ItemStatus, observation: null as string | null,
      ruleCode: r.code, ruleDesc: r.desc,
    }))
  }, [parsedPoints, hasAutocontrole, hasResults, activeRules])

  const toggleBlock = useCallback((key: string) => {
    setCollapsedBlocks(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key); else next.add(key)
      return next
    })
  }, [])

  const toggleItem = useCallback((key: string) => {
    setExpandedItems(prev => {
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

  const needsReviewCount = useMemo(() =>
    systemResults.filter(r => needsHumanReview(r)).length, [systemResults])

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
            {hasResults && needsReviewCount > 0 && (
              <span className="vblock-review-badge">
                <User size={10} /> {needsReviewCount} a verifier
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

        <div id="vblock-system-content" className={`collapsible ${systemCollapsed ? 'collapsed' : 'expanded'}`} style={{ maxHeight: systemCollapsed ? 0 : 5000 }}>
          {hasResults && <Legend />}
          <div className="vblock-inner">
            {hasResults ? (
              groupedSystem.map(group => (
                <div key={group.key}>
                  <div className="vblock-group-header">{group.label}</div>
                  {group.items.map(item => {
                    const r = item.result
                    const status: ItemStatus = r ? statutToItemStatus(r.statut) : 'pending'
                    const sd = STATUS_DISPLAY[status]
                    const isExpanded = expandedItems.has(item.code)
                    const conf = r ? confidenceLevel(r) : null
                    const review = r ? needsHumanReview(r) : false

                    return (
                      <div key={item.code}>
                        <div className={`vblock-item ${review ? 'vblock-item-review' : ''}`}
                          onClick={() => toggleItem(item.code)}
                          style={{ cursor: 'pointer' }}
                        >
                          <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                          <span className="vblock-code">{item.code}</span>
                          <div className="vblock-content">
                            <div className="vblock-label">{item.label}</div>
                            {r?.detail && !isExpanded && <div className="vblock-detail">{r.detail}</div>}
                          </div>

                          {/* Status label — toujours visible */}
                          <span className="vblock-status-badge" style={{ background: sd.bg, color: sd.color }}>
                            {sd.label}
                          </span>

                          {/* Source badge */}
                          {r?.source && (
                            <span className={`vblock-source-tag ${needsHumanReview(r) ? 'llm' : 'deterministe'}`}>
                              {sourceLabel(r.source)}
                            </span>
                          )}

                          {/* Review indicator */}
                          {review && (
                            <span className="vblock-review-icon" title="A verifier manuellement">
                              <AlertTriangle size={12} />
                            </span>
                          )}

                          {/* Inline correction */}
                          {r?.id && (
                            <select
                              className="vblock-select"
                              value={r.statut}
                              onChange={e => { e.stopPropagation(); handleCorrect(r.id!, e.target.value) }}
                              onClick={e => e.stopPropagation()}
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

                          {isExpanded ? <ChevronUp size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} /> : <ChevronDown size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} />}
                        </div>

                        {/* Expanded detail panel */}
                        {isExpanded && (
                          <div className="vblock-expand">
                            <div className="vblock-expand-desc">{item.desc}</div>

                            {(r?.valeurAttendue || r?.valeurTrouvee) && (
                              <div className="vblock-expand-compare">
                                {r.valeurAttendue && (
                                  <div className="vblock-expand-val">
                                    <span className="vblock-expand-val-label">Attendu</span>
                                    <span className="vblock-expand-val-data">{r.valeurAttendue}</span>
                                  </div>
                                )}
                                {r.valeurTrouvee && (
                                  <div className="vblock-expand-val">
                                    <span className="vblock-expand-val-label">Trouve</span>
                                    <span className={`vblock-expand-val-data ${r.statut === 'NON_CONFORME' ? 'danger' : ''}`}>{r.valeurTrouvee}</span>
                                  </div>
                                )}
                              </div>
                            )}

                            {r?.detail && <div className="vblock-expand-detail">{r.detail}</div>}

                            <div className="vblock-expand-meta">
                              {r?.source && (
                                <span className="vblock-expand-meta-item">
                                  Source : <strong>{sourceLabel(r.source)}</strong>
                                </span>
                              )}
                              {conf && (
                                <span className="vblock-expand-meta-item">
                                  Confiance : <strong style={{ color: conf.color }}>{conf.pct}%</strong>
                                  <span className="vblock-confidence-bar">
                                    <span className="vblock-confidence-fill" style={{ width: `${conf.pct}%`, background: conf.color }} />
                                  </span>
                                </span>
                              )}
                              {review && (
                                <span className="vblock-expand-meta-item vblock-expand-review">
                                  <AlertTriangle size={11} /> A verifier manuellement
                                </span>
                              )}
                              {r?.commentaire && (
                                <span className="vblock-expand-meta-item">
                                  Commentaire : {r.commentaire}
                                </span>
                              )}
                              {r?.corrigePar && (
                                <span className="vblock-expand-meta-item">
                                  Corrige par : <strong>{r.corrigePar}</strong>
                                </span>
                              )}
                            </div>
                            {r?.documentIds && onNavigateDoc && (
                              <div className="vblock-expand-docs">
                                {r.documentIds.map(docId => {
                                  const doc = dossier.documents.find(d => d.id === docId)
                                  if (!doc) return null
                                  return (
                                    <button key={docId} className="btn btn-secondary btn-sm"
                                      onClick={e => { e.stopPropagation(); onNavigateDoc(docId) }}>
                                      <FileText size={11} /> {TYPE_DOCUMENT_LABELS[doc.typeDocument] || doc.typeDocument}
                                    </button>
                                  )
                                })}
                              </div>
                            )}
                          </div>
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
                    <div key={item.code} className="vblock-item" onClick={() => toggleItem(item.code)} style={{ cursor: 'pointer' }}>
                      <span className="vblock-pill" style={{ background: 'var(--ink-05)', color: 'var(--ink-30)' }}>&middot;</span>
                      <span className="vblock-code">{item.code}</span>
                      <div className="vblock-content">
                        <span style={{ fontSize: 12, color: 'var(--ink-50)' }}>{item.label}</span>
                        {expandedItems.has(item.code) && (
                          <div className="vblock-detail" style={{ marginTop: 4, whiteSpace: 'normal' }}>{item.desc}</div>
                        )}
                      </div>
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

        <div id="vblock-auto-content" className={`collapsible ${autoCollapsed ? 'collapsed' : 'expanded'}`} style={{ maxHeight: autoCollapsed ? 0 : 5000 }}>
          <Legend />
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
                const isExpanded = expandedItems.has(`auto-${i}`)
                return (
                  <div key={i}>
                    <div className="vblock-item" onClick={() => toggleItem(`auto-${i}`)} style={{ cursor: 'pointer' }}>
                      <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                      <span className="vblock-code" style={{ width: 22 }}>{item.num}</span>
                      <div className="vblock-content">
                        <div className="vblock-label">{item.desc}</div>
                        {item.observation && item.observation !== '\u2014' && !isExpanded && (
                          <div className="vblock-detail">{item.observation}</div>
                        )}
                      </div>
                      {item.ruleCode && (
                        <span className="vblock-source-tag deterministe">{item.ruleCode}</span>
                      )}
                      <span className="vblock-status-badge" style={{ background: sd.bg, color: sd.color }}>
                        {sd.label}
                      </span>
                      {isExpanded ? <ChevronUp size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} /> : <ChevronDown size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} />}
                    </div>

                    {isExpanded && (
                      <div className="vblock-expand">
                        {item.ruleDesc && <div className="vblock-expand-desc">{item.ruleDesc}</div>}
                        {item.observation && (
                          <div className="vblock-expand-detail">
                            <strong>Observation :</strong> {item.observation}
                          </div>
                        )}
                        <div className="vblock-expand-meta">
                          <span className="vblock-expand-meta-item">
                            Source : <strong>{hasAutocontrole ? 'Autocontrole — extrait du document' : 'Verifie par le systeme'}</strong>
                          </span>
                          <span className="vblock-expand-meta-item">
                            Confiance : <strong style={{ color: hasAutocontrole ? 'var(--warning)' : 'var(--info)' }}>{hasAutocontrole ? '70%' : '100%'}</strong>
                            <span className="vblock-confidence-bar">
                              <span className="vblock-confidence-fill" style={{ width: hasAutocontrole ? '70%' : '100%', background: hasAutocontrole ? 'var(--warning)' : 'var(--info)' }} />
                            </span>
                          </span>
                          {hasAutocontrole && (
                            <span className="vblock-expand-meta-item vblock-expand-review">
                              <AlertTriangle size={11} /> A verifier manuellement (extraction IA)
                            </span>
                          )}
                        </div>
                      </div>
                    )}
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
