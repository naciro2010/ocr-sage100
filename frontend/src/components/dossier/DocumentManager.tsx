import { memo, useRef, useState, useCallback } from 'react'
import type { DossierDetail, DocumentInfo, TypeDocument } from '../../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import { uploadDocuments, reprocessDocument, changeDocumentType, deleteDocument, getDocumentFileUrl, openWithAuth } from '../../api/dossierApi'
import { useToast } from '../Toast'
import DocumentPipeline from '../DocumentPipeline'
import type { DocProgress } from '../../hooks/useDocumentEvents'
import {
  FileText, Upload, RefreshCw, Loader2, Trash2, Eye, XCircle, Download, ExternalLink
} from 'lucide-react'

interface Props {
  dossier: DossierDetail
  id: string
  liveProgress: Record<string, DocProgress>
  onReload: () => void
  onReloadAudit: () => void
}

export default memo(function DocumentManager({ dossier, id, liveProgress, onReload, onReloadAudit }: Props) {
  const { toast } = useToast()
  const inputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [selectedDoc, setSelectedDoc] = useState<DocumentInfo | null>(null)
  const [showPdf, setShowPdf] = useState(false)
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null)
  const [loadingPdf, setLoadingPdf] = useState(false)

  const handleUpload = useCallback(async (files: FileList | File[] | null) => {
    if (!files) return
    const fileArr = Array.from(files)
    setUploading(true)
    setDragging(false)
    try {
      await uploadDocuments(id, fileArr)
      toast('success', `${fileArr.length} document(s) uploade(s)`)
      onReload()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Upload failed')
    } finally { setUploading(false) }
  }, [id, onReload, toast])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    setDragging(false)
    const files = Array.from(e.dataTransfer.files).filter(f => f.name.toLowerCase().endsWith('.pdf'))
    if (files.length > 0) handleUpload(files)
    else toast('warning', 'Seuls les fichiers PDF sont acceptes')
  }, [handleUpload, toast])

  const handleReprocess = useCallback(async (docId: string) => {
    try {
      await reprocessDocument(id, docId)
      toast('info', 'Retraitement lance')
      onReload()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Reprocess failed')
    }
  }, [id, onReload, toast])

  const handleChangeType = useCallback(async (docId: string, newType: string) => {
    try {
      await changeDocumentType(id, docId, newType)
      toast('success', `Type modifie en ${TYPE_DOCUMENT_LABELS[newType as keyof typeof TYPE_DOCUMENT_LABELS] || newType}`)
      onReload()
      onReloadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }, [id, onReload, onReloadAudit, toast])

  const handleDeleteDoc = useCallback(async (docId: string, docName: string) => {
    if (!confirm(`Supprimer ${docName} ?`)) return
    try {
      await deleteDocument(id, docId)
      toast('success', `${docName} supprime`)
      if (selectedDoc?.id === docId) setSelectedDoc(null)
      onReload()
      onReloadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    }
  }, [id, selectedDoc, onReload, onReloadAudit, toast])

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

  const getDataForType = (type: TypeDocument): Record<string, unknown> | null => {
    const map: Record<string, Record<string, unknown> | null> = {
      FACTURE: dossier.facture, BON_COMMANDE: dossier.bonCommande,
      CONTRAT_AVENANT: dossier.contratAvenant, ORDRE_PAIEMENT: dossier.ordrePaiement,
      CHECKLIST_AUTOCONTROLE: dossier.checklistAutocontrole, TABLEAU_CONTROLE: dossier.tableauControle,
      PV_RECEPTION: dossier.pvReception, ATTESTATION_FISCALE: dossier.attestationFiscale,
    }
    return map[type] || null
  }

  return (
    <>
      {/* Document grid with drag & drop */}
      <div className="card"
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        style={dragging ? { borderColor: 'var(--teal-600)', background: 'var(--teal-50)' } : undefined}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <h2 style={{ marginBottom: 0 }}><FileText size={14} /> Documents du dossier</h2>
          {dossier.documents.length > 0 && (
            <button className="btn btn-secondary btn-sm" onClick={() => dossier.documents.forEach(doc => handleReprocess(doc.id))}>
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
                <select className="form-select" value={doc.typeDocument} title="Cliquez pour corriger le type"
                  style={{ fontSize: 11, fontWeight: 700, padding: '0 2px', border: 'none', background: 'transparent', color: 'var(--slate-800)', cursor: 'pointer', width: '100%' }}
                  onClick={e => e.stopPropagation()} onChange={e => { e.stopPropagation(); handleChangeType(doc.id, e.target.value) }}>
                  {Object.entries(TYPE_DOCUMENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </div>
              <div style={{ fontSize: 11, color: 'var(--slate-500)', marginBottom: 6 }}>{doc.nomFichier}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {extractionBadge(doc)}
                {doc.statutExtraction === 'EN_COURS' && <Loader2 size={12} className="spin" style={{ color: 'var(--blue-600)' }} />}
              </div>
              {doc.erreurExtraction && <div style={{ fontSize: 10, color: 'var(--danger)', marginTop: 4, lineHeight: 1.3 }}>{doc.erreurExtraction}</div>}
              <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
                {doc.statutExtraction === 'ERREUR' && (
                  <button className="btn btn-secondary btn-sm" onClick={e => { e.stopPropagation(); handleReprocess(doc.id) }}>
                    <RefreshCw size={11} /> Relancer
                  </button>
                )}
                <button className="btn btn-danger btn-sm" onClick={e => { e.stopPropagation(); handleDeleteDoc(doc.id, doc.nomFichier) }}>
                  <Trash2 size={11} />
                </button>
              </div>
            </div>
          ))}

          <div className="drop-zone" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: 90, padding: 20 }}
            onClick={() => inputRef.current?.click()}>
            <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => handleUpload(e.target.files)} />
            {uploading ? <Loader2 size={22} className="spin" style={{ color: 'var(--teal-600)' }} /> : <Upload size={22} style={{ color: 'var(--slate-400)' }} />}
            <span style={{ fontSize: 12, color: 'var(--slate-500)', marginTop: 6 }}>{uploading ? 'Upload...' : 'Ajouter des PDFs'}</span>
          </div>
        </div>
      </div>

      {/* Selected doc viewer */}
      {selectedDoc && (
        <>
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <h2 style={{ marginBottom: 0 }}><Eye size={14} /> {TYPE_DOCUMENT_LABELS[selectedDoc.typeDocument]} — {selectedDoc.nomFichier}</h2>
              <div style={{ display: 'flex', gap: 6 }}>
                <button className="btn btn-secondary btn-sm" disabled={loadingPdf} onClick={async () => {
                  if (showPdf) { setShowPdf(false); if (pdfBlobUrl) { URL.revokeObjectURL(pdfBlobUrl); setPdfBlobUrl(null) }; return }
                  setLoadingPdf(true)
                  try {
                    const res = await fetch(getDocumentFileUrl(id, selectedDoc.id), { headers: { 'Authorization': `Basic ${localStorage.getItem('recondoc_auth') || ''}` } })
                    if (!res.ok) throw new Error(`HTTP ${res.status}`)
                    const blob = await res.blob()
                    setPdfBlobUrl(URL.createObjectURL(blob))
                    setShowPdf(true)
                  } catch { toast('error', 'Impossible de charger le PDF') }
                  finally { setLoadingPdf(false) }
                }}>
                  {loadingPdf ? <><Loader2 size={14} className="spin" /> Chargement...</> : showPdf ? <><XCircle size={14} /> Masquer PDF</> : <><FileText size={14} /> Voir PDF</>}
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => openWithAuth(getDocumentFileUrl(id, selectedDoc.id))}>
                  <Download size={14} /> Telecharger
                </button>
              </div>
            </div>
            {showPdf && pdfBlobUrl && <div className="pdf-viewer"><iframe src={pdfBlobUrl} title={selectedDoc.nomFichier} /></div>}
          </div>

          {/* Extracted data */}
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
            <ExtractedDataView data={getDataForType(selectedDoc.typeDocument) || selectedDoc.donneesExtraites} />
          </div>
        </>
      )}
    </>
  )
})

