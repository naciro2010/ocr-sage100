import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Briefcase, Plus, Search, X, ChevronRight, Wallet, ScrollText, Package, FileText,
} from 'lucide-react'
import { listEngagements, getEngagementStats } from '../api/engagementApi'
import type { EngagementListItem, EngagementStats, StatutEngagement, TypeEngagement } from '../api/engagementTypes'
import {
  TYPE_CONFIG, STATUT_ENG_CONFIG, TYPE_OPTIONS, STATUT_OPTIONS,
  fmtMad, fmtCompact, consumptionColor,
} from '../api/engagementTypes'

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
    return filterType === 'ALL' ? items : items.filter(e => e.type === filterType)
  }, [items, filterType])

  if (!filtered) return <Skeleton />

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

      {stats && !isEmpty && <StatsCards stats={stats} />}

      <div className="dossier-filters">
        <FilterChips label="type" value={filterType}
          options={[{ k: 'ALL', l: 'Tous les types' }, ...TYPE_OPTIONS.map(t => ({ k: t, l: TYPE_CONFIG[t].shortLabel }))]}
          onChange={v => setFilterType(v as TypeEngagement | 'ALL')} />
        <FilterChips label="statut" value={filterStatut}
          options={[{ k: 'ALL', l: 'Tous les statuts' }, ...STATUT_OPTIONS.map(s => ({ k: s, l: STATUT_ENG_CONFIG[s].label }))]}
          onChange={v => setFilterStatut(v as StatutEngagement | 'ALL')} />
        <div className="dossier-filters-right">
          <div className="dossier-search-wrap">
            <Search size={14} className="dossier-search-icon" />
            <input className="dossier-search" placeholder="Rechercher par reference..."
              value={query} onChange={e => setQuery(e.target.value)}
              aria-label="Rechercher un engagement" />
            {query && (
              <button className="dossier-search-clear" onClick={() => setQuery('')} aria-label="Effacer">
                <X size={12} />
              </button>
            )}
          </div>
        </div>
      </div>

      {isEmpty ? <EmptyState /> :
        !hasResults ? <NoResults /> :
          <EngagementTable items={filtered} />}
    </div>
  )
}

function Skeleton() {
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

function StatsCards({ stats }: { stats: EngagementStats }) {
  return (
    <div className="stats-grid">
      <StatCard icon={<ScrollText size={16} />} colorClass="teal" value={stats.nbMarches} label="Marches publics" />
      <StatCard icon={<Package size={16} />} colorClass="blue" value={stats.nbBonsCommande} label="BC cadres" />
      <StatCard icon={<FileText size={16} />} colorClass="green" value={stats.nbContrats} label="Contrats" />
      <StatCard icon={<Wallet size={16} />} colorClass="amber" value={fmtCompact(stats.montantTotalTtc)}
        label={`MAD engages (${fmtCompact(stats.montantTotalConsomme)} consommes)`} />
    </div>
  )
}

function StatCard({ icon, colorClass, value, label }: { icon: React.ReactNode; colorClass: string; value: string | number; label: string }) {
  return (
    <div className="stat-card">
      <div className={`stat-icon ${colorClass}`}>{icon}</div>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  )
}

function FilterChips<T extends string>({ value, options, onChange }: {
  label: string
  value: T
  options: Array<{ k: T; l: string }>
  onChange: (v: T) => void
}) {
  return (
    <div className="dossier-chips">
      {options.map(opt => (
        <button key={opt.k} type="button"
          className={`dossier-chip ${value === opt.k ? 'active' : ''}`}
          onClick={() => onChange(opt.k)}
          aria-pressed={value === opt.k}>
          {opt.l}
        </button>
      ))}
    </div>
  )
}

function EmptyState() {
  return (
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
  )
}

function NoResults() {
  return (
    <div className="card" style={{ textAlign: 'center', padding: 32, color: 'var(--ink-40)' }}>
      <Search size={28} style={{ color: 'var(--ink-20)', marginBottom: 8 }} />
      <div style={{ fontWeight: 600, color: 'var(--ink-60)', marginBottom: 4 }}>Aucun resultat</div>
      <div style={{ fontSize: 12 }}>Aucun engagement ne correspond aux filtres.</div>
    </div>
  )
}

function EngagementTable({ items }: { items: EngagementListItem[] }) {
  return (
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
          {items.map(e => <EngagementRow key={e.id} e={e} />)}
        </tbody>
      </table>
    </div>
  )
}

function EngagementRow({ e }: { e: EngagementListItem }) {
  const tc = TYPE_CONFIG[e.type]
  const sc = STATUT_ENG_CONFIG[e.statut]
  const TypeIcon = tc.icon
  const StatIcon = sc.icon
  const taux = e.tauxConsommation || 0
  const tauxColor = consumptionColor(taux)

  return (
    <tr>
      <td>
        <span className="status-badge" style={{ background: tc.bg, color: tc.color, gap: 4 }}>
          <TypeIcon size={11} /> {tc.shortLabel}
        </span>
      </td>
      <td className="cell-mono" style={{ fontWeight: 500 }}>
        <Link to={`/engagements/${e.id}`} style={{ color: 'inherit' }}>{e.reference}</Link>
      </td>
      <td style={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={e.objet || ''}>
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
        {fmtMad(e.montantTtc)}
      </td>
      <td>
        <span className="status-badge" style={{ background: sc.bg, color: sc.color, gap: 4 }}>
          <StatIcon size={10} /> {sc.label}
        </span>
      </td>
      <td>
        <Link to={`/engagements/${e.id}`} className="btn btn-secondary btn-sm" aria-label={`Detail ${e.reference}`}>
          <ChevronRight size={14} />
        </Link>
      </td>
    </tr>
  )
}
