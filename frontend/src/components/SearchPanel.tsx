import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search, FolderOpen, ArrowRight } from 'lucide-react'
import { searchDossiers } from '../api/dossierApi'
import { STATUT_CONFIG } from '../api/dossierTypes'
import type { DossierListItem } from '../api/dossierTypes'

interface Props {
  open: boolean
  onClose: () => void
}

export default function SearchPanel({ open, onClose }: Props) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<DossierListItem[]>([])
  const [loading, setLoading] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  useEffect(() => {
    if (open) {
      setQuery('')
      setResults([])
      setTimeout(() => inputRef.current?.focus(), 50)
    }
  }, [open])

  useEffect(() => {
    if (!query.trim()) { setResults([]); return }
    const timer = setTimeout(async () => {
      setLoading(true)
      try {
        const res = await searchDossiers({ fournisseur: query, size: 8 })
        setResults(res.content)
      } catch { setResults([]) }
      finally { setLoading(false) }
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') { e.preventDefault(); onClose() }
    }
    if (open) window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  const go = (id: string) => { navigate(`/dossiers/${id}`); onClose() }

  if (!open) return null

  return (
    <div className="search-overlay" onClick={onClose} role="presentation">
      <div className="search-panel" role="dialog" aria-modal="true" aria-label="Recherche de dossiers" onClick={e => e.stopPropagation()}>
        <div className="search-input-wrapper">
          <Search size={18} style={{ color: 'var(--ink-30)' }} aria-hidden="true" />
          <input
            ref={inputRef}
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Rechercher un dossier (fournisseur, reference...)"
            aria-label="Rechercher un dossier"
          />
          <kbd className="kbd-key" style={{ fontSize: 10 }}>ESC</kbd>
        </div>
        <div className="search-results" role="listbox" aria-label="Resultats de recherche">
          {loading && <div className="search-hint" role="status">Recherche...</div>}
          {!loading && query && results.length === 0 && <div className="search-hint">Aucun resultat</div>}
          {!loading && !query && <div className="search-hint">Tapez pour rechercher par fournisseur</div>}
          {results.map(d => {
            const cfg = STATUT_CONFIG[d.statut]
            return (
              <div key={d.id} className="search-result-item" role="option" tabIndex={0}
                onClick={() => go(d.id)}
                onKeyDown={e => { if (e.key === 'Enter') go(d.id) }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                  <FolderOpen size={16} style={{ color: 'var(--accent)', opacity: 0.6 }} aria-hidden="true" />
                  <div>
                    <div style={{ fontWeight: 700, fontSize: 13 }}>{d.reference}</div>
                    <div style={{ fontSize: 12, color: 'var(--ink-40)' }}>{d.fournisseur || 'Sans fournisseur'}</div>
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="status-badge" style={{ backgroundColor: cfg.color + '20', color: cfg.color }}>{cfg.label}</span>
                  <ArrowRight size={14} style={{ color: 'var(--ink-30)' }} aria-hidden="true" />
                </div>
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
