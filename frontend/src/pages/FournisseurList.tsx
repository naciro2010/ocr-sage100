import { useEffect, useState, useRef, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { listFournisseurs, getFournisseursStats } from '../api/fournisseurApi'
import type { FournisseurSummary, FournisseursStats } from '../api/fournisseurTypes'
import {
  Users, Search, X, Building2, TrendingUp, CheckCircle,
  ChevronRight, Briefcase, Wallet
} from 'lucide-react'

function fmt(n: number | null | undefined): string {
  if (n == null) return '0'
  return n.toLocaleString('fr-FR', { maximumFractionDigits: 2 })
}

export default function FournisseurList() {
  const [fournisseurs, setFournisseurs] = useState<FournisseurSummary[] | null>(null)
  const [stats, setStats] = useState<FournisseursStats | null>(null)
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [error, setError] = useState('')
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null)

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => setDebouncedQuery(query), 300)
    return () => { if (debounceRef.current) clearTimeout(debounceRef.current) }
  }, [query])

  useEffect(() => {
    const ctrl = new AbortController()
    listFournisseurs(debouncedQuery || undefined, ctrl.signal)
      .then(setFournisseurs)
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
        setFournisseurs([])
      })
    return () => ctrl.abort()
  }, [debouncedQuery])

  useEffect(() => {
    const ctrl = new AbortController()
    getFournisseursStats(ctrl.signal).then(setStats).catch(() => {})
    return () => ctrl.abort()
  }, [])

  const sorted = useMemo(() => {
    if (!fournisseurs) return null
    return [...fournisseurs].sort((a, b) => b.nbDossiers - a.nbDossiers)
  }, [fournisseurs])

  if (!fournisseurs) {
    return (
      <div className="skeleton">
        <div className="skeleton-bar h-lg w-40" />
        <div className="skeleton-grid">
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
        </div>
        <div className="skeleton-card" style={{ height: 300 }} />
      </div>
    )
  }

  const isEmpty = sorted!.length === 0 && !debouncedQuery

  return (
    <div>
      <div className="page-header">
        <h1><Users size={18} /> Fournisseurs</h1>
        <div className="page-header-actions">
          <span className="tag" style={{ background: 'var(--ink-10)' }}>
            {fournisseurs.length} fournisseur{fournisseurs.length > 1 ? 's' : ''}
          </span>
        </div>
      </div>

      {error && <div className="alert alert-error mb-3">{error}</div>}

      {stats && (
        <div className="stats-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 16 }}>
          <div className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-40)', fontSize: 12, marginBottom: 8 }}>
              <Building2 size={14} /> Total fournisseurs
            </div>
            <div style={{ fontSize: 24, fontWeight: 600 }}>{stats.totalFournisseurs}</div>
          </div>
          <div className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-40)', fontSize: 12, marginBottom: 8 }}>
              <TrendingUp size={14} /> Fournisseurs actifs
            </div>
            <div style={{ fontSize: 24, fontWeight: 600, color: 'var(--warning)' }}>{stats.fournisseursActifs}</div>
            <div style={{ fontSize: 11, color: 'var(--ink-40)', marginTop: 4 }}>Dossiers en cours</div>
          </div>
          <div className="card" style={{ padding: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--ink-40)', fontSize: 12, marginBottom: 8 }}>
              <Wallet size={14} /> Montant total engage
            </div>
            <div style={{ fontSize: 20, fontWeight: 600, fontFamily: 'var(--font-mono, monospace)' }}>
              {fmt(stats.montantTotalEngage)} MAD
            </div>
          </div>
        </div>
      )}

      <div className="dossier-filters">
        <div className="dossier-filters-right" style={{ width: '100%' }}>
          <div className="dossier-search-wrap" style={{ flex: 1 }}>
            <Search size={14} className="dossier-search-icon" />
            <input
              className="dossier-search"
              placeholder="Rechercher un fournisseur..."
              value={query}
              onChange={e => setQuery(e.target.value)}
            />
            {query && (
              <button className="dossier-search-clear" onClick={() => setQuery('')} aria-label="Effacer">
                <X size={12} />
              </button>
            )}
          </div>
        </div>
      </div>

      {isEmpty ? (
        <div className="dossier-empty">
          <div className="dossier-empty-icon">
            <Users size={40} />
          </div>
          <h2>Aucun fournisseur</h2>
          <p>Les fournisseurs apparaissent automatiquement des qu'un dossier de paiement leur est associe.</p>
          <div className="dossier-empty-actions">
            <Link to="/dossiers" className="btn btn-primary">
              <Briefcase size={15} /> Voir les dossiers
            </Link>
          </div>
        </div>
      ) : sorted!.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: 32, color: 'var(--ink-40)' }}>
          Aucun fournisseur ne correspond a "{debouncedQuery}".
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>Fournisseur</th>
                <th>ICE</th>
                <th>Dossiers</th>
                <th>Repartition</th>
                <th>Montant total TTC</th>
                <th>Montant valide</th>
                <th>Dernier dossier</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {sorted!.map(f => {
                const validePct = f.nbDossiers > 0 ? Math.round((f.nbValides / f.nbDossiers) * 100) : 0
                return (
                  <tr key={f.nom}>
                    <td>
                      <Link to={`/fournisseurs/${encodeURIComponent(f.nom)}`} style={{ fontWeight: 500 }}>
                        {f.nom}
                      </Link>
                    </td>
                    <td className="cell-mono" style={{ fontSize: 12, color: 'var(--ink-40)' }}>
                      {f.ice || '\u2014'}
                    </td>
                    <td><strong>{f.nbDossiers}</strong></td>
                    <td>
                      <div style={{ display: 'flex', gap: 4, fontSize: 11 }}>
                        {f.nbBrouillons > 0 && (
                          <span className="status-badge" style={{ background: '#f1f5f9', color: '#475569' }}>
                            {f.nbBrouillons} br.
                          </span>
                        )}
                        {f.nbEnVerification > 0 && (
                          <span className="status-badge" style={{ background: '#fffbeb', color: '#d97706' }}>
                            {f.nbEnVerification} cours
                          </span>
                        )}
                        {f.nbValides > 0 && (
                          <span className="status-badge" style={{ background: '#ecfdf5', color: '#059669' }}>
                            <CheckCircle size={10} style={{ display: 'inline', marginRight: 2 }} />
                            {f.nbValides}
                          </span>
                        )}
                        {f.nbRejetes > 0 && (
                          <span className="status-badge" style={{ background: '#fef2f2', color: '#dc2626' }}>
                            {f.nbRejetes} rej.
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="cell-mono">{fmt(f.montantTotalTtc)} MAD</td>
                    <td className="cell-mono" style={{ color: 'var(--success)' }}>
                      {fmt(f.montantValide)} MAD
                      <span style={{ fontSize: 10, color: 'var(--ink-40)', marginLeft: 6 }}>({validePct}%)</span>
                    </td>
                    <td className="audit-date">
                      {f.dernierDossier ? new Date(f.dernierDossier).toLocaleDateString('fr-FR') : '\u2014'}
                    </td>
                    <td>
                      <Link to={`/fournisseurs/${encodeURIComponent(f.nom)}`} className="btn btn-secondary btn-sm" aria-label="Voir le detail">
                        <ChevronRight size={14} />
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
