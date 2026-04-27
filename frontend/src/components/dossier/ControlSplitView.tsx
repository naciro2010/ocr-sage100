import { useState, useMemo, useCallback, useEffect, useRef, memo } from 'react'
import type { DossierDetail, ValidationResult, DocumentInfo } from '../../api/dossierTypes'
import { updateValidationResult, correctAndRerun, getDocumentFileUrl, downloadWithAuth } from '../../api/dossierApi'
import { getActiveRules, DOC_CATEGORIES, DOC_CATEGORY_LABEL, ALL_RULES } from '../../config/validationRules'
import type { CustomRule } from '../../api/customRulesApi'
import { parseChecklistPoints, STATUS_DISPLAY, STATUT_OPTIONS, statutToItemStatus, estValideToItemStatus, type ItemStatus } from '../../config/checklistUtils'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import type { TypeDocument } from '../../api/dossierTypes'
import { useToast } from '../Toast'
import EvidenceList from './EvidenceList'
import PdfFrame from './PdfFrame'
import {
  ShieldCheck, Loader2,
  FileText, RefreshCw, Edit3, Save, X,
  MessageSquare, ChevronLeft, ChevronRight, Download,
  Zap as ZapIcon, MousePointer, Search, CheckCircle2,
  XCircle, AlertCircle, MinusCircle, Clock, Play, Sparkles,
  ChevronDown, Keyboard
} from 'lucide-react'

function useLocalStorageState<T>(key: string, initial: T, validate?: (v: unknown) => v is T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key)
      if (raw == null) return initial
      const parsed: unknown = JSON.parse(raw)
      if (validate && !validate(parsed)) return initial
      return parsed as T
    } catch { return initial }
  })
  const firstRender = useRef(true)
  useEffect(() => {
    // Ne pas reecrire la valeur d'init apres la lecture : evite un setItem
    // inutile au premier rendu quand rien n'a change cote utilisateur.
    if (firstRender.current) { firstRender.current = false; return }
    try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* ignore */ }
  }, [key, value])
  return [value, setValue]
}

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
  customRules?: CustomRule[]
}

type ControlEngine = 'system' | 'ai' | 'human'
type EngineFilter = 'any' | ControlEngine

interface RuleItem {
  key: string
  code: string
  label: string
  desc: string
  result: ValidationResult | undefined
  status: ItemStatus
  group: string
  category: 'system' | 'checklist' | 'custom'
  engine: ControlEngine
  custom?: CustomRule
  mini: string | null
}

/** Sources backend qui identifient un jugement IA (vs deterministe / humain). */
const AI_SOURCES = new Set(['llm', 'ia', 'custom', 'custom_batch'])

/**
 * Classe un controle par moteur d'execution : system (calcul local), ai (Claude)
 * ou human (saisie autocontrole). Source de verite unique utilisee par les badges,
 * les filtres et la provenance du verdict.
 */
function deriveEngine(item: {
  category: 'system' | 'checklist' | 'custom'
  result: ValidationResult | undefined
}): ControlEngine {
  if (item.category === 'checklist') return 'human'
  if (item.category === 'custom') return 'ai'
  return AI_SOURCES.has((item.result?.source || '').toLowerCase()) ? 'ai' : 'system'
}

const ENGINE_LABEL: Record<ControlEngine, { short: string; long: string; hint: string }> = {
  system: {
    short: 'Systeme',
    long: 'Controle systeme (deterministe)',
    hint: 'Execute en local par le backend. Reproducible, zero appel IA, moins de 100 ms.',
  },
  ai: {
    short: 'IA',
    long: 'Controle IA (Claude)',
    hint: 'Delegue a Claude en un appel groupe par dossier. Jugement a verifier si critique.',
  },
  human: {
    short: 'Humain',
    long: 'Saisie humaine',
    hint: 'Point renseigne dans la checklist autocontrole (CCF-EN-04). Valeur saisie par un operateur.',
  },
}

type Provenance = { label: string; hint: string; tone: 'info' | 'success' | 'warning' | 'neutral' }

