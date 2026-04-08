import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listDossiers, createDossier } from '../api/dossierApi'
import type { DossierListItem, PageResponse, DossierType } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { FolderOpen, Plus, ChevronLeft, ChevronRight, RefreshCw, Loader2 } from 'lucide-react'

export default function DossierList() {
  const [data, setData] = useState<PageResponse<DossierListItem> | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [newType, setNewType] = useState<DossierType>('BC')
  const [newFournisseur, setNewFournisseur] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const navigate = useNavigate()

  const load = () => { listDossiers(page).then(setData).catch(e => setError(e.message)) }
  useEffect(load, [page])

  const handleCreate = async () => {
    setCreating(true)
    try {
      const d = await createDossier(newType, newFournisseur || undefined, newDesc || undefined)
      navigate(`/dossiers/${d.id}`)
    } catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur') }
    finally { setCreating(false) }
  }

  const fmt = (n: number | null) => n != null ? n.toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '—'

  return (
    <div>
      <div className="page-header">
        <h1><FolderOpen size={24} /> Dossiers de paiement</h1>
        <div className="header-actions">
          <button className="btn btn-secondary" onClick={load}><RefreshCw size={16} /> Rafraichir</button>
          <button className="btn btn-primary" onClick={() => setShowCreate(!showCreate)}><Plus size={16} /> Nouveau dossier</button>
        </div>
      </div>

      {error && <div className="result-banner error mb-3">{error}</div>}

      {showCreate && (
        <div className="card mb-3">
          <h2>Nouveau dossier</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16 }}>
            <div>
              <label className="form-label">Type</label>
              <select className="form-select full-width" value={newType} onChange={e => setNewType(e.target.value as DossierType)}>
                <option value="BC">Bon de commande</option>
                <option value="CONTRACTUEL">Contractuel</option>
              </select>
            </div>
            <div>
              <label className="form-label">Fournisseur</label>
              <input className="form-input" placeholder="Ex: MAYMANA" value={newFournisseur} onChange={e => setNewFournisseur(e.target.value)} />
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">Description</label>
            <input className="form-input" placeholder="Ex: Atelier cartographie des risques" value={newDesc} onChange={e => setNewDesc(e.target.value)} />
          </div>
          <button className="btn btn-primary" disabled={creating} onClick={handleCreate}>
            {creating ? <><Loader2 size={16} className="spin" /> Creation...</> : <><Plus size={16} /> Creer</>}
          </button>
        </div>
      )}

      <div className="card">
        <table className="invoice-table">
          <thead>
            <tr>
              <th>Reference</th>
              <th>Fournisseur</th>
              <th>Type</th>
              <th>Montant TTC</th>
              <th>Docs</th>
              <th>Checks</th>
              <th>Statut</th>
              <th>Date</th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(d => {
              const cfg = STATUT_CONFIG[d.statut]
              return (
                <tr key={d.id}>
                  <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                  <td>{d.fournisseur || '—'}</td>
                  <td><span className="preprocess-tag">{d.type}</span></td>
                  <td className="cell-amount">{fmt(d.montantTtc)} MAD</td>
                  <td>{d.nbDocuments}</td>
                  <td>{d.nbChecksTotal > 0 ? `${d.nbChecksConformes}/${d.nbChecksTotal}` : '—'}</td>
                  <td><span className="status-badge" style={{ backgroundColor: cfg.color + '20', color: cfg.color, borderColor: cfg.color }}>{cfg.label}</span></td>
                  <td>{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                </tr>
              )
            })}
            {data?.content.length === 0 && <tr><td colSpan={8} className="empty-text">Aucun dossier</td></tr>}
          </tbody>
        </table>

        {data && data.totalPages > 1 && (
          <div className="pagination">
            <button className="btn btn-secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}><ChevronLeft size={16} /></button>
            <span>Page {page + 1} / {data.totalPages}</span>
            <button className="btn btn-secondary" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}><ChevronRight size={16} /></button>
          </div>
        )}
      </div>
    </div>
  )
}
