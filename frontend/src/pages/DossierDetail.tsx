import { useEffect, useState, useRef } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getDossier, uploadDocuments, validateDossier, changeStatut, reprocessDocument, getAuditLog, getDocumentFileUrl } from '../api/dossierApi'
import type { DossierDetail as DossierDetailType } from '../api/dossierTypes'
import { STATUT_CONFIG, TYPE_DOCUMENT_LABELS, CHECK_ICONS } from '../api/dossierTypes'
import type { DocumentInfo, TypeDocument, AuditEntry } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import {
  ArrowLeft, RefreshCw, Upload, FileText, CheckCircle, XCircle, AlertTriangle,
  Loader2, ShieldCheck, Banknote, FileCheck, Ban, FolderOpen, Eye, Clock,
  ExternalLink, Download,
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
  const inputRef = useRef<HTMLInputElement>(null)

  const load = () => {
    if (!id) return
    setError('')
    getDossier(id).then(setDossier).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
  }
  useEffect(() => {
    const ctrl = new AbortController()
    if (id) getDossier(id, ctrl.signal).then(setDossier).catch(e => { if (e.name !== 'AbortError') setError(e.message) })
    if (id) getAuditLog(id).then(setAudit).catch(() => {})
    return () => ctrl.abort()
  }, [id])

  const handleUpload = async (files: FileList | null) => {
    if (!files || !id) return
    setUploading(true)
    try {
      await uploadDocuments(id, Array.from(files))
      toast('success', `${files.length} document(s) uploade(s)`)
      load()
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Upload failed'
      setError(msg)
      toast('error', msg)
    }
    finally { setUploading(false) }
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
      const msg = e instanceof Error ? e.message : 'Validation failed'
      setError(msg)
      toast('error', msg)
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
      const msg = e instanceof Error ? e.message : 'Erreur'
      setError(msg)
      toast('error', msg)
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

  if (error && !dossier) return <div className="card error-card"><AlertTriangle size={20} /> {error}</div>
  if (!dossier) return <div className="loading">Chargement...</div>

  const cfg = STATUT_CONFIG[dossier.statut]
  const fmt = (n: number | null | undefined) => n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '—'
  const nbConformes = dossier.resultatsValidation.filter(r => r.statut === 'CONFORME').length
  const nbNonConformes = dossier.resultatsValidation.filter(r => r.statut === 'NON_CONFORME').length
  const nbWarn = dossier.resultatsValidation.filter(r => r.statut === 'AVERTISSEMENT').length

  const getDataForType = (type: TypeDocument): Record<string, unknown> | null => {
    const map: Record<string, Record<string, unknown> | null> = {
      FACTURE: dossier.facture,
      BON_COMMANDE: dossier.bonCommande,
      CONTRAT_AVENANT: dossier.contratAvenant,
      ORDRE_PAIEMENT: dossier.ordrePaiement,
      CHECKLIST_AUTOCONTROLE: dossier.checklistAutocontrole,
      TABLEAU_CONTROLE: dossier.tableauControle,
      PV_RECEPTION: dossier.pvReception,
      ATTESTATION_FISCALE: dossier.attestationFiscale,
    }
    return map[type] || null
  }

  return (
    <div>
      <div className="page-header">
        <h1>
          <Link to="/dossiers" className="back-link"><ArrowLeft size={20} /></Link>
          {dossier.reference}
        </h1>
        <div className="header-actions">
          <button className="btn btn-secondary" onClick={load}><RefreshCw size={16} /></button>
          <button className="btn btn-primary" onClick={handleValidate} disabled={validating}>
            {validating ? <><Loader2 size={16} className="spin" /> Verification...</> : <><ShieldCheck size={16} /> Verifier</>}
          </button>
          {dossier.statut === 'EN_VERIFICATION' && nbNonConformes === 0 && (
            <button className="btn btn-primary" onClick={() => handleStatut('VALIDE')} style={{ background: '#10b981' }}>
              <CheckCircle size={16} /> Valider
            </button>
          )}
          {dossier.statut !== 'REJETE' && dossier.statut !== 'VALIDE' && (
            <button className="btn btn-secondary" onClick={() => setRejectModal(true)} style={{ color: '#ef4444' }}>
              <Ban size={16} /> Rejeter
            </button>
          )}
        </div>
      </div>

      {error && <div className="result-banner error mb-3"><XCircle size={18} /> {error}</div>}

      {/* Reject confirmation modal */}
      <Modal
        open={rejectModal}
        title="Rejeter le dossier"
        message="Etes-vous sur de vouloir rejeter ce dossier ? Cette action sera enregistree dans l'historique."
        confirmLabel="Rejeter"
        confirmColor="#ef4444"
        onConfirm={() => handleStatut('REJETE')}
        onCancel={() => { setRejectModal(false); setMotifRejet('') }}
      >
        <div style={{ marginBottom: 16 }}>
          <label className="form-label">Motif de rejet (optionnel)</label>
          <input className="form-input" value={motifRejet} onChange={e => setMotifRejet(e.target.value)} placeholder="Ex: Documents manquants, montants incoherents..." />
        </div>
      </Modal>

      {/* Header info */}
      <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <span className="status-badge" style={{ backgroundColor: cfg.color + '20', color: cfg.color, borderColor: cfg.color, fontSize: 13 }}>{cfg.label}</span>
          <span className="preprocess-tag" style={{ marginLeft: 8 }}>{dossier.type === 'BC' ? 'Bon de commande' : 'Contractuel'}</span>
          {dossier.fournisseur && <strong style={{ marginLeft: 12, fontSize: 16 }}>{dossier.fournisseur}</strong>}
        </div>
        <div style={{ textAlign: 'right', fontSize: 13, color: '#64748b' }}>
          {dossier.description}
        </div>
      </div>

      {/* Metrics */}
      <div className="stats-grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)' }}>
        <div className="stat-card">
          <div className="stat-icon purple"><Banknote size={20} /></div>
          <div className="stat-value">{fmt(dossier.montantTtc)}</div>
          <div className="stat-label">Montant TTC</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Banknote size={20} /></div>
          <div className="stat-value">{fmt(dossier.montantNetAPayer ?? dossier.montantHt)}</div>
          <div className="stat-label">{dossier.montantNetAPayer ? 'Net a payer' : 'Montant HT'}</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><FolderOpen size={20} /></div>
          <div className="stat-value">{dossier.documents.length}</div>
          <div className="stat-label">Documents</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><FileCheck size={20} /></div>
          <div className="stat-value">{nbConformes}/{dossier.resultatsValidation.length}</div>
          <div className="stat-label">Checks conformes</div>
        </div>
      </div>

      {/* Documents */}
      <div className="card">
        <h2><FileText size={16} /> Documents du dossier</h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12, marginBottom: 16 }}>
          {dossier.documents.map(doc => (
            <div
              key={doc.id}
              className={`radio-card ${selectedDoc?.id === doc.id ? 'selected' : ''}`}
              style={{ cursor: 'pointer', padding: 14, flexDirection: 'column', alignItems: 'stretch' }}
              onClick={() => { setSelectedDoc(selectedDoc?.id === doc.id ? null : doc); setShowPdf(false) }}
            >
              <div>
                <div className="fw-700" style={{ fontSize: 13 }}>
                  {TYPE_DOCUMENT_LABELS[doc.typeDocument] || doc.typeDocument}
                </div>
                <div className="text-sm text-muted" style={{ marginTop: 4 }}>{doc.nomFichier}</div>
                <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
                  <span className="status-badge" style={{
                    backgroundColor: doc.statutExtraction === 'EXTRAIT' ? '#ecfdf5' : doc.statutExtraction === 'ERREUR' ? '#fef2f2' : '#fffbeb',
                    color: doc.statutExtraction === 'EXTRAIT' ? '#065f46' : doc.statutExtraction === 'ERREUR' ? '#991b1b' : '#92400e',
                    borderColor: doc.statutExtraction === 'EXTRAIT' ? '#a7f3d0' : doc.statutExtraction === 'ERREUR' ? '#fecaca' : '#fde68a',
                  }}>
                    {doc.statutExtraction === 'EXTRAIT' ? 'Extrait' : doc.statutExtraction === 'ERREUR' ? 'Erreur' : doc.statutExtraction === 'EN_COURS' ? 'En cours...' : 'En attente'}
                  </span>
                </div>
                <div style={{ marginTop: 8, display: 'flex', gap: 4 }}>
                  {doc.statutExtraction === 'ERREUR' && (
                    <button
                      className="btn btn-secondary"
                      style={{ fontSize: 11, padding: '2px 8px' }}
                      onClick={(e) => { e.stopPropagation(); handleReprocess(doc.id) }}
                    >
                      <RefreshCw size={12} /> Relancer
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}

          {/* Upload zone */}
          <div
            className="drop-zone"
            style={{ padding: 24, minHeight: 100, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}
            onClick={() => inputRef.current?.click()}
          >
            <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => handleUpload(e.target.files)} />
            {uploading ? <Loader2 size={24} className="spin" /> : <Upload size={24} className="drop-icon" />}
            <p className="text-sm text-muted" style={{ marginTop: 8 }}>{uploading ? 'Upload en cours...' : 'Ajouter des PDFs'}</p>
          </div>
        </div>
      </div>

      {/* Selected document: PDF viewer + extracted data */}
      {selectedDoc && (
        <>
          {/* PDF Viewer */}
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ marginBottom: 0 }}><Eye size={16} /> {TYPE_DOCUMENT_LABELS[selectedDoc.typeDocument]} — {selectedDoc.nomFichier}</h2>
              <div style={{ display: 'flex', gap: 6 }}>
                <button className="btn btn-secondary" style={{ fontSize: 11, padding: '4px 10px' }} onClick={() => setShowPdf(!showPdf)}>
                  {showPdf ? <><XCircle size={14} /> Masquer PDF</> : <><FileText size={14} /> Voir PDF</>}
                </button>
                {id && (
                  <a
                    href={getDocumentFileUrl(id, selectedDoc.id)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="btn btn-secondary"
                    style={{ fontSize: 11, padding: '4px 10px', textDecoration: 'none' }}
                  >
                    <Download size={14} /> Telecharger
                  </a>
                )}
              </div>
            </div>
            {showPdf && id && (
              <div className="pdf-viewer">
                <iframe src={getDocumentFileUrl(id, selectedDoc.id)} title={selectedDoc.nomFichier} />
              </div>
            )}
          </div>

          {/* Extracted data */}
          <div className="card">
            <h2><ExternalLink size={16} /> Donnees extraites</h2>
            {selectedDoc.statutExtraction === 'ERREUR' && (
              <div className="result-banner error"><XCircle size={16} /> {selectedDoc.erreurExtraction}</div>
            )}
            {(() => {
              const data = getDataForType(selectedDoc.typeDocument) || selectedDoc.donneesExtraites
              if (!data) return <p className="text-muted">Aucune donnee extraite</p>
              return (
                <table className="invoice-table">
                  <thead><tr><th>Champ</th><th>Valeur</th></tr></thead>
                  <tbody>
                    {Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object').map(([k, v]) => (
                      <tr key={k}>
                        <td style={{ fontWeight: 600, color: '#475569' }}>{k}</td>
                        <td>{String(v)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )
            })()}
          </div>
        </>
      )}

      {/* Validation results */}
      {dossier.resultatsValidation.length > 0 && (
        <div className="card">
          <h2><ShieldCheck size={16} /> Verification croisee ({nbConformes} conformes, {nbNonConformes} non conformes, {nbWarn} avertissements)</h2>
          <div className="status-list">
            {dossier.resultatsValidation.map((r, i) => {
              const chk = CHECK_ICONS[r.statut]
              return (
                <div key={i} className="status-item" style={{ padding: '10px 12px', borderRadius: 8, background: r.statut === 'NON_CONFORME' ? '#fef2f2' : r.statut === 'AVERTISSEMENT' ? '#fffbeb' : 'transparent' }}>
                  <span style={{ color: chk.color, fontWeight: 800, fontSize: 16, width: 24 }}>{chk.icon}</span>
                  <div style={{ flex: 1 }}>
                    <div style={{ fontWeight: 600, fontSize: 13 }}>
                      <span style={{ color: '#94a3b8', marginRight: 6 }}>[{r.regle}]</span>
                      {r.libelle}
                    </div>
                    {r.detail && <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>{r.detail}</div>}
                    {r.valeurAttendue && r.valeurTrouvee && r.statut === 'NON_CONFORME' && (
                      <div style={{ fontSize: 11, marginTop: 4, color: '#991b1b' }}>
                        Attendu: <code>{r.valeurAttendue}</code> | Trouve: <code>{r.valeurTrouvee}</code>
                      </div>
                    )}
                  </div>
                  <span className="preprocess-tag" style={{ fontSize: 9 }}>{r.source}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {/* Audit log */}
      {audit.length > 0 && (
        <div className="card">
          <h2><Clock size={16} /> Historique</h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {audit.map((a, i) => (
              <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 12px', borderRadius: 6, background: i % 2 === 0 ? '#f8fafc' : 'transparent', fontSize: 13 }}>
                <div>
                  <span style={{ fontWeight: 700, color: '#334155' }}>{a.action}</span>
                  {a.detail && <span style={{ color: '#64748b', marginLeft: 8 }}>{a.detail}</span>}
                </div>
                <span style={{ color: '#94a3b8', fontSize: 12 }}>{new Date(a.dateAction).toLocaleString('fr-FR')}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
