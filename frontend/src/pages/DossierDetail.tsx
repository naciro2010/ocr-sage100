import { useEffect, useState, useCallback, useMemo, lazy, Suspense } from 'react'
import { useParams } from 'react-router-dom'

const EMPTY_DOCS: never[] = []
const EMPTY_RESULTS: never[] = []
import { getDossierSummary, getDocumentsWithData, getValidationResults, validateDossier, changeStatut, getAuditLog, rerunValidationRule, getRuleConfig, updateRuleConfig, getCascadeScope } from '../api/dossierApi'
import { listCustomRules, type CustomRule } from '../api/customRulesApi'
import type { DossierSummary, DocumentsWithData } from '../api/dossierApi'
import type { ValidationResult, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import { useDocumentEvents } from '../hooks/useDocumentEvents'
import { AlertTriangle, RefreshCw, Gauge, ShieldCheck, FileText as FileIcon, History as HistoryIcon } from 'lucide-react'

const DossierHeader = lazy(() => import('../components/dossier/DossierHeader'))
const DossierEditForm = lazy(() => import('../components/dossier/DossierEditForm'))
const MetricsBar = lazy(() => import('../components/dossier/MetricsBar'))
const CompareView = lazy(() => import('../components/dossier/CompareView'))
const FieldDiffMatrix = lazy(() => import('../components/dossier/FieldDiffMatrix'))
const DocumentManager = lazy(() => import('../components/dossier/DocumentManager'))
const ControlSplitView = lazy(() => import('../components/dossier/ControlSplitView'))
const AuditLog = lazy(() => import('../components/dossier/AuditLog'))
const RequiredDocumentsConfig = lazy(() => import('../components/dossier/RequiredDocumentsConfig'))

function HeaderSkeleton() {
  return (
    <div className="skel-stagger">
      <div className="skel-header">
        <div className="skel-header-row">
          <div className="skeleton-circle" />
          <div className="skeleton-line h-lg" style={{ width: 180 }} />
          <div className="skeleton-line" style={{ width: 60, marginLeft: 8 }} />
          <div className="skel-header-actions">
            <div className="skel-header-btn" />
            <div className="skel-header-btn" />
            <div className="skel-header-btn" style={{ width: 100 }} />
          </div>
        </div>
      </div>
      <div className="skeleton-card">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div className="skeleton-line" style={{ width: 80, height: 22, borderRadius: 4 }} />
          <div className="skeleton-line" style={{ width: 60, height: 20, borderRadius: 4 }} />
          <div className="skeleton-line h-lg" style={{ width: 140 }} />
        </div>
      </div>
    </div>
  )
}

function MetricsSkeleton() {
  return (
    <div className="skeleton-card" style={{ padding: '12px 18px' }}>
      <div className="skel-metrics">
        <div className="skel-metrics-amount">
          <div className="skeleton-line h-sm" style={{ width: 70 }} />
          <div className="skeleton-line h-lg" style={{ width: 140 }} />
        </div>
        <div style={{ width: 1, height: 30, background: 'var(--ink-05)' }} />
        <div className="skel-metrics-amount">
          <div className="skeleton-line h-sm" style={{ width: 60 }} />
          <div className="skeleton-line" style={{ width: 100 }} />
        </div>
      </div>
      <div className="skel-timeline">
        <div className="skel-timeline-step" />
        <div className="skel-timeline-connector" />
        <div className="skel-timeline-step" />
        <div className="skel-timeline-connector" />
        <div className="skel-timeline-step" />
        <div className="skel-timeline-connector" />
        <div className="skel-timeline-step" />
      </div>
    </div>
  )
}

function DocsSkeleton() {
  return (
    <div className="skeleton-card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <div className="skeleton-line h-sm" style={{ width: 140 }} />
        <div className="skel-header-btn" style={{ width: 90 }} />
      </div>
      <div className="skel-doc-grid">
        {[1, 2, 3, 4].map(i => (
          <div key={i} className="skel-doc-card">
            <div className="skeleton-line" style={{ width: '70%' }} />
            <div className="skeleton-line h-sm" style={{ width: '90%' }} />
            <div className="skeleton-line" style={{ width: 60, height: 18, borderRadius: 4, marginTop: 'auto' }} />
          </div>
        ))}
      </div>
    </div>
  )
}

function VerifSkeleton() {
  return (
    <div style={{ borderRadius: 8, overflow: 'hidden', border: '1px solid var(--ink-05)', marginBottom: 12 }}>
      <div className="skel-verif-header" />
      <div className="skel-verif-rows">
        {[1, 2, 3].map(i => (
          <div key={i} className="skel-verif-row">
            <div className="skeleton-circle" style={{ width: 22, height: 22 }} />
            <div className="skeleton-line" style={{ width: 28 }} />
            <div className="skeleton-line" style={{ flex: 1 }} />
            <div className="skeleton-line" style={{ width: 60, height: 18, borderRadius: 4 }} />
          </div>
        ))}
      </div>
    </div>
  )
}

function BlockError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="card" style={{ padding: '14px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--danger)', fontSize: 12 }}>
        <AlertTriangle size={14} />
        <span>{message}</span>
      </div>
      <button className="btn btn-secondary btn-sm" onClick={onRetry} aria-label="Reessayer">
        <RefreshCw size={12} /> Reessayer
      </button>
    </div>
  )
}

