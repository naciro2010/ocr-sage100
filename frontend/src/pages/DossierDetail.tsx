import { useEffect, useState, useRef, useCallback, useMemo, lazy, Suspense } from 'react'
import { useParams } from 'react-router-dom'
import { getDossier, validateDossier, changeStatut, getAuditLog } from '../api/dossierApi'
import type { DossierDetail as DossierDetailType } from '../api/dossierTypes'
import type { AuditEntry } from '../api/dossierTypes'
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

function DetailSkeleton() {
  return (
    <div className="skeleton">
      <div className="skeleton-bar h-lg w-60" />
      <div className="skeleton-card" style={{ height: 60 }} />
      <div className="skeleton-card" style={{ height: 40 }} />
      <div className="skeleton-grid">
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
      </div>
      <div className="skeleton-card" style={{ height: 200 }} />
      <div className="skeleton-card" style={{ height: 150 }} />
    </div>
  )
}

export default function DossierDetail() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()
  const [dossier, setDossier] = useState<DossierDetailType | null>(null)
  const [error, setError] = useState('')
  const [validating, setValidating] = useState(false)
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const liveProgress = useDocumentEvents(id, () => load())

  const loadAudit = useCallback(() => {
    if (id) getAuditLog(id).then(setAudit).catch(() => {})
  }, [id])

  const dossierRef = useRef<string | null>(null)

  const load = useCallback(() => {
    if (!id) return
    setError('')
    getDossier(id).then(d => {
      setDossier(d)
      const processing = d.documents.some(doc => doc.statutExtraction === 'EN_COURS' || doc.statutExtraction === 'EN_ATTENTE')
      if (processing && !pollRef.current) {
        pollRef.current = setInterval(() => {
          getDossier(id).then(fresh => {
            const fingerprint = JSON.stringify(fresh.documents.map(doc => doc.statutExtraction)) + fresh.resultatsValidation.length
            if (fingerprint !== dossierRef.current) {
              dossierRef.current = fingerprint
              setDossier(fresh)
            }
            const stillProcessing = fresh.documents.some(doc => doc.statutExtraction === 'EN_COURS' || doc.statutExtraction === 'EN_ATTENTE')
            if (!stillProcessing && pollRef.current) {
              clearInterval(pollRef.current)
              pollRef.current = null
              loadAudit()
            }
          }).catch(() => {})
        }, 3000)
      }
    }).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
  }, [id, loadAudit])

  useEffect(() => {
    load()
    loadAudit()
    return () => { if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null } }
  }, [id, load, loadAudit])

  const handleValidate = useCallback(async () => {
    if (!id) return
    setValidating(true)
    try {
      await validateDossier(id)
      toast('success', 'Verification terminee')
      load()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Validation failed')
    } finally { setValidating(false) }
  }, [id, load, loadAudit, toast])

  const handleStatut = useCallback(async (statut: string) => {
    if (!id) return
    try {
      await changeStatut(id, statut, statut === 'REJETE' ? motifRejet : undefined)
      toast('success', statut === 'VALIDE' ? 'Dossier valide' : 'Dossier rejete')
      setRejectModal(false)
      setMotifRejet('')
      load()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }, [id, motifRejet, load, loadAudit, toast])

  const copyRef = useCallback(() => {
    if (!dossier) return
    navigator.clipboard.writeText(dossier.reference)
    toast('info', `Reference ${dossier.reference} copiee`)
  }, [dossier, toast])

  const fmt = useCallback((n: number | null | undefined) =>
    n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014', [])

  const { nbConformes, nbNonConformes } = useMemo(() => {
    if (!dossier) return { nbConformes: 0, nbNonConformes: 0 }
    let conformes = 0, nonConformes = 0
    for (const r of dossier.resultatsValidation) {
      if (r.statut === 'CONFORME') conformes++
      else if (r.statut === 'NON_CONFORME') nonConformes++
    }
    return { nbConformes: conformes, nbNonConformes: nonConformes }
  }, [dossier])

  const hasProcessing = useMemo(() =>
    dossier?.documents.some(d => d.statutExtraction === 'EN_COURS') ?? false, [dossier])

  if (error && !dossier) return <div className="alert alert-error"><AlertTriangle size={18} /> {error}</div>
  if (!dossier) return <DetailSkeleton />

  return (
    <Suspense fallback={<DetailSkeleton />}>
      <div>
        {editing ? (
          <DossierEditForm dossier={dossier} id={id!} onDone={() => { setEditing(false); load(); loadAudit() }} onCancel={() => setEditing(false)} />
        ) : (
          <DossierHeader
            dossier={dossier} id={id!}
            hasProcessing={hasProcessing} validating={validating}
            editing={editing} nbNonConformes={nbNonConformes}
            showCompare={showCompare}
            onLoad={load} onStartEdit={() => setEditing(true)}
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

        <MetricsBar dossier={dossier} nbConformes={nbConformes} fmt={fmt} hasProcessing={hasProcessing} />
        {showCompare && <CompareView dossier={dossier} />}
        <DocumentManager dossier={dossier} id={id!} liveProgress={liveProgress}
          onReload={load} onReloadAudit={loadAudit} />
        {dossier.documents.length > 0 && (
          <VerificationBlocks dossier={dossier} validating={validating} onValidate={handleValidate}
            onNavigateDoc={(docId) => {
              const el = document.querySelector(`[data-doc-id="${docId}"]`)
              if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'center' }); (el as HTMLElement).click() }
            }} />
        )}
        <AuditLog audit={audit} />
      </div>
    </Suspense>
  )
}
