import { useEffect, useState, useRef, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { listFournisseurs, getFournisseursStats } from '../api/fournisseurApi'
import type { FournisseurSummary, FournisseursStats } from '../api/fournisseurTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import type { StatutDossier } from '../api/dossierTypes'
import {
  Users, Search, X, Building2, TrendingUp, CheckCircle,
  ChevronRight, Briefcase, Wallet, ArrowUpDown, Trophy
} from 'lucide-react'

type SortKey = 'dossiers' | 'montant' | 'nom' | 'recent'

const CIRCLE_R = 13
const CIRCLE_CIRCUMFERENCE = 2 * Math.PI * CIRCLE_R

function fmt(n: number | null | undefined): string {
  if (n == null) return '0,00'
  return n.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtCompact(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)} M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)} k`
  return String(Math.round(n))
}

const STATUT_BADGES: Array<{ key: StatutDossier; countOf: (f: FournisseurSummary) => number; shortLabel: string; showIcon?: boolean }> = [
  { key: 'BROUILLON', countOf: f => f.nbBrouillons, shortLabel: 'br.' },
  { key: 'EN_VERIFICATION', countOf: f => f.nbEnVerification, shortLabel: 'cours' },
  { key: 'VALIDE', countOf: f => f.nbValides, shortLabel: '', showIcon: true },
  { key: 'REJETE', countOf: f => f.nbRejetes, shortLabel: 'rej.' },
]

export default function FournisseurList() {
  const [fournisseurs, setFournisseurs] = useState<FournisseurSummary[] | null>(null)
  const [stats, setStats] = useState<FournisseursStats | null>(null)
  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [sortKey, setSortKey] = useState<SortKey>('dossiers')
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
      .then(res => { if (!ctrl.signal.aborted) setFournisseurs(res) })
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
        setFournisseurs([])
      })
    return () => ctrl.abort()
  }, [debouncedQuery])

  useEffect(() => {
    const ctrl = new AbortController()
    getFournisseursStats(ctrl.signal)
      .then(res => { if (!ctrl.signal.aborted) setStats(res) })
      .catch(() => {})
    return () => ctrl.abort()
  }, [])

  const sorted = useMemo(() => {
    if (!fournisseurs) return null
    const arr = [...fournisseurs]
    switch (sortKey) {
      case 'nom': return arr.sort((a, b) => a.nom.localeCompare(b.nom, 'fr'))
      case 'montant': return arr.sort((a, b) => b.montantTotalTtc - a.montantTotalTtc)
      case 'recent': return arr.sort((a, b) => (b.dernierDossier || '').localeCompare(a.dernierDossier || ''))
      case 'dossiers':
      default: return arr.sort((a, b) => b.nbDossiers - a.nbDossiers)
    }
  }, [fournisseurs, sortKey])

  const maxMontant = useMemo(() => {
    if (!sorted || sorted.length === 0) return 1
    return Math.max(...sorted.map(f => f.montantTotalTtc), 1)
  }, [sorted])

  const topMax = useMemo(() => {
    if (!stats || stats.topFournisseurs.length === 0) return 1
    return Math.max(...stats.topFournisseurs.map(f => f.montantTotalTtc), 1)
  }, [stats])

  if (!fournisseurs) {
    return (
      <div className="skeleton">
        <div className="skeleton-line h-lg w-40" />
        <div className="skeleton-grid">
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
        </div>
        <div className="skeleton-card" style={{ height: 320 }} />
      </div>
    )
  }

  const isEmpty = sorted!.length === 0 && !debouncedQuery
  const hasResults = sorted!.length > 0

  return (
    <div>
      <div className="page-header">
        <h1><Users size={18} /> Fournisseurs</h1>
        <div className="header-actions">
          <Link to="/dossiers" className="btn btn-secondary btn-sm">
            <Briefcase size={14} /> Voir les dossiers
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error mb-3">{error}</div>}

      {stats && !isEmpty && (
        <div className="stats-grid">
          <div className="stat-card">
            <div className="stat-icon teal"><Building2 size={16} /></div>
            <div className="stat-value">{stats.totalFournisseurs}</div>
            <div className="stat-label">Total fournisseurs</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon blue"><TrendingUp size={16} /></div>
            <div className="stat-value">{stats.fournisseursActifs}</div>
            <div className="stat-label">Actifs (dossiers en cours)</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon amber"><Wallet size={16} /></div>
            <div className="stat-value">{fmtCompact(stats.montantTotalEngage)}</div>
            <div className="stat-label">MAD engages</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon green"><Trophy size={16} /></div>
            <div className="stat-value" style={{ fontSize: 14, lineHeight: 1.3 }}>
              {stats.topFournisseurs[0]?.nom || '\u2014'}
            </div>
            <div className="stat-label">Top fournisseur</div>
          </div>
        </div>
      )}

      {stats && stats.topFournisseurs.length > 0 && !isEmpty && (
        <div className="card" style={{ marginBottom: 12 }}>
          <div className="card-flex" style={{ marginBottom: 10 }}>
            <h2 style={{ marginBottom: 0 }}><Trophy size={12} /> Top 5 par montant engage</h2>
          </div>
          <div className="chart-bar-container">
            {stats.topFournisseurs.map(f => {
              const pct = (f.montantTotalTtc / topMax) * 100
              return (
                <div key={f.nom} className="chart-bar-row">
                  <span className="chart-bar-label" style={{ width: 140 }}>
                    <Link to={`/fournisseurs/${encodeURIComponent(f.nom)}`} style={{ color: 'inherit', textDecoration: 'none' }}>
                      {f.nom}
                    </Link>
                  </span>
                  <div className="chart-bar-track">
                    <div className="chart-bar-fill" style={{ width: `${Math.max(pct, 4)}%`, background: 'var(--accent)' }} />
                  </div>
                  <span className="chart-bar-value" style={{ width: 80 }}>{fmtCompact(f.montantTotalTtc)}</span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      <div className="dossier-filters">
        <div className="dossier-chips" role="group" aria-label="Trier">
          <ArrowUpDown size={12} style={{ color: 'var(--ink-40)', marginRight: 4 }} />
          {([
            { k: 'dossiers', l: 'Nb dossiers' },
            { k: 'montant', l: 'Montant' },
            { k: 'recent', l: 'Plus recent' },
            { k: 'nom', l: 'Nom (A-Z)' },
          ] as Array<{ k: SortKey; l: string }>).map(opt => (
            <button key={opt.k}
              className={`dossier-chip ${sortKey === opt.k ? 'active' : ''}`}
              onClick={() => setSortKey(opt.k)}
              aria-pressed={sortKey === opt.k}
            >
              {opt.l}
            </button>
          ))}
        </div>
        <div className="dossier-filters-right">
          <div className="dossier-search-wrap">
            <Search size={14} className="dossier-search-icon" />
            <input
              className="dossier-search"
              placeholder="Rechercher un fournisseur..."
              value={query}
              onChange={e => setQuery(e.target.value)}
              aria-label="Rechercher un fournisseur"
            />
            {query && (
              <button className="dossier-search-clear" onClick={() => setQuery('')} aria-label="Effacer la recherche">
                <X size={12} />
              </button>
            )}
          </div>
        </div>
      </div>

      {isEmpty ? (
        <div className="dossier-empty">
          <div className="dossier-empty-icon"><Users size={40} /></div>
          <h2>Aucun fournisseur</h2>
          <p>Les fournisseurs apparaissent automatiquement des qu'un dossier de paiement leur est associe.</p>
          <div className="dossier-empty-actions">
            <Link to="/dossiers" className="btn btn-primary">
              <Briefcase size={15} /> Aller aux dossiers
            </Link>
          </div>
        </div>
      ) : !hasResults ? (
        <div className="card" style={{ textAlign: 'center', padding: 32, color: 'var(--ink-40)' }}>
          <Search size={28} style={{ color: 'var(--ink-20)', marginBottom: 8 }} />
          <div style={{ fontWeight: 600, color: 'var(--ink-60)', marginBottom: 4 }}>Aucun resultat</div>
          <div style={{ fontSize: 12 }}>Aucun fournisseur ne correspond a "{debouncedQuery}".</div>
        </div>
      ) : (
        <div className="card">
          <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Fournisseur</th>
                <th>ICE</th>
                <th style={{ textAlign: 'center' }}>Dossiers</th>
                <th>Repartition</th>
                <th>Montant TTC</th>
                <th>Taux valide</th>
                <th>Dernier</th>
                <th aria-label="actions"></th>
              </tr>
            </thead>
            <tbody>
              {sorted!.map(f => {
                const validePct = f.nbDossiers > 0 ? Math.round((f.nbValides / f.nbDossiers) * 100) : 0
                const montantPct = (f.montantTotalTtc / maxMontant) * 100
                return (
                  <tr key={f.nom}>
                    <td>
                      <Link to={`/fournisseurs/${encodeURIComponent(f.nom)}`} style={{ fontWeight: 500 }}>
                        {f.nom}
                      </Link>
                    </td>
                    <td className="cell-mono" style={{ fontSize: 11, color: 'var(--ink-40)' }}>
                      {f.ice || '\u2014'}
                    </td>
                    <td style={{ textAlign: 'center', fontWeight: 600 }}>{f.nbDossiers}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
                        {STATUT_BADGES.map(b => {
                          const n = b.countOf(f)
                          if (n <= 0) return null
                          const cfg = STATUT_CONFIG[b.key]
                          return (
                            <span key={b.key} className="status-badge"
                              style={{ background: cfg.bg, color: cfg.color }} title={cfg.label}>
                              {b.showIcon && <CheckCircle size={10} style={{ display: 'inline', marginRight: 2, verticalAlign: 'middle' }} />}
                              {n}{b.shortLabel ? ` ${b.shortLabel}` : ''}
                            </span>
                          )
                        })}
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 140 }}>
                        <div style={{ flex: 1, height: 6, background: 'var(--ink-05)', borderRadius: 3, overflow: 'hidden' }}>
                          <div style={{ width: `${Math.max(montantPct, 2)}%`, height: '100%', background: 'var(--accent)', transition: 'width 0.4s ease' }} />
                        </div>
                        <span className="cell-mono" style={{ fontSize: 11, whiteSpace: 'nowrap', minWidth: 80, textAlign: 'right' }}>
                          {fmt(f.montantTotalTtc)}
                        </span>
                      </div>
                    </td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <div style={{ width: 32, height: 32, position: 'relative' }}>
                          <svg viewBox="0 0 32 32" style={{ transform: 'rotate(-90deg)' }}>
                            <circle cx="16" cy="16" r={CIRCLE_R} fill="none" stroke="var(--ink-05)" strokeWidth="3" />
                            <circle cx="16" cy="16" r={CIRCLE_R} fill="none" stroke="var(--success)" strokeWidth="3"
                              strokeDasharray={`${(validePct / 100) * CIRCLE_CIRCUMFERENCE} ${CIRCLE_CIRCUMFERENCE}`} strokeLinecap="round" />
                          </svg>
                        </div>
                        <span style={{ fontSize: 11, fontWeight: 600, color: validePct >= 50 ? 'var(--success)' : 'var(--ink-60)' }}>
                          {validePct}%
                        </span>
                      </div>
                    </td>
                    <td className="audit-date">
                      {f.dernierDossier ? new Date(f.dernierDossier).toLocaleDateString('fr-FR') : '\u2014'}
                    </td>
                    <td>
                      <Link to={`/fournisseurs/${encodeURIComponent(f.nom)}`} className="btn btn-secondary btn-sm" aria-label={`Voir le detail de ${f.nom}`}>
                        <ChevronRight size={14} />
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          </div>
        </div>
      )}
    </div>
  )
}
