import { useEffect, useState, useRef, useCallback, useMemo, lazy, Suspense } from 'react'
import { useParams } from 'react-router-dom'
import { getDossierSummary, getDocumentsWithData, getValidationResults, validateDossier, changeStatut, getAuditLog } from '../api/dossierApi'
import type { DossierSummary, DocumentsWithData } from '../api/dossierApi'
import type { ValidationResult, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import { useDocumentEvents } from '../hooks/useDocumentEvents'
import { AlertTriangle, XCircle } from 'lucide-react'

const DossierHeader = lazy(() => import('../components/dossier/DossierHeader'))
const DossierEditForm = lazy(() => import('../components/dossier/DossierEditForm'))
const MetricsBar = lazy(() => import('../components/dossier/MetricsBar'))
const CompareView = lazy(() => import('../components/dossier/CompareView'))
const DocumentManager = lazy(() => import('../components/dossier/DocumentManager'))
const VerificationBlocks = lazy(() => import('../components/dossier/VerificationBlocks'))
const AuditLog = lazy(() => import('../components/dossier/AuditLog'))

function BlockSkeleton({ height = 80 }: { height?: number }) {
  return <div className="skeleton-card skeleton" style={{ height }} />
}

export default function DossierDetail() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()

  // Independent data states — each block loads separately
  const [summary, setSummary] = useState<DossierSummary | null>(null)
  const [docsData, setDocsData] = useState<DocumentsWithData | null>(null)
  const [validationResults, setValidationResults] = useState<ValidationResult[]>([])
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [error, setError] = useState('')

  // UI states
  const [validating, setValidating] = useState(false)
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const liveProgress = useDocumentEvents(id, () => loadDocs())

  // --- Independent loaders ---
  const loadSummary = useCallback(() => {
    if (!id) return
    getDossierSummary(id).then(setSummary).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
  }, [id])

  const loadValidation = useCallback(() => {
    if (!id) return
    getValidationResults(id).then(setValidationResults).catch(() => {})
  }, [id])

  const loadAudit = useCallback(() => {
    if (!id) return
    getAuditLog(id).then(setAudit).catch(() => {})
  }, [id])

  const loadDocs = useCallback(() => {
    if (!id) return
    getDocumentsWithData(id).then(data => {
      setDocsData(data)
      const processing = data.documents.some(d => d.statutExtraction === 'EN_COURS' || d.statutExtraction === 'EN_ATTENTE')
      if (processing && !pollRef.current) {
        pollRef.current = setInterval(() => {
          getDocumentsWithData(id).then(fresh => {
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
    }).catch(() => {})
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
    docsData?.documents.some(d => d.statutExtraction === 'EN_COURS') ?? false, [docsData])

  // Build a DossierDetail-like object for components that need it (backward compat)
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

  if (error && !summary) return <div className="alert alert-error"><AlertTriangle size={18} /> {error}</div>

  return (
    <Suspense fallback={<BlockSkeleton height={60} />}>
      <div>
        {/* Block 1: Header — loads from summary (fast) */}
        {!summary ? <BlockSkeleton height={120} /> : (
          <>
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

            {error && <div className="alert alert-error mb-3"><XCircle size={16} /> {error}</div>}

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

            {/* Block 2: Metrics — uses summary (already loaded) */}
            <MetricsBar dossier={dossierCompat!} nbConformes={nbConformes} fmt={fmt} hasProcessing={hasProcessing} />
          </>
        )}

        {/* Block 3: Compare — loads from docs data */}
        {showCompare && (docsData ? (
          <CompareView dossier={dossierCompat!} />
        ) : <BlockSkeleton height={200} />)}

        {/* Block 4: Documents — independent load */}
        {!docsData ? <BlockSkeleton height={200} /> : (
          <DocumentManager dossier={dossierCompat!} id={id!} liveProgress={liveProgress}
            onReload={() => { loadDocs(); loadSummary() }} onReloadAudit={loadAudit} />
        )}

        {/* Block 5: Verification — independent load */}
        {docsData && docsData.documents.length > 0 && (
          !validationResults ? <BlockSkeleton height={100} /> : (
            <VerificationBlocks dossier={dossierCompat!} validating={validating} onValidate={handleValidate}
              onNavigateDoc={(docId) => {
                const el = document.querySelector(`[data-doc-id="${docId}"]`)
                if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); (el as HTMLElement).click() }
              }} />
          )
        )}

        {/* Block 6: Audit — independent load */}
        <AuditLog audit={audit} />
      </div>
    </Suspense>
  )
}
