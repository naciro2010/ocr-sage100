import { useEffect, useState, useRef } from 'react'
import { Link } from 'react-router-dom'
import { Search, X, FileText, Loader2 } from 'lucide-react'
import { searchDocuments, type DocumentSearchHit } from '../../api/dossierApi'

interface Props {
  open: boolean
  onClose: () => void
}

/**
 * Full-text search across every OCR'd document. Hits the new
 * /api/dossiers/search-documents endpoint (Postgres tsvector + GIN index).
 * Debounced to keep the API usage reasonable when the user types.
 */
export default function DocumentSearchModal({ open, onClose }: Props) {
  const [q, setQ] = useState('')
  const [hits, setHits] = useState<DocumentSearchHit[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (!open) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setQ(''); setHits([]); setErr(null)
    }
  }, [open])

  useEffect(() => {
    if (debounce.current) clearTimeout(debounce.current)
    if (!q.trim()) {
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setHits([])
      return
    }
    debounce.current = setTimeout(() => {
      setLoading(true)
      setErr(null)
      searchDocuments(q.trim(), 50)
        .then(setHits)
        .catch(e => setErr(e?.message || 'Erreur'))
        .finally(() => setLoading(false))
    }, 300)
    return () => { if (debounce.current) clearTimeout(debounce.current) }
  }, [q])

  if (!open) return null
  return (
    <div role="dialog" aria-modal="true" style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
      display: 'flex', justifyContent: 'center', alignItems: 'flex-start', paddingTop: 80,
      zIndex: 100
    }} onClick={onClose}>
      <div className="card" style={{ width: 'min(720px, 92vw)', maxHeight: '70vh', display: 'flex', flexDirection: 'column' }}
        onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Search size={16} />
          <input autoFocus className="form-input" placeholder="Rechercher dans les documents (OCR + nom)…"
            value={q} onChange={e => setQ(e.target.value)} style={{ flex: 1 }} />
          <button className="btn btn-secondary btn-sm" onClick={onClose} aria-label="Fermer"><X size={14} /></button>
        </div>
        <div style={{ overflowY: 'auto', flex: 1 }}>
          {loading && <p style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--ink-30)', fontSize: 13 }}><Loader2 size={14} className="spin" /> Recherche…</p>}
          {err && <p style={{ color: 'var(--danger)', fontSize: 13 }}>{err}</p>}
          {!loading && !err && q.trim() && hits.length === 0 && (
            <p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Aucun document ne correspond.</p>
          )}
          {hits.map(h => (
            <Link key={h.documentId} to={`/dossiers/${h.dossierId}`} onClick={onClose}
              style={{ display: 'block', padding: '8px 10px', borderRadius: 6, textDecoration: 'none', color: 'inherit' }}
              className="search-hit">
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <FileText size={14} style={{ color: 'var(--ink-30)' }} />
                <span style={{ fontWeight: 500 }}>{h.nomFichier}</span>
                <span style={{ fontSize: 11, color: 'var(--ink-30)' }}>· {h.typeDocument}</span>
              </div>
              <div style={{ fontSize: 11, color: 'var(--ink-30)', marginLeft: 22 }}>
                {h.dossierReference} · {new Date(h.dateUpload).toLocaleDateString('fr-FR')}
                {h.rank > 0 && <> · score {h.rank.toFixed(2)}</>}
              </div>
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}
