import { useEffect, useState, useRef } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getDashboardStats, listDossiers, createDossier, uploadDocuments } from '../api/dossierApi'
import type { DossierListItem, DashboardStats } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import {
  BarChart3, FolderOpen, CheckCircle, AlertTriangle, Clock, ArrowRight,
  Shield, TrendingUp, Upload, FileText, Plus, Loader2
} from 'lucide-react'

export default function Dashboard() {
  const { toast } = useToast()
  const navigate = useNavigate()
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [recent, setRecent] = useState<DossierListItem[]>([])
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const ctrl = new AbortController()
    getDashboardStats(ctrl.signal).then(setStats).catch(() => setStats({ total: 0, brouillons: 0, enVerification: 0, valides: 0, rejetes: 0, montantTotal: 0 }))
    listDossiers(0, 5).then(d => setRecent(d.content)).catch(() => {})
    return () => ctrl.abort()
  }, [])

  const handleQuickUpload = async (files: File[]) => {
    if (files.length === 0) return
    setUploading(true)
    try {
      const d = await createDossier('BC', undefined, `Upload rapide - ${files.length} document(s)`)
      await uploadDocuments(d.id, files)
      toast('success', `Dossier ${d.reference} cree avec ${files.length} document(s)`)
      navigate(`/dossiers/${d.id}`)
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setUploading(false) }
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); setDragging(false)
    const files = Array.from(e.dataTransfer.files).filter(f => f.name.toLowerCase().endsWith('.pdf'))
    if (files.length > 0) handleQuickUpload(files)
    else toast('warning', 'Seuls les fichiers PDF sont acceptes')
  }

  if (!stats) return <div className="loading">Chargement...</div>

  const isEmpty = stats.total === 0
  const total = stats.total || 1
  const statutBars = [
    { label: 'Brouillons', value: stats.brouillons, color: 'var(--slate-500)' },
    { label: 'En verification', value: stats.enVerification, color: 'var(--amber-600)' },
    { label: 'Valides', value: stats.valides, color: 'var(--emerald-600)' },
    { label: 'Rejetes', value: stats.rejetes, color: 'var(--red-600)' },
  ]
  const tauxValidation = stats.total > 0 ? Math.round((stats.valides / stats.total) * 100) : 0
  const tauxRejet = stats.total > 0 ? Math.round((stats.rejetes / stats.total) * 100) : 0

  return (
    <div>
      <div className="page-header">
        <h1><BarChart3 size={18} /> Tableau de bord</h1>
        {!isEmpty && (
          <div className="header-actions">
            <Link to="/dossiers" className="btn btn-primary"><Plus size={14} /> Nouveau dossier</Link>
          </div>
        )}
      </div>

      {/* Hero drop zone - always visible, bigger when empty */}
      <div
        className={`hero-drop ${dragging ? 'dragging' : ''}`}
        style={isEmpty ? {} : { padding: '20px 24px', marginBottom: 16 }}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
      >
        <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => {
          const files = Array.from(e.target.files || [])
          if (files.length > 0) handleQuickUpload(files)
        }} />
        {uploading ? (
          <>
            <Loader2 size={isEmpty ? 40 : 20} className="spin" style={{ color: 'var(--teal-600)', marginBottom: isEmpty ? 12 : 0 }} />
            {isEmpty && <div className="hero-drop-title">Creation du dossier en cours...</div>}
          </>
        ) : (
          <>
            <Upload size={isEmpty ? 40 : 18} className="hero-drop-icon" style={isEmpty ? {} : { marginBottom: 0, display: 'inline' }} />
            {isEmpty ? (
              <>
                <div className="hero-drop-title">Deposez vos documents PDF pour creer un dossier</div>
                <div className="hero-drop-hint">Le systeme classifiera automatiquement chaque document et lancera l'extraction</div>
                <div className="hero-drop-formats">
                  <span>PDF</span><span>Facture</span><span>BC</span><span>OP</span><span>Contrat</span>
                </div>
              </>
            ) : (
              <span style={{ fontSize: 12, color: 'var(--slate-500)', marginLeft: 8 }}>
                Deposez des PDFs pour creer un nouveau dossier rapidement
              </span>
            )}
          </>
        )}
      </div>

      {isEmpty ? (
        /* Empty state */
        <div className="card" style={{ textAlign: 'center', padding: '32px 20px' }}>
          <FileText size={32} style={{ color: 'var(--slate-300)', marginBottom: 12 }} />
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--slate-600)', marginBottom: 4 }}>
            Aucun dossier de paiement
          </div>
          <div style={{ fontSize: 12, color: 'var(--slate-400)', marginBottom: 16 }}>
            Deposez des PDFs ci-dessus ou creez un dossier manuellement
          </div>
          <Link to="/dossiers" className="btn btn-primary"><Plus size={14} /> Creer un dossier</Link>
        </div>
      ) : (
        <>
          {/* Stats */}
          <div className="stats-grid">
            <div className="stat-card">
              <div className="stat-icon teal"><FolderOpen size={16} /></div>
              <div className="stat-value">{stats.total}</div>
              <div className="stat-label">Total</div>
            </div>
            <div className="stat-card">
              <div className="stat-icon blue"><Clock size={16} /></div>
              <div className="stat-value">{stats.enVerification + stats.brouillons}</div>
              <div className="stat-label">En cours</div>
            </div>
            <div className="stat-card">
              <div className="stat-icon amber"><AlertTriangle size={16} /></div>
              <div className="stat-value">{stats.rejetes}</div>
              <div className="stat-label">Rejetes</div>
            </div>
            <div className="stat-card">
              <div className="stat-icon green"><CheckCircle size={16} /></div>
              <div className="stat-value">{stats.valides}</div>
              <div className="stat-label">Valides</div>
            </div>
          </div>

          <div className="cards-row" style={{ marginBottom: 12 }}>
            <div className="card">
              <h2><BarChart3 size={12} /> Repartition</h2>
              <div className="chart-bar-container">
                {statutBars.map(bar => (
                  <div key={bar.label} className="chart-bar-row">
                    <span className="chart-bar-label">{bar.label}</span>
                    <div className="chart-bar-track">
                      <div className="chart-bar-fill" style={{ width: `${Math.max((bar.value / total) * 100, bar.value > 0 ? 8 : 0)}%`, background: bar.color }} />
                    </div>
                    <span className="chart-bar-value">{bar.value}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="card">
              <h2><TrendingUp size={12} /> Indicateurs</h2>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
                    <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--slate-500)' }}>Validation</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: 18, fontWeight: 700, color: 'var(--emerald-600)' }}>{tauxValidation}%</span>
                  </div>
                  <div style={{ height: 4, background: 'var(--slate-100)', borderRadius: 2, overflow: 'hidden' }}>
                    <div style={{ height: '100%', width: `${tauxValidation}%`, background: 'var(--emerald-600)', borderRadius: 2, transition: 'width 0.4s' }} />
                  </div>
                </div>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
                    <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--slate-500)' }}>Rejet</span>
                    <span style={{ fontFamily: 'var(--font-mono)', fontSize: 18, fontWeight: 700, color: 'var(--red-600)' }}>{tauxRejet}%</span>
                  </div>
                  <div style={{ height: 4, background: 'var(--slate-100)', borderRadius: 2, overflow: 'hidden' }}>
                    <div style={{ height: '100%', width: `${tauxRejet}%`, background: 'var(--red-600)', borderRadius: 2, transition: 'width 0.4s' }} />
                  </div>
                </div>
                <div style={{ borderTop: '1px solid var(--slate-200)', paddingTop: 12 }}>
                  <div className="stat-label" style={{ marginBottom: 4 }}>Montant total</div>
                  <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--slate-900)', fontFamily: 'var(--font-mono)', letterSpacing: -0.5 }}>
                    {Number(stats.montantTotal).toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
                  </div>
                  <div className="stat-label">MAD</div>
                </div>
              </div>
            </div>
          </div>

          {/* Recent dossiers */}
          <div className="card">
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
              <h2 style={{ marginBottom: 0 }}><FolderOpen size={12} /> Dossiers recents</h2>
              <Link to="/dossiers" style={{ fontSize: 11, fontWeight: 600, color: 'var(--teal-700)', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: 4 }}>
                Voir tout <ArrowRight size={12} />
              </Link>
            </div>
            <table className="data-table">
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
                      <td>{d.fournisseur || '\u2014'}</td>
                      <td><span className="tag">{d.type}</span></td>
                      <td className="cell-mono">{d.montantTtc != null ? Number(d.montantTtc).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) + ' MAD' : '\u2014'}</td>
                      <td><span className="status-badge" style={{ background: c.bg, color: c.color }}>{c.label}</span></td>
                      <td style={{ color: 'var(--slate-500)' }}>{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px' }}>
        <Shield size={14} style={{ color: 'var(--teal-700)', opacity: 0.4 }} />
        <span style={{ fontSize: 11, color: 'var(--slate-400)' }}>
          <strong>ReconDoc MADAEF</strong> — Reconciliation documentaire | Groupe CDG
        </span>
      </div>
    </div>
  )
}
