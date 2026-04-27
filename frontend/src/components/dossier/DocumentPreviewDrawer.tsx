import { useEffect, useState, useMemo } from 'react'
import { X, ExternalLink, FileText, ChevronLeft, ChevronRight, Download } from 'lucide-react'
import { getDocumentFileUrl, downloadWithAuth } from '../../api/dossierApi'
import type { DocumentInfo } from '../../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'
import PdfFrame from './PdfFrame'

interface Props {
  dossierId: string
  documents: DocumentInfo[]
  activeDocId: string | null
  onClose: () => void
  onChangeActive: (docId: string) => void
  highlightField?: string | null
}

function useBlobUrl(apiUrl: string | null) {
  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!apiUrl) return
    let cancelled = false
    let created: string | null = null
    const auth = localStorage.getItem('recondoc_auth')
    const promise = fetch(apiUrl, { headers: auth ? { 'Authorization': `Basic ${auth}` } : undefined })
      .then(r => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.blob()
      })
    Promise.resolve().then(() => {
      if (cancelled) return
      setLoading(true)
      setError(null)
    })
    promise
      .then(blob => {
        if (cancelled) return
        const u = URL.createObjectURL(blob)
        created = u
        setBlobUrl(u)
      })
      .catch(e => { if (!cancelled) setError(e instanceof Error ? e.message : 'Erreur') })
      .finally(() => { if (!cancelled) setLoading(false) })

    return () => {
      cancelled = true
      if (created) URL.revokeObjectURL(created)
      setBlobUrl(null)
    }
  }, [apiUrl])

  return { blobUrl, loading, error }
}

export default function DocumentPreviewDrawer({ dossierId, documents, activeDocId, onClose, onChangeActive, highlightField }: Props) {
  const activeDoc = documents.find(d => d.id === activeDocId) || null
  const apiUrl = useMemo(() =>
    activeDoc ? getDocumentFileUrl(dossierId, activeDoc.id) : null,
    [dossierId, activeDoc])
  const { blobUrl, loading, error } = useBlobUrl(apiUrl)

  const currentIdx = activeDocId ? documents.findIndex(d => d.id === activeDocId) : -1
  const canPrev = currentIdx > 0
  const canNext = currentIdx >= 0 && currentIdx < documents.length - 1

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && activeDocId) onClose()
      if (e.key === 'ArrowLeft' && canPrev) onChangeActive(documents[currentIdx - 1].id)
      if (e.key === 'ArrowRight' && canNext) onChangeActive(documents[currentIdx + 1].id)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [activeDocId, canPrev, canNext, currentIdx, documents, onChangeActive, onClose])

  if (!activeDocId || !activeDoc) return null

  const isPdf = activeDoc.nomFichier.toLowerCase().endsWith('.pdf')
  const isImage = /\.(png|jpe?g|webp|gif)$/i.test(activeDoc.nomFichier)

  return (
    <>
      <div className="preview-overlay" onClick={onClose} aria-hidden="true" />
      <aside role="dialog" aria-label={`Apercu du document ${activeDoc.nomFichier}`} className="preview-drawer">
        <div className="preview-header">
          <FileText size={14} className="preview-header-icon" />
          <div className="preview-header-info">
            <div className="preview-header-title">
              {TYPE_DOCUMENT_LABELS[activeDoc.typeDocument] || activeDoc.typeDocument}
            </div>
            <div className="preview-header-sub">
              {activeDoc.nomFichier}
              {highlightField && (
                <> &middot; <strong>champ : {highlightField}</strong></>
              )}
            </div>
          </div>
          {documents.length > 1 && (
            <div className="preview-nav">
              <button className="btn btn-secondary btn-sm" disabled={!canPrev}
                onClick={() => canPrev && onChangeActive(documents[currentIdx - 1].id)}
                aria-label="Document precedent">
                <ChevronLeft size={12} />
              </button>
              {currentIdx + 1}/{documents.length}
              <button className="btn btn-secondary btn-sm" disabled={!canNext}
                onClick={() => canNext && onChangeActive(documents[currentIdx + 1].id)}
                aria-label="Document suivant">
                <ChevronRight size={12} />
              </button>
            </div>
          )}
          <button className="btn btn-secondary btn-sm" title="Telecharger"
            onClick={() => downloadWithAuth(getDocumentFileUrl(dossierId, activeDoc.id), activeDoc.nomFichier)}
            aria-label="Telecharger">
            <Download size={12} />
          </button>
          <a className="btn btn-secondary btn-sm" href={blobUrl || '#'} target="_blank" rel="noreferrer"
            title="Ouvrir dans un nouvel onglet" aria-label="Ouvrir dans un nouvel onglet">
            <ExternalLink size={12} />
          </a>
          <button className="btn btn-secondary btn-sm" onClick={onClose} aria-label="Fermer l'apercu">
            <X size={14} />
          </button>
        </div>

        <div className="preview-body">
          {loading && <div className="preview-loading">Chargement...</div>}
          {error && <div className="preview-error">Impossible de charger le document : {error}</div>}
          {blobUrl && !error && (
            isPdf ? (
              <PdfFrame blobUrl={blobUrl} title={activeDoc.nomFichier} docId={activeDoc.id} />
            ) : isImage ? (
              <div className="preview-image-wrap">
                <img src={blobUrl} alt={activeDoc.nomFichier} />
              </div>
            ) : (
              <div className="preview-unsupported">
                Format non previsualisable.
                <a href={blobUrl} download={activeDoc.nomFichier}>Telecharger</a>
              </div>
            )
          )}
        </div>
      </aside>
    </>
  )
}
