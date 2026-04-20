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

/**
 * Etiquette humaine de la methode d'obtention du verdict, sans pourcentage magique.
 * Le chiffre de confiance affiche jusqu'ici etait fabrique cote front (85%, 60%, 70%, 90%)
 * et pouvait faire croire a une metrique calibree. Remplace par un label explicite.
 */
function verdictProvenance(r: ValidationResult): { label: string; hint: string; tone: 'info' | 'success' | 'warning' | 'neutral' } {
  const src = (r.source || '').toLowerCase()
  if (src === 'deterministe' || src === 'regex') {
    return {
      label: 'Calcul deterministe',
      hint: 'Execute cote backend a partir des donnees extraites. Resultat reproductible.',
      tone: 'info',
    }
  }
  if (src === 'llm' || src === 'ia') {
    return {
      label: 'Jugement IA (Claude)',
      hint: 'Le modele Claude a ete sollicite pour trancher. A croiser avec les documents sources en cas de doute.',
      tone: 'warning',
    }
  }
  if (src === 'checklist') {
    return {
      label: 'Saisie humaine',
      hint: 'Valeur issue de la checklist autocontrole ou d\'une correction manuelle. Depend de l\'operateur qui l\'a renseignee.',
      tone: 'warning',
    }
  }
  if (r.corrigePar) {
    return {
      label: `Corrige par ${r.corrigePar}`,
      hint: 'Statut force manuellement apres analyse humaine.',
      tone: 'neutral',
    }
  }
  return { label: 'Source inconnue', hint: 'La provenance du verdict n\'est pas tracee.', tone: 'neutral' }
}

function isStale(r: ValidationResult | undefined, documents: DocumentInfo[]): boolean {
  if (!r?.dateExecution || !r?.documentIds?.length) return false
  const execTime = new Date(r.dateExecution).getTime()
  return r.documentIds.some(docId => {
    const doc = documents.find(d => d.id === docId)
    return doc && new Date(doc.dateUpload).getTime() > execTime
  })
}

function clamp(n: number, min: number, max: number) {
  return Math.max(min, Math.min(max, n))
}

