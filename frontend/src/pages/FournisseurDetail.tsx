import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getFournisseurDetail } from '../api/fournisseurApi'
import type { FournisseurDetail } from '../api/fournisseurTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import {
  ArrowLeft, Building2, FileText, CheckCircle, AlertTriangle, Clock,
  Wallet, Calendar, Hash, CreditCard, FolderOpen, TrendingUp, Receipt
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'

function fmt(n: number | null | undefined): string {
  if (n == null) return '0,00'
  return n.toLocaleString('fr-FR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const PLACEHOLDER = <span style={{ color: 'var(--ink-30)' }}>Non renseigne</span>

export default function FournisseurDetail() {
  const { nom } = useParams<{ nom: string }>()
  const [data, setData] = useState<FournisseurDetail | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!nom) return
    const ctrl = new AbortController()
    getFournisseurDetail(decodeURIComponent(nom), ctrl.signal)
      .then(res => { if (!ctrl.signal.aborted) setData(res) })
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
      })
    return () => ctrl.abort()
  }, [nom])

  if (error) {
    return (
      <div>
        <div style={{ marginBottom: 12 }}>
          <Link to="/fournisseurs" className="btn btn-secondary btn-sm">
            <ArrowLeft size={14} /> Retour
          </Link>
        </div>
        <div className="alert alert-error">{error}</div>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="skeleton">
        <div className="skeleton-line h-lg w-40" />
        <div className="skeleton-card" style={{ height: 120, marginBottom: 16 }} />
        <div className="skeleton-grid">
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
          <div className="skeleton-grid-item" />
        </div>
        <div className="skeleton-card" style={{ height: 240 }} />
      </div>
    )
  }

  const tauxValidation = data.nbDossiers > 0 ? Math.round((data.nbValides / data.nbDossiers) * 100) : 0
  const tauxRejet = data.nbDossiers > 0 ? Math.round((data.nbRejetes / data.nbDossiers) * 100) : 0
  const total = data.nbDossiers || 1

  const statutBars: Array<{ label: string; value: number; color: string }> = [
    { label: STATUT_CONFIG.BROUILLON.label, value: data.nbBrouillons, color: 'var(--ink-50)' },
    { label: STATUT_CONFIG.EN_VERIFICATION.label, value: data.nbEnVerification, color: STATUT_CONFIG.EN_VERIFICATION.color },
    { label: STATUT_CONFIG.VALIDE.label, value: data.nbValides, color: STATUT_CONFIG.VALIDE.color },
    { label: STATUT_CONFIG.REJETE.label, value: data.nbRejetes, color: STATUT_CONFIG.REJETE.color },
  ]

  const identityFields: Array<{ icon: LucideIcon; label: string; value: string | null; mono?: number }> = [
    { icon: Hash, label: 'ICE', value: data.ice },
    { icon: Hash, label: 'Identifiant Fiscal', value: data.identifiantFiscal },
    { icon: Hash, label: 'RC', value: data.rc },
    { icon: CreditCard, label: 'RIB', value: data.rib, mono: 12 },
  ]

  const bilanBoxes: Array<{ label: string; value: number; tone?: 'success' | 'warning' | 'neutral' }> = [
    { label: 'Montant HT', value: data.montantTotalHt, tone: 'neutral' },
    { label: 'Montant TVA', value: data.montantTotalTva, tone: 'neutral' },
    { label: 'Valide', value: data.montantValide, tone: 'success' },
    { label: 'En cours', value: data.montantEnCours, tone: 'warning' },
  ]
  const boxStyle = (tone?: 'success' | 'warning' | 'neutral') => {
    if (tone === 'success') return { bg: 'var(--success-bg)', border: 'var(--success)', fg: 'var(--success)' }
    if (tone === 'warning') return { bg: 'var(--warning-bg)', border: 'var(--warning)', fg: 'var(--warning)' }
    return { bg: 'var(--ink-02)', border: 'var(--ink-10)', fg: 'inherit' }
  }

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Link to="/fournisseurs" className="btn btn-secondary btn-sm">
          <ArrowLeft size={14} /> Retour aux fournisseurs
        </Link>
      </div>

      <div className="page-header">
        <h1><Building2 size={18} /> {data.nom}</h1>
        <div className="header-actions">
          <Link to={`/dossiers?fournisseur=${encodeURIComponent(data.nom)}`} className="btn btn-secondary btn-sm">
            <FolderOpen size={14} /> Filtrer les dossiers
          </Link>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <div className="card-flex" style={{ marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}><Receipt size={12} /> Identite fiscale</h2>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          {identityFields.map(field => {
            const Icon = field.icon
            return (
              <div key={field.label}>
                <div className="stat-label" style={{ marginBottom: 4 }}>
                  <Icon size={11} style={{ display: 'inline', marginRight: 4, verticalAlign: 'middle' }} /> {field.label}
                </div>
                <div className="cell-mono" style={{ fontSize: field.mono || 13 }}>
                  {field.value || PLACEHOLDER}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon teal"><FileText size={16} /></div>
          <div className="stat-value">{data.nbDossiers}</div>
          <div className="stat-label">Total dossiers</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Clock size={16} /></div>
          <div className="stat-value">{data.nbEnVerification + data.nbBrouillons}</div>
          <div className="stat-label">En cours</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><CheckCircle size={16} /></div>
          <div className="stat-value">{data.nbValides}</div>
          <div className="stat-label">Valides</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><AlertTriangle size={16} /></div>
          <div className="stat-value">{data.nbRejetes}</div>
          <div className="stat-label">Rejetes</div>
        </div>
      </div>

      <div className="cards-row" style={{ marginBottom: 12 }}>
        <div className="card">
          <h2><FileText size={12} /> Repartition par statut</h2>
          <div className="chart-bar-container">
            {statutBars.map(bar => (
              <div key={bar.label} className="chart-bar-row">
                <span className="chart-bar-label">{bar.label}</span>
                <div className="chart-bar-track">
                  <div className="chart-bar-fill" style={{ width: `${Math.max((bar.value / total) * 100, bar.value > 0 ? 6 : 0)}%`, background: bar.color }} />
                </div>
                <span className="chart-bar-value">{bar.value}</span>
              </div>
            ))}
          </div>
        </div>
        <div className="card">
          <h2><TrendingUp size={12} /> Bilan financier</h2>
          <div className="indicator-group">
            <div>
              <div className="indicator-row">
                <span className="indicator-label">Taux de validation</span>
                <span className="indicator-value success">{tauxValidation}%</span>
              </div>
              <div className="indicator-track">
                <div className="indicator-fill success" style={{ width: `${tauxValidation}%` }} />
              </div>
            </div>
            <div>
              <div className="indicator-row">
                <span className="indicator-label">Taux de rejet</span>
                <span className="indicator-value danger">{tauxRejet}%</span>
              </div>
              <div className="indicator-track">
                <div className="indicator-fill danger" style={{ width: `${tauxRejet}%` }} />
              </div>
            </div>
            <div className="indicator-divider">
              <div className="stat-label" style={{ marginBottom: 4 }}>Montant total engage (TTC)</div>
              <div className="indicator-amount">{fmt(data.montantTotalTtc)}</div>
              <div className="stat-label">MAD</div>
            </div>
          </div>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <div className="card-flex" style={{ marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}><Wallet size={12} /> Bilan financier detaille</h2>
          <span className="stat-label" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Calendar size={11} />
            {data.premierDossier ? new Date(data.premierDossier).toLocaleDateString('fr-FR') : '\u2014'}
            {' \u2192 '}
            {data.dernierDossier ? new Date(data.dernierDossier).toLocaleDateString('fr-FR') : '\u2014'}
          </span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
          {bilanBoxes.map(box => {
            const s = boxStyle(box.tone)
            return (
              <div key={box.label} style={{ padding: 12, background: s.bg, borderRadius: 6, border: `1px solid ${s.border}` }}>
                <div className="stat-label" style={{ marginBottom: 4, color: s.fg }}>{box.label}</div>
                <div className="cell-mono" style={{ fontSize: 15, fontWeight: 600, color: s.fg }}>{fmt(box.value)}</div>
                <div className="stat-label" style={{ marginTop: 2, color: s.fg }}>MAD</div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="card">
        <div className="card-flex" style={{ marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}>
            <FolderOpen size={12} /> Dossiers associes
            <span className="tag" style={{ marginLeft: 8 }}>{data.dossiers.length}</span>
          </h2>
        </div>
        {data.dossiers.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 24, color: 'var(--ink-40)' }}>
            Aucun dossier pour ce fournisseur.
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Reference</th>
                <th>Type</th>
                <th>Statut</th>
                <th>Montant TTC</th>
                <th>Docs</th>
                <th>Controles</th>
                <th>Date</th>
              </tr>
            </thead>
            <tbody>
              {data.dossiers.map(d => {
                const cfg = STATUT_CONFIG[d.statut]
                const ratio = d.nbChecksTotal > 0 ? Math.round((d.nbChecksConformes / d.nbChecksTotal) * 100) : 0
                return (
                  <tr key={d.id}>
                    <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                    <td><span className="tag">{d.type}</span></td>
                    <td><span className="status-badge" style={{ background: cfg.bg, color: cfg.color }}>{cfg.label}</span></td>
                    <td className="cell-mono">{fmt(d.montantTtc)} MAD</td>
                    <td>{d.nbDocuments}</td>
                    <td>
                      <span className="cell-mono" style={{ fontSize: 11 }}>
                        {d.nbChecksConformes}/{d.nbChecksTotal}
                      </span>
                      {d.nbChecksTotal > 0 && (
                        <span style={{ marginLeft: 6, fontSize: 10, color: ratio >= 80 ? 'var(--success)' : ratio >= 50 ? 'var(--warning)' : 'var(--danger)' }}>
                          ({ratio}%)
                        </span>
                      )}
                    </td>
                    <td className="audit-date">{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
