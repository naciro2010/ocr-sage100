import { useEffect, useState, useRef, useCallback, useMemo } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getDossier, uploadDocuments, validateDossier, changeStatut, reprocessDocument, changeDocumentType, deleteDocument, getAuditLog, getDocumentFileUrl, updateDossier, openWithAuth } from '../api/dossierApi'
import type { DossierDetail as DossierDetailType } from '../api/dossierTypes'
import { STATUT_CONFIG, TYPE_DOCUMENT_LABELS } from '../api/dossierTypes'
import type { DocumentInfo, TypeDocument, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import ValidationPanel from '../components/ValidationPanel'
import DocumentPipeline from '../components/DocumentPipeline'
import { getActiveRules } from '../config/validationRules'
import { useDocumentEvents } from '../hooks/useDocumentEvents'
import {
  ArrowLeft, RefreshCw, Upload, FileText, CheckCircle, XCircle, AlertTriangle,
  Loader2, ShieldCheck, Banknote, FileCheck, Ban, FolderOpen, Eye, Clock,
  ExternalLink, Download, Pencil, Save, X, Columns2, Copy, Trash2,
} from 'lucide-react'

export default function DossierDetail() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()
  const [dossier, setDossier] = useState<DossierDetailType | null>(null)
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)
  const [validating, setValidating] = useState(false)
  const [selectedDoc, setSelectedDoc] = useState<DocumentInfo | null>(null)
  const [showPdf, setShowPdf] = useState(false)
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null)
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [editFields, setEditFields] = useState({ fournisseur: '', description: '', montantTtc: '', montantHt: '', montantTva: '', montantNetAPayer: '' })
  const [saving, setSaving] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const [compareLeft, setCompareLeft] = useState<string>('FACTURE')
  const [compareRight, setCompareRight] = useState<string>('')
  const inputRef = useRef<HTMLInputElement>(null)

  // SSE: real-time document processing progress
  const liveProgress = useDocumentEvents(id, () => load())

  const loadAudit = useCallback(() => {
    if (id) getAuditLog(id).then(setAudit).catch(() => {})
  }, [id])

  const load = useCallback(() => {
    if (!id) return
    setError('')
    getDossier(id).then(d => {
      setDossier(d)
      const processing = d.documents.some(doc => doc.statutExtraction === 'EN_COURS' || doc.statutExtraction === 'EN_ATTENTE')
      if (processing) {
        setTimeout(() => load(), 10000) // Fallback poll — SSE handles real-time
      }
    }).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
  }, [id])

  useEffect(() => {
    const ctrl = new AbortController()
    if (id) getDossier(id, ctrl.signal).then(setDossier).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    loadAudit()
    return () => ctrl.abort()
  }, [id, loadAudit])

  const startEdit = () => {
    if (!dossier) return
    setEditFields({
      fournisseur: dossier.fournisseur || '',
      description: dossier.description || '',
      montantTtc: dossier.montantTtc?.toString() || '',
      montantHt: dossier.montantHt?.toString() || '',
      montantTva: dossier.montantTva?.toString() || '',
      montantNetAPayer: dossier.montantNetAPayer?.toString() || '',
    })
    setEditing(true)
  }

  const handleSave = async () => {
    if (!id) return
    setSaving(true)
    try {
      const data: Record<string, unknown> = {}
      if (editFields.fournisseur) data.fournisseur = editFields.fournisseur
      if (editFields.description) data.description = editFields.description
      if (editFields.montantTtc) data.montantTtc = parseFloat(editFields.montantTtc)
      if (editFields.montantHt) data.montantHt = parseFloat(editFields.montantHt)
      if (editFields.montantTva) data.montantTva = parseFloat(editFields.montantTva)
      if (editFields.montantNetAPayer) data.montantNetAPayer = parseFloat(editFields.montantNetAPayer)
      await updateDossier(id, data)
      toast('success', 'Dossier mis a jour')
      setEditing(false)
      load()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setSaving(false) }
  }

  const handleUpload = async (files: FileList | File[] | null) => {
    if (!files || !id) return
    const fileArr = Array.from(files)
    setUploading(true)
    setDragging(false)
    try {
      await uploadDocuments(id, fileArr)
      toast('success', `${fileArr.length} document(s) uploade(s)`)
      load()
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Upload failed'
      setError(msg)
      toast('error', msg)
    }
    finally { setUploading(false) }
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const files = Array.from(e.dataTransfer.files).filter(f => f.name.toLowerCase().endsWith('.pdf'))
    if (files.length > 0) handleUpload(files)
    else toast('warning', 'Seuls les fichiers PDF sont acceptes')
  }

  const handleValidate = async () => {
    if (!id) return
    setValidating(true)
    try {
      await validateDossier(id)
      toast('success', 'Verification terminee')
      load()
      loadAudit()
    }
    catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Validation failed')
    }
    finally { setValidating(false) }
  }

  const handleStatut = async (statut: string) => {
    if (!id) return
    try {
      await changeStatut(id, statut, statut === 'REJETE' ? motifRejet : undefined)
      toast('success', statut === 'VALIDE' ? 'Dossier valide' : 'Dossier rejete')
      setRejectModal(false)
      setMotifRejet('')
      load()
      loadAudit()
    }
    catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }

  const handleReprocess = async (docId: string) => {
    if (!id) return
    try {
      await reprocessDocument(id, docId)
      toast('info', 'Retraitement lance')
      load()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Reprocess failed')
    }
  }

  const handleChangeType = async (docId: string, newType: string) => {
    if (!id) return
    try {
      await changeDocumentType(id, docId, newType)
      toast('success', `Type modifie en ${TYPE_DOCUMENT_LABELS[newType as keyof typeof TYPE_DOCUMENT_LABELS] || newType}`)
      load()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }

  const handleDeleteDoc = async (docId: string, docName: string) => {
    if (!id || !confirm(`Supprimer ${docName} ?`)) return
    try {
      await deleteDocument(id, docId)
      toast('success', `${docName} supprime`)
      if (selectedDoc?.id === docId) setSelectedDoc(null)
      load()
      loadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }

  const fmt = useCallback((n: number | null | undefined) => n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014', [])

  const { nbConformes, nbNonConformes, nbWarn: _nbWarn } = useMemo(() => {
    if (!dossier) return { nbConformes: 0, nbNonConformes: 0, nbWarn: 0 }
    let conformes = 0, nonConformes = 0, warn = 0
    for (const r of dossier.resultatsValidation) {
      if (r.statut === 'CONFORME') conformes++
      else if (r.statut === 'NON_CONFORME') nonConformes++
      else if (r.statut === 'AVERTISSEMENT') warn++
    }
    return { nbConformes: conformes, nbNonConformes: nonConformes, nbWarn: warn }
  }, [dossier])

  const hasProcessing = useMemo(() => dossier?.documents.some(d => d.statutExtraction === 'EN_COURS') ?? false, [dossier])

  const dataTypeMap = useMemo<Record<string, Record<string, unknown> | null>>(() => {
    if (!dossier) return {} as Record<string, Record<string, unknown> | null>
    return {
      FACTURE: dossier.facture, BON_COMMANDE: dossier.bonCommande,
      CONTRAT_AVENANT: dossier.contratAvenant, ORDRE_PAIEMENT: dossier.ordrePaiement,
      CHECKLIST_AUTOCONTROLE: dossier.checklistAutocontrole, TABLEAU_CONTROLE: dossier.tableauControle,
      PV_RECEPTION: dossier.pvReception, ATTESTATION_FISCALE: dossier.attestationFiscale,
    }
  }, [dossier])

  const getDataForType = useCallback((type: TypeDocument): Record<string, unknown> | null => {
    return dataTypeMap[type] || null
  }, [dataTypeMap])

  const copyRef = () => {
    if (!dossier) return
    navigator.clipboard.writeText(dossier.reference)
    toast('info', `Reference ${dossier.reference} copiee`)
  }

  if (error && !dossier) return <div className="alert alert-error"><AlertTriangle size={18} /> {error}</div>
  if (!dossier) return <div className="loading">Chargement...</div>

  const cfg = STATUT_CONFIG[dossier.statut]

  const extractionBadge = (doc: DocumentInfo) => {
    const statut = doc.statutExtraction
    const hasData = doc.donneesExtraites && Object.keys(doc.donneesExtraites).length > 0
    const fieldCount = hasData ? Object.values(doc.donneesExtraites!).filter(v => v !== null).length : 0

    const badge = (() => {
      if (statut === 'EXTRAIT' && !hasData)
        return <span className="status-badge" style={{ background: 'var(--amber-50)', color: 'var(--amber-600)' }}>Texte seul</span>
      if (statut === 'EXTRAIT' && hasData)
        return <span className="status-badge" style={{ background: 'var(--success-bg)', color: 'var(--success)' }}>{fieldCount} champs</span>
      if (statut === 'ERREUR')
        return <span className="status-badge" style={{ background: 'var(--danger-bg)', color: 'var(--danger)' }} title={doc.erreurExtraction || ''}>Erreur</span>
      if (statut === 'EN_COURS')
        return <span className="status-badge badge-processing" style={{ background: 'var(--info-bg)', color: 'var(--info)' }}>Analyse en cours</span>
      return <span className="status-badge badge-processing" style={{ background: 'var(--ink-05)', color: 'var(--ink-40)' }}>En file d'attente</span>
    })()

    const progressClass = statut === 'EXTRAIT' ? 'done' : statut === 'ERREUR' ? 'error' : statut === 'EN_COURS' ? 'processing' : 'pending'

    return (
      <>
        {badge}
        <div className="doc-card-progress">
          <div className={`doc-card-progress-bar ${progressClass}`} />
        </div>
      </>
    )
  }

  return (
    <div>
      {/* Header */}
      <div className="page-header">
        <h1>
          <Link to="/dossiers" className="back-link"><ArrowLeft size={20} /></Link>
          <span onClick={copyRef} style={{ cursor: 'pointer' }} title="Cliquer pour copier">{dossier.reference}</span>
          <Copy size={14} style={{ color: 'var(--slate-400)', cursor: 'pointer' }} onClick={copyRef} />
        </h1>
        <div className="header-actions">
          {hasProcessing && <span style={{ fontSize: 11, color: 'var(--amber-600)', display: 'flex', alignItems: 'center', gap: 4 }}><Loader2 size={14} className="spin" /> Extraction en cours...</span>}
          <button className="btn btn-secondary" onClick={load}><RefreshCw size={15} /></button>
          {dossier.statut === 'BROUILLON' && !editing && (
            <button className="btn btn-secondary" onClick={startEdit}><Pencil size={15} /> Modifier</button>
          )}
          <button className="btn btn-secondary" onClick={() => setShowCompare(!showCompare)}>
            <Columns2 size={15} /> {showCompare ? 'Masquer' : 'Comparer'}
          </button>
          <button className="btn btn-primary" onClick={handleValidate} disabled={validating}>
            {validating ? <><Loader2 size={15} className="spin" /> Verification...</> : <><ShieldCheck size={15} /> Verifier</>}
          </button>
          {dossier.statut !== 'VALIDE' && (
            <button className="btn btn-success" onClick={() => handleStatut('VALIDE')}
              title={nbNonConformes > 0 ? `${nbNonConformes} controle(s) non conforme(s)` : ''}>
              <CheckCircle size={15} /> Valider
            </button>
          )}
          {dossier.statut !== 'REJETE' && (
            <button className="btn btn-danger" onClick={() => setRejectModal(true)}>
              <Ban size={15} /> Rejeter
            </button>
          )}
          {dossier.resultatsValidation.length > 0 && dossier.statut !== 'VALIDE' && (
            <Link to={`/dossiers/${id}/finalize`} className="btn btn-primary" style={{ textDecoration: 'none', background: 'linear-gradient(135deg, var(--accent-deep), var(--accent))' }}>
              <FileText size={15} /> Finaliser
            </Link>
          )}
          {(dossier.statut === 'VALIDE' || dossier.statut === 'REJETE') && (
            <button className="btn btn-secondary" onClick={() => handleStatut('BROUILLON')}>
              Reouvrir
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error mb-3"><XCircle size={16} /> {error}</div>}

      <Modal open={rejectModal} title="Rejeter le dossier" message="Etes-vous sur de vouloir rejeter ce dossier ? Cette action sera enregistree dans l'historique."
        confirmLabel="Rejeter" confirmColor="var(--danger)" onConfirm={() => handleStatut('REJETE')} onCancel={() => { setRejectModal(false); setMotifRejet('') }}>
        <div style={{ marginBottom: 16 }}>
          <label className="form-label">Motif de rejet (optionnel)</label>
          <input className="form-input" value={motifRejet} onChange={e => setMotifRejet(e.target.value)} placeholder="Ex: Documents manquants, montants incoherents..." />
        </div>
      </Modal>

      {/* Header info / Edit mode */}
      <div className="card">
        {editing ? (
          <div>
            <h2><Pencil size={14} /> Modifier le dossier</h2>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
              <div>
                <label className="form-label">Fournisseur</label>
                <input className="form-input" value={editFields.fournisseur} onChange={e => setEditFields(f => ({ ...f, fournisseur: e.target.value }))} />
              </div>
              <div>
                <label className="form-label">Description</label>
                <input className="form-input" value={editFields.description} onChange={e => setEditFields(f => ({ ...f, description: e.target.value }))} />
              </div>
              <div>
                <label className="form-label">Montant TTC</label>
                <input className="form-input" type="number" step="0.01" value={editFields.montantTtc} onChange={e => setEditFields(f => ({ ...f, montantTtc: e.target.value }))} />
              </div>
              <div>
                <label className="form-label">Montant HT</label>
                <input className="form-input" type="number" step="0.01" value={editFields.montantHt} onChange={e => setEditFields(f => ({ ...f, montantHt: e.target.value }))} />
              </div>
              <div>
                <label className="form-label">Montant TVA</label>
                <input className="form-input" type="number" step="0.01" value={editFields.montantTva} onChange={e => setEditFields(f => ({ ...f, montantTva: e.target.value }))} />
              </div>
              <div>
                <label className="form-label">Net a payer</label>
                <input className="form-input" type="number" step="0.01" value={editFields.montantNetAPayer} onChange={e => setEditFields(f => ({ ...f, montantNetAPayer: e.target.value }))} />
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-primary" disabled={saving} onClick={handleSave}>
                {saving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Save size={14} /> Sauvegarder</>}
              </button>
              <button className="btn btn-secondary" onClick={() => setEditing(false)}><X size={14} /> Annuler</button>
            </div>
          </div>
        ) : (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span className="status-badge" style={{ background: cfg.bg, color: cfg.color, fontSize: 12 }}>{cfg.label}</span>
              <span className="tag">{dossier.type === 'BC' ? 'Bon de commande' : 'Contractuel'}</span>
              {dossier.fournisseur && <strong style={{ fontSize: 15, color: 'var(--slate-900)' }}>{dossier.fournisseur}</strong>}
            </div>
            {dossier.description && <span style={{ fontSize: 13, color: 'var(--slate-500)' }}>{dossier.description}</span>}
          </div>
        )}
      </div>

      {/* Workflow Timeline */}
      {(() => {
        const hasDocs = dossier.documents.length > 0
        const allExtracted = hasDocs && dossier.documents.every(d => d.statutExtraction === 'EXTRAIT')
        const hasErrors = dossier.documents.some(d => d.statutExtraction === 'ERREUR')
        const hasValidation = dossier.resultatsValidation.length > 0
        const isValidated = dossier.statut === 'VALIDE'
        const isRejected = dossier.statut === 'REJETE'

        const steps = [
          { label: 'Upload', done: hasDocs, active: !hasDocs, error: false },
          { label: 'Extraction', done: allExtracted, active: hasDocs && !allExtracted, error: hasErrors },
          { label: 'Verification', done: hasValidation, active: allExtracted && !hasValidation, error: false },
          { label: 'Decision', done: isValidated || isRejected, active: hasValidation && !isValidated && !isRejected, error: isRejected },
        ]

        return (
          <div className="timeline">
            {steps.map((step, i) => (
              <div key={step.label} style={{ display: 'flex', alignItems: 'center' }}>
                <div className={`timeline-step ${step.error ? 'error' : step.done ? 'done' : step.active ? 'active' : ''}`}>
                  {step.done ? <CheckCircle size={12} /> : step.error ? <XCircle size={12} /> : step.active ? <Loader2 size={12} className={step.active && i === 1 && hasProcessing ? 'spin' : ''} /> : null}
                  {step.label}
                </div>
                {i < steps.length - 1 && <div className={`timeline-connector ${step.done ? 'done' : ''}`} />}
              </div>
            ))}
          </div>
        )
      })()}

      {/* Metrics */}
      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon teal"><Banknote size={18} /></div>
          <div className="stat-value">{fmt(dossier.montantTtc)}</div>
          <div className="stat-label">Montant TTC</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Banknote size={18} /></div>
          <div className="stat-value">{fmt(dossier.montantNetAPayer ?? dossier.montantHt)}</div>
          <div className="stat-label">{dossier.montantNetAPayer ? 'Net a payer' : 'Montant HT'}</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><FolderOpen size={18} /></div>
          <div className="stat-value">{dossier.documents.length}</div>
          <div className="stat-label">Documents</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><FileCheck size={18} /></div>
          <div className="stat-value">{nbConformes}/{dossier.resultatsValidation.length}</div>
          <div className="stat-label">Checks conformes</div>
        </div>
      </div>

      {/* Comparison view — selectable documents */}
      {showCompare && (() => {
        const docTypes = [
          { key: 'FACTURE', label: 'Facture', data: dossier.facture },
          { key: 'BON_COMMANDE', label: 'Bon de commande', data: dossier.bonCommande },
          { key: 'CONTRAT_AVENANT', label: 'Contrat / Avenant', data: dossier.contratAvenant },
          { key: 'ORDRE_PAIEMENT', label: 'Ordre de paiement', data: dossier.ordrePaiement },
          { key: 'CHECKLIST', label: 'Checklist autocontrole', data: dossier.checklistAutocontrole },
          { key: 'TABLEAU_CONTROLE', label: 'Tableau de controle', data: dossier.tableauControle },
          { key: 'PV_RECEPTION', label: 'PV de reception', data: dossier.pvReception },
          { key: 'ATTESTATION_FISCALE', label: 'Attestation fiscale', data: dossier.attestationFiscale },
        ].filter(d => d.data != null)

        // Auto-select right if not set
        if (!compareRight && docTypes.length > 1) {
          const auto = docTypes.find(d => d.key !== compareLeft)
          if (auto) setCompareRight(auto.key)
        }

        const leftDoc = docTypes.find(d => d.key === compareLeft)
        const rightDoc = docTypes.find(d => d.key === compareRight)

        const renderData = (data: Record<string, unknown>) => (
          <table className="kv-table"><tbody>
            {Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object').map(([k, v]) => (
              <tr key={k}><td>{k}</td><td>{String(v)}</td></tr>
            ))}
          </tbody></table>
        )

        return (
        <div className="card">
          <h2><Columns2 size={14} /> Comparaison de documents</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
            <select className="form-select" value={compareLeft}
              onChange={e => setCompareLeft(e.target.value)}>
              {docTypes.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
            </select>
            <select className="form-select" value={compareRight}
              onChange={e => setCompareRight(e.target.value)}>
              {docTypes.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
            </select>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <div className="stat-label" style={{ color: 'var(--accent-deep)', marginBottom: 8 }}>{leftDoc?.label || 'Selectionnez'}</div>
              {leftDoc?.data && renderData(leftDoc.data as Record<string, unknown>)}
            </div>
            <div>
              <div className="stat-label" style={{ color: 'var(--info)', marginBottom: 8 }}>{rightDoc?.label || 'Selectionnez'}</div>
              {rightDoc?.data && renderData(rightDoc.data as Record<string, unknown>)}
            </div>
          </div>
        </div>
        )
      })()}

      {/* Documents with drag & drop */}
      <div className="card"
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        style={dragging ? { borderColor: 'var(--teal-600)', background: 'var(--teal-50)' } : undefined}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h2 style={{ marginBottom: 0 }}><FileText size={14} /> Documents du dossier</h2>
          {dossier.documents.length > 0 && (
            <button className="btn btn-secondary btn-sm" onClick={() => {
              dossier.documents.forEach(doc => handleReprocess(doc.id))
            }}>
              <RefreshCw size={11} /> Tout relancer
            </button>
          )}
        </div>
        {dragging && (
          <div style={{ padding: 32, textAlign: 'center', border: '2px dashed var(--teal-600)', borderRadius: 8, marginBottom: 16, color: 'var(--teal-700)', fontWeight: 700 }}>
            <Upload size={32} /><div style={{ marginTop: 8 }}>Deposez vos PDFs ici</div>
          </div>
        )}
        {/* Processing pipelines */}
        {dossier.documents.some(d => d.statutExtraction === 'EN_ATTENTE' || d.statutExtraction === 'EN_COURS') && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 }}>
            {dossier.documents
              .filter(d => d.statutExtraction === 'EN_ATTENTE' || d.statutExtraction === 'EN_COURS')
              .map(doc => <DocumentPipeline key={doc.id} doc={doc} liveProgress={liveProgress[doc.id]} />)}
          </div>
        )}

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))', gap: 10, marginBottom: 12 }}>
          {dossier.documents.map(doc => (
            <div key={doc.id} className={`doc-card ${selectedDoc?.id === doc.id ? 'selected' : ''}`}
              onClick={() => { setSelectedDoc(selectedDoc?.id === doc.id ? null : doc); setShowPdf(false) }}>
              <div style={{ marginBottom: 2 }}>
                <select
                  className="form-select"
                  value={doc.typeDocument}
                  title="Cliquez pour corriger le type"
                  style={{ fontSize: 11, fontWeight: 700, padding: '0 2px', border: 'none', background: 'transparent', color: 'var(--slate-800)', cursor: 'pointer', width: '100%' }}
                  onClick={e => e.stopPropagation()}
                  onChange={e => { e.stopPropagation(); handleChangeType(doc.id, e.target.value) }}
                >
                  {Object.entries(TYPE_DOCUMENT_LABELS).map(([k, v]) => (
                    <option key={k} value={k}>{v}</option>
                  ))}
                </select>
              </div>
              <div style={{ fontSize: 11, color: 'var(--slate-500)', marginBottom: 6 }}>{doc.nomFichier}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {extractionBadge(doc)}
                {doc.statutExtraction === 'EN_COURS' && <Loader2 size={12} className="spin" style={{ color: 'var(--blue-600)' }} />}
              </div>
              {doc.erreurExtraction && (
                <div style={{ fontSize: 10, color: 'var(--danger)', marginTop: 4, lineHeight: 1.3 }}>{doc.erreurExtraction}</div>
              )}
              <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
                {doc.statutExtraction === 'ERREUR' && (
                  <button className="btn btn-secondary btn-sm"
                    onClick={(e) => { e.stopPropagation(); handleReprocess(doc.id) }}>
                    <RefreshCw size={11} /> Relancer
                  </button>
                )}
                <button className="btn btn-danger btn-sm"
                  onClick={(e) => { e.stopPropagation(); handleDeleteDoc(doc.id, doc.nomFichier) }}>
                  <Trash2 size={11} />
                </button>
              </div>
            </div>
          ))}

          <div className="drop-zone"
            style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: 90, padding: 20 }}
            onClick={() => inputRef.current?.click()}>
            <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => handleUpload(e.target.files)} />
            {uploading ? <Loader2 size={22} className="spin" style={{ color: 'var(--teal-600)' }} /> : <Upload size={22} style={{ color: 'var(--slate-400)' }} />}
            <span style={{ fontSize: 12, color: 'var(--slate-500)', marginTop: 6 }}>{uploading ? 'Upload...' : 'Ajouter des PDFs'}</span>
          </div>
        </div>
      </div>

      {/* Selected doc: PDF + data */}
      {selectedDoc && (
        <>
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ marginBottom: 0 }}><Eye size={14} /> {TYPE_DOCUMENT_LABELS[selectedDoc.typeDocument]} — {selectedDoc.nomFichier}</h2>
              <div style={{ display: 'flex', gap: 6 }}>
                <button className="btn btn-secondary btn-sm" onClick={async () => {
                  if (showPdf) { setShowPdf(false); if (pdfBlobUrl) { URL.revokeObjectURL(pdfBlobUrl); setPdfBlobUrl(null) }; return }
                  if (!id) return
                  try {
                    const res = await fetch(getDocumentFileUrl(id, selectedDoc.id), { headers: { 'Authorization': `Basic ${localStorage.getItem('recondoc_auth') || ''}` } })
                    if (!res.ok) throw new Error(`HTTP ${res.status}`)
                    const blob = await res.blob()
                    setPdfBlobUrl(URL.createObjectURL(blob))
                    setShowPdf(true)
                  } catch { toast('error', 'Impossible de charger le PDF') }
                }}>
                  {showPdf ? <><XCircle size={14} /> Masquer PDF</> : <><FileText size={14} /> Voir PDF</>}
                </button>
                {id && <button className="btn btn-secondary btn-sm"
                  onClick={() => openWithAuth(getDocumentFileUrl(id, selectedDoc.id))}>
                  <Download size={14} /> Telecharger
                </button>}
              </div>
            </div>
            {showPdf && pdfBlobUrl && <div className="pdf-viewer"><iframe src={pdfBlobUrl} title={selectedDoc.nomFichier} /></div>}
          </div>
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <h2 style={{ marginBottom: 0 }}><ExternalLink size={14} /> Donnees extraites</h2>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <span className="data-field-source ocr" style={{ fontSize: 9 }}>OCR</span>
                <span className="data-field-source ai" style={{ fontSize: 9 }}>IA</span>
                <span style={{ fontSize: 10, color: 'var(--ink-30)' }}>Cliquez sur une valeur pour corriger</span>
              </div>
            </div>
            {selectedDoc.statutExtraction === 'ERREUR' && <div className="alert alert-error mb-2"><XCircle size={14} /> {selectedDoc.erreurExtraction}</div>}
            {(() => {
              const data = getDataForType(selectedDoc.typeDocument) || selectedDoc.donneesExtraites
              if (!data) return <p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Aucune donnee extraite</p>

              // Separate scalar fields, arrays (like points/lignes), and objects
              const scalars = Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object')
              const points = (data['points'] as Array<Record<string, unknown>> | undefined) || []
              const lignes = (data['lignes'] as Array<Record<string, unknown>> | undefined) || []
              const pieces = (data['pieces'] as Array<Record<string, unknown>> | undefined) || []
              const isLlmExtracted = scalars.length > 3

              return (
                <>
                  {/* Scalar fields with inline edit */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                    {scalars.map(([k, v]) => (
                      <div key={k} className="data-field">
                        <span className="data-field-key">{k}</span>
                        <span
                          className="data-field-value"
                          contentEditable
                          suppressContentEditableWarning
                          onBlur={e => {
                            const newVal = e.currentTarget.textContent || ''
                            if (newVal !== String(v)) {
                              toast('info', `${k}: ${String(v)} → ${newVal}`)
                            }
                          }}
                        >{String(v)}</span>
                        <span className={`data-field-source ${isLlmExtracted ? 'ai' : 'ocr'}`}>
                          {isLlmExtracted ? 'IA' : 'OCR'}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* Checklist points */}
                  {points.length > 0 && (() => {
                    const nbValides = points.filter(p => p.estValide === true).length
                    return (
                    <div style={{ marginTop: 14 }}>
                      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
                        Points de controle ({nbValides}/{points.length} valides)
                      </div>
                      {points.map((pt, i) => {
                        const isValid = pt.estValide === true
                        const isFalse = pt.estValide === false
                        return (
                          <div key={i} className="check-point">
                            <span className="check-point-num">{pt.numero != null ? String(pt.numero) : String(i + 1)}</span>
                            <span className={`check-point-icon ${isValid ? 'pass' : isFalse ? 'fail' : 'na'}`}>
                              {isValid ? '✓' : isFalse ? '✗' : '—'}
                            </span>
                            <div className="check-point-body">
                              <div className="check-point-label">{String(pt.description || pt.designation || `Point ${i + 1}`)}</div>
                              {pt.observation != null && <div className="check-point-obs">{String(pt.observation)}</div>}
                            </div>
                          </div>
                        )
                      })}
                    </div>
                    )})()}

                  {/* Signataires */}
                  {(() => {
                    const signataires = (data['signataires'] as Array<Record<string, unknown>> | undefined) || []
                    const signataire = data['signataire'] as string | undefined
                    if (signataires.length === 0 && !signataire) return null
                    return (
                      <div style={{ marginTop: 14, padding: '10px 12px', background: 'var(--ink-02)', borderRadius: 6 }}>
                        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
                          Signatures
                        </div>
                        {signataires.length > 0 ? signataires.map((sig, i) => (
                          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0' }}>
                            <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 16, height: 16, fontSize: 10 }}>
                              {sig.aSignature ? '✓' : '—'}
                            </span>
                            <span style={{ fontWeight: 600, fontSize: 12, color: 'var(--ink)' }}>{String(sig.nom || 'Inconnu')}</span>
                            {sig.date != null && <span style={{ fontSize: 11, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)' }}>{String(sig.date)}</span>}
                            {sig.aSignature === true && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Signe</span>}
                          </div>
                        )) : (
                          <div style={{ fontSize: 12, color: 'var(--ink-50)' }}>{signataire}</div>
                        )}
                      </div>
                    )
                  })()}

                  {/* Checklist pieces */}
                  {pieces.length > 0 && (
                    <div style={{ marginTop: 14 }}>
                      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
                        Pieces justificatives ({pieces.length})
                      </div>
                      {pieces.map((pc, i) => (
                        <div key={i} className="check-point">
                          <span className={`check-point-icon ${pc.estPresent ? 'pass' : pc.estPresent === false ? 'fail' : 'na'}`}>
                            {pc.estPresent ? '✓' : pc.estPresent === false ? '✗' : '?'}
                          </span>
                          <div className="check-point-body">
                            <div className="check-point-label">{String(pc.designation || `Piece ${i + 1}`)}</div>
                            {pc.observation != null && <div className="check-point-obs">{String(pc.observation)}</div>}
                          </div>
                          <span className="tag" style={{ fontSize: 9 }}>{pc.original ? 'Original' : 'Copie'}</span>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Line items */}
                  {lignes.length > 0 && (
                    <div style={{ marginTop: 14 }}>
                      <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
                        Lignes ({lignes.length})
                      </div>
                      <table className="data-table">
                        <thead>
                          <tr>
                            <th>Designation</th>
                            <th>Qte</th>
                            <th>PU HT</th>
                            <th>Total HT</th>
                          </tr>
                        </thead>
                        <tbody>
                          {lignes.map((ln, i) => (
                            <tr key={i}>
                              <td>{String(ln.designation || ln.codeArticle || '—')}</td>
                              <td className="cell-mono">{ln.quantite != null ? String(ln.quantite) : '—'}</td>
                              <td className="cell-mono">{ln.prixUnitaireHT != null ? String(ln.prixUnitaireHT) : '—'}</td>
                              <td className="cell-mono">{ln.montantTotalHT != null ? String(ln.montantTotalHT) : '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              )
            })()}
          </div>
        </>
      )}

      {/* Pre-validation: checklist from document + system rules */}
      {dossier.resultatsValidation.length === 0 && dossier.documents.length > 0 && (() => {
        const activeRules = getActiveRules(dossier.type as 'BC' | 'CONTRACTUEL')
        const systemRules = activeRules.filter(r => r.category === 'system')
        const defaultChecklist = activeRules.filter(r => r.category === 'checklist')

        // Use extracted checklist from uploaded autocontrole document if available
        const checklistData = dossier.checklistAutocontrole
        const extractedPoints = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []
        const extractedSignataires = (checklistData?.signataires as Array<Record<string, unknown>> | undefined) || []
        const hasExtracted = extractedPoints.length > 0
        const checklistPrestataire = (checklistData?.prestataire as string) || dossier.fournisseur || '\u2014'
        const checklistRef = (checklistData?.referenceFacture as string) || ''

        return (
        <>
        {/* Checklist autocontrole */}
        <div className="card">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <h2 style={{ marginBottom: 0 }}>
              <ShieldCheck size={14} /> Check-list d'autocontrole des dossiers de paiement
            </h2>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              {hasExtracted && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Extrait du document</span>}
              <span style={{ fontSize: 9, fontFamily: 'var(--font-mono)', color: 'var(--ink-30)' }}>CCF-EN-04-V02</span>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginBottom: 14, fontSize: 12 }}>
            <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Prestataire : </span>{checklistPrestataire}</div>
            <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Reference facture : </span>{checklistRef}</div>
            <div><span style={{ fontWeight: 700, fontSize: 10, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Dossier : </span>{dossier.reference}</div>
          </div>

          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 40 }}>#</th>
                <th>Liste des points de controle</th>
                <th style={{ width: 50 }}>OK *</th>
                <th style={{ width: 120 }}>Observation</th>
              </tr>
            </thead>
            <tbody>
              {hasExtracted ? (
                // Points from the actual uploaded autocontrole document
                extractedPoints.map((pt, i) => {
                  const isOk = pt.estValide === true
                  const isFail = pt.estValide === false
                  return (
                    <tr key={i}>
                      <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)' }}>
                        {pt.numero != null ? String(pt.numero) : i + 1}
                      </td>
                      <td>
                        <div style={{ fontSize: 12, lineHeight: 1.5 }}>
                          <strong>Point {pt.numero != null ? String(pt.numero) : i + 1} : </strong>
                          {String(pt.description || defaultChecklist[i]?.desc || '')}
                        </div>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        <span className={`check-point-icon ${isOk ? 'pass' : isFail ? 'fail' : 'na'}`}
                          style={{ width: 18, height: 18, fontSize: 10, display: 'inline-flex' }}>
                          {isOk ? '\u2713' : isFail ? '\u2717' : '\u2014'}
                        </span>
                      </td>
                      <td style={{ fontSize: 11, color: 'var(--ink-40)' }}>
                        {pt.observation != null && pt.observation !== '\\u2014' ? String(pt.observation).replace(/\\u2014/g, '—') : ''}
                      </td>
                    </tr>
                  )
                })
              ) : (
                // Default checklist from config (no document uploaded yet)
                defaultChecklist.map((rule, i) => (
                  <tr key={rule.code}>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)' }}>{i + 1}</td>
                    <td>
                      <div style={{ fontSize: 12, lineHeight: 1.5 }}>
                        <strong>Point {i + 1} : </strong>{rule.desc}
                      </div>
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <span className="check-point-icon na" style={{ width: 18, height: 18, fontSize: 10, display: 'inline-flex' }}>\u2014</span>
                    </td>
                    <td></td>
                  </tr>
                ))
              )}
            </tbody>
          </table>

          {/* Signataires from document */}
          {extractedSignataires.length > 0 && (
            <div style={{ marginTop: 12, padding: '10px 12px', background: 'var(--ink-02)', borderRadius: 6 }}>
              <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 6, fontFamily: 'var(--font-mono)' }}>Signataires</div>
              {extractedSignataires.map((sig, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '3px 0', fontSize: 12 }}>
                  <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 14, height: 14, fontSize: 9 }}>
                    {sig.aSignature ? '\u2713' : '\u2014'}
                  </span>
                  <span style={{ fontWeight: 600 }}>{String(sig.nom || '')}</span>
                  {sig.date != null && <span style={{ fontSize: 10, color: 'var(--ink-30)', fontFamily: 'var(--font-mono)' }}>{String(sig.date)}</span>}
                </div>
              ))}
            </div>
          )}

          <div style={{ fontSize: 10, color: 'var(--ink-30)', marginTop: 8, fontStyle: 'italic' }}>
            *Les controles doivent etre obligatoirement exhaustifs
          </div>
        </div>

        {/* System rules — compact */}
        <div className="card">
          <h2><ShieldCheck size={14} /> Verifications automatiques ({systemRules.length})</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '2px 12px' }}>
            {systemRules.map(rule => (
              <div key={rule.code} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '3px 0', fontSize: 11 }}>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', width: 28, flexShrink: 0 }}>{rule.code}</span>
                <span style={{ color: 'var(--ink-50)' }}>{rule.label}</span>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 12 }}>
            <button className="btn btn-primary" onClick={handleValidate} disabled={validating}>
              {validating ? <><Loader2 size={14} className="spin" /> Verification...</> : <><ShieldCheck size={14} /> Lancer la verification</>}
            </button>
          </div>
        </div>
        </>
        )})()}

      {/* Validation results */}
      {dossier.resultatsValidation.length > 0 && (
        <ValidationPanel dossier={dossier} onValidate={handleValidate} validating={validating} />
      )}

      {/* Audit log */}
      {audit.length > 0 && (
        <div className="card">
          <h2><Clock size={14} /> Historique</h2>
          {audit.map((a, i) => (
            <div key={i} className="audit-row">
              <div>
                <span style={{ fontWeight: 700, color: 'var(--slate-700)' }}>{a.action}</span>
                {a.detail && <span style={{ color: 'var(--slate-500)', marginLeft: 8 }}>{a.detail}</span>}
              </div>
              <span style={{ color: 'var(--slate-400)', fontSize: 12, whiteSpace: 'nowrap' }}>
                {new Date(a.dateAction).toLocaleString('fr-FR')}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