function useResizer(opts: { initial: number; min: number; max: number; storageKey: string; direction: 'left' | 'right' }) {
  const { initial, min, max, storageKey, direction } = opts
  const [width, setWidth] = useState<number>(() => {
    try {
      const raw = localStorage.getItem(storageKey)
      if (raw != null) {
        const n = parseInt(raw, 10)
        if (!Number.isNaN(n)) return clamp(n, min, max)
      }
    } catch { /* ignore */ }
    return initial
  })

  useEffect(() => {
    try { localStorage.setItem(storageKey, String(width)) } catch { /* ignore */ }
  }, [width, storageKey])

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    const startX = e.clientX
    const startWidth = width
    const handleMove = (ev: PointerEvent) => {
      const dx = direction === 'left' ? ev.clientX - startX : startX - ev.clientX
      setWidth(clamp(startWidth + dx, min, max))
    }
    const cleanup = () => {
      document.removeEventListener('pointermove', handleMove)
      document.removeEventListener('pointerup', cleanup)
      document.removeEventListener('pointercancel', cleanup)
      document.body.style.userSelect = ''
      document.body.style.cursor = ''
    }
    document.addEventListener('pointermove', handleMove)
    document.addEventListener('pointerup', cleanup)
    document.addEventListener('pointercancel', cleanup)
    document.body.style.userSelect = 'none'
    document.body.style.cursor = 'col-resize'
  }, [width, min, max, direction])

  const onDoubleClick = useCallback(() => setWidth(initial), [initial])

  return { width, onPointerDown, onDoubleClick }
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

  const STATUS_PRIORITY: Record<ItemStatus, number> = {
    ko: 0, warn: 1, pending: 2, na: 3, ok: 4,
  }
  const grouped = useMemo(() => {
    const groups: { label: string; items: RuleItem[]; okCount: number; koCount: number }[] = []
    let cur: typeof groups[0] | null = null
    for (const item of filtered) {
      if (!cur || cur.label !== item.group) { cur = { label: item.group, items: [], okCount: 0, koCount: 0 }; groups.push(cur) }
      cur.items.push(item)
      if (item.status === 'ok') cur.okCount++
      if (item.status === 'ko' || item.status === 'warn') cur.koCount++
    }
    // Within each group, surface problems first
    for (const g of groups) {
      g.items.sort((a, b) => (STATUS_PRIORITY[a.status] ?? 9) - (STATUS_PRIORITY[b.status] ?? 9))
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

interface ProblemNav {
  hasPrev: boolean
  hasNext: boolean
  index: number
  total: number
  goPrev: () => void
  goNext: () => void
}

/* ===== CENTER PANEL ===== */
function CenterPanel({ item, dossier, dossierId, onRefreshResults, onReplaceResults, onRerunRule, onOptimisticUpdate, onOpenDoc, cascadeScope, rerunning, problemNav }: {
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
  problemNav: ProblemNav
}) {
  const { toast } = useToast()
  const [editing, setEditing] = useState(false)
  const [editValues, setEditValues] = useState({ valeurTrouvee: '', valeurAttendue: '', commentaire: '' })
  const [editDocIds, setEditDocIds] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  const r = item?.result
  const provenance = r ? verdictProvenance(r) : null
  const ruleMeta = item ? ALL_RULES.find(rl => rl.code === item.code) : undefined
  const stale = isStale(r, dossier.documents)
  const cascadeSize = item ? (cascadeScope?.[item.code.split('.')[0]]?.length || 1) : 0

  const startEdit = useCallback(() => {
    if (!r) return
    setEditing(true)
    setEditValues({ valeurTrouvee: r.valeurTrouvee || '', valeurAttendue: r.valeurAttendue || '', commentaire: r.commentaire || '' })
    setEditDocIds(r.documentIds ? [...r.documentIds] : [])
  }, [r])

  const addEditDoc = useCallback((docId: string) => {
    setEditDocIds(prev => prev.includes(docId) ? prev : [...prev, docId])
  }, [])
  const removeEditDoc = useCallback((docId: string) => {
    setEditDocIds(prev => prev.filter(id => id !== docId))
  }, [])

  const saveEdit = useCallback(async (alsoRerun: boolean) => {
    if (!r?.id) return
    setSaving(true)
    try {
      const originalIds = (r.documentIds || []).join(',')
      const nextIds = editDocIds.join(',')
      const updates: Parameters<typeof updateValidationResult>[2] = {
        valeurTrouvee: editValues.valeurTrouvee || undefined,
        valeurAttendue: editValues.valeurAttendue || undefined,
        commentaire: editValues.commentaire || undefined,
      }
      if (nextIds !== originalIds) updates.documentIds = nextIds
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
  }, [r, dossierId, editValues, editDocIds, onReplaceResults, onRefreshResults, toast])

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
        {problemNav.total > 0 && (item.status === 'ko' || item.status === 'warn') && (
          <div className="ctrl-detail-nav">
            <span className="ctrl-detail-nav-label">
              Probleme <strong>{problemNav.index + 1}</strong> sur <strong>{problemNav.total}</strong>
            </span>
            <div className="ctrl-detail-nav-buttons">
              <button className="ctrl-btn-ghost ctrl-detail-nav-btn"
                onClick={problemNav.goPrev} disabled={problemNav.total < 2}
                title="Probleme precedent">
                <ChevronLeft size={14} /> Precedent
              </button>
              <button className="ctrl-btn-ghost ctrl-detail-nav-btn"
                onClick={problemNav.goNext} disabled={problemNav.total < 2}
                title="Probleme suivant">
                Suivant <ChevronRight size={14} />
              </button>
            </div>
          </div>
        )}
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

        {/* Methode du controle — formule + methode + provenance */}
        {(ruleMeta?.formula || ruleMeta?.method || provenance) && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Methode du controle</div>
            <div className="ctrl-method">
              {ruleMeta?.formula && (
                <div className="ctrl-method-row">
                  <div className="ctrl-method-label">Formule</div>
                  <code className="ctrl-method-formula">{ruleMeta.formula}</code>
                </div>
              )}
              {ruleMeta?.method && (
                <div className="ctrl-method-row">
                  <div className="ctrl-method-label">Comment</div>
                  <div className="ctrl-method-text">{ruleMeta.method}</div>
                </div>
              )}
              {ruleMeta?.fields && ruleMeta.fields.length > 0 && (
                <div className="ctrl-method-row">
                  <div className="ctrl-method-label">Champs lus</div>
                  <div className="ctrl-method-fields">
                    {ruleMeta.fields.map(f => (
                      <code key={f} className="ctrl-method-field">{f}</code>
                    ))}
                  </div>
                </div>
              )}
              {provenance && (
                <div className="ctrl-method-row">
                  <div className="ctrl-method-label">Verdict</div>
                  <div className={`ctrl-method-provenance tone-${provenance.tone}`}>
                    <span className="ctrl-method-provenance-label">{provenance.label}</span>
                    <span className="ctrl-method-provenance-hint">{provenance.hint}</span>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

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
            <div className={`ctrl-compare ${valsDiffer ? 'differ' : valsEqual ? 'equal' : ''}`}>
              <div className="ctrl-compare-side">
                <div className="ctrl-compare-label">Attendu</div>
                <div className="ctrl-compare-value">{r?.valeurAttendue || '—'}</div>
                <div className="ctrl-compare-source">Source non tracee — voir documents lies</div>
              </div>
              <div className="ctrl-compare-op" aria-hidden="true">
                {valsEqual ? '=' : valsDiffer ? '≠' : '→'}
              </div>
              <div className="ctrl-compare-side">
                <div className="ctrl-compare-label">Trouve</div>
                <div className={`ctrl-compare-value ${valsDiffer ? 'danger' : valsEqual ? 'ok' : ''}`}>{r?.valeurTrouvee || '—'}</div>
                <div className="ctrl-compare-source">Source non tracee — voir documents lies</div>
              </div>
            </div>
          </div>
        )}

        {/* Evidence — always rendered when result exists, with empty-state */}
        {r && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Preuves ({r.evidences?.length ?? 0})</div>
            {r.evidences && r.evidences.length > 0 ? (
              <EvidenceList evidences={r.evidences} statut={r.statut} onOpenDocument={onOpenDoc} />
            ) : (
              <div className="ctrl-evidence-empty">
                Aucune preuve structuree enregistree pour ce controle.
                La regle a produit un verdict sans citer explicitement la valeur attendue, la valeur trouvee ou le document source.
                Utilisez le bouton <strong>Relancer</strong> pour re-executer la regle ou <strong>Corriger</strong> pour renseigner manuellement les valeurs.
              </div>
            )}
          </div>
        )}

        {/* Document links — click opens the PDF preview on the right */}
        {r?.documentIds && r.documentIds.length > 0 && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Documents source</div>
            <div className="ctrl-docs-chips">
              {r.documentIds.map(docId => {
                const doc = dossier.documents.find(d => d.id === docId)
                if (!doc) return null
                return (
                  <button key={docId} className="ctrl-doc-chip" onClick={() => onOpenDoc(docId)}
                    title="Afficher le PDF a droite">
                    <FileText size={12} />
                    <span>{TYPE_DOCUMENT_LABELS[doc.typeDocument as TypeDocument] || doc.typeDocument}</span>
                    <span className="ctrl-doc-chip-action">Afficher</span>
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

              <div className="ctrl-edit-row">
                <label>Documents lies a cette regle</label>
                <div className="ctrl-edit-docs">
                  {editDocIds.length === 0 && (
                    <span className="ctrl-edit-docs-empty">Aucun document lie</span>
                  )}
                  {editDocIds.map(docId => {
                    const doc = dossier.documents.find(d => d.id === docId)
                    const label = doc ? (TYPE_DOCUMENT_LABELS[doc.typeDocument as TypeDocument] || doc.typeDocument) : 'Document supprime'
                    return (
                      <span key={docId} className="ctrl-edit-doc-chip">
                        <FileText size={11} />
                        <span title={doc?.nomFichier}>{label}</span>
                        {doc && (
                          <button type="button" className="ctrl-edit-doc-view"
                            onClick={() => onOpenDoc(docId)}
                            title="Afficher le PDF">
                            Voir
                          </button>
                        )}
                        <button type="button" className="ctrl-edit-doc-remove"
                          onClick={() => removeEditDoc(docId)}
                          title="Retirer ce document de la regle">
                          <X size={11} />
                        </button>
                      </span>
                    )
                  })}
                </div>
                <select className="form-input"
                  style={{ marginTop: 6, fontSize: 12, padding: '6px 8px' }}
                  value=""
                  onChange={e => {
                    if (e.target.value) addEditDoc(e.target.value)
                    e.target.value = ''
                  }}>
                  <option value="" disabled>+ Ajouter un document du dossier…</option>
                  {dossier.documents
                    .filter(d => !editDocIds.includes(d.id))
                    .map(d => (
                      <option key={d.id} value={d.id}>
                        {TYPE_DOCUMENT_LABELS[d.typeDocument as TypeDocument] || d.typeDocument} — {d.nomFichier}
                      </option>
                    ))}
                </select>
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

        {/* Meta — date execution, correction humaine. La provenance du verdict est
            deja affichee dans la section "Methode du controle" ci-dessus. */}
        {r && (r.dateExecution || r.corrigePar) && (
          <dl className="ctrl-detail-meta">
            {r.dateExecution && (
              <div className="ctrl-meta-item">
                <dt>Execute le</dt>
                <dd>{new Date(r.dateExecution).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })}</dd>
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
function RightPanel({ dossierId, docId, documents, highlightField, onChangeDoc, onClose }: {
  dossierId: string
  docId: string | null
  documents: DocumentInfo[]
  highlightField: string | null
  onChangeDoc: (id: string) => void
  onClose: () => void
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
        <button className="btn btn-secondary btn-sm" title="Fermer le document"
          onClick={onClose} aria-label="Fermer le document">
          <X size={12} />
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

  const leftPane  = useResizer({ initial: 320, min: 220, max: 520, storageKey: 'ctrl-split.left',  direction: 'left'  })
  const rightPane = useResizer({ initial: 400, min: 300, max: 720, storageKey: 'ctrl-split.right', direction: 'right' })

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

  const problemItems = useMemo(
    () => items.filter(i => i.status === 'ko' || i.status === 'warn'),
    [items]
  )
  const problemNav = useMemo(() => {
    if (problemItems.length === 0 || !selectedKey) return { hasPrev: false, hasNext: false, index: -1, total: 0, goPrev: () => {}, goNext: () => {} }
    const idx = problemItems.findIndex(i => i.key === selectedKey)
    const goPrev = () => {
      if (problemItems.length === 0) return
      const cur = idx >= 0 ? idx : 0
      const next = (cur - 1 + problemItems.length) % problemItems.length
      setSelectedKey(problemItems[next].key)
    }
    const goNext = () => {
      if (problemItems.length === 0) return
      const cur = idx >= 0 ? idx : -1
      const next = (cur + 1) % problemItems.length
      setSelectedKey(problemItems[next].key)
    }
    return {
      hasPrev: problemItems.length > 1 || idx < 0,
      hasNext: problemItems.length > 1 || idx < 0,
      index: idx,
      total: problemItems.length,
      goPrev, goNext,
    }
  }, [problemItems, selectedKey])

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
      {/* Compact hero — single band */}
      <section className="ctrl-hero">
        <div className="ctrl-hero-main">
          <div className="ctrl-hero-text">
            <h2 className="ctrl-hero-title">{headline}</h2>
            {hasResults ? (
              <div className="ctrl-hero-stats-inline">
                <span><strong>{counts.ok}</strong>/<span className="ctrl-hero-total">{counts.total}</span> conformes</span>
                {counts.ko > 0 && <span className="ctrl-hero-pill pill-ko">{counts.ko} KO</span>}
                {counts.warn > 0 && <span className="ctrl-hero-pill pill-warn">{counts.warn} avertissements</span>}
                {counts.pending > 0 && <span className="ctrl-hero-muted">{counts.pending} en attente</span>}
                {lastRunLabel && <span className="ctrl-hero-muted">Derniere verification {lastRunLabel}</span>}
              </div>
            ) : (
              <div className="ctrl-hero-stats-inline ctrl-hero-muted">
                Lancez la verification pour executer les controles systeme et la checklist autocontrole.
              </div>
            )}
          </div>
          <div className="ctrl-hero-actions-top">
            {counts.ko + counts.warn > 0 && (
              <button className="ctrl-btn-ghost" onClick={jumpToFirstProblem}>
                Aller au probleme →
              </button>
            )}
            <button className="ctrl-btn-primary" onClick={onValidate} disabled={validating}>
              {validating ? <Loader2 size={13} className="spin" /> : null}
              {hasResults ? 'Relancer' : 'Lancer la verification'}
            </button>
          </div>
        </div>

        {counts.total > 0 && (
          <div className="ctrl-hero-bar" role="img" aria-label={`${counts.ok} conformes, ${counts.ko} non conformes, ${counts.warn} avertissements sur ${counts.total}`}>
            <span className="ctrl-hero-bar-seg ok" style={{ width: `${pctOk}%` }} />
            <span className="ctrl-hero-bar-seg ko" style={{ width: `${pctKo}%` }} />
            <span className="ctrl-hero-bar-seg warn" style={{ width: `${pctWarn}%` }} />
          </div>
        )}
      </section>

      {/* Resizable split: 2 columns by default, 3 when a doc preview is open */}
      <div
        className={`ctrl-split ${previewDocId ? 'with-preview' : ''}`}
        style={{
          gridTemplateColumns: previewDocId
            ? `${leftPane.width}px 6px minmax(0, 1fr) 6px ${rightPane.width}px`
            : `${leftPane.width}px 6px minmax(0, 1fr)`,
        }}
      >
        <LeftPanel items={items} selectedKey={selectedKey} onSelect={setSelectedKey}
          filterMode={filterMode} onFilterChange={setFilterMode} counts={counts}
          search={search} onSearchChange={setSearch} />

        <div
          className="ctrl-split-handle"
          onPointerDown={leftPane.onPointerDown}
          onDoubleClick={leftPane.onDoubleClick}
          role="separator" aria-orientation="vertical"
          aria-label="Redimensionner la liste des controles (double-clic pour reinitialiser)"
          title="Glisser pour redimensionner · double-clic = reset"
        />

        <CenterPanel item={selectedItem} dossier={dossier} dossierId={dossierId}
          onRefreshResults={onRefreshResults} onReplaceResults={onReplaceResults}
          onRerunRule={handleRerunRule} onOptimisticUpdate={onOptimisticUpdate}
          onOpenDoc={handleOpenDoc} cascadeScope={cascadeScope} rerunning={rerunning}
          problemNav={problemNav} />

        {previewDocId && (
          <>
            <div
              className="ctrl-split-handle"
              onPointerDown={rightPane.onPointerDown}
              onDoubleClick={rightPane.onDoubleClick}
              role="separator" aria-orientation="vertical"
              aria-label="Redimensionner le visualiseur PDF (double-clic pour reinitialiser)"
              title="Glisser pour redimensionner · double-clic = reset"
            />
            <RightPanel dossierId={dossierId} docId={previewDocId}
              documents={dossier.documents} highlightField={highlightField}
              onChangeDoc={(id) => { setPreviewDocId(id); setHighlightField(null) }}
              onClose={() => { setPreviewDocId(null); setHighlightField(null) }} />
          </>
        )}
      </div>
    </div>
  )
})
