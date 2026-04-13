import { useEffect, useState, useRef, useCallback, useMemo, lazy, Suspense } from 'react'
import { useParams } from 'react-router-dom'
import { getDossierSummary, getDocumentsWithData, getValidationResults, validateDossier, changeStatut, getAuditLog } from '../api/dossierApi'
import type { DossierSummary, DocumentsWithData } from '../api/dossierApi'
import type { ValidationResult, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import { useDocumentEvents } from '../hooks/useDocumentEvents'
import { AlertTriangle, RefreshCw } from 'lucide-react'

const DossierHeader = lazy(() => import('../components/dossier/DossierHeader'))
const DossierEditForm = lazy(() => import('../components/dossier/DossierEditForm'))
const MetricsBar = lazy(() => import('../components/dossier/MetricsBar'))
const CompareView = lazy(() => import('../components/dossier/CompareView'))
const DocumentManager = lazy(() => import('../components/dossier/DocumentManager'))
const VerificationBlocks = lazy(() => import('../components/dossier/VerificationBlocks'))
const AuditLog = lazy(() => import('../components/dossier/AuditLog'))

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

  // UI states
  const [validating, setValidating] = useState(false)
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const liveProgress = useDocumentEvents(id, () => loadDocs())

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
      const processing = data.documents.some(d => d.statutExtraction === 'EN_COURS' || d.statutExtraction === 'EN_ATTENTE')
      if (processing && !pollRef.current) {
        pollRef.current = setInterval(() => {
          getDocumentsWithData(id).then(fresh => {
            if (!fresh || !fresh.documents) return
            setDocsData(fresh)
            const still = fresh.documents.some(d => d.statutExtraction === 'EN_COURS' || d.statutExtraction === 'EN_ATTENTE')
            if (!still && pollRef.current) {
              clearInterval(pollRef.current)
              pollRef.current = null
              loadSummary()
              loadAudit()
            }
          }).catch(() => {})
        }, 3000)
      }
    }).catch(e => setDocsError(e instanceof Error ? e.message : 'Erreur de chargement des documents'))
  }, [id, loadSummary, loadAudit])

  // Load all blocks in parallel on mount
  useEffect(() => {
    loadSummary()
    loadDocs()
    loadValidation()
    loadAudit()
    return () => { if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null } }
  }, [id, loadSummary, loadDocs, loadValidation, loadAudit])

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
      loadSummary()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Validation failed')
    } finally { setValidating(false) }
  }, [id, loadSummary, loadAudit, toast])

  const handleStatut = useCallback(async (statut: string) => {
    if (!id) return
    try {
      await changeStatut(id, statut, statut === 'REJETE' ? motifRejet : undefined)
      toast('success', statut === 'VALIDE' ? 'Dossier valide' : 'Dossier rejete')
      setRejectModal(false)
      setMotifRejet('')
      loadSummary()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }, [id, motifRejet, loadSummary, loadAudit, toast])

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

  const hasProcessing = useMemo(() =>
    docsData?.documents?.some(d => d.statutExtraction === 'EN_COURS') ?? false, [docsData])

  // Build compat object for child components
  const dossierCompat = useMemo(() => {
    if (!summary) return null
    return {
      ...summary,
      documents: docsData?.documents || [],
      facture: docsData?.factures?.[0] || null,
      factures: docsData?.factures || [],
      bonCommande: docsData?.bonCommande || null,
      contratAvenant: docsData?.contratAvenant || null,
      ordrePaiement: docsData?.ordrePaiement || null,
      checklistAutocontrole: docsData?.checklistAutocontrole || null,
      tableauControle: docsData?.tableauControle || null,
      pvReception: docsData?.pvReception || null,
      attestationFiscale: docsData?.attestationFiscale || null,
      resultatsValidation: validationResults,
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
            <div className="block-loaded" style={{ animationDelay: '0.05s' }}>
              <MetricsBar dossier={dossierCompat!} nbConformes={nbConformes} fmt={fmt} hasProcessing={hasProcessing} />
            </div>
          </>
        )}

        {/* Block 3: Compare */}
        {showCompare && (docsData && dossierCompat ? (
          <div className="block-loaded"><CompareView dossier={dossierCompat} /></div>
        ) : <DocsSkeleton />)}

        {/* Block 4: Documents — independent load */}
        {docsError ? (
          <BlockError message={docsError} onRetry={loadDocs} />
        ) : !docsData ? (
          <DocsSkeleton />
        ) : dossierCompat ? (
          <div className="block-loaded" style={{ animationDelay: '0.1s' }}>
            <DocumentManager dossier={dossierCompat} id={id!} liveProgress={liveProgress}
              onReload={() => { loadDocs(); loadSummary() }} onReloadAudit={loadAudit} />
          </div>
        ) : null}

        {/* Block 5: Verification — independent load */}
        {validationError ? (
          <BlockError message={validationError} onRetry={loadValidation} />
        ) : docsData && docsData.documents.length > 0 && dossierCompat ? (
          <div className="block-loaded" style={{ animationDelay: '0.15s' }}>
            <VerificationBlocks dossier={dossierCompat} validating={validating} onValidate={handleValidate}
              onNavigateDoc={(docId) => {
                const el = document.querySelector(`[data-doc-id="${docId}"]`)
                if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); (el as HTMLElement).click() }
              }} />
          </div>
        ) : (docsData === null && !docsError) ? <VerifSkeleton /> : null}

        {/* Block 6: Audit */}
        {audit.length > 0 && (
          <div className="block-loaded" style={{ animationDelay: '0.2s' }}>
            <AuditLog audit={audit} />
          </div>
        )}
      </div>
    </Suspense>
  )
}
