import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getDashboardStats, listDossiers } from '../api/dossierApi'
import type { DossierListItem, DashboardStats } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { BarChart3, FolderOpen, CheckCircle, AlertTriangle, Clock, ArrowRight, Shield, TrendingUp } from 'lucide-react'

export default function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [recent, setRecent] = useState<DossierListItem[]>([])

  useEffect(() => {
    const ctrl = new AbortController()
    getDashboardStats(ctrl.signal).then(setStats).catch(() => {})
    listDossiers(0, 5).then(d => setRecent(d.content)).catch(() => {})
    return () => ctrl.abort()
  }, [])

  if (!stats) return <div className="loading">Chargement...</div>

  const total = stats.total || 1
  const statutBars = [
    { label: 'Brouillons', value: stats.brouillons, color: '#6b7280' },
    { label: 'En verification', value: stats.enVerification, color: '#f59e0b' },
    { label: 'Valides', value: stats.valides, color: '#10b981' },
    { label: 'Rejetes', value: stats.rejetes, color: '#ef4444' },
  ]

  const tauxValidation = stats.total > 0 ? Math.round((stats.valides / stats.total) * 100) : 0
  const tauxRejet = stats.total > 0 ? Math.round((stats.rejetes / stats.total) * 100) : 0

  return (
    <div>
      <div className="page-header">
        <h1><BarChart3 size={24} /> Tableau de bord</h1>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon purple"><FolderOpen size={20} /></div>
          <div className="stat-value">{stats.total}</div>
          <div className="stat-label">Total dossiers</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Clock size={20} /></div>
          <div className="stat-value">{stats.enVerification + stats.brouillons}</div>
          <div className="stat-label">En cours</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><AlertTriangle size={20} /></div>
          <div className="stat-value">{stats.rejetes}</div>
          <div className="stat-label">Rejetes</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><CheckCircle size={20} /></div>
          <div className="stat-value">{stats.valides}</div>
          <div className="stat-label">Valides</div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 }}>
        {/* Repartition par statut */}
        <div className="card">
          <h2><BarChart3 size={16} /> Repartition par statut</h2>
          <div className="chart-bar-container">
            {statutBars.map(bar => (
              <div key={bar.label} className="chart-bar-row">
                <span className="chart-bar-label">{bar.label}</span>
                <div className="chart-bar-track">
                  <div
                    className="chart-bar-fill"
                    style={{
                      width: `${Math.max((bar.value / total) * 100, bar.value > 0 ? 8 : 0)}%`,
                      background: bar.color,
                    }}
                  />
                </div>
                <span className="chart-bar-value">{bar.value}</span>
              </div>
            ))}
          </div>
        </div>

        {/* KPIs */}
        <div className="card">
          <h2><TrendingUp size={16} /> Indicateurs cles</h2>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 20, marginTop: 8 }}>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-muted)' }}>Taux de validation</span>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 20, fontWeight: 700, color: '#10b981' }}>{tauxValidation}%</span>
              </div>
              <div style={{ height: 6, background: 'var(--surface)', borderRadius: 3, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${tauxValidation}%`, background: '#10b981', borderRadius: 3, transition: 'width 0.5s' }} />
              </div>
            </div>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
                <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink-muted)' }}>Taux de rejet</span>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 20, fontWeight: 700, color: '#ef4444' }}>{tauxRejet}%</span>
              </div>
              <div style={{ height: 6, background: 'var(--surface)', borderRadius: 3, overflow: 'hidden' }}>
                <div style={{ height: '100%', width: `${tauxRejet}%`, background: '#ef4444', borderRadius: 3, transition: 'width 0.5s' }} />
              </div>
            </div>
            <div style={{ borderTop: '1px solid var(--border)', paddingTop: 16 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-faint)', textTransform: 'uppercase', letterSpacing: 0.8, marginBottom: 8 }}>Montant total</div>
              <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--ink)', fontFamily: 'var(--mono)', letterSpacing: -1 }}>
                {Number(stats.montantTotal).toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
              </div>
              <div className="stat-label" style={{ marginTop: 4 }}>MAD</div>
            </div>
          </div>
        </div>
      </div>

      {/* Dossiers recents */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <h2 style={{ marginBottom: 0 }}><FolderOpen size={16} /> Dossiers recents</h2>
          <Link to="/dossiers" style={{ fontSize: 12, fontWeight: 600, color: 'var(--accent)', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: 4 }}>
            Voir tout <ArrowRight size={14} />
          </Link>
        </div>
        {recent.length === 0 ? <p className="empty-text">Aucun dossier</p> : (
          <table className="invoice-table">
            <thead>
              <tr>
                <th>Reference</th>
                <th>Fournisseur</th>
                <th>Type</th>
                <th>Montant TTC</th>
                <th>Statut</th>
                <th>Date</th>
              </tr>
            </thead>
            <tbody>
              {recent.map(d => {
                const c = STATUT_CONFIG[d.statut]
                return (
                  <tr key={d.id}>
                    <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                    <td>{d.fournisseur || '—'}</td>
                    <td><span className="preprocess-tag">{d.type}</span></td>
                    <td className="cell-amount">{d.montantTtc != null ? Number(d.montantTtc).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) + ' MAD' : '—'}</td>
                    <td><span className="status-badge" style={{ backgroundColor: c.color + '20', color: c.color, borderColor: c.color }}>{c.label}</span></td>
                    <td>{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* About */}
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '16px 24px' }}>
        <Shield size={18} style={{ color: 'var(--accent)', opacity: 0.5 }} />
        <span style={{ fontSize: 12, color: 'var(--ink-muted)' }}>
          <strong>ReconDoc MADAEF</strong> — Reconciliation documentaire des dossiers de paiement | Groupe CDG
        </span>
      </div>
    </div>
  )
}