type SectionTone = 'ok' | 'ko' | 'warn' | 'info' | null
interface SectionItem {
  id: string
  label: string
  icon: typeof Gauge
  badge: string | null
  tone: SectionTone
}

export default function DossierDetail() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()

  // Independent data states
  const [summary, setSummary] = useState<DossierSummary | null>(null)
  const [docsData, setDocsData] = useState<DocumentsWithData | null>(null)
  const [validationResults, setValidationResults] = useState<ValidationResult[]>([])
  const [audit, setAudit] = useState<AuditEntry[]>([])

  // Error states per block
  const [summaryError, setSummaryError] = useState('')
  const [docsError, setDocsError] = useState('')
  const [validationError, setValidationError] = useState('')

  const [ruleConfig, setRuleConfig] = useState<{ global: Record<string, boolean>; overrides: Record<string, boolean> } | null>(null)
  const [customRules, setCustomRules] = useState<CustomRule[]>([])
  const [cascadeScope, setCascadeScope] = useState<Record<string, string[]>>({})
  // UI states
  const [validating, setValidating] = useState(false)
  const [actionLoading, setActionLoading] = useState(false)
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [showCompare, setShowCompare] = useState(false)

  const hasProcessing = useMemo(() =>
    docsData?.documents?.some(d => d.statutExtraction === 'EN_COURS' || d.statutExtraction === 'EN_ATTENTE') ?? false, [docsData])

  const liveProgress = useDocumentEvents(id, () => { loadDocs(); loadSummary() }, hasProcessing)

  // --- Independent loaders with error handling ---
  const loadSummary = useCallback(() => {
    if (!id) return
    setSummaryError('')
    getDossierSummary(id)
      .then(setSummary)
      .catch(e => setSummaryError(e instanceof Error ? e.message : 'Erreur de chargement'))
  }, [id])

  const loadValidation = useCallback(() => {
    if (!id) return
    setValidationError('')
    getValidationResults(id)
      .then(setValidationResults)
      .catch(e => setValidationError(e instanceof Error ? e.message : 'Erreur'))
  }, [id])

  const loadAudit = useCallback(() => {
    if (!id) return
    getAuditLog(id).then(setAudit).catch(() => {})
  }, [id])

  const loadDocs = useCallback(() => {
    if (!id) return
    setDocsError('')
    getDocumentsWithData(id).then(data => {
      if (!data || !data.documents) {
        setDocsError('Format de reponse inattendu')
        return
      }
      setDocsData(data)
    }).catch(e => setDocsError(e instanceof Error ? e.message : 'Erreur de chargement des documents'))
  }, [id])

  const loadRuleConfig = useCallback(() => {
    if (!id) return
    getRuleConfig(id).then(setRuleConfig).catch(() => {})
  }, [id])

  const loadCustomRules = useCallback(() => {
    listCustomRules().then(setCustomRules).catch(() => setCustomRules([]))
  }, [])

  // Chaque endpoint a sa responsabilite (REST atomique). Le navigateur
  // multiplexe les 5 GET en parallele ; combines au cache HTTP/ETag/SW,
  // chaque bloc affiche son skeleton individuel et apparait des qu'il est
  // pret. Pas de cascade artificielle : si le summary tombe, les docs
  // peuvent quand meme s'afficher.
  useEffect(() => {
    loadSummary()
    loadDocs()
    loadValidation()
    loadAudit()
    loadRuleConfig()
    loadCustomRules()
  }, [id, loadSummary, loadDocs, loadValidation, loadAudit, loadRuleConfig, loadCustomRules])

  const reloadAll = useCallback(() => {
    loadSummary(); loadDocs(); loadValidation(); loadAudit()
  }, [loadSummary, loadDocs, loadValidation, loadAudit])

  const handleValidate = useCallback(async () => {
    if (!id) return
    setValidating(true)
    try {
      const results = await validateDossier(id)
      setValidationResults(results)
      toast('success', 'Verification terminee')
      // Refresh summary for updated check counts, audit for new entry
      loadSummary()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Validation failed')
    } finally { setValidating(false) }
  }, [id, loadSummary, loadAudit, toast])

  const handleStatut = useCallback(async (statut: string) => {
    if (!id || !summary) return
    setActionLoading(true)
    // Optimistic: update summary statut immediately
    const previousSummary = summary
    setSummary(prev => prev ? { ...prev, statut: statut as DossierSummary['statut'] } : prev)
    setRejectModal(false)
    try {
      const detail = await changeStatut(id, statut, statut === 'REJETE' ? motifRejet : undefined)
      // Inject server response into summary (dateValidation, validePar, motifRejet updated)
      setSummary(prev => prev ? {
        ...prev,
        statut: detail.statut as DossierSummary['statut'],
        dateValidation: detail.dateValidation,
        validePar: detail.validePar,
        motifRejet: detail.motifRejet,
      } : prev)
      toast('success', statut === 'VALIDE' ? 'Dossier valide' : statut === 'REJETE' ? 'Dossier rejete' : 'Statut mis a jour')
      setMotifRejet('')
      loadAudit()
    } catch (e: unknown) {
      // Revert optimistic update
      setSummary(previousSummary)
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setActionLoading(false) }
  }, [id, summary, motifRejet, loadAudit, toast])

  const handleRerunRule = useCallback(async (regle: string) => {
    if (!id) return
    const results = await rerunValidationRule(id, regle)
    setValidationResults(prev => {
      const updated = [...prev]
      for (const newR of results) {
        const idx = updated.findIndex(r => r.regle === newR.regle)
        if (idx >= 0) updated[idx] = newR
        else updated.push(newR)
      }
      return updated
    })
    loadSummary()
    loadAudit()
  }, [id, loadSummary, loadAudit])

  const handleReplaceResults = useCallback((results: ValidationResult[]) => {
    setValidationResults(prev => {
      const updated = [...prev]
      for (const newR of results) {
        const idx = updated.findIndex(r => (newR.id && r.id === newR.id) || r.regle === newR.regle)
        if (idx >= 0) updated[idx] = newR
        else updated.push(newR)
      }
      return updated
    })
    loadSummary()
    loadAudit()
  }, [loadSummary, loadAudit])


  // Prefetch cascade scope for every rule code that has a result (bounded, cached 60s)
  useEffect(() => {
    const codes = new Set<string>()
    for (const r of validationResults) {
      const base = r.regle.split('.')[0]
      codes.add(base)
    }
    codes.forEach(code => {
      if (cascadeScope[code]) return
      getCascadeScope(code).then(res => {
        setCascadeScope(prev => prev[code] ? prev : { ...prev, [code]: res.cascade })
      }).catch(() => {})
    })
  }, [validationResults, cascadeScope])

  const handleToggleRule = useCallback((regle: string, enabled: boolean) => {
    if (!id) return
    updateRuleConfig(id, { [regle]: enabled })
      .then(setRuleConfig)
      .catch(e => toast('error', e instanceof Error ? e.message : 'Erreur'))
  }, [id, toast])

  const copyRef = useCallback(() => {
    if (!summary) return
    navigator.clipboard.writeText(summary.reference)
    toast('info', `Reference ${summary.reference} copiee`)
  }, [summary, toast])

  const fmt = useCallback((n: number | null | undefined) =>
    n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014', [])

  const nbConformes = useMemo(() =>
    validationResults.filter(r => r.statut === 'CONFORME').length, [validationResults])
  const nbNonConformes = useMemo(() =>
    validationResults.filter(r => r.statut === 'NON_CONFORME').length, [validationResults])
  const nbAvertissements = useMemo(() =>
    validationResults.filter(r => r.statut === 'AVERTISSEMENT').length, [validationResults])

  const nbDocs = docsData?.documents?.length ?? 0
  const nbDocsProblemes = useMemo(() =>
    docsData?.documents?.filter(d => d.statutExtraction === 'ERREUR').length ?? 0, [docsData])

  // Sections accessibles via le sous-nav sticky. Limite la perte d'orientation
  // quand la page depasse l'ecran : chaque section a son ancre et son compteur.
  const sections = useMemo(() => {
    const controlesBadge: { badge: string; tone: SectionTone } | null =
      nbNonConformes > 0 ? { badge: String(nbNonConformes), tone: 'ko' } :
      nbAvertissements > 0 ? { badge: String(nbAvertissements), tone: 'warn' } :
      validationResults.length > 0 ? { badge: 'OK', tone: 'ok' } :
      null
    const documentsBadge: { badge: string; tone: SectionTone } | null =
      nbDocsProblemes > 0 ? { badge: String(nbDocsProblemes), tone: 'ko' } :
      hasProcessing && nbDocs > 0 ? { badge: String(nbDocs), tone: 'info' } :
      nbDocs > 0 ? { badge: String(nbDocs), tone: null } :
      null
    const all: (SectionItem & { visible: boolean })[] = [
      { id: 'vue', label: 'Vue', icon: Gauge, visible: true, badge: null, tone: null },
      { id: 'controles', label: 'Controles', icon: ShieldCheck, visible: nbDocs > 0, badge: controlesBadge?.badge ?? null, tone: controlesBadge?.tone ?? null },
      { id: 'documents', label: 'Documents', icon: FileIcon, visible: true, badge: documentsBadge?.badge ?? null, tone: documentsBadge?.tone ?? null },
      { id: 'historique', label: 'Historique', icon: HistoryIcon, visible: audit.length > 0, badge: String(audit.length), tone: null },
    ]
    return all.filter(s => s.visible)
  }, [nbDocs, nbNonConformes, nbAvertissements, nbDocsProblemes, hasProcessing, validationResults.length, audit.length])

  const scrollToSection = useCallback((id: string) => {
    const el = document.getElementById(`dossier-section-${id}`)
    if (!el) return
    // Decale le scroll pour laisser voir le header du bloc sous la nav sticky
    const y = el.getBoundingClientRect().top + window.scrollY - 80
    window.scrollTo({ top: y, behavior: 'smooth' })
  }, [])

  // Build compat object for child components
  const dossierCompat = useMemo(() => {
    if (!summary) return null
    return {
      ...summary,
      documents: docsData?.documents ?? EMPTY_DOCS,
      facture: docsData?.factures?.[0] ?? null,
      factures: docsData?.factures ?? EMPTY_DOCS,
      bonCommande: docsData?.bonCommande || null,
      contratAvenant: docsData?.contratAvenant || null,
      ordrePaiement: docsData?.ordrePaiement || null,
      checklistAutocontrole: docsData?.checklistAutocontrole || null,
      tableauControle: docsData?.tableauControle || null,
      pvReception: docsData?.pvReception || null,
      attestationFiscale: docsData?.attestationFiscale || null,
      resultatsValidation: validationResults.length > 0 ? validationResults : EMPTY_RESULTS,
    }
  }, [summary, docsData, validationResults])

  // Full page error — only if summary itself failed
  if (summaryError && !summary) {
    return (
      <div className="card" style={{ textAlign: 'center', padding: '48px 20px' }}>
        <AlertTriangle size={32} style={{ color: 'var(--danger)', marginBottom: 12 }} />
        <div style={{ fontSize: 16, fontWeight: 700, marginBottom: 4 }}>{summaryError}</div>
        <div style={{ fontSize: 13, color: 'var(--ink-40)', marginBottom: 16 }}>Impossible de charger le dossier</div>
        <button className="btn btn-primary" onClick={loadSummary}><RefreshCw size={14} /> Recharger</button>
      </div>
    )
  }

  return (
    <Suspense fallback={<HeaderSkeleton />}>
      <div className="skel-stagger">
        {/* Block 1: Header — appears first */}
        {!summary ? (
          <>
            <HeaderSkeleton />
            <MetricsSkeleton />
          </>
        ) : (
          <>
            <div className="block-loaded">
              {editing && dossierCompat ? (
                <DossierEditForm dossier={dossierCompat} id={id!} onDone={() => { setEditing(false); reloadAll() }} onCancel={() => setEditing(false)} />
              ) : (
                <DossierHeader
                  dossier={dossierCompat!} id={id!}
                  hasProcessing={hasProcessing} validating={validating}
                  actionLoading={actionLoading}
                  editing={editing} nbNonConformes={nbNonConformes}
                  showCompare={showCompare}
                  onLoad={reloadAll} onStartEdit={() => setEditing(true)}
                  onToggleCompare={() => setShowCompare(!showCompare)}
                  onValidate={handleValidate}
                  onValider={() => handleStatut('VALIDE')}
                  onRejeter={() => setRejectModal(true)}
                  onReouvrir={() => handleStatut('BROUILLON')}
                  onCopyRef={copyRef}
                />
              )}
            </div>

            <Modal open={rejectModal} title="Rejeter le dossier"
              message="Etes-vous sur de vouloir rejeter ce dossier ? Cette action sera enregistree dans l'historique."
              confirmLabel="Rejeter" confirmColor="var(--danger)"
              onConfirm={() => handleStatut('REJETE')}
              onCancel={() => { setRejectModal(false); setMotifRejet('') }}>
              <div style={{ marginBottom: 16 }}>
                <label className="form-label" htmlFor="motif-rejet">Motif de rejet (optionnel)</label>
                <input id="motif-rejet" className="form-input" value={motifRejet} onChange={e => setMotifRejet(e.target.value)}
                  placeholder="Ex: Documents manquants, montants incoherents..." />
              </div>
            </Modal>

            {/* Block 2: Metrics — same as header */}
            <div id="dossier-section-vue" className="block-loaded" style={{ animationDelay: '0.05s' }}>
              <MetricsBar dossier={dossierCompat!} nbConformes={nbConformes} fmt={fmt} hasProcessing={hasProcessing} />
            </div>

            {sections.length > 1 && (
              <nav className="dossier-subnav" aria-label="Sections du dossier">
                <div className="dossier-subnav-inner">
                  {sections.map(s => {
                    const Icon = s.icon
                    return (
                      <button key={s.id} type="button" className="dossier-subnav-item"
                        onClick={() => scrollToSection(s.id)}>
                        <Icon size={13} />
                        <span>{s.label}</span>
                        {s.badge && (
                          <span className={`dossier-subnav-badge${s.tone ? ` tone-${s.tone}` : ''}`}>
                            {s.badge}
                          </span>
                        )}
                      </button>
                    )
                  })}
                </div>
              </nav>
            )}
          </>
        )}

        {/* Block 3: Compare */}
        {showCompare && (docsData && dossierCompat ? (
          <>
            <div className="block-loaded"><FieldDiffMatrix dossierId={id!} /></div>
            <div className="block-loaded"><CompareView dossier={dossierCompat} /></div>
          </>
        ) : <DocsSkeleton />)}

        {/* Block 4: Verification — split-view, element central de la page */}
        {validationError ? (
          <BlockError message={validationError} onRetry={loadValidation} />
        ) : docsData && docsData.documents.length > 0 && dossierCompat ? (
          <div id="dossier-section-controles" className="block-loaded" style={{ animationDelay: '0.1s' }}>
            <ControlSplitView
              dossier={dossierCompat}
              dossierId={id!}
              validating={validating}
              onValidate={handleValidate}
              onRefreshResults={loadValidation}
              onRerunRule={handleRerunRule}
              onReplaceResults={handleReplaceResults}
              onOptimisticUpdate={(resultId, newStatut) => {
                setValidationResults(prev => prev.map(r => r.id === resultId ? { ...r, statut: newStatut as ValidationResult['statut'] } : r))
              }}
              onToggleRule={handleToggleRule}
              ruleConfig={ruleConfig || undefined}
              cascadeScope={cascadeScope}
              customRules={customRules}
            />
          </div>
        ) : (docsData === null && !docsError) ? <VerifSkeleton /> : null}

        {/* Block 5: Documents — reference, sous les controles */}
        {docsError ? (
          <BlockError message={docsError} onRetry={loadDocs} />
        ) : !docsData ? (
          <DocsSkeleton />
        ) : dossierCompat ? (
          <>
            <div id="dossier-section-documents" className="block-loaded" style={{ animationDelay: '0.15s' }}>
              <DocumentManager dossier={dossierCompat} id={id!} liveProgress={liveProgress}
                onReload={() => { loadDocs(); loadSummary() }} onReloadAudit={loadAudit}
                onValidationResultsUpdated={handleReplaceResults} />
            </div>
            <div className="block-loaded" style={{ animationDelay: '0.17s' }}>
              <RequiredDocumentsConfig dossierId={id!} onChanged={() => { loadValidation(); loadAudit() }} />
            </div>
          </>
        ) : null}

        {/* Block 6: Audit */}
        {audit.length > 0 && (
          <div id="dossier-section-historique" className="block-loaded" style={{ animationDelay: '0.2s' }}>
            <AuditLog audit={audit} />
          </div>
        )}
      </div>
    </Suspense>
  )
}
