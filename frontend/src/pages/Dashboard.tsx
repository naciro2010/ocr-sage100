import { useEffect, useState, useRef } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { getDashboardStats, listDossiers, createDossier, uploadDocuments } from '../api/dossierApi'
import type { DossierListItem, DashboardStats } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import { getDossierSnapshot } from '../api/dossierApi'
import * as Pages from '../routes/lazyPages'
import {
  BarChart3, FolderOpen, CheckCircle, AlertTriangle, Clock, ArrowRight,
  Shield, TrendingUp, Upload, FileText, Plus, Loader2
} from 'lucide-react'

function DashboardSkeleton() {
  return (
    <div className="skeleton">
      <div className="skeleton-bar h-lg w-40" />
      <div className="skeleton-card" style={{ height: 120, marginBottom: 16 }} />
      <div className="skeleton-grid">
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
      </div>
      <div className="skeleton-card" style={{ height: 200 }} />
    </div>
  )
}

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
    // Les deux blocs sont independants : ils ne se bloquent pas l'un l'autre.
    // Le squelette ne s'affiche que si stats ET recents sont absents : si le
    // backend renvoie une stats lente, l'utilisateur voit deja sa liste.
    getDashboardStats(ctrl.signal)
      .then(setStats)
      .catch(() => { if (!ctrl.signal.aborted) setStats({ total: 0, brouillons: 0, enVerification: 0, valides: 0, rejetes: 0, montantTotal: 0 }) })
    listDossiers(0, 5, ctrl.signal)
      .then(d => { if (!ctrl.signal.aborted) setRecent(d.content) })
      .catch(() => {})
    // Prefetch des routes probables a partir du dashboard pendant l'idle :
    // l'utilisateur clique presque toujours sur "Dossiers" ensuite.
    Pages.DossierList.preload()
    Pages.DossierDetail.preload()
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

  if (!stats) return <DashboardSkeleton />

  const isEmpty = stats.total === 0
  const total = stats.total || 1
  const statutBars = [
    { label: 'Brouillons', value: stats.brouillons, color: 'var(--ink-50)' },
    { label: 'En verification', value: stats.enVerification, color: 'var(--warning)' },
    { label: 'Valides', value: stats.valides, color: 'var(--success)' },
    { label: 'Rejetes', value: stats.rejetes, color: 'var(--danger)' },
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

      <div
        className={`hero-drop ${dragging ? 'dragging' : ''}`}
        style={isEmpty ? {} : { padding: '20px 24px', marginBottom: 16 }}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
        aria-label="Deposer des fichiers PDF pour creer un dossier"
        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click() }}
      >
        <input ref={inputRef} type="file" accept=".pdf" multiple hidden onChange={e => {
          const files = Array.from(e.target.files || [])
          if (files.length > 0) handleQuickUpload(files)
        }} />
        {uploading ? (
          <>
            <Loader2 size={isEmpty ? 40 : 20} className="spin" style={{ color: 'var(--accent)', marginBottom: isEmpty ? 12 : 0 }} />
            {isEmpty && <div className="hero-drop-title">Creation du dossier en cours...</div>}
          </>
        ) : (
          <>
            <Upload size={isEmpty ? 40 : 18} className="hero-drop-icon" style={isEmpty ? {} : { marginBottom: 0, display: 'inline' }} aria-hidden="true" />
            {isEmpty ? (
              <>
                <div className="hero-drop-title">Deposez vos documents PDF pour creer un dossier</div>
                <div className="hero-drop-hint">Le systeme classifiera automatiquement chaque document et lancera l'extraction</div>
                <div className="hero-drop-formats">
                  <span>PDF</span><span>Facture</span><span>BC</span><span>OP</span><span>Contrat</span>
                </div>
              </>
            ) : (
              <span className="inline-hint">
                Deposez des PDFs pour creer un nouveau dossier rapidement
              </span>
            )}
          </>
        )}
      </div>

      {isEmpty ? (
        <div className="card" style={{ textAlign: 'center', padding: '32px 20px' }}>
          <FileText size={32} style={{ color: 'var(--ink-20)', marginBottom: 12 }} aria-hidden="true" />
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink-60)', marginBottom: 4 }}>
            Aucun dossier de paiement
          </div>
          <div style={{ fontSize: 12, color: 'var(--ink-40)', marginBottom: 16 }}>
            Deposez des PDFs ci-dessus ou creez un dossier manuellement
          </div>
          <Link to="/dossiers" className="btn btn-primary"><Plus size={14} /> Creer un dossier</Link>
        </div>
      ) : (
        <>
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
              <div className="indicator-group">
                <div>
                  <div className="indicator-row">
                    <span className="indicator-label">Validation</span>
                    <span className="indicator-value success">{tauxValidation}%</span>
                  </div>
                  <div className="indicator-track">
                    <div className="indicator-fill success" style={{ width: `${tauxValidation}%` }} />
                  </div>
                </div>
                <div>
                  <div className="indicator-row">
                    <span className="indicator-label">Rejet</span>
                    <span className="indicator-value danger">{tauxRejet}%</span>
                  </div>
                  <div className="indicator-track">
                    <div className="indicator-fill danger" style={{ width: `${tauxRejet}%` }} />
                  </div>
                </div>
                <div className="indicator-divider">
                  <div className="stat-label" style={{ marginBottom: 4 }}>Montant total</div>
                  <div className="indicator-amount">
                    {Number(stats.montantTotal).toLocaleString('fr-FR', { minimumFractionDigits: 2 })}
                  </div>
                  <div className="stat-label">MAD</div>
                </div>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="card-flex" style={{ marginBottom: 10 }}>
              <h2 style={{ marginBottom: 0 }}><FolderOpen size={12} /> Dossiers recents</h2>
              <Link to="/dossiers" style={{ fontSize: 11, fontWeight: 600, color: 'var(--accent-deep)', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: 4 }}>
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
                  // Au survol d'une ligne : on charge le bundle JS du detail
                  // ET le snapshot data en parallele -> au clic, la page
                  // s'affiche instantanement (cache hit cote dossierApi).
                  const prefetch = () => {
                    Pages.DossierDetail.preload()
                    void getDossierSnapshot(d.id).catch(() => {})
                  }
                  return (
                    <tr key={d.id} onMouseEnter={prefetch} onFocus={prefetch}>
                      <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                      <td>{d.fournisseur || '\u2014'}</td>
                      <td><span className="tag">{d.type}</span></td>
                      <td className="cell-mono">{d.montantTtc != null ? Number(d.montantTtc).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) + ' MAD' : '\u2014'}</td>
                      <td><span className="status-badge" style={{ background: c.bg, color: c.color }}>{c.label}</span></td>
                      <td style={{ color: 'var(--ink-50)' }}>{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px' }}>
        <Shield size={14} style={{ color: 'var(--accent-deep)', opacity: 0.4 }} aria-hidden="true" />
        <span style={{ fontSize: 11, color: 'var(--ink-40)' }}>
          <strong>ReconDoc MADAEF</strong> — Reconciliation documentaire | Groupe CDG
        </span>
      </div>
    </div>
  )
}
