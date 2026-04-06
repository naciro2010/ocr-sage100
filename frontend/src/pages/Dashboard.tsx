import { useEffect, useState } from 'react'
import { getDashboard } from '../api/client'
import type { DashboardStats } from '../api/types'
import { BarChart3, RefreshCw, AlertCircle, FileText, CheckCircle2, Clock, Banknote } from 'lucide-react'

const STATUS_LABELS: Record<string, string> = {
  UPLOADED: 'Uploadee',
  OCR_IN_PROGRESS: 'OCR en cours',
  OCR_COMPLETED: 'OCR termine',
  AI_EXTRACTION_IN_PROGRESS: 'Extraction IA',
  EXTRACTED: 'Extraite',
  VALIDATION_FAILED: 'Validation echouee',
  READY_FOR_SAGE: 'Prete Sage',
  SAGE_SYNCED: 'Synchronisee',
  SAGE_SYNC_FAILED: 'Sync echouee',
  ERROR: 'Erreur',
}

const STATUS_COLORS: Record<string, string> = {
  UPLOADED: '#7a7a7a',
  OCR_IN_PROGRESS: '#d4940a',
  OCR_COMPLETED: '#4a6fa5',
  AI_EXTRACTION_IN_PROGRESS: '#d4940a',
  EXTRACTED: '#7c5cbf',
  VALIDATION_FAILED: '#d94f4f',
  READY_FOR_SAGE: '#10a37f',
  SAGE_SYNCED: '#0d8c6c',
  SAGE_SYNC_FAILED: '#d94f4f',
  ERROR: '#c04040',
}

export default function Dashboard() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    getDashboard().then(setStats).catch(e => setError(e.message))
  }

  useEffect(load, [])

  if (error) {
    return (
      <div className="card error-card">
        <AlertCircle size={18} />
        <span>Erreur : {error}</span>
      </div>
    )
  }

  if (!stats) return <div className="loading">Chargement...</div>

  return (
    <div>
      <div className="page-header">
        <h1><BarChart3 size={22} /> Tableau de bord</h1>
        <button className="btn btn-secondary" onClick={load}>
          <RefreshCw size={14} /> Rafraichir
        </button>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon purple"><FileText size={18} /></div>
          <div className="stat-value">{stats.totalInvoices}</div>
          <div className="stat-label">Total factures</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><CheckCircle2 size={18} /></div>
          <div className="stat-value">{stats.sageSynced}</div>
          <div className="stat-label">Synchronisees Sage</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><Clock size={18} /></div>
          <div className="stat-value">{stats.pendingSync}</div>
          <div className="stat-label">En attente de sync</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Banknote size={18} /></div>
          <div className="stat-value">
            {stats.totalProcessedAmount.toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
          </div>
          <div className="stat-label">Montant total (MAD)</div>
        </div>
      </div>

      <div className="cards-row">
        <div className="card">
          <h2>Par statut</h2>
          <div className="status-list">
            {Object.entries(stats.byStatus)
              .filter(([, count]) => count > 0)
              .map(([status, count]) => (
                <div key={status} className="status-item">
                  <span className="status-dot" style={{ backgroundColor: STATUS_COLORS[status] || '#7a7a7a' }} />
                  <span className="status-name">{STATUS_LABELS[status] || status}</span>
                  <span className="status-count">{count}</span>
                </div>
              ))}
            {Object.values(stats.byStatus).every(c => c === 0) && (
              <p className="empty-text">Aucune facture</p>
            )}
          </div>
        </div>

        <div className="card">
          <h2>Top fournisseurs</h2>
          <div className="status-list">
            {Object.entries(stats.topSuppliers).map(([name, count]) => (
              <div key={name} className="status-item">
                <span className="status-name">{name}</span>
                <span className="status-count">{count}</span>
              </div>
            ))}
            {Object.keys(stats.topSuppliers).length === 0 && (
              <p className="empty-text">Aucun fournisseur</p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