const PROVENANCE_BY_SOURCE: Record<string, Provenance> = {
  deterministe: { label: 'Calcul deterministe', hint: 'Execute cote backend a partir des donnees extraites. Resultat reproductible.', tone: 'info' },
  regex: { label: 'Calcul deterministe', hint: 'Execute cote backend a partir des donnees extraites. Resultat reproductible.', tone: 'info' },
  llm: { label: 'Jugement IA (Claude)', hint: 'Le modele Claude a ete sollicite pour trancher. A croiser avec les documents sources en cas de doute.', tone: 'warning' },
  ia: { label: 'Jugement IA (Claude)', hint: 'Le modele Claude a ete sollicite pour trancher. A croiser avec les documents sources en cas de doute.', tone: 'warning' },
  custom_batch: { label: 'Jugement IA (Claude, appel groupe)', hint: 'Evalue par Claude dans un appel unique couvrant toutes les regles personnalisees du dossier. A croiser avec les documents sources si critique.', tone: 'warning' },
  custom: { label: 'Jugement IA (Claude, regle personnalisee)', hint: 'Regle personnalisee evaluee par Claude. A croiser avec les documents sources en cas de doute.', tone: 'warning' },
  checklist: { label: 'Saisie humaine', hint: 'Valeur issue de la checklist autocontrole ou d\'une correction manuelle. Depend de l\'operateur qui l\'a renseignee.', tone: 'warning' },
}

/**
 * Etiquette de la methode d'obtention du verdict, sans pourcentage magique.
 * Le chiffre de confiance affiche jusqu'ici etait fabrique cote front et pouvait
 * faire croire a une metrique calibree — remplace par un label explicite.
 */
function verdictProvenance(r: ValidationResult): Provenance {
  const hit = PROVENANCE_BY_SOURCE[(r.source || '').toLowerCase()]
  if (hit) return hit
  if (r.corrigePar) return { label: `Corrige par ${r.corrigePar}`, hint: 'Statut force manuellement apres analyse humaine.', tone: 'neutral' }
  return { label: 'Source inconnue', hint: 'La provenance du verdict n\'est pas tracee.', tone: 'neutral' }
}

function ellipsize(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 2) + '…' : s
}

/**
 * Preference : "attendu ≠ trouve" si les deux existent, sinon la 1re ligne du
 * detail. Tronque a ~80 chars pour rester sur une ligne dans la liste.
 */
function miniVerdict(r: ValidationResult | undefined): string | null {
  if (!r) return null
  if (r.statut !== 'NON_CONFORME' && r.statut !== 'AVERTISSEMENT') return null
  const a = (r.valeurAttendue || '').trim()
  const t = (r.valeurTrouvee || '').trim()
  if (a && t && a !== t) return ellipsize(`${a} ≠ ${t}`, 80)
  const d = (r.detail || '').trim()
  if (!d) return null
  return ellipsize(d.split(/[.\n]/)[0] || d, 80)
}

/**
 * Le verdict banner affiche deja soit "attendu X, trouve Y" (KO), soit le
 * detail brut si on n'a pas ces valeurs. On n'affiche "Analyse detaillee"
 * que si le detail apporte plus que le banner : detail long (>100 c) OU
 * valeurs presentes ET detail different d'elles.
 */
