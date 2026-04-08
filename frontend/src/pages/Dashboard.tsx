import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listDossiers } from '../api/dossierApi'
import type { DossierListItem, PageResponse } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { BarChart3, FolderOpen, CheckCircle, AlertTriangle, Clock, ArrowRight } from 'lucide-react'

export default function Dashboard() {
  const [data, setData] = useState<PageResponse<DossierListItem> | null>(null)

  useEffect(() => { listDossiers(0, 100).then(setData).catch(() => {}) }, [])

  if (!data) return <div className="loading">Chargement...</div>

  const all = data.content
  const valides = all.filter(d => d.statut === 'VALIDE').length
  const enVerif = all.filter(d => d.statut === 'EN_VERIFICATION').length
  const rejetes = all.filter(d => d.statut === 'REJETE').length
  const brouillons = all.filter(d => d.statut === 'BROUILLON').length
  const totalMontant = all.reduce((s, d) => s + (d.montantTtc || 0), 0)
  const recent = all.slice(0, 5)

  return (
    <div>
      <div className="page-header">
        <h1><BarChart3 size={24} /> Tableau de bord</h1>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon purple"><FolderOpen size={20} /></div>
          <div className="stat-value">{all.length}</div>
          <div className="stat-label">Total dossiers</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Clock size={20} /></div>
          <div className="stat-value">{enVerif + brouillons}</div>
          <div className="stat-label">En cours</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><AlertTriangle size={20} /></div>
          <div className="stat-value">{rejetes}</div>
          <div className="stat-label">Rejetes</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><CheckCircle size={20} /></div>
          <div className="stat-value">{valides}</div>
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
              {totalMontant.toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
            </div>
            <div className="stat-label" style={{ marginTop: 4 }}>MAD total en dossiers</div>
          </div>
        </div>
      </div>
    </div>
  )
}
