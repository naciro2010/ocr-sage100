import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getDashboardStats, listDossiers } from '../api/dossierApi'
import type { DossierListItem, DashboardStats } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { BarChart3, FolderOpen, CheckCircle, AlertTriangle, Clock, ArrowRight } from 'lucide-react'

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

      <div className="cards-row">
        <div className="card">
          <h2><FolderOpen size={16} /> Dossiers recents</h2>
          {recent.length === 0 ? <p className="empty-text">Aucun dossier</p> : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {recent.map(d => {
                const cfg = STATUT_CONFIG[d.statut]
                return (
                  <Link key={d.id} to={`/dossiers/${d.id}`} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 12px', borderRadius: 8, textDecoration: 'none' }}>
                    <div>
                      <div style={{ fontWeight: 700, color: '#0f172a', fontSize: 13 }}>{d.reference}</div>
                      <div style={{ fontSize: 12, color: '#64748b' }}>{d.fournisseur || 'Sans fournisseur'}</div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <span className="status-badge" style={{ backgroundColor: cfg.color + '20', color: cfg.color, borderColor: cfg.color }}>{cfg.label}</span>
                      <ArrowRight size={14} style={{ color: '#94a3b8' }} />
                    </div>
                  </Link>
                )
              })}
            </div>
          )}
        </div>

        <div className="card">
          <h2><BarChart3 size={16} /> Montants</h2>
          <div style={{ marginTop: 8 }}>
            <div style={{ fontSize: 30, fontWeight: 800, color: '#0f172a' }}>
              {Number(stats.montantTotal).toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
            </div>
            <div className="stat-label" style={{ marginTop: 4 }}>MAD total en dossiers</div>
          </div>
        </div>
      </div>
    </div>
  )
}
