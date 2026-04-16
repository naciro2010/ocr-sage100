import { useEffect, useState, useMemo } from 'react'
import { X, ExternalLink, FileText, ChevronLeft, ChevronRight, Download } from 'lucide-react'
import { getDocumentFileUrl, downloadWithAuth } from '../../api/dossierApi'
import type { DocumentInfo } from '../../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../../api/dossierTypes'

interface Props {
  dossierId: string
  documents: DocumentInfo[]    // docs relevant to the current focus (from evidence/documentIds)
  activeDocId: string | null
  onClose: () => void
  onChangeActive: (docId: string) => void
  // Optional hint about which field to highlight ("montantTTC"). Forwarded to OCR viewer in the future.
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
    // Defer the initial state updates into a microtask so they're async w.r.t. the effect body
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

  // ESC closes
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
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.35)',
          zIndex: 900, animation: 'fadeIn 120ms ease-out'
        }}
        aria-hidden="true"
      />
      <aside
        role="dialog"
        aria-label={`Apercu du document ${activeDoc.nomFichier}`}
        style={{
          position: 'fixed', top: 0, right: 0, bottom: 0,
          width: 'min(720px, 55vw)',
          background: 'white', boxShadow: '-8px 0 24px rgba(15,23,42,0.18)',
          zIndex: 901, display: 'flex', flexDirection: 'column',
          animation: 'slideLeft 180ms ease-out'
        }}
      >
        {/* Header */}
        <div style={{
          padding: '10px 14px', borderBottom: '1px solid var(--ink-05)',
          display: 'flex', alignItems: 'center', gap: 8
        }}>
          <FileText size={14} style={{ color: 'var(--teal-700)' }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13, fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {TYPE_DOCUMENT_LABELS[activeDoc.typeDocument] || activeDoc.typeDocument}
            </div>
            <div style={{ fontSize: 10, color: 'var(--ink-40)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {activeDoc.nomFichier}
              {highlightField && (
                <> &middot; <strong style={{ color: 'var(--info)' }}>champ : {highlightField}</strong></>
              )}
            </div>
          </div>
          {documents.length > 1 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: 'var(--ink-40)' }}>
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

        {/* Body */}
        <div style={{ flex: 1, position: 'relative', background: 'var(--ink-02)', overflow: 'auto' }}>
          {loading && (
            <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', color: 'var(--ink-40)', fontSize: 12 }}>
              Chargement...
            </div>
          )}
          {error && (
            <div style={{ padding: 20, color: 'var(--danger)', fontSize: 12 }}>
              Impossible de charger le document : {error}
            </div>
          )}
          {blobUrl && !error && (
            isPdf ? (
              <iframe
                src={`${blobUrl}#view=FitH`}
                title={activeDoc.nomFichier}
                style={{ width: '100%', height: '100%', border: 'none', display: 'block' }}
              />
            ) : isImage ? (
              <div style={{ padding: 16, display: 'grid', placeItems: 'center', minHeight: '100%' }}>
                <img src={blobUrl} alt={activeDoc.nomFichier} style={{ maxWidth: '100%', maxHeight: '80vh', boxShadow: '0 4px 12px rgba(15,23,42,0.1)' }} />
              </div>
            ) : (
              <div style={{ padding: 20, fontSize: 12, color: 'var(--ink-60)' }}>
                Format non previsualisable.
                <a href={blobUrl} download={activeDoc.nomFichier} style={{ marginLeft: 8 }}>Telecharger</a>
              </div>
            )
          )}
        </div>
      </aside>
      <style>{`
        @keyframes fadeIn { from { opacity: 0 } to { opacity: 1 } }
        @keyframes slideLeft {
          from { transform: translateX(100%); opacity: 0.6 }
          to { transform: translateX(0); opacity: 1 }
        }
      `}</style>
    </>
  )
}
