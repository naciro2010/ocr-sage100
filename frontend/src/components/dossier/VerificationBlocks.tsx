import { memo, useState, useMemo, useCallback, useEffect, useRef } from 'react'
import type { DossierDetail, ValidationResult } from '../../api/dossierTypes'
import { updateValidationResult, correctAndRerun } from '../../api/dossierApi'
import { getActiveRules, RULE_GROUPS, ALL_RULES } from '../../config/validationRules'
import { parseChecklistPoints, STATUS_DISPLAY, STATUT_OPTIONS, statutToItemStatus, estValideToItemStatus, type ItemStatus } from '../../config/checklistUtils'
import { useToast } from '../Toast'
import { Zap, ClipboardCheck, ShieldCheck, Loader2, ChevronDown, ChevronUp, AlertTriangle, User, FileText, Filter, RefreshCw, Eye, Edit3, Save, X, MessageSquare, Zap as ZapIcon } from 'lucide-react'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import EvidenceList from './EvidenceList'

type FilterMode = 'all' | 'problems' | 'conforme'
type RuleConfigShape = { global: Record<string, boolean>; overrides: Record<string, boolean> }

function isRuleActive(config: RuleConfigShape | undefined, code: string): boolean {
  return config?.overrides?.[code] ?? config?.global?.[code] ?? true
}

interface Props {
  dossier: DossierDetail
  validating: boolean
  onValidate: () => void
  onRefreshResults?: () => void
  onOptimisticUpdate?: (resultId: string, newStatut: string) => void
  onNavigateDoc?: (docId: string) => void
  onRerunRule?: (regle: string) => Promise<void>
  onToggleRule?: (regle: string, enabled: boolean) => void
  ruleConfig?: RuleConfigShape
  onOpenPreview?: (docId: string, field?: string) => void
  cascadeScope?: Record<string, string[]>
  onReplaceResults?: (results: ValidationResult[]) => void
}

