import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getFournisseurDetail } from '../api/fournisseurApi'
import type { FournisseurDetail } from '../api/fournisseurTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import {
  ArrowLeft, Building2, FileText, CheckCircle, AlertTriangle, Clock,
  Wallet, Calendar, Hash, CreditCard, FolderOpen
} from 'lucide-react'

function fmt(n: number | null | undefined): string {
  if (n == null) return '0'
  return n.toLocaleString('fr-FR', { maximumFractionDigits: 2 })
}

export default function FournisseurDetail() {
  const { nom } = useParams<{ nom: string }>()
  const [data, setData] = useState<FournisseurDetail | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!nom) return
    const ctrl = new AbortController()
    getFournisseurDetail(decodeURIComponent(nom), ctrl.signal)
      .then(setData)
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
      })
    return () => ctrl.abort()
  }, [nom])

  if (error) {
    return (
      <div>
        <Link to="/fournisseurs" className="btn btn-secondary btn-sm mb-3"><ArrowLeft size={14} /> Retour</Link>
        <div className="alert alert-error">{error}</div>
      </div>
    )
  }

  if (!data) {
    return (
      <div className="skeleton">
        <div className="skeleton-bar h-lg w-40" />
        <div className="skeleton-card" style={{ height: 120, marginBottom: 16 }} />
        <div className="skeleton-card" style={{ height: 200 }} />
      </div>
    )
  }

  const tauxValidation = data.nbDossiers > 0 ? Math.round((data.nbValides / data.nbDossiers) * 100) : 0

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Link to="/fournisseurs" className="btn btn-secondary btn-sm">
          <ArrowLeft size={14} /> Retour aux fournisseurs
        </Link>
      </div>

      <div className="page-header">
        <h1><Building2 size={18} /> {data.nom}</h1>
        <div className="page-header-actions">
          <Link to={`/dossiers?fournisseur=${encodeURIComponent(data.nom)}`} className="btn btn-secondary btn-sm">
            <FolderOpen size={14} /> Filtrer les dossiers
          </Link>
        </div>
      </div>

      {/* Identite fournisseur */}
      <div className="card mb-3" style={{ padding: 16 }}>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
          <div>
            <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 4 }}>
              <Hash size={11} style={{ display: 'inline', marginRight: 4 }} /> ICE
            </div>
            <div className="cell-mono">{data.ice || '\u2014'}</div>
          </div>
          <div>
            <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 4 }}>
              <Hash size={11} style={{ display: 'inline', marginRight: 4 }} /> Identifiant Fiscal
            </div>
            <div className="cell-mono">{data.identifiantFiscal || '\u2014'}</div>
          </div>
          <div>
            <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 4 }}>
              <Hash size={11} style={{ display: 'inline', marginRight: 4 }} /> RC
            </div>
            <div className="cell-mono">{data.rc || '\u2014'}</div>
          </div>
          <div>
            <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 4 }}>
              <CreditCard size={11} style={{ display: 'inline', marginRight: 4 }} /> RIB
            </div>
            <div className="cell-mono" style={{ fontSize: 12 }}>{data.rib || '\u2014'}</div>
          </div>
        </div>
      </div>

      {/* Bilan par statut */}
      <h2 style={{ fontSize: 14, margin: '16px 0 8px', color: 'var(--ink-70)' }}>Bilan par statut</h2>
      <div className="stats-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginBottom: 16 }}>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--ink-40)', fontSize: 11, marginBottom: 6 }}>
            <FileText size={12} /> Total dossiers
          </div>
          <div style={{ fontSize: 22, fontWeight: 600 }}>{data.nbDossiers}</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#475569', fontSize: 11, marginBottom: 6 }}>
            <FileText size={12} /> Brouillons
          </div>
          <div style={{ fontSize: 22, fontWeight: 600, color: '#475569' }}>{data.nbBrouillons}</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#d97706', fontSize: 11, marginBottom: 6 }}>
            <Clock size={12} /> En verification
          </div>
          <div style={{ fontSize: 22, fontWeight: 600, color: '#d97706' }}>{data.nbEnVerification}</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#059669', fontSize: 11, marginBottom: 6 }}>
            <CheckCircle size={12} /> Valides
          </div>
          <div style={{ fontSize: 22, fontWeight: 600, color: '#059669' }}>{data.nbValides}</div>
          <div style={{ fontSize: 11, color: 'var(--ink-40)', marginTop: 2 }}>{tauxValidation}% du total</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#dc2626', fontSize: 11, marginBottom: 6 }}>
            <AlertTriangle size={12} /> Rejetes
          </div>
          <div style={{ fontSize: 22, fontWeight: 600, color: '#dc2626' }}>{data.nbRejetes}</div>
        </div>
      </div>

      {/* Bilan financier */}
      <h2 style={{ fontSize: 14, margin: '16px 0 8px', color: 'var(--ink-70)' }}>Bilan financier</h2>
      <div className="stats-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12, marginBottom: 16 }}>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--ink-40)', fontSize: 11, marginBottom: 6 }}>
            <Wallet size={12} /> Montant total TTC
          </div>
          <div className="cell-mono" style={{ fontSize: 18, fontWeight: 600 }}>{fmt(data.montantTotalTtc)} MAD</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ color: 'var(--ink-40)', fontSize: 11, marginBottom: 6 }}>Montant HT</div>
          <div className="cell-mono" style={{ fontSize: 16 }}>{fmt(data.montantTotalHt)} MAD</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ color: 'var(--ink-40)', fontSize: 11, marginBottom: 6 }}>Montant TVA</div>
          <div className="cell-mono" style={{ fontSize: 16 }}>{fmt(data.montantTotalTva)} MAD</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ color: '#059669', fontSize: 11, marginBottom: 6 }}>Montant valide</div>
          <div className="cell-mono" style={{ fontSize: 16, color: '#059669' }}>{fmt(data.montantValide)} MAD</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ color: '#d97706', fontSize: 11, marginBottom: 6 }}>En cours de verification</div>
          <div className="cell-mono" style={{ fontSize: 16, color: '#d97706' }}>{fmt(data.montantEnCours)} MAD</div>
        </div>
        <div className="card" style={{ padding: 14 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--ink-40)', fontSize: 11, marginBottom: 6 }}>
            <Calendar size={12} /> Periode
          </div>
          <div style={{ fontSize: 12 }}>
            <div>Du: {data.premierDossier ? new Date(data.premierDossier).toLocaleDateString('fr-FR') : '\u2014'}</div>
            <div>Au: {data.dernierDossier ? new Date(data.dernierDossier).toLocaleDateString('fr-FR') : '\u2014'}</div>
          </div>
        </div>
      </div>

      {/* Liste des dossiers */}
      <h2 style={{ fontSize: 14, margin: '16px 0 8px', color: 'var(--ink-70)' }}>
        Dossiers de {data.nom} ({data.dossiers.length})
      </h2>
      {data.dossiers.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: 24, color: 'var(--ink-40)' }}>
          Aucun dossier pour ce fournisseur.
        </div>
      ) : (
        <div className="card">
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
                return (
                  <tr key={d.id}>
                    <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                    <td><span className="tag">{d.type}</span></td>
                    <td><span className="status-badge" style={{ background: cfg.bg, color: cfg.color }}>{cfg.label}</span></td>
                    <td className="cell-mono">{fmt(d.montantTtc)} MAD</td>
                    <td>{d.nbDocuments}</td>
                    <td>{d.nbChecksConformes}/{d.nbChecksTotal}</td>
                    <td className="audit-date">{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
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