function detailAddsInfo(r: ValidationResult, status: ItemStatus): boolean {
  const d = (r.detail || '').trim()
  if (!d) return false
  if (d.length > 100) return true
  if (status === 'ko') {
    const a = (r.valeurAttendue || '').trim()
    const t = (r.valeurTrouvee || '').trim()
    if (a && t && a !== t) return true // banner montre attendu/trouve, detail ajoute du contexte
    return false // banner montre deja detail
  }
  return false // ok/warn/na/pending : banner montre deja detail
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
function LeftPanel({ items, selectedKey, onSelect, filterMode, onFilterChange, counts, engineFilter, onEngineFilterChange, engineCounts, search, onSearchChange }: {
  items: RuleItem[]
  selectedKey: string | null
  onSelect: (key: string) => void
  filterMode: FilterMode
  onFilterChange: (m: FilterMode) => void
  counts: { ok: number; ko: number; warn: number; pending: number; total: number }
  engineFilter: EngineFilter
  onEngineFilterChange: (f: EngineFilter) => void
  engineCounts: Record<ControlEngine, number>
  search: string
  onSearchChange: (q: string) => void
}) {
  const pendingCount = counts.pending
  const filtered = useMemo(() => {
    let list = items
    if (filterMode === 'problems') list = list.filter(i => i.status === 'ko' || i.status === 'warn')
    else if (filterMode === 'conforme') list = list.filter(i => i.status === 'ok')
    else if (filterMode === 'pending') list = list.filter(i => i.status === 'pending' || i.status === 'na')
    if (engineFilter !== 'any') list = list.filter(i => i.engine === engineFilter)
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(i =>
        i.code.toLowerCase().includes(q) ||
        i.label.toLowerCase().includes(q) ||
        i.desc.toLowerCase().includes(q)
      )
    }
    return list
  }, [items, filterMode, engineFilter, search])

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
        <div className="ctrl-left-engine-tabs" role="group" aria-label="Filtrer par moteur d'execution">
          <button className={`ctrl-engine-chip ${engineFilter === 'any' ? 'active' : ''}`}
            onClick={() => onEngineFilterChange('any')}
            title="Tous les moteurs (systeme, IA, humain)">
            Tous moteurs
          </button>
          <button className={`ctrl-engine-chip engine-system ${engineFilter === 'system' ? 'active' : ''}`}
            onClick={() => onEngineFilterChange(engineFilter === 'system' ? 'any' : 'system')}
            disabled={engineCounts.system === 0}
            title="Controles deterministes executes en local par le backend (0 $)">
            <span className="ctrl-engine-chip-dot dot-system" aria-hidden="true" />
            Systeme <span className="ctrl-engine-chip-num">{engineCounts.system}</span>
          </button>
          <button className={`ctrl-engine-chip engine-ai ${engineFilter === 'ai' ? 'active' : ''}`}
            onClick={() => onEngineFilterChange(engineFilter === 'ai' ? 'any' : 'ai')}
            disabled={engineCounts.ai === 0}
            title="Regles personnalisees evaluees par Claude en un appel groupe">
            <span className="ctrl-engine-chip-dot dot-ai" aria-hidden="true" />
            IA <span className="ctrl-engine-chip-num">{engineCounts.ai}</span>
          </button>
          <button className={`ctrl-engine-chip engine-human ${engineFilter === 'human' ? 'active' : ''}`}
            onClick={() => onEngineFilterChange(engineFilter === 'human' ? 'any' : 'human')}
            disabled={engineCounts.human === 0}
            title="Points de l'autocontrole (CCF-EN-04) renseignes par un operateur">
            <span className="ctrl-engine-chip-dot dot-human" aria-hidden="true" />
            Humain <span className="ctrl-engine-chip-num">{engineCounts.human}</span>
          </button>
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
              const mini = item.mini
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`ctrl-rule-row status-${item.status} engine-${item.engine} ${isSelected ? 'selected' : ''} ${mini ? 'has-mini' : ''}`}
                  onClick={() => onSelect(item.key)}
                  role="option"
                  aria-selected={isSelected}
                  aria-label={`${item.code} - ${item.label} - ${sd.label} - ${ENGINE_LABEL[item.engine].long}${mini ? ` - ${mini}` : ''}`}
                >
                  <StatusIcon status={item.status} size={14} />
                  <span className="ctrl-rule-code">{item.code}</span>
                  <span className="ctrl-rule-body">
                    <span className="ctrl-rule-label">{item.label}</span>
                    {mini && <span className="ctrl-rule-mini" title={mini}>{mini}</span>}
                  </span>
                  <span className={`ctrl-rule-engine engine-${item.engine}`} title={ENGINE_LABEL[item.engine].hint} aria-hidden="true">
                    {ENGINE_LABEL[item.engine].short}
                  </span>
                </button>
              )
            })}
          </div>
        ))}
      </div>
    </div>
  )
}