function needsHumanReview(r: ValidationResult): boolean {
  if (r.statut === 'NON_CONFORME') return true
  if (r.statut === 'AVERTISSEMENT') return true
  // LLM-sourced items only need review if not CONFORME
  if ((r.source === 'llm' || r.source === 'ia') && r.statut !== 'CONFORME') return true
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

function isStale(r: ValidationResult | undefined | null, documents: DossierDetail['documents']): boolean {
  if (!r?.dateExecution || !r?.documentIds?.length) return false
  const execTime = new Date(r.dateExecution).getTime()
  return r.documentIds.some(docId => {
    const doc = documents.find(d => d.id === docId)
    return doc && new Date(doc.dateUpload).getTime() > execTime
  })
}

const LEGEND_ITEMS = [
  { icon: '\u2713', bg: '#ecfdf5', color: '#059669', label: 'Conforme' },
  { icon: '\u2717', bg: '#fef2f2', color: '#dc2626', label: 'Non conforme' },
  { icon: '!', bg: '#fffbeb', color: '#d97706', label: 'A verifier' },
  { icon: '\u2014', bg: '#f3f4f6', color: '#6b7280', label: 'Non applicable' },
] as const

function Legend() {
  return (
    <div className="vblock-legend">
      <span className="vblock-legend-title">Legende :</span>
      {LEGEND_ITEMS.map(s => (
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

function ProgressBar({ results }: { results: ValidationResult[] }) {
  if (results.length === 0) return null
  const counts = { ok: 0, ko: 0, warn: 0, na: 0 }
  for (const r of results) {
    if (r.statut === 'CONFORME') counts.ok++
    else if (r.statut === 'NON_CONFORME') counts.ko++
    else if (r.statut === 'AVERTISSEMENT') counts.warn++
    else counts.na++
  }
  const total = results.length
  return (
    <div className="vblock-progress">
      <span className="vblock-progress-label">{counts.ok}/{total}</span>
      <div className="vblock-progress-bar">
        <div className="vblock-progress-segment" style={{ width: `${(counts.ok / total) * 100}%`, background: 'var(--success)' }} />
        <div className="vblock-progress-segment" style={{ width: `${(counts.ko / total) * 100}%`, background: 'var(--danger)' }} />
        <div className="vblock-progress-segment" style={{ width: `${(counts.warn / total) * 100}%`, background: 'var(--warning)' }} />
        <div className="vblock-progress-segment" style={{ width: `${(counts.na / total) * 100}%`, background: 'var(--ink-20)' }} />
      </div>
      {counts.ko > 0 && <span className="vblock-progress-label" style={{ color: 'var(--danger)' }}>{counts.ko} KO</span>}
      {counts.warn > 0 && <span className="vblock-progress-label" style={{ color: 'var(--warning)' }}>{counts.warn} !</span>}
    </div>
  )
}

function EditPanel({ resultId, editValues, setEditValues, onSave, onCancel, saving, cascadeSize }: {
  resultId: string
  editValues: { valeurTrouvee: string; valeurAttendue: string; commentaire: string }
  setEditValues: React.Dispatch<React.SetStateAction<{ valeurTrouvee: string; valeurAttendue: string; commentaire: string }>>
  onSave: (id: string, alsoRerun?: boolean) => void
  onCancel: () => void
  saving: boolean
  cascadeSize?: number
}) {
  return (
    <div className="vblock-edit-panel" onClick={e => e.stopPropagation()}>
      <div className="vblock-edit-row">
        <label>Valeur attendue</label>
        <input className="form-input" value={editValues.valeurAttendue}
          onChange={e => setEditValues(v => ({ ...v, valeurAttendue: e.target.value }))}
          placeholder="Valeur attendue" />
      </div>
      <div className="vblock-edit-row">
        <label>Valeur trouvee</label>
        <input className="form-input" value={editValues.valeurTrouvee}
          onChange={e => setEditValues(v => ({ ...v, valeurTrouvee: e.target.value }))}
          placeholder="Valeur trouvee" />
      </div>
      <div className="vblock-edit-row">
        <label><MessageSquare size={10} /> Commentaire</label>
        <input className="form-input" value={editValues.commentaire}
          onChange={e => setEditValues(v => ({ ...v, commentaire: e.target.value }))}
          placeholder="Raison de la correction..." />
      </div>
      <div style={{ display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
        <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => onSave(resultId, true)}
          title={cascadeSize && cascadeSize > 1 ? `Relance ce controle et ${cascadeSize - 1} dependant(s)` : 'Sauvegarde et relance ce controle'}>
          {saving ? <Loader2 size={11} className="spin" /> : <ZapIcon size={11} />} Sauvegarder + Relancer
          {cascadeSize && cascadeSize > 1 ? (
            <span style={{ marginLeft: 4, background: 'rgba(255,255,255,0.25)', padding: '1px 5px', borderRadius: 8, fontSize: 9, fontWeight: 700 }}>
              +{cascadeSize - 1}
            </span>
          ) : null}
        </button>
        <button className="btn btn-secondary btn-sm" disabled={saving} onClick={() => onSave(resultId, false)}>
          {saving ? <Loader2 size={11} className="spin" /> : <Save size={11} />} Sauvegarder
        </button>
        <button className="btn btn-secondary btn-sm" onClick={onCancel} disabled={saving}>
          <X size={11} /> Annuler
        </button>
      </div>
    </div>
  )
}

export default memo(function VerificationBlocks({ dossier, validating, onValidate, onRefreshResults, onOptimisticUpdate, onNavigateDoc, onRerunRule, onToggleRule, ruleConfig, onOpenPreview, cascadeScope, onReplaceResults }: Props) {
  const { toast } = useToast()
  const [rerunning, setRerunning] = useState<string | null>(null)
  const [recentlyRerun, setRecentlyRerun] = useState<Set<string>>(new Set())

  const hasResults = dossier.resultatsValidation.length > 0

  const [collapsedBlocks, setCollapsedBlocks] = useState<Set<string>>(() =>
    hasResults ? new Set() : new Set(['system', 'autocontrole'])
  )
  const [expandedItems, setExpandedItems] = useState<Set<string>>(() => {
    if (!hasResults) return new Set()
    const problems = new Set<string>()
    for (const r of dossier.resultatsValidation) {
      if (r.statut === 'NON_CONFORME' || r.statut === 'AVERTISSEMENT') problems.add(r.regle)
    }
    return problems.size <= 5 ? problems : new Set()
  })
  const [filterMode, setFilterMode] = useState<FilterMode>('all')
  const [editingResult, setEditingResult] = useState<string | null>(null)
  const [editValues, setEditValues] = useState<{ valeurTrouvee: string; valeurAttendue: string; commentaire: string }>({ valeurTrouvee: '', valeurAttendue: '', commentaire: '' })
  const [savingId, setSavingId] = useState<string | null>(null)
  const [focusedIdx, setFocusedIdx] = useState(-1)
  const containerRef = useRef<HTMLDivElement>(null)

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
      // Fix: use exact match or code + '.' prefix to avoid R1 matching R10, R11, etc.
      const result = systemResults.find(r => r.regle === code || r.regle.startsWith(code + '.'))
      return { code, label: ruleDef?.label || code, desc: fullDef?.desc || '', result }
    })
    return { ...g, items }
  }).filter(g => g.items.length > 0), [systemRuleDefs, systemResults])

  const parsedPoints = useMemo(() => parseChecklistPoints(dossier), [dossier])
  const hasAutocontrole = parsedPoints.length > 0

  const autocontroleItems = useMemo(() => {
    if (hasAutocontrole) {
      return parsedPoints.map(pt => {
        const ckRule = ALL_RULES.find(r => r.category === 'checklist' && r.desc.toLowerCase().includes(pt.desc.substring(0, 20).toLowerCase()))
        // Match R12.XX validation result from backend
        const rCode = `R12.${String(pt.num).padStart(2, '0')}`
        const validationResult = systemResults.find(r => r.regle === rCode) || null
        return {
          num: pt.num, desc: pt.desc,
          status: validationResult ? statutToItemStatus(validationResult.statut) : estValideToItemStatus(pt.estValide, hasResults),
          observation: pt.observation,
          ruleCode: ckRule?.code || null,
          ruleDesc: ckRule?.desc || null,
          result: validationResult,
        }
      })
    }
    return activeRules.filter(r => r.category === 'checklist').map((r, i) => ({
      num: i + 1, desc: r.label, status: 'pending' as ItemStatus, observation: null as string | null,
      ruleCode: r.code, ruleDesc: r.desc, result: null as ValidationResult | null,
    }))
  }, [parsedPoints, hasAutocontrole, hasResults, activeRules, systemResults])

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

  const handleCorrect = useCallback((resultId: string, newStatut: string) => {
    if (onOptimisticUpdate) onOptimisticUpdate(resultId, newStatut)
    updateValidationResult(dossier.id, resultId, { statut: newStatut })
      .then(() => toast('success', 'Corrige'))
      .catch(e => {
        toast('error', e instanceof Error ? e.message : 'Erreur de correction')
        if (onRefreshResults) onRefreshResults()
      })
  }, [dossier.id, onOptimisticUpdate, onRefreshResults, toast])

  const startEditing = useCallback((r: ValidationResult) => {
    setEditingResult(r.id)
    setEditValues({
      valeurTrouvee: r.valeurTrouvee || '',
      valeurAttendue: r.valeurAttendue || '',
      commentaire: r.commentaire || ''
    })
  }, [])

  const cancelEditing = useCallback(() => {
    setEditingResult(null)
    setEditValues({ valeurTrouvee: '', valeurAttendue: '', commentaire: '' })
  }, [])

  const saveEditing = useCallback(async (resultId: string, alsoRerun = false) => {
    setSavingId(resultId)
    try {
      const updates = {
        valeurTrouvee: editValues.valeurTrouvee || undefined,
        valeurAttendue: editValues.valeurAttendue || undefined,
        commentaire: editValues.commentaire || undefined,
      }
      if (alsoRerun) {
        const results = await correctAndRerun(dossier.id, resultId, updates)
        if (onReplaceResults) onReplaceResults(results)
        const affectedCodes = new Set(results.map(r => r.regle))
        setRecentlyRerun(affectedCodes)
        setTimeout(() => setRecentlyRerun(new Set()), 2000)
        toast('success', `Corrige et ${results.length} controle(s) relance(s)`)
      } else {
        await updateValidationResult(dossier.id, resultId, updates)
        toast('success', 'Valeurs corrigees')
        if (onRefreshResults) onRefreshResults()
      }
      setEditingResult(null)
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally {
      setSavingId(null)
    }
  }, [dossier.id, editValues, onRefreshResults, onReplaceResults, toast])

  const handleRerun = useCallback(async (regle: string) => {
    if (!onRerunRule) return
    setRerunning(regle)
    try {
      await onRerunRule(regle)
      const scope = cascadeScope?.[regle.split('.')[0]] || [regle]
      setRecentlyRerun(new Set(scope))
      setTimeout(() => setRecentlyRerun(new Set()), 2000)
      toast('success', `Controle ${regle} relance`)
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally {
      setRerunning(null)
    }
  }, [onRerunRule, toast, cascadeScope])

  const { sysOk, sysKo, sysWarn, needsReviewCount, autoOk, autoKo } = useMemo(() => {
    let ok = 0, ko = 0, warn = 0, review = 0
    for (const r of systemResults) {
      if (r.statut === 'CONFORME') ok++
      else if (r.statut === 'NON_CONFORME') ko++
      else if (r.statut === 'AVERTISSEMENT') warn++
      if (needsHumanReview(r)) review++
    }
    let aOk = 0, aKo = 0
    for (const i of autocontroleItems) {
      if (i.status === 'ok') aOk++
      else if (i.status === 'ko') aKo++
    }
    return { sysOk: ok, sysKo: ko, sysWarn: warn, needsReviewCount: review, autoOk: aOk, autoKo: aKo }
  }, [systemResults, autocontroleItems])

  // Filter items based on filter mode
  const filterResult = useCallback((r: ValidationResult | undefined | null): boolean => {
    if (filterMode === 'all') return true
    if (!r) return filterMode !== 'conforme' // Show pending items in 'problems' view
    if (filterMode === 'problems') return r.statut === 'NON_CONFORME' || r.statut === 'AVERTISSEMENT'
    if (filterMode === 'conforme') return r.statut === 'CONFORME'
    return true
  }, [filterMode])

  const expandAllProblems = useCallback(() => {
    const problemCodes = new Set<string>()
    for (const r of systemResults) {
      if (r.statut === 'NON_CONFORME' || r.statut === 'AVERTISSEMENT') problemCodes.add(r.regle)
    }
    for (let i = 0; i < autocontroleItems.length; i++) {
      if (autocontroleItems[i].status === 'ko' || autocontroleItems[i].status === 'warn') problemCodes.add(`auto-${i}`)
    }
    setExpandedItems(problemCodes)
  }, [systemResults, autocontroleItems])

  const systemCollapsed = collapsedBlocks.has('system')
  const autoCollapsed = collapsedBlocks.has('autocontrole')

  const flatItems = useMemo(() => {
    const items: { key: string; code: string; result?: ValidationResult }[] = []
    for (const group of groupedSystem) {
      for (const item of group.items) {
        if (filterResult(item.result)) items.push({ key: item.code, code: item.code, result: item.result || undefined })
      }
    }
    autocontroleItems.forEach((item, i) => {
      items.push({ key: `auto-${i}`, code: `R12.${String(item.num).padStart(2, '0')}`, result: item.result || undefined })
    })
    return items
  }, [groupedSystem, autocontroleItems, filterResult])

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return
      if (e.key === 'j' || e.key === 'J') {
        e.preventDefault()
        setFocusedIdx(prev => {
          const next = Math.min(prev + 1, flatItems.length - 1)
          setExpandedItems(s => { const n = new Set(s); n.add(flatItems[next].key); return n })
          return next
        })
      }
      if (e.key === 'k' || e.key === 'K') {
        e.preventDefault()
        setFocusedIdx(prev => {
          const next = Math.max(prev - 1, 0)
          setExpandedItems(s => { const n = new Set(s); n.add(flatItems[next].key); return n })
          return next
        })
      }
      if ((e.key === 'e' || e.key === 'E') && focusedIdx >= 0 && focusedIdx < flatItems.length) {
        const item = flatItems[focusedIdx]
        if (item.result) startEditing(item.result)
      }
      if ((e.key === 'r' || e.key === 'R') && focusedIdx >= 0 && focusedIdx < flatItems.length) {
        handleRerun(flatItems[focusedIdx].code)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [flatItems, focusedIdx, startEditing, handleRerun])

  useEffect(() => {
    if (focusedIdx < 0 || !containerRef.current) return
    const key = flatItems[focusedIdx]?.key
    if (!key) return
    const el = containerRef.current.querySelector(`[data-vblock-key="${key}"]`)
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [focusedIdx, flatItems])

  return (
    <div ref={containerRef} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>

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
                <span style={{ color: '#10b981' }}>{sysOk} OK</span>
                {sysKo > 0 && <> &middot; <span style={{ color: '#ef4444', fontWeight: 800 }}>{sysKo} KO</span></>}
                {sysWarn > 0 && <> &middot; <span style={{ color: '#f59e0b' }}>{sysWarn} !</span></>}
                &middot; {systemResults.length} total
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
          {hasResults && (
            <div className="vblock-toolbar">
              <Legend />
              <div className="vblock-filters">
                <button className={`vblock-filter-btn ${filterMode === 'all' ? 'active' : ''}`} onClick={() => setFilterMode('all')}>
                  Tout ({systemResults.length})
                </button>
                <button className={`vblock-filter-btn vblock-filter-problems ${filterMode === 'problems' ? 'active' : ''}`} onClick={() => setFilterMode('problems')} disabled={sysKo + sysWarn === 0}>
                  <Filter size={9} /> Problemes ({sysKo + sysWarn})
                </button>
                <button className={`vblock-filter-btn vblock-filter-ok ${filterMode === 'conforme' ? 'active' : ''}`} onClick={() => setFilterMode('conforme')}>
                  Conformes ({sysOk})
                </button>
                {(sysKo + sysWarn) > 0 && (
                  <button className="vblock-filter-btn vblock-filter-expand" onClick={e => { e.stopPropagation(); expandAllProblems() }} title="Deployer tous les problemes">
                    <Eye size={9} /> Voir problemes
                  </button>
                )}
              </div>
            </div>
          )}
          {hasResults && <ProgressBar results={systemResults} />}
          <div className="vblock-inner">
            {hasResults ? (
              groupedSystem.map(group => {
                const visibleItems = group.items.filter(item => filterResult(item.result))
                if (visibleItems.length === 0) return null
                return (
                <div key={group.key}>
                  <div className="vblock-group-header">{group.label}</div>
                  {visibleItems.map(item => {
                    const r = item.result
                    const status: ItemStatus = r ? statutToItemStatus(r.statut) : 'pending'
                    const sd = STATUS_DISPLAY[status]
                    const isExpanded = expandedItems.has(item.code)
                    const conf = r ? confidenceLevel(r) : null
                    const review = r ? needsHumanReview(r) : false

                    return (
                      <div key={item.code}>
                        <div
                          data-vblock-key={item.code}
                          className={`vblock-item ${review ? 'vblock-item-review' : ''} ${recentlyRerun.has(item.code) ? 'vblock-item-rerunning' : ''} ${focusedIdx >= 0 && flatItems[focusedIdx]?.key === item.code ? 'vblock-item-focused' : ''}`}
                          onClick={() => toggleItem(item.code)}
                          role="button"
                          tabIndex={0}
                          onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleItem(item.code) } }}
                          style={{ cursor: 'pointer' }}
                        >
                          <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                          <span className="vblock-code">{item.code}</span>
                          <div className="vblock-content">
                            <div className="vblock-label">
                              {item.label}
                              {isStale(r, dossier.documents) && (
                                <span className="vblock-stale-badge" style={{ marginLeft: 6 }}>
                                  <AlertTriangle size={8} /> Obsolete
                                </span>
                              )}
                            </div>
                            {r?.detail && !isExpanded && <div className="vblock-detail">{r.detail}</div>}
                            {/* Document sources inline */}
                            {r?.documentIds && !isExpanded && (onOpenPreview || onNavigateDoc) && (
                              <div className="vblock-doc-links">
                                {r.documentIds.slice(0, 3).map(docId => {
                                  const doc = dossier.documents.find(d => d.id === docId)
                                  return doc ? (
                                    <button key={docId} className="vblock-doc-link"
                                      onClick={e => { e.stopPropagation(); (onOpenPreview || onNavigateDoc)?.(docId) }}>
                                      <FileText size={10} /> {TYPE_DOCUMENT_LABELS[doc.typeDocument]?.split(' ')[0] || doc.typeDocument}
                                    </button>
                                  ) : null
                                })}
                              </div>
                            )}
                          </div>

                          {/* Inline correction dropdown OR static status badge */}
                          {r?.id ? (
                            <select
                              className="vblock-select"
                              value={r.statut}
                              onChange={e => { e.stopPropagation(); handleCorrect(r.id!, e.target.value) }}
                              onClick={e => e.stopPropagation()}
                              style={{ background: sd.bg, color: sd.color }}
                              aria-label={`Corriger le statut de ${item.code}`}
                            >
                              {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                            </select>
                          ) : (
                            <span className="vblock-status-badge" style={{ background: sd.bg, color: sd.color }}>
                              {sd.label}
                            </span>
                          )}

                          {r?.statutOriginal && r.statutOriginal !== r.statut && (
                            <span className="vblock-corrected">corrige</span>
                          )}

                          {r && onRerunRule && (
                            <button className="vblock-rerun-inline" title="Relancer ce controle"
                              onClick={e => { e.stopPropagation(); handleRerun(item.code) }}
                              disabled={rerunning === item.code}>
                              {rerunning === item.code ? <Loader2 size={11} className="spin" /> : <RefreshCw size={11} />}
                            </button>
                          )}

                          {r?.id && editingResult !== r.id && (
                            <button className="vblock-edit-inline" title="Corriger les valeurs"
                              onClick={e => { e.stopPropagation(); startEditing(r) }}>
                              <Edit3 size={11} />
                            </button>
                          )}

                          {review && (
                            <span className="vblock-review-icon" title="A verifier manuellement">
                              <AlertTriangle size={12} />
                            </span>
                          )}

                          {isExpanded ? <ChevronUp size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} /> : <ChevronDown size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} />}
                        </div>

                        {isExpanded && (
                          <div className="vblock-expand">
                            <div className="vblock-expand-desc">{item.desc}</div>

                            {r?.id && editingResult === r.id ? (
                              <EditPanel resultId={r.id!} editValues={editValues} setEditValues={setEditValues}
                                onSave={saveEditing} onCancel={cancelEditing} saving={savingId === r.id}
                                cascadeSize={cascadeScope?.[item.code]?.length} />
                            ) : (
                              <>
                                {r?.evidences && r.evidences.length > 0 ? (
                                  <EvidenceList evidences={r.evidences} statut={r.statut} onOpenDocument={onOpenPreview} />
                                ) : (r?.valeurAttendue || r?.valeurTrouvee) && (
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
                              </>
                            )}

                            {r?.detail && <div className="vblock-expand-detail">{r.detail}</div>}

                            {r?.commentaire && (
                              <div className="vblock-expand-detail" style={{ color: 'var(--info)' }}>
                                <MessageSquare size={10} style={{ display: 'inline', verticalAlign: 'middle' }} /> {r.commentaire}
                                {r.corrigePar && <span style={{ color: 'var(--ink-40)' }}> — {r.corrigePar}</span>}
                              </div>
                            )}

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
                            </div>
                            {r?.documentIds && (!r.evidences || r.evidences.length === 0) && (
                              <>
                                <div className="vblock-expand-docs">
                                  {r.documentIds.map(docId => {
                                    const doc = dossier.documents.find(d => d.id === docId)
                                    if (!doc) return null
                                    return (
                                      <button key={docId} className="btn btn-secondary btn-sm"
                                        onClick={e => { e.stopPropagation(); (onOpenPreview || onNavigateDoc)?.(docId) }}>
                                        <FileText size={11} /> {TYPE_DOCUMENT_LABELS[doc.typeDocument] || doc.typeDocument}
                                      </button>
                                    )
                                  })}
                                </div>
                              </>
                            )}
                            {onToggleRule && (
                              <div style={{ marginTop: 8, borderTop: '1px solid var(--ink-05)', paddingTop: 8, display: 'flex', gap: 6 }}>
                                <button className="btn btn-secondary btn-sm" onClick={e => {
                                  e.stopPropagation()
                                  onToggleRule(item.code, !isRuleActive(ruleConfig, item.code))
                                }}
                                  style={{ fontSize: 10, color: isRuleActive(ruleConfig, item.code) ? 'var(--ink-40)' : 'var(--danger)' }}>
                                  {isRuleActive(ruleConfig, item.code) ? 'Desactiver cette regle' : 'Reactiver cette regle'}
                                </button>
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>
                )
              })
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
          <div className="vblock-actions">
            {hasResults && (
              <button className="btn btn-sm vblock-btn-rerun" onClick={e => { e.stopPropagation(); onValidate() }} disabled={validating} aria-label="Relancer la verification">
                {validating ? <Loader2 size={10} className="spin" /> : '\u21bb'}
              </button>
            )}
            {autoCollapsed ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronUp size={14} aria-hidden="true" />}
          </div>
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
                const r = item.result
                const sd = STATUS_DISPLAY[item.status]
                const isExpanded = expandedItems.has(`auto-${i}`)
                const review = item.status === 'ko' || item.status === 'warn'
                const conf = r ? confidenceLevel(r) : null
                const ckRuleCode = `R12.${String(item.num).padStart(2, '0')}`
                return (
                  <div key={i}>
                    <div
                      data-vblock-key={`auto-${i}`}
                      className={`vblock-item ${review ? 'vblock-item-review' : ''} ${recentlyRerun.has(ckRuleCode) ? 'vblock-item-rerunning' : ''} ${focusedIdx >= 0 && flatItems[focusedIdx]?.key === `auto-${i}` ? 'vblock-item-focused' : ''}`}
                      onClick={() => toggleItem(`auto-${i}`)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleItem(`auto-${i}`) } }}
                      style={{ cursor: 'pointer' }}>
                      <span className="vblock-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                      <span className="vblock-code" style={{ width: 22 }}>{item.num}</span>
                      <div className="vblock-content">
                        <div className="vblock-label">{item.desc}</div>
                        {r?.detail && !isExpanded && <div className="vblock-detail">{r.detail}</div>}
                        {!r?.detail && item.observation && item.observation !== '\u2014' && !isExpanded && (
                          <div className="vblock-detail">{item.observation}</div>
                        )}
                      </div>

                      {/* Source tag */}
                      {r?.source ? (
                        <span className={`vblock-source-tag ${r.source === 'CHECKLIST' ? 'llm' : 'deterministe'}`}>
                          {sourceLabel(r.source)}
                        </span>
                      ) : item.ruleCode ? (
                        <span className="vblock-source-tag deterministe">{item.ruleCode}</span>
                      ) : null}

                      {/* Inline correction */}
                      {r?.id && (
                        <select
                          className="vblock-select"
                          value={r.statut}
                          onChange={e => { e.stopPropagation(); handleCorrect(r.id!, e.target.value) }}
                          onClick={e => e.stopPropagation()}
                          style={{ background: sd.bg, color: sd.color }}
                          aria-label={`Corriger le statut du point ${item.num}`}
                        >
                          {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                        </select>
                      )}

                      {/* Status label */}
                      {!r?.id && (
                        <span className="vblock-status-badge" style={{ background: sd.bg, color: sd.color }}>
                          {sd.label}
                        </span>
                      )}

                      {r?.statutOriginal && r.statutOriginal !== r.statut && (
                        <span className="vblock-corrected">corrige</span>
                      )}

                      {r && onRerunRule && (
                        <button className="vblock-rerun-inline" title="Relancer ce controle"
                          onClick={e => { e.stopPropagation(); handleRerun(ckRuleCode) }}
                          disabled={rerunning === ckRuleCode}>
                          {rerunning === ckRuleCode ? <Loader2 size={11} className="spin" /> : <RefreshCw size={11} />}
                        </button>
                      )}

                      {r?.id && editingResult !== r.id && (
                        <button className="vblock-edit-inline" title="Corriger les valeurs"
                          onClick={e => { e.stopPropagation(); startEditing(r) }}>
                          <Edit3 size={11} />
                        </button>
                      )}

                      {review && (
                        <span className="vblock-review-icon" title="A verifier manuellement">
                          <AlertTriangle size={12} />
                        </span>
                      )}

                      {isExpanded ? <ChevronUp size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} /> : <ChevronDown size={12} style={{ color: 'var(--ink-30)', flexShrink: 0 }} />}
                    </div>

                    {isExpanded && (
                      <div className="vblock-expand">
                        {item.ruleDesc && <div className="vblock-expand-desc">{item.ruleDesc}</div>}

                        {r?.id && editingResult === r.id ? (
                          <EditPanel resultId={r.id!} editValues={editValues} setEditValues={setEditValues}
                            onSave={saveEditing} onCancel={cancelEditing} saving={savingId === r.id}
                            cascadeSize={cascadeScope?.[ckRuleCode]?.length} />
                        ) : (
                          <>
                            {r?.evidences && r.evidences.length > 0 ? (
                              <EvidenceList evidences={r.evidences} statut={r.statut} onOpenDocument={onOpenPreview} />
                            ) : (r?.valeurAttendue || r?.valeurTrouvee) && (
                              <div className="vblock-expand-compare">
                                {r.valeurAttendue && (
                                  <div className="vblock-expand-val">
                                    <span className="vblock-expand-val-label">Documents verifies</span>
                                    <span className="vblock-expand-val-data">{r.valeurAttendue}</span>
                                  </div>
                                )}
                                {r.valeurTrouvee && (
                                  <div className="vblock-expand-val">
                                    <span className="vblock-expand-val-label">Resultat</span>
                                    <span className={`vblock-expand-val-data ${r.statut === 'NON_CONFORME' ? 'danger' : ''}`}>{r.valeurTrouvee}</span>
                                  </div>
                                )}
                              </div>
                            )}
                          </>
                        )}

                        {r?.detail && (
                          <div className="vblock-expand-detail"><strong>Detail :</strong> {r.detail}</div>
                        )}
                        {item.observation && (
                          <div className="vblock-expand-detail"><strong>Observation autocontrole :</strong> {item.observation}</div>
                        )}

                        {r?.commentaire && (
                          <div className="vblock-expand-detail" style={{ color: 'var(--info)' }}>
                            <MessageSquare size={10} style={{ display: 'inline', verticalAlign: 'middle' }} /> {r.commentaire}
                            {r.corrigePar && <span style={{ color: 'var(--ink-40)' }}> — {r.corrigePar}</span>}
                          </div>
                        )}

                        <div className="vblock-expand-meta">
                          <span className="vblock-expand-meta-item">
                            Source : <strong>{r ? sourceLabel(r.source) : hasAutocontrole ? 'Autocontrole — extrait du document' : 'Verifie par le systeme'}</strong>
                          </span>
                          {conf && (
                            <span className="vblock-expand-meta-item">
                              Confiance : <strong style={{ color: conf.color }}>{conf.pct}%</strong>
                              <span className="vblock-confidence-bar">
                                <span className="vblock-confidence-fill" style={{ width: `${conf.pct}%`, background: conf.color }} />
                              </span>
                            </span>
                          )}
                        </div>

                        {r?.documentIds && (!r.evidences || r.evidences.length === 0) && (
                          <>
                            <div className="vblock-expand-docs">
                              {r.documentIds.map(docId => {
                                const doc = dossier.documents.find(d => d.id === docId)
                                if (!doc) return null
                                return (
                                  <button key={docId} className="btn btn-secondary btn-sm"
                                    onClick={e => { e.stopPropagation(); (onOpenPreview || onNavigateDoc)?.(docId) }}>
                                    <FileText size={11} /> {TYPE_DOCUMENT_LABELS[doc.typeDocument] || doc.typeDocument}
                                  </button>
                                )
                              })}
                            </div>
                          </>
                        )}
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
