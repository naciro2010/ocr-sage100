import { useEffect, useState, useRef, useCallback } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { listDossiers, createDossier, searchDossiers, deleteDossier, uploadDocuments } from '../api/dossierApi'
import type { DossierListItem, PageResponse, DossierType } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import { FolderOpen, Plus, ChevronLeft, ChevronRight, RefreshCw, Loader2, X, Trash2, Upload, Download } from 'lucide-react'

export default function DossierList() {
  const { toast } = useToast()
  const [data, setData] = useState<PageResponse<DossierListItem> | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [newType, setNewType] = useState<DossierType>('BC')
  const [newFournisseur, setNewFournisseur] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [filterStatut, setFilterStatut] = useState('')
  const [filterType, setFilterType] = useState('')
  const [filterFournisseur, setFilterFournisseur] = useState('')
  const [debouncedFournisseur, setDebouncedFournisseur] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<DossierListItem | null>(null)
  const [sortKey, setSortKey] = useState<string>('dateCreation')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [dragging, setDragging] = useState(false)
  const [quickUploading, setQuickUploading] = useState(false)
  const dropInputRef = useRef<HTMLInputElement>(null)
  const debounceRef = useRef<ReturnType<typeof setTimeout>>(null)
  const navigate = useNavigate()

  const handleFournisseurChange = useCallback((value: string) => {
    setFilterFournisseur(value)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => {
      setDebouncedFournisseur(value)
      setPage(0)
    }, 300)
  }, [])

  useEffect(() => () => { if (debounceRef.current) clearTimeout(debounceRef.current) }, [])

  const load = () => {
    const hasFilters = filterStatut || filterType || debouncedFournisseur
    if (hasFilters) {
      searchDossiers({ page, statut: filterStatut || undefined, type: filterType || undefined, fournisseur: debouncedFournisseur || undefined })
        .then(setData).catch(e => setError(e.message))
    } else {
      listDossiers(page).then(setData).catch(e => setError(e.message))
    }
  }
  useEffect(load, [page, filterStatut, filterType, debouncedFournisseur])

  const handleCreate = async () => {
    setCreating(true)
    try {
      const d = await createDossier(newType, newFournisseur || undefined, newDesc || undefined)
      toast('success', 'Dossier cree')
      navigate(`/dossiers/${d.id}`)
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Erreur'
      setError(msg)
      toast('error', msg)
    }
    finally { setCreating(false) }
  }

  const handleDelete = async () => {
    if (!deleteTarget) return
    try {
      await deleteDossier(deleteTarget.id)
      toast('success', `Dossier ${deleteTarget.reference} supprime`)
      setDeleteTarget(null)
      load()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur de suppression')
    }
  }

  const handleQuickUpload = async (files: File[]) => {
    if (files.length === 0) return
    setQuickUploading(true)
    try {
      const d = await createDossier(newType || 'BC', newFournisseur || undefined)
      await uploadDocuments(d.id, files)
      toast('success', `Dossier ${d.reference} cree avec ${files.length} document(s)`)
      navigate(`/dossiers/${d.id}`)
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setQuickUploading(false) }
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); setDragging(false)
    const files = Array.from(e.dataTransfer.files).filter(f => f.name.toLowerCase().endsWith('.pdf'))
    if (files.length > 0) handleQuickUpload(files)
    else toast('warning', 'Seuls les fichiers PDF sont acceptes')
  }

  const fmt = (n: number | null) => n != null ? n.toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014'
  const hasFilters = filterStatut || filterType || debouncedFournisseur

  return (
    <div>
      <div className="page-header">
        <h1><FolderOpen size={22} /> Dossiers de paiement</h1>
        <div className="header-actions">
          <button className="btn btn-secondary" onClick={load} aria-label="Rafraichir la liste"><RefreshCw size={15} /></button>
          {data && data.content.length > 0 && (
            <button className="btn btn-secondary" onClick={() => {
              const rows = data.content.map(d => [d.reference, d.fournisseur || '', d.type, d.montantTtc ?? '', d.statut, d.nbDocuments, new Date(d.dateCreation).toLocaleDateString('fr-FR')].join(';'))
              const csv = 'Reference;Fournisseur;Type;Montant TTC;Statut;Docs;Date\n' + rows.join('\n')
              const blob = new Blob([csv], { type: 'text/csv' })
              const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'dossiers.csv'; a.click()
              toast('success', 'Export CSV telecharge')
            }}>
              <Download size={15} /> CSV
            </button>
          )}
          <button className="btn btn-primary" onClick={() => setShowCreate(!showCreate)}><Plus size={15} /> Nouveau</button>
        </div>
      </div>

      {/* Quick upload drop zone */}
      <div
        className={`hero-drop ${dragging ? 'dragging' : ''}`}
        style={{ padding: '16px 20px', marginBottom: 12 }}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => dropInputRef.current?.click()}
      >
        <input ref={dropInputRef} type="file" accept=".pdf" multiple hidden onChange={e => {
          const files = Array.from(e.target.files || [])
          if (files.length > 0) handleQuickUpload(files)
        }} />
        {quickUploading ? (
          <Loader2 size={18} className="spin" style={{ color: 'var(--teal-600)' }} />
        ) : (
          <>
            <Upload size={16} style={{ color: 'var(--ink-40)', display: 'inline', marginRight: 8 }} aria-hidden="true" />
            <span className="inline-hint">
              Deposez des PDFs ici pour creer un dossier rapidement
            </span>
          </>
        )}
      </div>

      {error && <div className="alert alert-error mb-3">{error}</div>}

      <Modal
        open={!!deleteTarget}
        title="Supprimer le dossier"
        message={`Etes-vous sur de vouloir supprimer le dossier ${deleteTarget?.reference || ''} ? Cette action est irreversible.`}
        confirmLabel="Supprimer"
        confirmColor="var(--danger)"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {showCreate && (
        <div className="card mb-3">
          <h2><Plus size={14} /> Nouveau dossier</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14 }}>
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
            {creating ? <><Loader2 size={15} className="spin" /> Creation...</> : <><Plus size={15} /> Creer le dossier</>}
          </button>
        </div>
      )}

      <div className="card mb-3" style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', padding: '14px 20px' }}>
        <select className="form-select" value={filterStatut} onChange={e => { setFilterStatut(e.target.value); setPage(0) }} style={{ width: 'auto' }}>
          <option value="">Tous les statuts</option>
          <option value="BROUILLON">Brouillon</option>
          <option value="EN_VERIFICATION">En verification</option>
          <option value="VALIDE">Valide</option>
          <option value="REJETE">Rejete</option>
        </select>
        <select className="form-select" value={filterType} onChange={e => { setFilterType(e.target.value); setPage(0) }} style={{ width: 'auto' }}>
          <option value="">Tous les types</option>
          <option value="BC">Bon de commande</option>
          <option value="CONTRACTUEL">Contractuel</option>
        </select>
        <input
          className="form-input"
          placeholder="Rechercher fournisseur..."
          value={filterFournisseur}
          onChange={e => handleFournisseurChange(e.target.value)}
          style={{ width: 200 }}
        />
        {hasFilters && (
          <button className="btn btn-secondary btn-sm" onClick={() => { setFilterStatut(''); setFilterType(''); setFilterFournisseur(''); setDebouncedFournisseur(''); setPage(0) }}>
            <X size={14} /> Effacer
          </button>
        )}
      </div>

      <div className="card">
        <table className="data-table">
          <thead>
            <tr>
              {[
                { key: 'reference', label: 'Reference' },
                { key: 'fournisseur', label: 'Fournisseur' },
                { key: 'type', label: 'Type' },
                { key: 'montantTtc', label: 'Montant TTC' },
                { key: 'nbDocuments', label: 'Docs' },
                { key: 'nbChecksConformes', label: 'Checks' },
                { key: 'statut', label: 'Statut' },
                { key: 'dateCreation', label: 'Date' },
              ].map(col => (
                <th key={col.key} style={{ cursor: 'pointer', userSelect: 'none' }}
                  onClick={() => { setSortKey(col.key); setSortDir(prev => sortKey === col.key ? (prev === 'asc' ? 'desc' : 'asc') : 'desc') }}>
                  {col.label} {sortKey === col.key ? (sortDir === 'asc' ? '\u25B2' : '\u25BC') : ''}
                </th>
              ))}
              <th></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.slice().sort((a, b) => {
              const va = (a as unknown as Record<string, unknown>)[sortKey]
              const vb = (b as unknown as Record<string, unknown>)[sortKey]
              const cmp = String(va ?? '').localeCompare(String(vb ?? ''), 'fr', { numeric: true })
              return sortDir === 'asc' ? cmp : -cmp
            }).map(d => {
              const cfg = STATUT_CONFIG[d.statut]
              return (
                <tr key={d.id}>
                  <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                  <td>{d.fournisseur || '\u2014'}</td>
                  <td><span className="tag">{d.type}</span></td>
                  <td className="cell-mono">{fmt(d.montantTtc)} MAD</td>
                  <td>{d.nbDocuments}</td>
                  <td>{d.nbChecksTotal > 0 ? `${d.nbChecksConformes}/${d.nbChecksTotal}` : '\u2014'}</td>
                  <td><span className="status-badge" style={{ background: cfg.bg, color: cfg.color }}>{cfg.label}</span></td>
                  <td style={{ color: 'var(--slate-500)', fontSize: 12 }}>{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                  <td>
                    <button
                      className="btn btn-danger btn-sm"
                      onClick={(e) => { e.preventDefault(); setDeleteTarget(d) }}
                      aria-label={`Supprimer le dossier ${d.reference}`}
                    >
                      <Trash2 size={14} />
                    </button>
                  </td>
                </tr>
              )
            })}
            {data?.content.length === 0 && <tr><td colSpan={9} className="empty-text">Aucun dossier</td></tr>}
          </tbody>
        </table>

        {data && data.totalPages > 1 && (
          <div className="pagination">
            <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}><ChevronLeft size={15} /></button>
            <span>Page {page + 1} / {data.totalPages}</span>
            <button className="btn btn-secondary btn-sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}><ChevronRight size={15} /></button>
          </div>
        )}
      </div>
    </div>
  )
}