const VERDICT_FALLBACKS: Record<ItemStatus, string> = {
  ok: 'Controle conforme.',
  ko: 'Non-conformite detectee.',
  warn: 'Avertissement : verification manuelle recommandee.',
  na: 'Non applicable a ce dossier.',
  pending: 'En attente — lancez la verification pour obtenir un verdict.',
}

function verdictMessage(item: RuleItem): string {
  const r = item.result
  if (!r) return 'Ce controle n\'a pas encore ete execute.'
  if (item.status === 'ko') {
    const a = (r.valeurAttendue || '').trim()
    const t = (r.valeurTrouvee || '').trim()
    if (a && t && a !== t) return `Attendu ${a} — trouve ${t}.`
  }
  return r.detail?.trim() || VERDICT_FALLBACKS[item.status]
}

/**
 * Banner en tete du panneau de detail : icone + label + verdict en une phrase.
 * Objectif UX : comprendre la decision sans lire la methode ni scroller les
 * preuves.
 */
function VerdictBanner({ item }: { item: RuleItem }) {
  const sd = STATUS_DISPLAY[item.status]
  const message = verdictMessage(item)
  return (
    <div className={`ctrl-verdict-banner status-${item.status}`} role="status"
      aria-label={`Verdict : ${sd.label}. ${message}`}>
      <div className="ctrl-verdict-icon" aria-hidden="true">
        <StatusIcon status={item.status} size={20} />
      </div>
      <div className="ctrl-verdict-text">
        <span className="ctrl-verdict-label">{sd.label}</span>
        <span className="ctrl-verdict-msg">{message}</span>
      </div>
    </div>
  )
}

