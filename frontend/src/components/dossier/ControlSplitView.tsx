import { useState, useMemo, useCallback, useEffect, memo } from 'react'
import type { DossierDetail, ValidationResult, DocumentInfo } from '../../api/dossierTypes'
import { updateValidationResult, correctAndRerun, getDocumentFileUrl, downloadWithAuth } from '../../api/dossierApi'
import { getActiveRules, RULE_GROUPS, ALL_RULES } from '../../config/validationRules'
import { parseChecklistPoints, STATUS_DISPLAY, STATUT_OPTIONS, statutToItemStatus, estValideToItemStatus, type ItemStatus } from '../../config/checklistUtils'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import type { TypeDocument } from '../../api/dossierTypes'
import { useToast } from '../Toast'
import EvidenceList from './EvidenceList'
import {
  Zap, ShieldCheck, Loader2, AlertTriangle,
  FileText, RefreshCw, Eye, Edit3, Save, X,
  MessageSquare, ChevronLeft, ChevronRight, Download,
  Zap as ZapIcon, MousePointer
} from 'lucide-react'

type FilterMode = 'all' | 'problems' | 'conforme'
type RuleConfigShape = { global: Record<string, boolean>; overrides: Record<string, boolean> }

interface Props {
  dossier: DossierDetail
  dossierId: string
  validating: boolean
  onValidate: () => void
  onRefreshResults?: () => void
  onRerunRule?: (regle: string) => Promise<void>
  onReplaceResults?: (results: ValidationResult[]) => void
  onOptimisticUpdate?: (resultId: string, newStatut: string) => void
  onToggleRule?: (regle: string, enabled: boolean) => void
  ruleConfig?: RuleConfigShape
  cascadeScope?: Record<string, string[]>
}

interface RuleItem {
  key: string
  code: string
  label: string
  desc: string
  result: ValidationResult | undefined
  status: ItemStatus
  group: string
  category: 'system' | 'checklist'
}

function confidenceLevel(r: ValidationResult): { label: string; color: string; pct: number } {
  if (r.source === 'deterministe' || r.source === 'regex' || r.source === 'DETERMINISTE') return { label: 'Fiable', color: 'var(--info)', pct: 100 }
  if (r.source === 'llm' || r.source === 'ia') {
    return r.statut === 'CONFORME' ? { label: 'Fiable', color: 'var(--success)', pct: 85 } : { label: 'A verifier', color: 'var(--warning)', pct: 60 }
  }
  if (r.source === 'CHECKLIST') return { label: 'A verifier', color: 'var(--warning)', pct: 70 }
  return { label: 'Fiable', color: 'var(--ink-40)', pct: 90 }
}

function isStale(r: ValidationResult | undefined, documents: DocumentInfo[]): boolean {
  if (!r?.dateExecution || !r?.documentIds?.length) return false
  const execTime = new Date(r.dateExecution).getTime()
  return r.documentIds.some(docId => {
    const doc = documents.find(d => d.id === docId)
    return doc && new Date(doc.dateUpload).getTime() > execTime
  })
}

function useBlobUrl(apiUrl: string | null) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!apiUrl) { setBlobUrl(null); return }
    let cancelled = false
    let created: string | null = null
    const auth = localStorage.getItem('recondoc_auth')
    setLoading(true); setError(null)
    fetch(apiUrl, { headers: auth ? { 'Authorization': `Basic ${auth}` } : undefined })
      .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.blob() })
      .then(blob => { if (!cancelled) { created = URL.createObjectURL(blob); setBlobUrl(created) } })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : 'Erreur') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true; if (created) URL.revokeObjectURL(created); setBlobUrl(null) }
  }, [apiUrl])
  return { blobUrl, loading, error }
}

