import { useEffect, useState, useRef, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getDossier, uploadDocuments, validateDossier, changeStatut, reprocessDocument, changeDocumentType, deleteDocument, getAuditLog, getDocumentFileUrl, updateDossier } from '../api/dossierApi'
import type { DossierDetail as DossierDetailType } from '../api/dossierTypes'
import { STATUT_CONFIG, TYPE_DOCUMENT_LABELS, CHECK_ICONS } from '../api/dossierTypes'
import type { DocumentInfo, TypeDocument, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
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
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [rejectModal, setRejectModal] = useState(false)
  const [motifRejet, setMotifRejet] = useState('')
  const [editing, setEditing] = useState(false)
  const [editFields, setEditFields] = useState({ fournisseur: '', description: '', montantTtc: '', montantHt: '', montantTva: '', montantNetAPayer: '' })
  const [saving, setSaving] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [showCompare, setShowCompare] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const load = useCallback(() => {
    if (!id) return
    setError('')
    getDossier(id).then(d => {
      setDossier(d)
      const processing = d.documents.some(doc => doc.statutExtraction === 'EN_COURS' || doc.statutExtraction === 'EN_ATTENTE')
      if (processing) {
        setTimeout(() => load(), 3000)
      }
    }).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
  }, [id])

  useEffect(() => {
    const ctrl = new AbortController()
    if (id) getDossier(id, ctrl.signal).then(setDossier).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    if (id) getAuditLog(id).then(setAudit).catch(() => {})
    return () => ctrl.abort()
  }, [id])

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
      if (id) getAuditLog(id).then(setAudit).catch(() => {})
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
      if (id) getAuditLog(id).then(setAudit).catch(() => {})
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
      if (id) getAuditLog(id).then(setAudit).catch(() => {})
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
      if (id) getAuditLog(id).then(setAudit).catch(() => {})
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
      if (id) getAuditLog(id).then(setAudit).catch(() => {})
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }

  const copyRef = () => {
    if (!dossier) return
    navigator.clipboard.writeText(dossier.reference)
    toast('info', `Reference ${dossier.reference} copiee`)
  }

  if (error && !dossier) return <div className="alert alert-error"><AlertTriangle size={18} /> {error}</div>
  if (!dossier) return <div className="loading">Chargement...</div>

  const cfg = STATUT_CONFIG[dossier.statut]
  const fmt = (n: number | null | undefined) => n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014'
  const nbConformes = dossier.resultatsValidation.filter(r => r.statut === 'CONFORME').length
  const nbNonConformes = dossier.resultatsValidation.filter(r => r.statut === 'NON_CONFORME').length
  const nbWarn = dossier.resultatsValidation.filter(r => r.statut === 'AVERTISSEMENT').length
  const hasProcessing = dossier.documents.some(d => d.statutExtraction === 'EN_COURS')

  const getDataForType = (type: TypeDocument): Record<string, unknown> | null => {
    const map: Record<string, Record<string, unknown> | null> = {
      FACTURE: dossier.facture, BON_COMMANDE: dossier.bonCommande,
      CONTRAT_AVENANT: dossier.contratAvenant, ORDRE_PAIEMENT: dossier.ordrePaiement,
      CHECKLIST_AUTOCONTROLE: dossier.checklistAutocontrole, TABLEAU_CONTROLE: dossier.tableauControle,
      PV_RECEPTION: dossier.pvReception, ATTESTATION_FISCALE: dossier.attestationFiscale,
    }
    return map[type] || null
  }

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

  const factureData = dossier.facture
  const bcData = dossier.type === 'BC' ? dossier.bonCommande : dossier.contratAvenant
  const bcLabel = dossier.type === 'BC' ? 'Bon de commande' : 'Contrat / Avenant'

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
          {factureData && bcData && (
            <button className="btn btn-secondary" onClick={() => setShowCompare(!showCompare)}>
              <Columns2 size={15} /> {showCompare ? 'Masquer' : 'Comparer'}
            </button>
          )}
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
          {(dossier.statut === 'VALIDE' || dossier.statut === 'REJETE') && (
            <button className="btn btn-secondary" onClick={() => handleStatut('BROUILLON')}>
              Reouvrir
            </button>
          )}
        </div>
      </div>

      {error && <div className="alert alert-error mb-3"><XCircle size={16} /> {error}</div>}

      <Modal open={rejectModal} title="Rejeter le dossier" message="Etes-vous sur de vouloir rejeter ce dossier ? Cette action sera enregistree dans l'historique."
        confirmLabel="Rejeter" confirmColor="var(--red-600)" onConfirm={() => handleStatut('REJETE')} onCancel={() => { setRejectModal(false); setMotifRejet('') }}>
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

      {/* Comparison view */}
      {showCompare && factureData && bcData && (
        <div className="card">
          <h2><Columns2 size={14} /> Comparaison Facture / {bcLabel}</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <div>
              <div className="stat-label" style={{ color: 'var(--teal-700)', marginBottom: 10 }}>Facture</div>
              <table className="kv-table"><tbody>
                {Object.entries(factureData).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object').map(([k, v]) => (
                  <tr key={k}><td>{k}</td><td>{String(v)}</td></tr>
                ))}
              </tbody></table>
            </div>
            <div>
              <div className="stat-label" style={{ color: 'var(--blue-600)', marginBottom: 10 }}>{bcLabel}</div>
              <table className="kv-table"><tbody>
                {Object.entries(bcData).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object').map(([k, v]) => (
                  <tr key={k}><td>{k}</td><td>{String(v)}</td></tr>
                ))}
              </tbody></table>
            </div>
          </div>
        </div>
      )}

      {/* Documents with drag & drop */}
      <div className="card"
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        style={dragging ? { borderColor: 'var(--teal-600)', background: 'var(--teal-50)' } : undefined}
      >
        <h2><FileText size={14} /> Documents du dossier</h2>
        {dragging && (
          <div style={{ padding: 32, textAlign: 'center', border: '2px dashed var(--teal-600)', borderRadius: 8, marginBottom: 16, color: 'var(--teal-700)', fontWeight: 700 }}>
            <Upload size={32} /><div style={{ marginTop: 8 }}>Deposez vos PDFs ici</div>
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
                <button className="btn btn-secondary btn-sm" onClick={() => setShowPdf(!showPdf)}>
                  {showPdf ? <><XCircle size={14} /> Masquer PDF</> : <><FileText size={14} /> Voir PDF</>}
                </button>
                {id && <a href={getDocumentFileUrl(id, selectedDoc.id)} target="_blank" rel="noopener noreferrer"
                  className="btn btn-secondary btn-sm" style={{ textDecoration: 'none' }}>
                  <Download size={14} /> Telecharger
                </a>}
              </div>
            </div>
            {showPdf && id && <div className="pdf-viewer"><iframe src={getDocumentFileUrl(id, selectedDoc.id)} title={selectedDoc.nomFichier} /></div>}
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

      {/* Pre-validation: rules that will run */}
      {dossier.resultatsValidation.length === 0 && dossier.documents.length > 0 && (() => {
        const COMMON_RULES = [
          { code: 'R04/R05', label: 'Montant OP = TTC (- retenues)', desc: 'Avec ou sans retenues a la source' },
          { code: 'R07-R08', label: 'References croisees', desc: 'N° facture et BC/contrat cites dans l\'OP' },
          { code: 'R09-R10', label: 'Coherence identifiants fiscaux', desc: 'ICE et IF entre facture et ARF' },
          { code: 'R11', label: 'Coherence RIB', desc: 'RIB facture = RIB ordre de paiement' },
          { code: 'R12-R13', label: 'Checklist et Tableau de controle', desc: 'Tous les points valides' },
          { code: 'R17', label: 'Chronologie des dates', desc: 'BC/Contrat <= Facture <= OP' },
          { code: 'R18', label: 'Validite attestation fiscale', desc: '6 mois de validite' },
        ]
        const rules = [
          { code: 'R20', label: 'Completude du dossier', desc: `Pieces obligatoires (${dossier.type === 'BC' ? 'BC, Facture, Checklist, TC, OP' : 'Contrat, Facture, PV, Checklist, OP'})` },
          ...(dossier.type === 'BC'
            ? [{ code: 'R01-R03', label: 'Concordance montants BC / Facture', desc: 'HT, TVA, TTC' }]
            : [{ code: 'R15', label: 'Grille tarifaire x duree = HT', desc: 'Prix mensuel avenant x nombre de mois' }]),
          ...COMMON_RULES,
        ]
        return (
        <div className="card">
          <h2><ShieldCheck size={14} /> Controles a effectuer</h2>
          <div style={{ fontSize: 12, color: 'var(--ink-40)', marginBottom: 10 }}>
            Ces regles seront verifiees lors de la validation croisee :
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {rules.map(rule => (
              <div key={rule.code} className="check-point" style={{ padding: '6px 10px' }}>
                <span className="check-point-icon na" style={{ width: 18, height: 18, fontSize: 10 }}>—</span>
                <div className="check-point-body">
                  <div style={{ fontWeight: 600, fontSize: 12 }}>
                    <span style={{ color: 'var(--ink-30)', marginRight: 6, fontSize: 10, fontFamily: 'var(--font-mono)' }}>[{rule.code}]</span>
                    {rule.label}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--ink-30)' }}>{rule.desc}</div>
                </div>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 12 }}>
            <button className="btn btn-primary" onClick={handleValidate} disabled={validating}>
              {validating ? <><Loader2 size={14} className="spin" /> Verification...</> : <><ShieldCheck size={14} /> Lancer la verification</>}
            </button>
          </div>
        </div>
        )})()}

      {/* Validation results with provenance popover */}
      {dossier.resultatsValidation.length > 0 && (() => {
        const RULE_PROVENANCE: Record<string, { docs: string[]; fields: string[]; desc: string }> = {
          R01: { docs: ['Facture', 'Bon de commande'], fields: ['montantTtc'], desc: 'Compare le TTC de la facture avec le BC' },
          R02: { docs: ['Facture', 'Bon de commande'], fields: ['montantHt'], desc: 'Compare le HT de la facture avec le BC' },
          R03: { docs: ['Facture', 'Bon de commande'], fields: ['montantTva'], desc: 'Compare la TVA de la facture avec le BC' },
          R03b: { docs: ['Facture', 'Bon de commande'], fields: ['tauxTva'], desc: 'Verifie si les taux TVA correspondent' },
          R04: { docs: ['Facture', 'Ordre de paiement'], fields: ['montantTtc', 'montantOperation'], desc: 'OP = TTC facture (sans retenues)' },
          R05: { docs: ['Facture', 'Ordre de paiement'], fields: ['montantTtc', 'retenues'], desc: 'OP = TTC - retenues a la source' },
          R06: { docs: ['Ordre de paiement'], fields: ['retenues.base', 'retenues.taux'], desc: 'Calcul arithmetique des retenues' },
          R07: { docs: ['Facture', 'Ordre de paiement'], fields: ['numeroFacture', 'referenceFacture'], desc: 'N° facture cite dans l\'OP' },
          R08: { docs: ['BC / Contrat', 'Ordre de paiement'], fields: ['reference', 'referenceBcOuContrat'], desc: 'N° BC/contrat cite dans l\'OP' },
          R09: { docs: ['Facture', 'Attestation fiscale'], fields: ['ice'], desc: 'ICE identique entre documents' },
          R10: { docs: ['Facture', 'Attestation fiscale'], fields: ['identifiantFiscal'], desc: 'IF identique entre documents' },
          R11: { docs: ['Facture', 'Ordre de paiement'], fields: ['rib'], desc: 'RIB identique (espaces ignores)' },
          R12: { docs: ['Checklist autocontrole'], fields: ['points[].estValide'], desc: 'Tous les points de la checklist valides' },
          R13: { docs: ['Tableau de controle'], fields: ['points[].observation'], desc: 'Tous les points Conforme ou NA' },
          R14: { docs: ['Facture', 'BC', 'OP', 'Checklist'], fields: ['fournisseur'], desc: 'Nom fournisseur coherent partout' },
          R15: { docs: ['Contrat/Avenant', 'Facture', 'PV reception'], fields: ['grillesTarifaires', 'montantHt', 'periode'], desc: 'Grille tarifaire x duree = HT facture' },
          R17a: { docs: ['BC / Contrat', 'Facture'], fields: ['date'], desc: 'Date BC/contrat <= date facture' },
          R17b: { docs: ['Facture', 'Ordre de paiement'], fields: ['date'], desc: 'Date facture <= date OP' },
          R18: { docs: ['Attestation fiscale'], fields: ['dateEdition'], desc: 'Attestation valide 6 mois' },
          R20: { docs: ['Tous les documents'], fields: ['typeDocument'], desc: 'Pieces obligatoires presentes' },
        }

        // Sort by rule code, deduplicate
        const seen = new Set<string>()
        const sorted = [...dossier.resultatsValidation]
          .sort((a, b) => {
            const na = parseInt(a.regle.replace(/\D/g, '')) || 0
            const nb = parseInt(b.regle.replace(/\D/g, '')) || 0
            return na !== nb ? na - nb : a.regle.localeCompare(b.regle)
          })
          .filter(r => { const k = `${r.regle}:${r.libelle}`; if (seen.has(k)) return false; seen.add(k); return true })

        return (
        <div className="card">
          <h2>
            <ShieldCheck size={14} /> Verification croisee
            <span style={{ fontWeight: 500, fontSize: 11, color: 'var(--ink-40)', marginLeft: 8, textTransform: 'none', letterSpacing: 0 }}>
              {nbConformes} conformes, {nbNonConformes} non conformes, {nbWarn} avertissements
            </span>
          </h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {sorted.map((r, i) => {
              const chk = CHECK_ICONS[r.statut]
              const cls = r.statut === 'NON_CONFORME' ? 'fail' : r.statut === 'AVERTISSEMENT' ? 'warn' : ''
              const prov = RULE_PROVENANCE[r.regle]
              return (
                <div key={i} className={`validation-item ${cls} provenance-trigger`} tabIndex={0}>
                  <span style={{ color: chk.color, fontWeight: 800, fontSize: 15, width: 22, flexShrink: 0, textAlign: 'center' }}>{chk.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>
                      <span style={{ color: 'var(--ink-40)', marginRight: 6, fontSize: 11, fontFamily: 'var(--font-mono)' }}>[{r.regle}]</span>
                      {r.libelle}
                    </div>
                    {r.detail && <div style={{ fontSize: 12, color: 'var(--ink-50)', marginTop: 2 }}>{r.detail}</div>}
                    {r.valeurAttendue != null && r.valeurTrouvee != null && r.statut === 'NON_CONFORME' && (
                      <div style={{ fontSize: 11, marginTop: 4, color: 'var(--danger)', fontFamily: 'var(--font-mono)' }}>
                        Attendu: {r.valeurAttendue} | Trouve: {r.valeurTrouvee}
                      </div>
                    )}
                  </div>
                  <span className={`prov-badge ${r.source.toLowerCase()}`}>{r.source}</span>

                  {/* Provenance popover */}
                  {prov && (
                    <div className="provenance-pop">
                      <div className="provenance-pop-title">Provenance — {r.regle}</div>
                      <div className="provenance-row">
                        <span className="provenance-row-label">Regle</span>
                        <span className="provenance-row-value">{prov.desc}</span>
                      </div>
                      <div className="provenance-row">
                        <span className="provenance-row-label">Source</span>
                        <span className="provenance-row-value">{prov.docs.join(' ↔ ')}</span>
                      </div>
                      <div className="provenance-row">
                        <span className="provenance-row-label">Champs</span>
                        <span className="provenance-row-value">{prov.fields.map(f => <code key={f} style={{ background: 'rgba(255,255,255,0.06)', padding: '1px 4px', borderRadius: 3, marginRight: 4, fontSize: 10 }}>{f}</code>)}</span>
                      </div>
                      <div className="provenance-row">
                        <span className="provenance-row-label">Moteur</span>
                        <span className="provenance-row-value"><span className={`prov-badge ${r.source.toLowerCase()}`}>{r.source}</span></span>
                      </div>
                      <div className="provenance-row">
                        <span className="provenance-row-label">Resultat</span>
                        <span className="provenance-row-value"><span className={`prov-badge ${r.statut === 'CONFORME' ? 'conforme' : r.statut === 'NON_CONFORME' ? 'non-conforme' : 'avertissement'}`}>{r.statut}</span></span>
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
        )
      })()}

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