/* Circular score ring — visual anchor of the controls section */
function ConformityGauge({ pct, tone, ok, total, pending }: {
  pct: number
  tone: 'ok' | 'warn' | 'ko' | 'pending'
  ok: number
  total: number
  pending: number
}) {
  const size = 76
  const stroke = 7
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const hasRun = total - pending > 0
  const shownPct = hasRun ? pct : 0
  const dash = (shownPct / 100) * c
  const label = tone === 'pending' ? '—' : `${pct}%`
  return (
    <div className={`ctrl-gauge tone-${tone}`} aria-label={`Conformite ${pct}%`} role="img">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={size / 2} cy={size / 2} r={r}
          fill="none" stroke="var(--ink-05)" strokeWidth={stroke} />
        {hasRun && (
          <circle cx={size / 2} cy={size / 2} r={r}
            fill="none" className="ctrl-gauge-arc"
            strokeWidth={stroke} strokeLinecap="round"
            strokeDasharray={`${dash} ${c}`}
            transform={`rotate(-90 ${size / 2} ${size / 2})`} />
        )}
      </svg>
      <div className="ctrl-gauge-value">
        <strong>{label}</strong>
        <span>{ok}/{total}</span>
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
function CenterPanel({ item, dossier, dossierId, onRefreshResults, onReplaceResults, onRerunRule, onOptimisticUpdate, onOpenDoc, cascadeScope, rerunning, problemNav, ruleConfig, onToggleRule }: {
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
  ruleConfig?: RuleConfigShape
  onToggleRule?: (regle: string, enabled: boolean) => void
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

  const [methodOpen, setMethodOpen] = useLocalStorageState<boolean>(
    'ctrl-split.method-open', false,
    (v): v is boolean => typeof v === 'boolean'
  )

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
          <span className={`ctrl-detail-engine engine-${item.engine}`} title={ENGINE_LABEL[item.engine].hint}>
            <span className={`ctrl-engine-chip-dot dot-${item.engine}`} aria-hidden="true" />
            {ENGINE_LABEL[item.engine].long}
          </span>
          {stale && <span className="ctrl-chip-neutral" title="Un document lie a ete re-uploade depuis la derniere execution : le verdict peut ne plus etre a jour">Obsolete</span>}
        </div>
        <h3 className="ctrl-detail-title">{item.label}</h3>
        {item.desc && <p className="ctrl-detail-desc">{item.desc}</p>}
        {item.category === 'custom' && item.custom && (
          <CustomRuleBanner
            rule={item.custom}
            override={ruleConfig?.overrides?.[item.code]}
            onToggle={onToggleRule}
          />
        )}
      </div>

      <div className="ctrl-split-center-body">

        {/* Verdict banner : la reponse en une phrase, avant tout le detail.
            Objectif : qu'en arrivant sur un controle, l'operateur comprenne
            immediatement ce qui a ete juge, sans scroller ni lire la methode. */}
        <VerdictBanner item={item} />

        {/* Detail from validation — affiche uniquement si le texte apporte plus
            que ce que le verdict banner montre deja. Evite de dire deux fois
            la meme chose ; sinon on laisse respirer. */}
        {r?.detail && detailAddsInfo(r, item.status) && (
          <div className="ctrl-detail-section">
            <div className="ctrl-detail-section-title">Analyse detaillee</div>
            <p className="ctrl-detail-note">{r.detail}</p>
          </div>
        )}

        {/* Methode du controle — repliee par defaut. On privilegie le verdict
            et les preuves au-dessus ; la formule et les champs relevent du
            debug / audit et ne doivent pas voler l'attention. */}
        {(ruleMeta?.formula || ruleMeta?.method || provenance) && (
          <div className="ctrl-detail-section">
            <button type="button"
              className="ctrl-method-toggle"
              onClick={() => setMethodOpen(!methodOpen)}
              aria-expanded={methodOpen}>
              {methodOpen ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              Methode du controle
              {provenance && (
                <span className={`ctrl-method-toggle-tag tone-${provenance.tone}`}>
                  {provenance.label.replace(/^(Calcul |Jugement |Saisie )/, '')}
                </span>
              )}
            </button>
            {methodOpen && (
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
            )}
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
              {Array.from(new Set(r.documentIds)).map(docId => {
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

        <div className="ctrl-detail-kbd-hints" aria-hidden="true">
          <Keyboard size={11} />
          <span><kbd>J</kbd> suivant</span>
          <span><kbd>K</kbd> precedent</span>
          {problemNav.total > 1 && <span><kbd>&gt;</kbd> <kbd>&lt;</kbd> entre problemes</span>}
        </div>
      </div>

      {/* Sticky action bar — actions primaires toujours visibles.
          Evite a l'operateur de remonter en haut du panneau quand il scrolle
          les preuves et doit corriger / relancer. */}
      {r && !editing && (
        <div className="ctrl-detail-actionbar" role="toolbar" aria-label="Actions du controle">
          <div className="ctrl-detail-actionbar-status">
            <span className={`ctrl-detail-status-dot status-${item.status}`} aria-hidden="true" />
            <span className="ctrl-detail-actionbar-code">{item.code}</span>
            <span className={`ctrl-detail-actionbar-label status-${item.status}`}>{sd.label}</span>
          </div>
          <div className="ctrl-detail-actionbar-actions">
            <select className="ctrl-status-select" value={r.statut}
              onChange={e => handleCorrect(e.target.value)}
              aria-label="Changer le statut">
              {STATUT_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
            <button className="ctrl-btn-secondary" onClick={startEdit}>
              <Edit3 size={12} /> Corriger
            </button>
            <button className="ctrl-btn-primary" onClick={handleRerun} disabled={rerunning === item.code}>
              {rerunning === item.code ? <Loader2 size={12} className="spin" /> : <RefreshCw size={12} />}
              Relancer
              {cascadeSize > 1 && <span className="ctrl-cascade-badge">+{cascadeSize - 1}</span>}
            </button>
          </div>
        </div>
      )}
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
            <PdfFrame key={activeDoc.id} blobUrl={blobUrl} title={activeDoc.nomFichier} />
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
const FILTER_MODES = new Set<FilterMode>(['all', 'problems', 'conforme', 'pending'])
const ENGINE_FILTERS = new Set<EngineFilter>(['any', 'system', 'ai', 'human'])

export default memo(function ControlSplitView({ dossier, dossierId, validating, onValidate, onRefreshResults, onRerunRule, onReplaceResults, onOptimisticUpdate, cascadeScope, customRules, ruleConfig, onToggleRule }: Props) {
  const { toast } = useToast()
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  // Filtres/recherche persistes : l'operateur qui revient sur un dossier retrouve
  // son cadrage de travail (filtre "Problemes", moteur "IA", etc.).
  const [filterMode, setFilterMode] = useLocalStorageState<FilterMode>(
    'ctrl-split.filter', 'all',
    (v): v is FilterMode => typeof v === 'string' && FILTER_MODES.has(v as FilterMode)
  )
  const [engineFilter, setEngineFilter] = useLocalStorageState<EngineFilter>(
    'ctrl-split.engine', 'any',
    (v): v is EngineFilter => typeof v === 'string' && ENGINE_FILTERS.has(v as EngineFilter)
  )
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

    for (const cat of DOC_CATEGORIES) {
      if (cat.key === 'custom' || cat.key === 'autocontrole') continue // injectes plus bas via parsedPoints / customRules
      const catRuleCodes = systemRuleDefs.filter(r => (r as { docCategory?: string }).docCategory === cat.key).map(r => r.code)
      for (const code of catRuleCodes) {
        const ruleDef = systemRuleDefs.find(r => r.code === code)
        const fullDef = ALL_RULES.find(r => r.code === code)
        const result = results.find(r => r.regle === code || r.regle.startsWith(code + '.')) || undefined
        const status: ItemStatus = result
          ? statutToItemStatus(result.statut)
          : 'pending'
        list.push({ key: code, code, label: ruleDef?.label || code, desc: fullDef?.desc || '', result, status, group: cat.label, category: 'system', engine: deriveEngine({ category: 'system', result }), mini: miniVerdict(result) })
      }
    }

    // Autocontrole : R12 / R13 (system) + R12.01..R12.10 (checklist parsee)
    const autocontroleLabel = DOC_CATEGORY_LABEL.autocontrole
    const autocontroleSystemCodes = systemRuleDefs
      .filter(r => (r as { docCategory?: string }).docCategory === 'autocontrole')
      .map(r => r.code)
    for (const code of autocontroleSystemCodes) {
      const ruleDef = systemRuleDefs.find(r => r.code === code)
      const fullDef = ALL_RULES.find(r => r.code === code)
      const result = results.find(r => r.regle === code || r.regle.startsWith(code + '.')) || undefined
      const status: ItemStatus = result ? statutToItemStatus(result.statut) : 'pending'
      list.push({ key: code, code, label: ruleDef?.label || code, desc: fullDef?.desc || '', result, status, group: autocontroleLabel, category: 'system', engine: deriveEngine({ category: 'system', result }), mini: miniVerdict(result) })
    }
    if (parsedPoints.length > 0) {
      for (const pt of parsedPoints) {
        const rCode = `R12.${String(pt.num).padStart(2, '0')}`
        const result = results.find(r => r.regle === rCode) || undefined
        const status = result ? statutToItemStatus(result.statut) : estValideToItemStatus(pt.estValide, results.length > 0)
        list.push({ key: `ck-${pt.num}`, code: rCode, label: pt.desc, desc: '', result, status, group: autocontroleLabel, category: 'checklist', engine: 'human', mini: miniVerdict(result) })
      }
    }

    // Custom (user-defined) rules. Shown only if applicable to the dossier type
    // and not disabled via the per-dossier override. The rule's own `enabled`
    // flag is its global state; the override (if present) wins.
    if (customRules?.length) {
      for (const cr of customRules) {
        const applicable = dossier.type === 'BC' ? cr.appliesToBC : cr.appliesToContractuel
        if (!applicable) continue
        const override = ruleConfig?.overrides?.[cr.code]
        const effectivelyEnabled = override != null ? override : cr.enabled
        if (!effectivelyEnabled) continue
        const result = results.find(r => r.regle === cr.code) || undefined
        const status: ItemStatus = result ? statutToItemStatus(result.statut) : 'pending'
        list.push({
          key: `custom-${cr.code}`, code: cr.code, label: cr.libelle,
          desc: cr.description ?? '', result, status,
          group: DOC_CATEGORY_LABEL.custom, category: 'custom', engine: 'ai', custom: cr,
          mini: miniVerdict(result),
        })
      }
    }
    return list
  }, [systemRuleDefs, results, parsedPoints, customRules, ruleConfig, dossier.type])

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

  const engineCounts = useMemo<Record<ControlEngine, number>>(() => {
    const acc: Record<ControlEngine, number> = { system: 0, ai: 0, human: 0 }
    for (const item of items) acc[item.engine]++
    return acc
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

  // Navigation clavier : J/K parcourt toute la liste, "<" / ">" ne cycle que
  // sur les problemes (KO/WARN) pour accelerer la revue des anomalies.
  useEffect(() => {
    const step = (list: RuleItem[], prev: string | null, dir: 1 | -1, wrap: boolean): string | null => {
      if (list.length === 0) return prev
      const idx = prev ? list.findIndex(i => i.key === prev) : -1
      if (!wrap) {
        const next = idx + dir
        return next >= 0 && next < list.length ? list[next].key : prev
      }
      const next = idx < 0
        ? (dir === 1 ? 0 : list.length - 1)
        : (idx + dir + list.length) % list.length
      return list[next].key
    }
    const handler = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName
      if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return
      if (e.key === 'j' || e.key === 'J') {
        e.preventDefault()
        setSelectedKey(prev => step(items, prev, 1, false))
      } else if (e.key === 'k' || e.key === 'K') {
        e.preventDefault()
        setSelectedKey(prev => step(items, prev, -1, false))
      } else if ((e.key === '>' || e.key === '<') && !e.metaKey && !e.ctrlKey) {
        const problems = items.filter(i => i.status === 'ko' || i.status === 'warn')
        if (problems.length === 0) return
        e.preventDefault()
        setSelectedKey(prev => step(problems, prev, e.key === '>' ? 1 : -1, true))
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

  const filterByStatus = (mode: FilterMode, fallback?: ItemStatus) => {
    setFilterMode(mode)
    const target = fallback
      ? items.find(i => i.status === fallback)
      : items.find(i => i.status === 'ko') || items.find(i => i.status === 'warn')
    if (target) setSelectedKey(target.key)
  }

  return (
    <div className="ctrl-view">
      {/* Hero — element central, ancre visuelle du dossier */}
      <section className={`ctrl-hero ctrl-hero-featured tone-${healthTone}`}>
        <div className="ctrl-hero-accent" aria-hidden="true" />
        <div className="ctrl-hero-main">
          <ConformityGauge pct={pctOk} tone={healthTone}
            ok={counts.ok} total={counts.total} pending={counts.pending} />

          <div className="ctrl-hero-text">
            <div className="ctrl-hero-eyebrow">
              <ShieldCheck size={12} />
              <span>Controles du dossier</span>
              {lastRunLabel && (
                <>
                  <span className="ctrl-hero-eyebrow-dot" aria-hidden="true" />
                  <span className="ctrl-hero-eyebrow-time">Verifie {lastRunLabel}</span>
                </>
              )}
            </div>
            <h2 className="ctrl-hero-title">{headline}</h2>
            {hasResults ? (
              <div className="ctrl-hero-stats-inline">
                <button type="button"
                  className={`ctrl-hero-pill pill-ok ${filterMode === 'conforme' ? 'is-active' : ''}`}
                  onClick={() => filterByStatus('conforme', 'ok')}
                  aria-label={`${counts.ok} controles conformes, filtrer`}>
                  <CheckCircle2 size={11} /> {counts.ok} conformes
                </button>
                {counts.ko > 0 && (
                  <button type="button"
                    className={`ctrl-hero-pill pill-ko ${filterMode === 'problems' ? 'is-active' : ''}`}
                    onClick={() => filterByStatus('problems')}
                    aria-label={`${counts.ko} non conformes, filtrer`}>
                    <XCircle size={11} /> {counts.ko} non conformes
                  </button>
                )}
                {counts.warn > 0 && (
                  <button type="button"
                    className={`ctrl-hero-pill pill-warn ${filterMode === 'problems' ? 'is-active' : ''}`}
                    onClick={() => filterByStatus('problems', 'warn')}
                    aria-label={`${counts.warn} avertissements, filtrer`}>
                    <AlertCircle size={11} /> {counts.warn} avertissements
                  </button>
                )}
                {counts.pending > 0 && (
                  <button type="button"
                    className={`ctrl-hero-pill pill-pending ${filterMode === 'pending' ? 'is-active' : ''}`}
                    onClick={() => filterByStatus('pending', 'pending')}
                    aria-label={`${counts.pending} en attente, filtrer`}>
                    <Clock size={11} /> {counts.pending} en attente
                  </button>
                )}
              </div>
            ) : (
              <div className="ctrl-hero-empty">
                <Sparkles size={13} />
                <span>Lancez la verification pour executer les {counts.total} controles systeme et l'autocontrole.</span>
              </div>
            )}
            <div className="ctrl-hero-engines" aria-label="Repartition par moteur">
              {engineCounts.system > 0 && (
                <span className="ctrl-hero-engine engine-system" title={ENGINE_LABEL.system.hint}>
                  <span className="ctrl-engine-chip-dot dot-system" aria-hidden="true" />
                  {engineCounts.system} systeme
                </span>
              )}
              {engineCounts.ai > 0 && (
                <span className="ctrl-hero-engine engine-ai" title={ENGINE_LABEL.ai.hint}>
                  <span className="ctrl-engine-chip-dot dot-ai" aria-hidden="true" />
                  {engineCounts.ai} IA (Claude, 1 appel groupe)
                </span>
              )}
              {engineCounts.human > 0 && (
                <span className="ctrl-hero-engine engine-human" title={ENGINE_LABEL.human.hint}>
                  <span className="ctrl-engine-chip-dot dot-human" aria-hidden="true" />
                  {engineCounts.human} humain
                </span>
              )}
            </div>
          </div>

          <div className="ctrl-hero-actions-top">
            {counts.ko + counts.warn > 0 && (
              <button className="ctrl-btn-ghost" onClick={jumpToFirstProblem}>
                Aller au probleme <ChevronRight size={13} />
              </button>
            )}
            <button className="ctrl-btn-primary" onClick={onValidate} disabled={validating}>
              {validating ? <Loader2 size={13} className="spin" /> : hasResults ? <RefreshCw size={13} /> : <Play size={13} />}
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
          engineFilter={engineFilter} onEngineFilterChange={setEngineFilter}
          engineCounts={engineCounts}
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
          problemNav={problemNav} ruleConfig={ruleConfig} onToggleRule={onToggleRule} />

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

function CustomRuleBanner({ rule, override, onToggle }: {
  rule: CustomRule
  override: boolean | undefined
  onToggle?: (regle: string, enabled: boolean) => void
}) {
  const effective = override != null ? override : rule.enabled
  const hasOverride = override != null
  return (
    <div style={{
      marginTop: 10, padding: '10px 12px', borderRadius: 8,
      background: 'rgba(16,185,129,0.06)', border: '1px solid rgba(16,185,129,0.15)',
      display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap', fontSize: 12,
    }}>
      <span className="pill-meta accent" style={{ fontSize: 10 }}>Regle personnalisee</span>
      <span style={{ color: 'var(--ink-50)' }}>
        Severite:&nbsp;
        <strong style={{ color: rule.severity === 'NON_CONFORME' ? 'var(--danger)' : '#b45309' }}>
          {rule.severity === 'NON_CONFORME' ? 'Non conforme' : 'Avertissement'}
        </strong>
      </span>
      <span style={{ color: 'var(--ink-50)' }}>
        Global:&nbsp;<strong>{rule.enabled ? 'Actif' : 'Desactive'}</strong>
      </span>
      <label style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
        <span style={{ fontSize: 11, color: 'var(--ink-40)' }}>
          Dans ce dossier{hasOverride && ' (override)'}
        </span>
        <input type="checkbox" checked={effective}
          onChange={e => onToggle?.(rule.code, e.target.checked)}
          aria-label={`Activer la regle ${rule.code} pour ce dossier`} />
      </label>
    </div>
  )
}