// Sub-component for extracted data rendering
function ExtractedDataView({ data }: { data: Record<string, unknown> | null | undefined }) {
  const { toast } = useToast()
  if (!data) return <p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Aucune donnee extraite</p>

  const scalars = Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object')
  const points = (data['points'] as Array<Record<string, unknown>> | undefined) || []
  const lignes = (data['lignes'] as Array<Record<string, unknown>> | undefined) || []
  const pieces = (data['pieces'] as Array<Record<string, unknown>> | undefined) || []
  const isLlmExtracted = scalars.length > 3

  return (
    <>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        {scalars.map(([k, v]) => (
          <div key={k} className="data-field">
            <span className="data-field-key">{k}</span>
            <span className="data-field-value" contentEditable suppressContentEditableWarning
              onBlur={e => { const newVal = e.currentTarget.textContent || ''; if (newVal !== String(v)) toast('info', `${k}: ${String(v)} → ${newVal}`) }}>
              {String(v)}
            </span>
            <span className={`data-field-source ${isLlmExtracted ? 'ai' : 'ocr'}`}>{isLlmExtracted ? 'IA' : 'OCR'}</span>
          </div>
        ))}
      </div>

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
                    {isValid ? '\u2713' : isFalse ? '\u2717' : '\u2014'}
                  </span>
                  <div className="check-point-body">
                    <div className="check-point-label">{String(pt.description || pt.designation || `Point ${i + 1}`)}</div>
                    {pt.observation != null && <div className="check-point-obs">{String(pt.observation)}</div>}
                  </div>
                </div>
              )
            })}
          </div>
        )
      })()}

      {(() => {
        const signataires = (data['signataires'] as Array<Record<string, unknown>> | undefined) || []
        const signataire = data['signataire'] as string | undefined
        if (signataires.length === 0 && !signataire) return null
        return (
          <div style={{ marginTop: 14, padding: '10px 12px', background: 'var(--ink-02)', borderRadius: 6 }}>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>Signatures</div>
            {signataires.length > 0 ? signataires.map((sig, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0' }}>
                <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 16, height: 16, fontSize: 10 }}>
                  {sig.aSignature ? '\u2713' : '\u2014'}
                </span>
                <span style={{ fontWeight: 600, fontSize: 12, color: 'var(--ink)' }}>{String(sig.nom || 'Inconnu')}</span>
                {sig.date != null && <span style={{ fontSize: 11, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)' }}>{String(sig.date)}</span>}
                {sig.aSignature === true && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Signe</span>}
              </div>
            )) : <div style={{ fontSize: 12, color: 'var(--ink-50)' }}>{signataire}</div>}
          </div>
        )
      })()}

      {pieces.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
            Pieces justificatives ({pieces.length})
          </div>
          {pieces.map((pc, i) => (
            <div key={i} className="check-point">
              <span className={`check-point-icon ${pc.estPresent ? 'pass' : pc.estPresent === false ? 'fail' : 'na'}`}>
                {pc.estPresent ? '\u2713' : pc.estPresent === false ? '\u2717' : '?'}
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

      {lignes.length > 0 && (
        <div style={{ marginTop: 14 }}>
          <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 8, fontFamily: 'var(--font-mono)' }}>
            Lignes ({lignes.length})
          </div>
          <table className="data-table">
            <thead><tr><th>Designation</th><th>Qte</th><th>PU HT</th><th>Total HT</th></tr></thead>
            <tbody>
              {lignes.map((ln, i) => (
                <tr key={i}>
                  <td>{String(ln.designation || ln.codeArticle || '\u2014')}</td>
                  <td className="cell-mono">{ln.quantite != null ? String(ln.quantite) : '\u2014'}</td>
                  <td className="cell-mono">{ln.prixUnitaireHT != null ? String(ln.prixUnitaireHT) : '\u2014'}</td>
                  <td className="cell-mono">{ln.montantTotalHT != null ? String(ln.montantTotalHT) : '\u2014'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}
