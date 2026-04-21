import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Briefcase, Plus, Search, X, FileText, Package, ScrollText,
  CheckCircle, Clock, XCircle, ChevronRight, TrendingUp, Wallet
} from 'lucide-react'
import { listEngagements, getEngagementStats } from '../api/engagementApi'
import type { EngagementListItem, EngagementStats, StatutEngagement, TypeEngagement } from '../api/engagementTypes'
import { TYPE_CONFIG, STATUT_ENG_CONFIG } from '../api/engagementTypes'

const TYPE_ICON: Record<TypeEngagement, typeof FileText> = {
  MARCHE: ScrollText,
  BON_COMMANDE: Package,
  CONTRAT: FileText,
}

const STATUT_ICON: Record<StatutEngagement, typeof CheckCircle> = {
  ACTIF: CheckCircle,
  CLOTURE: XCircle,
  SUSPENDU: Clock,
}

function fmt(n: number | null | undefined): string {
  if (n == null) return '0,00'
  return n.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtCompact(n: number | null | undefined): string {
  if (n == null || n === 0) return '0'
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)} M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)} k`
  return String(Math.round(n))
}

export default function EngagementList() {
  const [items, setItems] = useState<EngagementListItem[] | null>(null)
  const [stats, setStats] = useState<EngagementStats | null>(null)
  const [query, setQuery] = useState('')
  const [filterType, setFilterType] = useState<TypeEngagement | 'ALL'>('ALL')
  const [filterStatut, setFilterStatut] = useState<StatutEngagement | 'ALL'>('ALL')
  const [error, setError] = useState('')

  useEffect(() => {
    const ctrl = new AbortController()
    listEngagements({
      statut: filterStatut === 'ALL' ? undefined : filterStatut,
      reference: query || undefined,
      size: 100,
    }, ctrl.signal)
      .then(res => { if (!ctrl.signal.aborted) setItems(res.content) })
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
        setItems([])
      })
    return () => ctrl.abort()
  }, [query, filterStatut])

  useEffect(() => {
    const ctrl = new AbortController()
    getEngagementStats(ctrl.signal)
      .then(res => { if (!ctrl.signal.aborted) setStats(res) })
      .catch(() => { /* ignore */ })
    return () => ctrl.abort()
  }, [])

  const filtered = useMemo(() => {
    if (!items) return null
    if (filterType === 'ALL') return items
    return items.filter(e => e.type === filterType)
  }, [items, filterType])

  if (!filtered) {
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

  const isEmpty = filtered.length === 0 && !query && filterType === 'ALL' && filterStatut === 'ALL'
  const hasResults = filtered.length > 0

  return (
    <div>
      <div className="page-header">
        <h1><Briefcase size={18} /> Engagements</h1>
        <div className="header-actions">
          <Link to="/engagements/nouveau" className="btn btn-primary btn-sm">
            <Plus size={14} /> Nouvel engagement
          </Link>
        </div>
      </div>

      {error && <div className="alert alert-error mb-3">{error}</div>}

      {stats && !isEmpty && (
        <div className="stats-grid">
          <div className="stat-card">
            <div className="stat-icon teal"><ScrollText size={16} /></div>
            <div className="stat-value">{stats.nbMarches}</div>
            <div className="stat-label">Marches publics</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon blue"><Package size={16} /></div>
            <div className="stat-value">{stats.nbBonsCommande}</div>
            <div className="stat-label">BC cadres</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon green"><FileText size={16} /></div>
            <div className="stat-value">{stats.nbContrats}</div>
            <div className="stat-label">Contrats</div>
          </div>
          <div className="stat-card">
            <div className="stat-icon amber"><Wallet size={16} /></div>
            <div className="stat-value">{fmtCompact(stats.montantTotalTtc)}</div>
            <div className="stat-label">MAD engages ({fmtCompact(stats.montantTotalConsomme)} consommes)</div>
          </div>
        </div>
      )}

      <div className="dossier-filters">
        <div className="dossier-chips" role="group" aria-label="Filtrer par type">
          <button className={`dossier-chip ${filterType === 'ALL' ? 'active' : ''}`} onClick={() => setFilterType('ALL')}>
            Tous les types
          </button>
          {(['MARCHE', 'BON_COMMANDE', 'CONTRAT'] as TypeEngagement[]).map(t => (
            <button key={t}
              className={`dossier-chip ${filterType === t ? 'active' : ''}`}
              onClick={() => setFilterType(t)}
              aria-pressed={filterType === t}
            >
              {TYPE_CONFIG[t].shortLabel}
            </button>
          ))}
        </div>
        <div className="dossier-chips" role="group" aria-label="Filtrer par statut">
          <button className={`dossier-chip ${filterStatut === 'ALL' ? 'active' : ''}`} onClick={() => setFilterStatut('ALL')}>
            Tous les statuts
          </button>
          {(['ACTIF', 'SUSPENDU', 'CLOTURE'] as StatutEngagement[]).map(s => (
            <button key={s}
              className={`dossier-chip ${filterStatut === s ? 'active' : ''}`}
              onClick={() => setFilterStatut(s)}
              aria-pressed={filterStatut === s}
            >
              {STATUT_ENG_CONFIG[s].label}
            </button>
          ))}
        </div>
        <div className="dossier-filters-right">
          <div className="dossier-search-wrap">
            <Search size={14} className="dossier-search-icon" />
            <input
              className="dossier-search"
              placeholder="Rechercher par reference..."
              value={query}
              onChange={e => setQuery(e.target.value)}
              aria-label="Rechercher un engagement"
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
          <div className="dossier-empty-icon"><Briefcase size={40} /></div>
          <h2>Aucun engagement</h2>
          <p>Les engagements (Marches, BC cadres, Contrats) regroupent plusieurs dossiers de paiement lies a un meme contrat juridique.</p>
          <div className="dossier-empty-actions">
            <Link to="/engagements/nouveau" className="btn btn-primary">
              <Plus size={15} /> Creer un engagement
            </Link>
          </div>
        </div>
      ) : !hasResults ? (
        <div className="card" style={{ textAlign: 'center', padding: 32, color: 'var(--ink-40)' }}>
          <Search size={28} style={{ color: 'var(--ink-20)', marginBottom: 8 }} />
          <div style={{ fontWeight: 600, color: 'var(--ink-60)', marginBottom: 4 }}>Aucun resultat</div>
          <div style={{ fontSize: 12 }}>Aucun engagement ne correspond aux filtres.</div>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Reference</th>
                <th>Objet</th>
                <th>Fournisseur</th>
                <th style={{ textAlign: 'center' }}>Dossiers</th>
                <th>Consommation</th>
                <th>Montant TTC</th>
                <th>Statut</th>
                <th aria-label="actions"></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(e => {
                const TypeIcon = TYPE_ICON[e.type]
                const StatIcon = STATUT_ICON[e.statut]
                const tc = TYPE_CONFIG[e.type]
                const sc = STATUT_ENG_CONFIG[e.statut]
                const taux = e.tauxConsommation || 0
                const tauxColor = taux > 95 ? 'var(--danger)' : taux > 80 ? 'var(--warning)' : 'var(--success)'
                return (
                  <tr key={e.id}>
                    <td>
                      <span className="status-badge" style={{ background: tc.bg, color: tc.color, gap: 4 }}>
                        <TypeIcon size={11} style={{ verticalAlign: 'middle' }} />
                        {tc.shortLabel}
                      </span>
                    </td>
                    <td className="cell-mono" style={{ fontWeight: 500 }}>
                      <Link to={`/engagements/${e.id}`} style={{ color: 'inherit' }}>{e.reference}</Link>
                    </td>
                    <td style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                        title={e.objet || ''}>
                      {e.objet || <span style={{ color: 'var(--ink-30)' }}>&mdash;</span>}
                    </td>
                    <td>{e.fournisseur || <span style={{ color: 'var(--ink-30)' }}>&mdash;</span>}</td>
                    <td style={{ textAlign: 'center', fontWeight: 600 }}>{e.nbDossiers}</td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 120 }}>
                        <div style={{ flex: 1, height: 6, background: 'var(--ink-05)', borderRadius: 3, overflow: 'hidden' }}>
                          <div style={{ width: `${Math.min(taux, 100)}%`, height: '100%', background: tauxColor, transition: 'width 0.4s ease' }} />
                        </div>
                        <span className="cell-mono" style={{ fontSize: 11, minWidth: 44, textAlign: 'right', color: tauxColor }}>
                          {taux.toFixed(0)}%
                        </span>
                      </div>
                    </td>
                    <td className="cell-mono" style={{ fontSize: 11, textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {fmt(e.montantTtc)}
                    </td>
                    <td>
                      <span className="status-badge" style={{ background: sc.bg, color: sc.color, gap: 4 }}>
                        <StatIcon size={10} style={{ verticalAlign: 'middle' }} />
                        {sc.label}
                      </span>
                    </td>
                    <td>
                      <Link to={`/engagements/${e.id}`} className="btn btn-secondary btn-sm" aria-label={`Detail ${e.reference}`}>
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
