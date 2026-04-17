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
  ShieldCheck, Loader2,
  FileText, RefreshCw, Edit3, Save, X,
  MessageSquare, ChevronLeft, ChevronRight, Download,
  Zap as ZapIcon, MousePointer, Search, CheckCircle2,
  XCircle, AlertCircle, MinusCircle, Clock
} from 'lucide-react'

type FilterMode = 'all' | 'problems' | 'conforme' | 'pending'
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
    // eslint-disable-next-line react-hooks/set-state-in-effect
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
function LeftPanel({ items, selectedKey, onSelect, filterMode, onFilterChange, counts, search, onSearchChange }: {
  items: RuleItem[]
  selectedKey: string | null
  onSelect: (key: string) => void
  filterMode: FilterMode
  onFilterChange: (m: FilterMode) => void
  counts: { ok: number; ko: number; warn: number; pending: number; total: number }
  search: string
  onSearchChange: (q: string) => void
}) {
  const pendingCount = counts.pending
  const filtered = useMemo(() => {
    let list = items
    if (filterMode === 'problems') list = list.filter(i => i.status === 'ko' || i.status === 'warn')
    else if (filterMode === 'conforme') list = list.filter(i => i.status === 'ok')
    else if (filterMode === 'pending') list = list.filter(i => i.status === 'pending' || i.status === 'na')
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(i =>
        i.code.toLowerCase().includes(q) ||
        i.label.toLowerCase().includes(q) ||
        i.desc.toLowerCase().includes(q)
      )
    }
    return list
  }, [items, filterMode, search])

  const grouped = useMemo(() => {
    const groups: { label: string; items: RuleItem[]; okCount: number; koCount: number }[] = []
    let cur: typeof groups[0] | null = null
    for (const item of filtered) {
      if (!cur || cur.label !== item.group) { cur = { label: item.group, items: [], okCount: 0, koCount: 0 }; groups.push(cur) }
      cur.items.push(item)
      if (item.status === 'ok') cur.okCount++
      if (item.status === 'ko' || item.status === 'warn') cur.koCount++
    }
    return groups
  }, [filtered])

  return (
    <div className="ctrl-split-left">
      <div className="ctrl-left-head">
        <div className="ctrl-left-head-title">
          <ShieldCheck size={14} />
          <span>Controles</span>
          <span className="ctrl-left-head-count">{counts.total}</span>
        </div>
        <div className="ctrl-left-search">
          <Search size={13} />
          <input
            type="text"
            value={search}
            onChange={e => onSearchChange(e.target.value)}
            placeholder="Rechercher un controle, un code..."
            aria-label="Rechercher un controle"
          />
          {search && (
            <button className="ctrl-left-search-clear" onClick={() => onSearchChange('')} aria-label="Effacer"><X size={12} /></button>
          )}
        </div>
        <div className="ctrl-left-tabs" role="tablist">
          <button className={`ctrl-left-tab ${filterMode === 'all' ? 'active' : ''}`} onClick={() => onFilterChange('all')} role="tab" aria-selected={filterMode === 'all'}>
            Tous <span className="ctrl-left-tab-num">{counts.total}</span>
          </button>
          <button className={`ctrl-left-tab ${filterMode === 'problems' ? 'active' : ''}`}
            onClick={() => onFilterChange('problems')} disabled={counts.ko + counts.warn === 0} role="tab" aria-selected={filterMode === 'problems'}>
            Problemes <span className="ctrl-left-tab-num">{counts.ko + counts.warn}</span>
          </button>
          <button className={`ctrl-left-tab ${filterMode === 'conforme' ? 'active' : ''}`}
            onClick={() => onFilterChange('conforme')} role="tab" aria-selected={filterMode === 'conforme'}>
            Conformes <span className="ctrl-left-tab-num">{counts.ok}</span>
          </button>
          {pendingCount > 0 && (
            <button className={`ctrl-left-tab ${filterMode === 'pending' ? 'active' : ''}`}
              onClick={() => onFilterChange('pending')} role="tab" aria-selected={filterMode === 'pending'}>
              En attente <span className="ctrl-left-tab-num">{pendingCount}</span>
            </button>
          )}
        </div>
      </div>

      <div className="ctrl-split-left-list" role="listbox" aria-label="Liste des controles">
        {grouped.length === 0 ? (
          <div className="ctrl-left-empty">
            <Search size={22} />
            <p>Aucun controle ne correspond {search ? <>a <strong>"{search}"</strong></> : 'a ce filtre'}.</p>
            {(search || filterMode !== 'all') && (
              <button className="ctrl-left-reset" onClick={() => { onSearchChange(''); onFilterChange('all') }}>
                Reinitialiser
              </button>
            )}
          </div>
        ) : grouped.map(g => (
          <div key={g.label} className="ctrl-left-group">
            <div className="ctrl-left-group-head">
              <span className="ctrl-left-group-label">{g.label}</span>
              <span className="ctrl-left-group-counts">
                {g.koCount > 0 && <span className="ctrl-left-group-ko">{g.koCount} KO</span>}
                <span className="ctrl-left-group-total">{g.items.length}</span>
              </span>
            </div>
            {g.items.map(item => {
              const sd = STATUS_DISPLAY[item.status]
              const isSelected = selectedKey === item.key
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`ctrl-rule-row status-${item.status} ${isSelected ? 'selected' : ''}`}
                  onClick={() => onSelect(item.key)}
                  role="option"
                  aria-selected={isSelected}
                  aria-label={`${item.code} - ${item.label} - ${sd.label}`}
                >
                  <StatusIcon status={item.status} size={14} />
                  <span className="ctrl-rule-code">{item.code}</span>
                  <span className="ctrl-rule-label">{item.label}</span>
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

function StatusIcon({ status, size = 16 }: { status: ItemStatus; size?: number }) {
  const props = { size, strokeWidth: 2.4 as const, className: `ctrl-status-icon icon-${status}` }
  if (status === 'ok') return <CheckCircle2 {...props} />
  if (status === 'ko') return <XCircle {...props} />
  if (status === 'warn') return <AlertCircle {...props} />
  if (status === 'na') return <MinusCircle {...props} />
  return <Clock {...props} />
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
    try { await onRerunRule(item.code) } catch { /* ignore */ }
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
        <div className="ctrl-empty">
          <div className="ctrl-empty-illus"><MousePointer size={28} /></div>
          <h4>Selectionnez un controle</h4>
          <p>Choisissez une regle dans la liste pour voir son detail, les preuves extraites et le document source associe.</p>
          <div className="ctrl-empty-hints">
            <span><kbd>J</kbd> suivant</span>
            <span><kbd>K</kbd> precedent</span>
          </div>
        </div>
      </div>
    )
  }

  const sd = STATUS_DISPLAY[item.status]

  const valsDiffer = r?.statut === 'NON_CONFORME'
  const valsEqual = r?.statut === 'CONFORME'

  return (
    <div className={`ctrl-split-center ctrl-detail status-${item.status}`}>
      {/* Header */}
      <div className="ctrl-detail-header">
        <div className="ctrl-detail-header-meta">
          <span className={`ctrl-detail-status-dot status-${item.status}`} aria-hidden="true" />
          <span className="ctrl-detail-code">{item.code}</span>
          <span className="ctrl-detail-dot" aria-hidden="true" />
          <span className="ctrl-detail-group">{item.group}</span>
          <span className="ctrl-detail-dot" aria-hidden="true" />
          <span className={`ctrl-detail-status-label status-${item.status}`}>{sd.label}</span>
          {stale && <span className="ctrl-chip-neutral">Obsolete</span>}
          {r && !editing && (
            <div className="ctrl-detail-header-actions">
              <select className="ctrl-status-select" value={r.statut}
                onChange={e => handleCorrect(e.target.value)}
                onClick={e => e.stopPropagation()}
                aria-label="Changer le statut">
                {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
              <button className="ctrl-btn-secondary" onClick={startEdit}>
                <Edit3 size={12} /> Corriger
              </button>
              <button className="ctrl-btn-secondary" onClick={handleRerun} disabled={rerunning === item.code}>
                {rerunning === item.code ? <Loader2 size={12} className="spin" /> : <RefreshCw size={12} />}
                Relancer
                {cascadeSize > 1 && <span className="ctrl-cascade-badge">+{cascadeSize - 1}</span>}
              </button>
            </div>
          )}
        </div>
        <h3 className="ctrl-detail-title">{item.label}</h3>
        {item.desc && <p className="ctrl-detail-desc">{item.desc}</p>}
      </div>

      <div className="ctrl-split-center-body">

        {/* Detail from validation */}
        {r?.detail && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Resultat</div>
            <p className="ctrl-detail-note">{r.detail}</p>
          </div>
        )}

        {/* Values comparison (when no evidences) */}
        {(!r?.evidences || r.evidences.length === 0) && (r?.valeurAttendue || r?.valeurTrouvee) && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Comparaison</div>
            <div className="ctrl-compare">
              <div className="ctrl-compare-side">
                <div className="ctrl-compare-label">Attendu</div>
                <div className="ctrl-compare-value">{r?.valeurAttendue || '—'}</div>
              </div>
              <div className="ctrl-compare-side">
                <div className="ctrl-compare-label">Trouve</div>
                <div className={`ctrl-compare-value ${valsDiffer ? 'danger' : valsEqual ? 'ok' : ''}`}>{r?.valeurTrouvee || '—'}</div>
              </div>
            </div>
          </div>
        )}

        {/* Evidence */}
        {r?.evidences && r.evidences.length > 0 && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Preuves</div>
            <EvidenceList evidences={r.evidences} statut={r.statut} onOpenDocument={onOpenDoc} />
          </div>
        )}

        {/* Document links */}
        {r?.documentIds && r.documentIds.length > 0 && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Documents source</div>
            <div className="ctrl-docs-chips">
              {r.documentIds.map(docId => {
                const doc = dossier.documents.find(d => d.id === docId)
                if (!doc) return null
                return (
                  <button key={docId} className="ctrl-doc-chip" onClick={() => onOpenDoc(docId)}>
                    <FileText size={12} />
                    <span>{TYPE_DOCUMENT_LABELS[doc.typeDocument as TypeDocument] || doc.typeDocument}</span>
                  </button>
                )
              })}
            </div>
          </div>
        )}

        {/* Edit panel */}
        {editing && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Correction manuelle</div>
            <div className="ctrl-edit-panel">
              <div className="ctrl-edit-row">
                <label>Valeur attendue</label>
                <input className="form-input" value={editValues.valeurAttendue}
                  onChange={e => setEditValues(v => ({ ...v, valeurAttendue: e.target.value }))} />
              </div>
              <div className="ctrl-edit-row">
                <label>Valeur trouvee</label>
                <input className="form-input" value={editValues.valeurTrouvee}
                  onChange={e => setEditValues(v => ({ ...v, valeurTrouvee: e.target.value }))} />
              </div>
              <div className="ctrl-edit-row">
                <label><MessageSquare size={11} /> Commentaire</label>
                <input className="form-input" value={editValues.commentaire}
                  onChange={e => setEditValues(v => ({ ...v, commentaire: e.target.value }))}
                  placeholder="Raison de la correction..." />
              </div>
              <div className="ctrl-edit-buttons">
                <button className="ctrl-btn-primary" disabled={saving} onClick={() => saveEdit(true)}>
                  {saving ? <Loader2 size={12} className="spin" /> : <ZapIcon size={12} />} Sauvegarder &amp; relancer
                  {cascadeSize > 1 && <span className="ctrl-cascade-badge">+{cascadeSize - 1}</span>}
                </button>
                <button className="ctrl-btn-secondary" disabled={saving} onClick={() => saveEdit(false)}>
                  <Save size={12} /> Sauvegarder
                </button>
                <button className="ctrl-btn-secondary" onClick={() => setEditing(false)} disabled={saving}>
                  <X size={12} /> Annuler
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Meta — plain inline row */}
        {r && (
          <dl className="ctrl-detail-meta">
            {conf && (
              <div className="ctrl-meta-item">
                <dt>Confiance</dt>
                <dd>{conf.pct}%</dd>
              </div>
            )}
            {r.source && (
              <div className="ctrl-meta-item">
                <dt>Source</dt>
                <dd>{r.source === 'deterministe' || r.source === 'DETERMINISTE' ? 'Systeme' : 'Extraction IA'}</dd>
              </div>
            )}
            {r.corrigePar && (
              <div className="ctrl-meta-item">
                <dt>Corrige par</dt>
                <dd>{r.corrigePar}</dd>
              </div>
            )}
          </dl>
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
        <div className="ctrl-empty ctrl-empty-docs">
          <div className="ctrl-empty-illus"><FileText size={28} /></div>
          <h4>Aucun document ouvert</h4>
          <p>Cliquez sur une preuve ou sur un chip "Documents source" pour afficher le PDF/image ici.</p>
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
  const [search, setSearch] = useState('')

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
    let ok = 0, ko = 0, warn = 0, pending = 0
    for (const item of items) {
      if (item.status === 'ok') ok++
      else if (item.status === 'ko') ko++
      else if (item.status === 'warn') warn++
      else if (item.status === 'pending' || item.status === 'na') pending++
    }
    return { ok, ko, warn, pending, total: items.length }
  }, [items])

  const pctOk = counts.total > 0 ? Math.round((counts.ok / counts.total) * 100) : 0
  const hasResults = results.length > 0
  const healthTone: 'ok' | 'warn' | 'ko' | 'pending' =
    !hasResults ? 'pending' :
    counts.ko > 0 ? 'ko' :
    counts.warn > 0 ? 'warn' :
    'ok'

  const lastRunLabel = useMemo(() => {
    let max = 0
    for (const r of results) {
      if (r.dateExecution) {
        const t = new Date(r.dateExecution).getTime()
        if (t > max) max = t
      }
    }
    if (max === 0) return null
    const m = Math.floor((Date.now() - max) / 60000)
    if (m < 1) return 'a l\'instant'
    if (m < 60) return `il y a ${m} min`
    const h = Math.floor(m / 60)
    if (h < 24) return `il y a ${h}h`
    return `il y a ${Math.floor(h / 24)}j`
  }, [results])

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

  const pctKo = counts.total > 0 ? (counts.ko / counts.total) * 100 : 0
  const pctWarn = counts.total > 0 ? (counts.warn / counts.total) * 100 : 0

  const headline = !hasResults ? 'Verification non executee'
    : healthTone === 'ok' ? 'Dossier conforme'
    : healthTone === 'ko' ? 'Non-conformites detectees'
    : healthTone === 'warn' ? 'Avertissements a revoir'
    : 'Controles en attente'

  const jumpToFirstProblem = () => {
    setFilterMode('problems')
    const first = items.find(i => i.status === 'ko') || items.find(i => i.status === 'warn')
    if (first) setSelectedKey(first.key)
  }

  return (
    <div className="ctrl-view">
      {/* Hero */}
      <section className="ctrl-hero">
        <div className="ctrl-hero-top">
          <div className="ctrl-hero-id">
            <span className="ctrl-hero-eyebrow">Verification du dossier</span>
            <h2 className="ctrl-hero-title">{headline}</h2>
            {hasResults && (
              <div className="ctrl-hero-sub">
                <span><strong>{pctOk}%</strong> conformes</span>
                <span className="ctrl-hero-dot" aria-hidden="true" />
                <span>{counts.total} controles</span>
                {lastRunLabel && (
                  <>
                    <span className="ctrl-hero-dot" aria-hidden="true" />
                    <span>Derniere verification {lastRunLabel}</span>
                  </>
                )}
              </div>
            )}
          </div>
          <div className="ctrl-hero-actions-top">
            {counts.ko + counts.warn > 0 && (
              <button className="ctrl-btn-ghost" onClick={jumpToFirstProblem}>
                Aller au 1er probleme →
              </button>
            )}
            <button className="ctrl-btn-primary" onClick={onValidate} disabled={validating}>
              {validating ? <Loader2 size={13} className="spin" /> : null}
              {hasResults ? 'Relancer' : 'Lancer la verification'}
            </button>
          </div>
        </div>

        <dl className="ctrl-hero-stats">
          <div className="ctrl-stat">
            <dt>Conformes</dt>
            <dd>
              <span className="ctrl-stat-num">{counts.ok}</span>
              <span className="ctrl-stat-total">/ {counts.total}</span>
            </dd>
          </div>
          <div className="ctrl-stat ctrl-stat-ko">
            <dt>Non conformes</dt>
            <dd><span className="ctrl-stat-num">{counts.ko}</span></dd>
          </div>
          <div className="ctrl-stat ctrl-stat-warn">
            <dt>Avertissements</dt>
            <dd><span className="ctrl-stat-num">{counts.warn}</span></dd>
          </div>
          <div className="ctrl-stat">
            <dt>En attente</dt>
            <dd><span className="ctrl-stat-num">{counts.pending}</span></dd>
          </div>
        </dl>

        {counts.total > 0 && (
          <div className="ctrl-hero-bar" role="img" aria-label={`${counts.ok} conformes, ${counts.ko} non conformes, ${counts.warn} avertissements sur ${counts.total}`}>
            <span className="ctrl-hero-bar-seg ok" style={{ width: `${pctOk}%` }} />
            <span className="ctrl-hero-bar-seg ko" style={{ width: `${pctKo}%` }} />
            <span className="ctrl-hero-bar-seg warn" style={{ width: `${pctWarn}%` }} />
          </div>
        )}
      </section>

      {/* 3-column split */}
      <div className="ctrl-split">
        <LeftPanel items={items} selectedKey={selectedKey} onSelect={setSelectedKey}
          filterMode={filterMode} onFilterChange={setFilterMode} counts={counts}
          search={search} onSearchChange={setSearch} />

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
