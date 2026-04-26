import { memo, useRef, useState, useCallback, useEffect, useMemo } from 'react'
import type { DossierDetail, DocumentInfo, TypeDocument, ValidationResult } from '../../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import { uploadDocuments, uploadZip, reprocessDocument, changeDocumentType, deleteDocument, getDocumentFileUrl, openWithAuth } from '../../api/dossierApi'
import { useToast } from '../Toast'
import DocumentPipeline from '../DocumentPipeline'
import ExtractedDataView from './ExtractedDataView'
import AttestationFiscalePanel from './AttestationFiscalePanel'
import type { DocProgress } from '../../hooks/useDocumentEvents'
import {
  FileText, Upload, RefreshCw, Loader2, Trash2, Eye, XCircle, Download, ExternalLink, ChevronDown, ChevronUp
} from 'lucide-react'

interface Props {
  dossier: DossierDetail
  id: string
  liveProgress: Record<string, DocProgress>
  onReload: () => void
  onReloadAudit: () => void
  onValidationResultsUpdated?: (results: ValidationResult[]) => void
}

export default memo(function DocumentManager({ dossier, id, liveProgress, onReload, onReloadAudit, onValidationResultsUpdated }: Props) {
  const { toast } = useToast()
  const inputRef = useRef<HTMLInputElement>(null)
  const zipInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [selectedDoc, setSelectedDoc] = useState<DocumentInfo | null>(null)
  const [showExtracted, setShowExtracted] = useState(false)
  const [showPdf, setShowPdf] = useState(false)
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null)
  const [loadingPdf, setLoadingPdf] = useState(false)
  const [pendingDeleteIds, setPendingDeleteIds] = useState<Set<string>>(new Set())
  const [pendingTypeByDoc, setPendingTypeByDoc] = useState<Record<string, TypeDocument>>({})

  const displayedDocuments = useMemo(() => {
    return dossier.documents
      .filter(doc => !pendingDeleteIds.has(doc.id))
      .map(doc => {
        const pendingType = pendingTypeByDoc[doc.id]
        if (!pendingType) return doc
        return {
          ...doc,
          typeDocument: pendingType,
          statutExtraction: 'EN_ATTENTE' as DocumentInfo['statutExtraction'],
          donneesExtraites: null,
        }
      })
  }, [dossier.documents, pendingDeleteIds, pendingTypeByDoc])

  // Cleanup blob URL on unmount
  useEffect(() => {
    return () => { if (pdfBlobUrl) URL.revokeObjectURL(pdfBlobUrl) }
  }, [pdfBlobUrl])

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

  const handleZipUpload = useCallback(async (file: File | null) => {
    if (!file) return
    setUploading(true)
    try {
      const res = await uploadZip(id, file)
      const { accepted, deduped, skipped } = res.stats
      const dedupeSuffix = deduped > 0 ? `, ${deduped} doublon(s)` : ''
      const skipSuffix = skipped > 0 ? `, ${skipped} ignore(s)` : ''
      toast('success', `${accepted - deduped} document(s) ajoute(s)${dedupeSuffix}${skipSuffix}`)
      onReload()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Upload ZIP failed')
    } finally { setUploading(false) }
  }, [id, onReload, toast])

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
    if (pendingTypeByDoc[docId]) return
    const castType = newType as TypeDocument
    setPendingTypeByDoc(prev => ({ ...prev, [docId]: castType }))
    try {
      await changeDocumentType(id, docId, newType)
      toast('success', `Type modifie en ${TYPE_DOCUMENT_LABELS[castType] || newType}`)
      onReload()
      onReloadAudit()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally {
      setPendingTypeByDoc(prev => {
        const next = { ...prev }
        delete next[docId]
        return next
      })
    }
  }, [id, onReload, onReloadAudit, pendingTypeByDoc, toast])

  const handleDeleteDoc = useCallback(async (docId: string, docName: string) => {
    if (!confirm(`Supprimer ${docName} ?`)) return
    // Optimistic: hide the card immediately, then sync with backend.
    if (selectedDoc?.id === docId) setSelectedDoc(null)
    setPendingDeleteIds(prev => {
      const next = new Set(prev)
      next.add(docId)
      return next
    })
    try {
      await deleteDocument(id, docId)
      toast('success', `${docName} supprime`)
      onReload()
      onReloadAudit()
    } catch (e: unknown) {
      setPendingDeleteIds(prev => {
        const next = new Set(prev)
        next.delete(docId)
        return next
      })
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

  const getDataForType = (type: TypeDocument, docId?: string): Record<string, unknown> | null => {
    if (type === 'FACTURE' && dossier.factures?.length > 0) {
      if (docId) {
        const match = dossier.factures.find(f => f.documentId === docId)
        if (match) return match
      }
      return dossier.factures[0]
    }
    const map: Record<string, Record<string, unknown> | null> = {
      BON_COMMANDE: dossier.bonCommande,
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
          {displayedDocuments.length > 0 && (
            <button className="btn btn-secondary btn-sm" aria-label="Relancer les documents en erreur" disabled={uploading} onClick={async () => {
              const failed = displayedDocuments.filter(doc => doc.statutExtraction === 'ERREUR')
              if (failed.length === 0) { toast('info', 'Aucun document en erreur'); return }
              setUploading(true)
              const results = await Promise.allSettled(failed.map(doc => reprocessDocument(id, doc.id)))
              const ok = results.filter(r => r.status === 'fulfilled').length
              const ko = results.filter(r => r.status === 'rejected').length
              setUploading(false)
              toast(ko > 0 ? 'warning' : 'info', `${ok} relance(s) ok${ko > 0 ? `, ${ko} echec(s)` : ''}`)
              onReload()
            }}>
              <RefreshCw size={11} /> {uploading ? 'Relance...' : 'Relancer'}
            </button>
          )}
        </div>
        {dragging && (
          <div style={{ padding: 32, textAlign: 'center', border: '2px dashed var(--teal-600)', borderRadius: 8, marginBottom: 16, color: 'var(--teal-700)', fontWeight: 700 }}>
            <Upload size={32} /><div style={{ marginTop: 8 }}>Deposez vos PDFs ici</div>
          </div>
        )}
        {/* Processing pipelines */}
        {displayedDocuments.some(d => d.statutExtraction === 'EN_ATTENTE' || d.statutExtraction === 'EN_COURS') && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 12 }}>
            {displayedDocuments
              .filter(d => d.statutExtraction === 'EN_ATTENTE' || d.statutExtraction === 'EN_COURS')
              .map(doc => <DocumentPipeline key={doc.id} doc={doc} liveProgress={liveProgress[doc.id]} />)}
          </div>
        )}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))', gap: 10, marginBottom: 12 }}>
          {(() => {
            // Precompute type counts to avoid O(n^2) in render
            const typeCounts = new Map<string, number>()
            const typeIndexes = new Map<string, number>()
            for (const d of displayedDocuments) {
              typeCounts.set(d.typeDocument, (typeCounts.get(d.typeDocument) || 0) + 1)
            }
            return displayedDocuments.map((doc) => {
            const count = typeCounts.get(doc.typeDocument) || 1
            const isMulti = count > 1
            const idx = (typeIndexes.get(doc.typeDocument) || 0) + 1
            typeIndexes.set(doc.typeDocument, idx)
            const multiIdx = isMulti ? idx : 0
            return (
            <div key={doc.id} data-doc-id={doc.id} className={`doc-card ${selectedDoc?.id === doc.id ? 'selected' : ''}`}
              onClick={() => { setSelectedDoc(selectedDoc?.id === doc.id ? null : doc); setShowPdf(false); setShowExtracted(false) }}>
              <div style={{ marginBottom: 2, display: 'flex', alignItems: 'center', gap: 4 }}>
                <select className="form-select" value={doc.typeDocument} title="Cliquez pour corriger le type"
                  style={{ fontSize: 11, fontWeight: 700, padding: '0 2px', border: 'none', background: 'transparent', color: 'var(--slate-800)', cursor: 'pointer', width: '100%' }}
                  onClick={e => e.stopPropagation()} onChange={e => { e.stopPropagation(); handleChangeType(doc.id, e.target.value) }}>
                  {Object.entries(TYPE_DOCUMENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
                {isMulti && <span className="tag" style={{ fontSize: 8, flexShrink: 0 }}>{multiIdx}/{count}</span>}
              </div>
              <div style={{ fontSize: 11, color: 'var(--ink-50)', marginBottom: 6 }}>{doc.nomFichier}</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {extractionBadge(doc)}
                {doc.statutExtraction === 'EN_COURS' && <Loader2 size={12} className="spin" style={{ color: 'var(--blue-600)' }} />}
              </div>
              {doc.extractionConfidence >= 0 && doc.statutExtraction === 'EXTRAIT' && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginTop: 4 }}>
                  <span style={{
                    fontSize: 9, fontWeight: 700, padding: '1px 5px', borderRadius: 4,
                    background: doc.extractionConfidence >= 0.8 ? '#ecfdf5' : doc.extractionConfidence >= 0.5 ? '#fffbeb' : '#fef2f2',
                    color: doc.extractionConfidence >= 0.8 ? '#059669' : doc.extractionConfidence >= 0.5 ? '#d97706' : '#dc2626',
                  }}>
                    {Math.round(doc.extractionConfidence * 100)}%
                  </span>
                  {doc.ocrEngine && <span style={{ fontSize: 9, color: 'var(--ink-40)' }}>{doc.ocrEngine}</span>}
                  {doc.extractionWarnings.length > 0 && (
                    <span title={doc.extractionWarnings.join('\n')} style={{ fontSize: 9, color: 'var(--warning)', cursor: 'help' }}>
                      {doc.extractionWarnings.length} warning{doc.extractionWarnings.length > 1 ? 's' : ''}
                    </span>
                  )}
                </div>
              )}
              {doc.erreurExtraction && <div style={{ fontSize: 10, color: 'var(--danger)', marginTop: 4, lineHeight: 1.3 }}>{doc.erreurExtraction}</div>}
              <div style={{ display: 'flex', gap: 4, marginTop: 6 }}>
                {doc.statutExtraction === 'ERREUR' && (
                  <button className="btn btn-secondary btn-sm" onClick={e => { e.stopPropagation(); handleReprocess(doc.id) }}>
                    <RefreshCw size={11} /> Relancer
                  </button>
                )}
                <button className="btn btn-danger btn-sm" onClick={e => { e.stopPropagation(); handleDeleteDoc(doc.id, doc.nomFichier) }} aria-label={`Supprimer ${doc.nomFichier}`}>
                  <Trash2 size={11} />
                </button>
              </div>
            </div>
            )
          })
          })()}

          <div className="drop-zone" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: 90, padding: 20 }}
            onClick={() => inputRef.current?.click()}>
            <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => handleUpload(e.target.files)} />
            {uploading ? <Loader2 size={22} className="spin" style={{ color: 'var(--teal-600)' }} /> : <Upload size={22} style={{ color: 'var(--slate-400)' }} />}
            <span style={{ fontSize: 12, color: 'var(--slate-500)', marginTop: 6 }}>{uploading ? 'Upload...' : 'Ajouter des PDFs'}</span>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              style={{ marginTop: 8 }}
              disabled={uploading}
              onClick={e => { e.stopPropagation(); zipInputRef.current?.click() }}
              title="Importer un ZIP : tous les PDF/images dedans deviennent des documents du dossier"
            >
              <Upload size={12} /> ou un ZIP
            </button>
            <input ref={zipInputRef} type="file" accept=".zip" hidden
              onChange={e => { const f = e.target.files?.[0] ?? null; handleZipUpload(f); e.target.value = '' }} />
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

          {/* Panel dedie attestation fiscale (R18, R18b, R19, R23) — apparait au-dessus
               des donnees extraites pour donner un verdict d'un coup d'oeil. */}
          {selectedDoc.typeDocument === 'ATTESTATION_FISCALE' && (
            <AttestationFiscalePanel
              dossierId={id}
              attestation={dossier.attestationFiscale}
              ordrePaiement={dossier.ordrePaiement}
              validationResults={dossier.resultatsValidation || []}
              onResultsUpdated={(results) => {
                if (onValidationResultsUpdated) onValidationResultsUpdated(results)
              }}
              onReload={() => { onReload(); onReloadAudit() }}
            />
          )}

          {/* Extracted data — collapsible */}
          <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
            <div
              className="card-flex"
              style={{ padding: '12px 18px', cursor: 'pointer' }}
              onClick={() => setShowExtracted(!showExtracted)}
              role="button"
              tabIndex={0}
              aria-expanded={showExtracted}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setShowExtracted(!showExtracted) } }}
            >
              <h2 style={{ marginBottom: 0 }}>
                <ExternalLink size={14} /> Donnees extraites
                {!showExtracted && selectedDoc.donneesExtraites && (
                  <span style={{ fontWeight: 400, fontSize: 11, color: 'var(--ink-40)', marginLeft: 8, textTransform: 'none', letterSpacing: 0 }}>
                    {Object.values(selectedDoc.donneesExtraites).filter(v => v !== null).length} champs
                  </span>
                )}
              </h2>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                {showExtracted && (
                  <>
                    <span className="data-field-source ocr" style={{ fontSize: 9 }}>OCR</span>
                    <span className="data-field-source ai" style={{ fontSize: 9 }}>IA</span>
                  </>
                )}
                {showExtracted ? <ChevronUp size={14} style={{ color: 'var(--ink-30)' }} /> : <ChevronDown size={14} style={{ color: 'var(--ink-30)' }} />}
              </div>
            </div>
            {showExtracted && (
              <div style={{ padding: '0 18px 18px' }}>
                {selectedDoc.statutExtraction === 'ERREUR' && <div className="alert alert-error mb-2"><XCircle size={14} /> {selectedDoc.erreurExtraction}</div>}
                <ExtractedDataView data={getDataForType(selectedDoc.typeDocument, selectedDoc.id) || selectedDoc.donneesExtraites} docType={selectedDoc.typeDocument} />
              </div>
            )}
          </div>
        </>
      )}
    </>
  )
})