/* ===== LEFT PANEL ===== */
function LeftPanel({ items, selectedKey, onSelect, filterMode, onFilterChange, counts }: {
  items: RuleItem[]
  selectedKey: string | null
  onSelect: (key: string) => void
  filterMode: FilterMode
  onFilterChange: (m: FilterMode) => void
  counts: { ok: number; ko: number; warn: number; total: number }
}) {
  const filtered = useMemo(() => {
    if (filterMode === 'all') return items
    if (filterMode === 'problems') return items.filter(i => i.status === 'ko' || i.status === 'warn')
    return items.filter(i => i.status === 'ok')
  }, [items, filterMode])

  const grouped = useMemo(() => {
    const groups: { label: string; items: RuleItem[] }[] = []
    let cur: typeof groups[0] | null = null
    for (const item of filtered) {
      if (!cur || cur.label !== item.group) { cur = { label: item.group, items: [] }; groups.push(cur) }
      cur.items.push(item)
    }
    return groups
  }, [filtered])

  return (
    <div className="ctrl-split-left">
      <div className="ctrl-split-left-header">
        <h3><ShieldCheck size={10} /> Controles</h3>
        <button className={`vblock-filter-btn ${filterMode === 'all' ? 'active' : ''}`} onClick={() => onFilterChange('all')}>
          Tout <span style={{ opacity: 0.6 }}>{counts.total}</span>
        </button>
        <button className={`vblock-filter-btn vblock-filter-problems ${filterMode === 'problems' ? 'active' : ''}`} onClick={() => onFilterChange('problems')}>
          KO <span style={{ opacity: 0.6 }}>{counts.ko + counts.warn}</span>
        </button>
        <button className={`vblock-filter-btn vblock-filter-ok ${filterMode === 'conforme' ? 'active' : ''}`} onClick={() => onFilterChange('conforme')}>
          OK <span style={{ opacity: 0.6 }}>{counts.ok}</span>
        </button>
      </div>
      {/* Progress */}
      {counts.total > 0 && (
        <div className="vblock-progress">
          <span className="vblock-progress-label">{counts.ok}/{counts.total}</span>
          <div className="vblock-progress-bar">
            <div className="vblock-progress-segment" style={{ width: `${(counts.ok / counts.total) * 100}%`, background: 'var(--success)' }} />
            <div className="vblock-progress-segment" style={{ width: `${(counts.ko / counts.total) * 100}%`, background: 'var(--danger)' }} />
            <div className="vblock-progress-segment" style={{ width: `${(counts.warn / counts.total) * 100}%`, background: 'var(--warning)' }} />
          </div>
        </div>
      )}
      <div className="ctrl-split-left-list">
        {grouped.map(g => (
          <div key={g.label}>
            <div className="ctrl-split-group-label">{g.label}</div>
            {g.items.map(item => {
              const sd = STATUS_DISPLAY[item.status]
              return (
                <div
                  key={item.key}
                  className={`ctrl-split-rule ${selectedKey === item.key ? 'selected' : ''}`}
                  onClick={() => onSelect(item.key)}
                >
                  <span className="ctrl-split-pill" style={{ background: sd.bg, color: sd.color }}>{sd.icon}</span>
                  <span className="ctrl-split-code">{item.code}</span>
                  <span className="ctrl-split-label">{item.label}</span>
                </div>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

/* ===== CENTER PANEL ===== */
function CenterPanel({ item, dossier, dossierId, onRefreshResults, onReplaceResults, onRerunRule, onOptimisticUpdate, onOpenDoc, cascadeScope, rerunning }: {
  item: RuleItem | null
  dossier: DossierDetail
  dossierId: string
  onRefreshResults?: () => void
  onReplaceResults?: (results: ValidationResult[]) => void
  onRerunRule?: (regle: string) => Promise<void>
  onOptimisticUpdate?: (resultId: string, newStatut: string) => void
  onOpenDoc: (docId: string, field?: string) => void
  cascadeScope?: Record<string, string[]>
  rerunning: string | null
}) {
  const { toast } = useToast()
  const [editing, setEditing] = useState(false)
  const [editValues, setEditValues] = useState({ valeurTrouvee: '', valeurAttendue: '', commentaire: '' })
  const [saving, setSaving] = useState(false)

  const r = item?.result
  const conf = r ? confidenceLevel(r) : null
  const stale = isStale(r, dossier.documents)
  const cascadeSize = item ? (cascadeScope?.[item.code.split('.')[0]]?.length || 1) : 0

  const startEdit = useCallback(() => {
    if (!r) return
    setEditing(true)
    setEditValues({ valeurTrouvee: r.valeurTrouvee || '', valeurAttendue: r.valeurAttendue || '', commentaire: r.commentaire || '' })
  }, [r])

  const saveEdit = useCallback(async (alsoRerun: boolean) => {
    if (!r?.id) return
    setSaving(true)
    try {
      const updates = {
        valeurTrouvee: editValues.valeurTrouvee || undefined,
        valeurAttendue: editValues.valeurAttendue || undefined,
        commentaire: editValues.commentaire || undefined,
      }
      if (alsoRerun) {
        const results = await correctAndRerun(dossierId, r.id, updates)
        if (onReplaceResults) onReplaceResults(results)
        toast('success', `Corrige et ${results.length} controle(s) relance(s)`)
      } else {
        await updateValidationResult(dossierId, r.id, updates)
        toast('success', 'Corrige')
        if (onRefreshResults) onRefreshResults()
      }
      setEditing(false)
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setSaving(false) }
  }, [r, dossierId, editValues, onReplaceResults, onRefreshResults, toast])

  const handleRerun = useCallback(async () => {
    if (!item || !onRerunRule) return
    try { await onRerunRule(item.code) } catch {}
  }, [item, onRerunRule])

  const handleCorrect = useCallback((newStatut: string) => {
    if (!r?.id) return
    if (onOptimisticUpdate) onOptimisticUpdate(r.id, newStatut)
    updateValidationResult(dossierId, r.id, { statut: newStatut })
      .then(() => toast('success', 'Corrige'))
      .catch(e => { toast('error', e instanceof Error ? e.message : 'Erreur'); onRefreshResults?.() })
  }, [r, dossierId, onOptimisticUpdate, onRefreshResults, toast])

  if (!item) {
    return (
      <div className="ctrl-split-center">
        <div className="ctrl-split-center-empty">
          <MousePointer size={32} />
          <p>Selectionnez un controle dans la liste pour voir le detail, les preuves et le document source</p>
        </div>
      </div>
    )
  }

  const sd = STATUS_DISPLAY[item.status]

  return (
    <div className="ctrl-split-center">
      <div className="ctrl-split-center-header">
        <span className="vblock-pill" style={{ background: sd.bg, color: sd.color, width: 28, height: 28, fontSize: 13 }}>{sd.icon}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h3 className="ctrl-split-center-header" style={{ padding: 0, background: 'none', border: 'none' }}>
            {item.code} — {item.label}
          </h3>
        </div>
        <span className="status-badge" style={{ background: sd.bg, color: sd.color }}>
          {r?.statut || 'EN ATTENTE'}
        </span>
        {stale && (
          <span className="vblock-stale-badge"><AlertTriangle size={8} /> Obsolete</span>
        )}
      </div>

      <div className="ctrl-split-center-body">
        {/* Description */}
        {item.desc && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-desc">{item.desc}</div>
          </div>
        )}

        {/* Detail from validation */}
        {r?.detail && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title"><FileText size={10} /> Detail</div>
            <div style={{ fontSize: 12.5, color: 'var(--ink-50)', lineHeight: 1.6 }}>{r.detail}</div>
          </div>
        )}

        {/* Evidence */}
        {r?.evidences && r.evidences.length > 0 && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title"><Eye size={10} /> Preuves</div>
            <EvidenceList evidences={r.evidences} statut={r.statut} onOpenDocument={onOpenDoc} />
          </div>
        )}

        {/* Values comparison (when no evidences) */}
        {(!r?.evidences || r.evidences.length === 0) && (r?.valeurAttendue || r?.valeurTrouvee) && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Comparaison</div>
            <div className="ctrl-detail-values">
              {r?.valeurAttendue && (
                <div className="ctrl-detail-val">
                  <div className="ctrl-detail-val-label">Attendu</div>
                  <div className="ctrl-detail-val-data">{r.valeurAttendue}</div>
                </div>
              )}
              {r?.valeurTrouvee && (
                <div className="ctrl-detail-val">
                  <div className="ctrl-detail-val-label">Trouve</div>
                  <div className={`ctrl-detail-val-data ${r.statut === 'NON_CONFORME' ? 'danger' : ''}`}>{r.valeurTrouvee}</div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Edit panel */}
        {editing && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title"><Edit3 size={10} /> Correction</div>
            <div className="vblock-edit-panel" style={{ margin: 0 }}>
              <div className="vblock-edit-row">
                <label>Valeur attendue</label>
                <input className="form-input" value={editValues.valeurAttendue}
                  onChange={e => setEditValues(v => ({ ...v, valeurAttendue: e.target.value }))} />
              </div>
              <div className="vblock-edit-row">
                <label>Valeur trouvee</label>
                <input className="form-input" value={editValues.valeurTrouvee}
                  onChange={e => setEditValues(v => ({ ...v, valeurTrouvee: e.target.value }))} />
              </div>
              <div className="vblock-edit-row">
                <label><MessageSquare size={10} /> Commentaire</label>
                <input className="form-input" value={editValues.commentaire}
                  onChange={e => setEditValues(v => ({ ...v, commentaire: e.target.value }))}
                  placeholder="Raison de la correction..." />
              </div>
              <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
                <button className="btn btn-primary btn-sm" disabled={saving} onClick={() => saveEdit(true)}>
                  {saving ? <Loader2 size={11} className="spin" /> : <ZapIcon size={11} />} Sauvegarder + Relancer
                  {cascadeSize > 1 && <span style={{ marginLeft: 4, background: 'rgba(255,255,255,0.25)', padding: '1px 5px', borderRadius: 8, fontSize: 9, fontWeight: 700 }}>+{cascadeSize - 1}</span>}
                </button>
                <button className="btn btn-secondary btn-sm" disabled={saving} onClick={() => saveEdit(false)}>
                  <Save size={11} /> Sauvegarder
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => setEditing(false)} disabled={saving}>
                  <X size={11} /> Annuler
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Meta */}
        {r && (
          <div className="ctrl-detail-meta">
            {conf && (
              <span className="ctrl-detail-meta-item">
                Confiance : <strong style={{ color: conf.color }}>{conf.pct}%</strong>
              </span>
            )}
            {r.source && (
              <span className="ctrl-detail-meta-item">
                <span className={`vblock-source-tag ${r.source === 'deterministe' || r.source === 'DETERMINISTE' ? 'deterministe' : 'llm'}`}>
                  {r.source === 'deterministe' || r.source === 'DETERMINISTE' ? 'Verifie' : 'Extrait par IA'}
                </span>
              </span>
            )}
            {r.corrigePar && (
              <span className="ctrl-detail-meta-item vblock-corrected" style={{ fontSize: 10 }}>
                Corrige par {r.corrigePar}
              </span>
            )}
          </div>
        )}

        {/* Actions */}
        <div className="ctrl-detail-actions">
          {r && !editing && (
            <>
              <button className="btn btn-secondary btn-sm" onClick={startEdit}>
                <Edit3 size={11} /> Corriger
              </button>
              <button className="btn btn-secondary btn-sm" onClick={handleRerun} disabled={rerunning === item.code}>
                {rerunning === item.code ? <Loader2 size={11} className="spin" /> : <RefreshCw size={11} />} Relancer
                {cascadeSize > 1 && <span style={{ marginLeft: 4, fontSize: 9, opacity: 0.6 }}>+{cascadeSize - 1}</span>}
              </button>
              <select className="vblock-select" value={r.statut}
                onChange={e => handleCorrect(e.target.value)}
                onClick={e => e.stopPropagation()}>
                {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </>
          )}
        </div>

        {/* Document links */}
        {r?.documentIds && r.documentIds.length > 0 && (
          <div className="ctrl-detail-section" style={{ marginTop: 12 }}>
            <div className="ctrl-detail-section-title"><FileText size={10} /> Documents source</div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {r.documentIds.map(docId => {
                const doc = dossier.documents.find(d => d.id === docId)
                if (!doc) return null
                return (
                  <button key={docId} className="btn btn-secondary btn-sm" onClick={() => onOpenDoc(docId)}>
                    <FileText size={10} /> {TYPE_DOCUMENT_LABELS[doc.typeDocument as TypeDocument]?.split(' ')[0] || doc.typeDocument}
                  </button>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

/* ===== RIGHT PANEL (inline PDF viewer) ===== */
function RightPanel({ dossierId, docId, documents, highlightField, onChangeDoc }: {
  dossierId: string
  docId: string | null
  documents: DocumentInfo[]
  highlightField: string | null
  onChangeDoc: (id: string) => void
}) {
  const activeDoc = docId ? documents.find(d => d.id === docId) : null
  const apiUrl = useMemo(() => activeDoc ? getDocumentFileUrl(dossierId, activeDoc.id) : null, [dossierId, activeDoc])
  const { blobUrl, loading, error } = useBlobUrl(apiUrl)
  const currentIdx = docId ? documents.findIndex(d => d.id === docId) : -1

  if (!activeDoc) {
    return (
      <div className="ctrl-split-right">
        <div className="ctrl-split-right-empty">
          <FileText size={32} />
          <p style={{ fontSize: 13 }}>Cliquez sur "Voir" dans les preuves pour afficher le document source ici</p>
        </div>
      </div>
    )
  }

  const isPdf = activeDoc.nomFichier.toLowerCase().endsWith('.pdf')
  const isImage = /\.(png|jpe?g|webp|gif)$/i.test(activeDoc.nomFichier)

  return (
    <div className="ctrl-split-right">
      <div className="preview-header">
        <FileText size={14} className="preview-header-icon" />
        <div className="preview-header-info">
          <div className="preview-header-title">{TYPE_DOCUMENT_LABELS[activeDoc.typeDocument] || activeDoc.typeDocument}</div>
          <div className="preview-header-sub">
            {activeDoc.nomFichier}
            {highlightField && <> &middot; <strong>champ : {highlightField}</strong></>}
          </div>
        </div>
        {documents.length > 1 && (
          <div className="preview-nav">
            <button className="btn btn-secondary btn-sm" disabled={currentIdx <= 0}
              onClick={() => currentIdx > 0 && onChangeDoc(documents[currentIdx - 1].id)}>
              <ChevronLeft size={12} />
            </button>
            {currentIdx + 1}/{documents.length}
            <button className="btn btn-secondary btn-sm" disabled={currentIdx >= documents.length - 1}
              onClick={() => currentIdx < documents.length - 1 && onChangeDoc(documents[currentIdx + 1].id)}>
              <ChevronRight size={12} />
            </button>
          </div>
        )}
        <button className="btn btn-secondary btn-sm" title="Telecharger"
          onClick={() => downloadWithAuth(getDocumentFileUrl(dossierId, activeDoc.id), activeDoc.nomFichier)}>
          <Download size={12} />
        </button>
      </div>
      <div className="preview-body">
        {loading && <div className="preview-loading">Chargement...</div>}
        {error && <div className="preview-error">Impossible de charger : {error}</div>}
        {blobUrl && !error && (
          isPdf ? (
            <iframe src={`${blobUrl}#view=FitH`} title={activeDoc.nomFichier}
              style={{ width: '100%', height: '100%', border: 'none', display: 'block' }} />
          ) : isImage ? (
            <div className="preview-image-wrap"><img src={blobUrl} alt={activeDoc.nomFichier} /></div>
          ) : (
            <div className="preview-unsupported">
              Format non previsualisable. <a href={blobUrl} download={activeDoc.nomFichier}>Telecharger</a>
            </div>
          )
        )}
      </div>
    </div>
  )
}

/* ===== MAIN SPLIT VIEW ===== */
export default memo(function ControlSplitView({ dossier, dossierId, validating, onValidate, onRefreshResults, onRerunRule, onReplaceResults, onOptimisticUpdate, cascadeScope }: Props) {
  const { toast } = useToast()
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [filterMode, setFilterMode] = useState<FilterMode>('all')
  const [previewDocId, setPreviewDocId] = useState<string | null>(null)
  const [highlightField, setHighlightField] = useState<string | null>(null)
  const [rerunning, setRerunning] = useState<string | null>(null)

  const activeRules = useMemo(() => getActiveRules(dossier.type as 'BC' | 'CONTRACTUEL'), [dossier.type])
  const systemRuleDefs = useMemo(() => activeRules.filter(r => r.category === 'system'), [activeRules])
  const results = dossier.resultatsValidation
  const parsedPoints = useMemo(() => parseChecklistPoints(dossier), [dossier])

  const items: RuleItem[] = useMemo(() => {
    const list: RuleItem[] = []

    for (const g of RULE_GROUPS) {
      const groupRuleCodes = systemRuleDefs.filter(r => (r as { group?: string }).group === g.key).map(r => r.code)
      for (const code of groupRuleCodes) {
        const ruleDef = systemRuleDefs.find(r => r.code === code)
        const fullDef = ALL_RULES.find(r => r.code === code)
        const result = results.find(r => r.regle === code || r.regle.startsWith(code + '.')) || undefined
        const status: ItemStatus = result
          ? statutToItemStatus(result.statut)
          : 'pending'
        list.push({ key: code, code, label: ruleDef?.label || code, desc: fullDef?.desc || '', result, status, group: g.label, category: 'system' })
      }
    }

    if (parsedPoints.length > 0) {
      for (const pt of parsedPoints) {
        const rCode = `R12.${String(pt.num).padStart(2, '0')}`
        const result = results.find(r => r.regle === rCode) || undefined
        const status = result ? statutToItemStatus(result.statut) : estValideToItemStatus(pt.estValide, results.length > 0)
        list.push({ key: `ck-${pt.num}`, code: rCode, label: pt.desc, desc: '', result, status, group: 'Autocontrole', category: 'checklist' })
      }
    }
    return list
  }, [systemRuleDefs, results, parsedPoints])

  const counts = useMemo(() => {
    let ok = 0, ko = 0, warn = 0
    for (const item of items) {
      if (item.status === 'ok') ok++
      else if (item.status === 'ko') ko++
      else if (item.status === 'warn') warn++
    }
    return { ok, ko, warn, total: items.length }
  }, [items])

  const selectedItem = useMemo(() => items.find(i => i.key === selectedKey) || null, [items, selectedKey])

  const handleOpenDoc = useCallback((docId: string, field?: string) => {
    setPreviewDocId(docId)
    setHighlightField(field || null)
  }, [])

  const handleRerunRule = useCallback(async (regle: string) => {
    if (!onRerunRule) return
    setRerunning(regle)
    try {
      await onRerunRule(regle)
      toast('success', `Controle ${regle} relance`)
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setRerunning(null) }
  }, [onRerunRule, toast])

  // Auto-select first problem on mount
  useEffect(() => {
    if (selectedKey || items.length === 0) return
    const first = items.find(i => i.status === 'ko' || i.status === 'warn') || items[0]
    if (first) setSelectedKey(first.key)
  }, [items, selectedKey])

  // Auto-open first evidence document when selecting a rule
  useEffect(() => {
    if (!selectedItem?.result?.evidences?.length) return
    const firstDocEvidence = selectedItem.result.evidences.find(e => e.documentId)
    if (firstDocEvidence?.documentId) {
      setPreviewDocId(firstDocEvidence.documentId)
      setHighlightField(firstDocEvidence.champ || null)
    }
  }, [selectedItem])

  // J/K keyboard navigation
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return
      if (e.key === 'j' || e.key === 'J') {
        e.preventDefault()
        setSelectedKey(prev => {
          const idx = items.findIndex(i => i.key === prev)
          return idx < items.length - 1 ? items[idx + 1].key : prev
        })
      }
      if (e.key === 'k' || e.key === 'K') {
        e.preventDefault()
        setSelectedKey(prev => {
          const idx = items.findIndex(i => i.key === prev)
          return idx > 0 ? items[idx - 1].key : prev
        })
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [items])

  return (
    <div>
      {/* Header bar */}
      <div className="card" style={{ padding: '10px 16px', marginBottom: 10, display: 'flex', alignItems: 'center', gap: 10 }}>
        <ShieldCheck size={16} style={{ color: 'var(--accent-deep)' }} />
        <span style={{ fontWeight: 700, fontSize: 14 }}>Verification du dossier</span>
        <span style={{ fontSize: 11, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)' }}>
          {counts.ok}/{counts.total} conformes
          {counts.ko > 0 && <> &middot; <span style={{ color: 'var(--danger)' }}>{counts.ko} KO</span></>}
        </span>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
          <button className="btn btn-primary btn-sm" onClick={onValidate} disabled={validating}>
            {validating ? <Loader2 size={12} className="spin" /> : <Zap size={12} />} Lancer verification
          </button>
        </div>
      </div>

      {/* 3-column split */}
      <div className="ctrl-split">
        <LeftPanel items={items} selectedKey={selectedKey} onSelect={setSelectedKey}
          filterMode={filterMode} onFilterChange={setFilterMode} counts={counts} />

        <CenterPanel item={selectedItem} dossier={dossier} dossierId={dossierId}
          onRefreshResults={onRefreshResults} onReplaceResults={onReplaceResults}
          onRerunRule={handleRerunRule} onOptimisticUpdate={onOptimisticUpdate}
          onOpenDoc={handleOpenDoc} cascadeScope={cascadeScope} rerunning={rerunning} />

        <RightPanel dossierId={dossierId} docId={previewDocId}
          documents={dossier.documents} highlightField={highlightField}
          onChangeDoc={(id) => { setPreviewDocId(id); setHighlightField(null) }} />
      </div>
    </div>
  )
})
