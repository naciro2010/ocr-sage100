import { useEffect, useState, useRef, useCallback, useMemo } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { listDossiers, createDossier, searchDossiers, deleteDossier, uploadDocuments, getExportTCUrl, getExportOPUrl, getExportExcelUrl, openWithAuth, downloadWithAuth, prefetchDossierDetail, bulkChangeStatut } from '../api/dossierApi'
import type { DossierListItem, PageResponse, DossierType } from '../api/dossierTypes'
import { STATUT_CONFIG } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import Modal from '../components/Modal'
import DocumentSearchModal from '../components/dossier/DocumentSearchModal'
import * as Pages from '../routes/lazyPages'
import {
  FolderOpen, Plus, ChevronLeft, ChevronRight, RefreshCw, Loader2,
  X, Trash2, Upload, Download, FileText, Search, Filter, CheckCircle2, XCircle
} from 'lucide-react'

const STATUT_CHIPS = [
  { value: '', label: 'Tous', icon: null },
  { value: 'BROUILLON', label: 'Brouillons', color: 'var(--ink-50)' },
  { value: 'EN_VERIFICATION', label: 'En cours', color: 'var(--warning)' },
  { value: 'VALIDE', label: 'Valides', color: 'var(--success)' },
  { value: 'REJETE', label: 'Rejetes', color: 'var(--danger)' },
]

const TABLE_COLUMNS = [
  { key: 'reference', label: 'Reference' },
  { key: 'fournisseur', label: 'Fournisseur' },
  { key: 'type', label: 'Type' },
  { key: 'montantTtc', label: 'Montant TTC' },
  { key: 'nbDocuments', label: 'Docs' },
  { key: 'statut', label: 'Statut' },
  { key: 'dateCreation', label: 'Date' },
] as const

export default function DossierList() {
  const { toast } = useToast()
  const [searchParams] = useSearchParams()
  const [data, setData] = useState<PageResponse<DossierListItem> | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [showCreate, setShowCreate] = useState(false)
  const [newType, setNewType] = useState<DossierType>('BC')
  const [newFournisseur, setNewFournisseur] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [filterStatut, setFilterStatut] = useState(searchParams.get('statut') || '')
  const [filterType, setFilterType] = useState('')
  const [filterFournisseur, setFilterFournisseur] = useState(searchParams.get('fournisseur') || '')
  const [debouncedFournisseur, setDebouncedFournisseur] = useState(searchParams.get('fournisseur') || '')
  const [deleteTarget, setDeleteTarget] = useState<DossierListItem | null>(null)
  const [sortKey, setSortKey] = useState<string>('dateCreation')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [dragging, setDragging] = useState(false)
  const [quickUploading, setQuickUploading] = useState(false)
  const [showFilters, setShowFilters] = useState(false)
  const [showDocSearch, setShowDocSearch] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkBusy, setBulkBusy] = useState(false)
  const [bulkRejectModal, setBulkRejectModal] = useState(false)
  const [bulkRejectMotif, setBulkRejectMotif] = useState('')
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

  const abortRef = useRef<AbortController | null>(null)
  useEffect(() => () => { if (debounceRef.current) clearTimeout(debounceRef.current) }, [])

  const load = useCallback(() => {
    if (abortRef.current) abortRef.current.abort()
    const ac = new AbortController()
    abortRef.current = ac
    const hasFilters = filterStatut || filterType || debouncedFournisseur
    const promise = hasFilters
      ? searchDossiers({ page, statut: filterStatut || undefined, type: filterType || undefined, fournisseur: debouncedFournisseur || undefined, signal: ac.signal })
      : listDossiers(page, 20, ac.signal)
    promise.then(result => {
      if (!ac.signal.aborted) setData(result)
    }).catch(e => {
      if (!ac.signal.aborted && e instanceof Error && e.name !== 'AbortError') setError(e.message)
    })
  }, [page, filterStatut, filterType, debouncedFournisseur])
  useEffect(() => { load(); return () => { if (abortRef.current) abortRef.current.abort() } }, [load])

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
    if (!deleteTarget || !data) return
    const target = deleteTarget
    // Optimistic: remove from list immediately
    setData(prev => prev ? {
      ...prev,
      content: prev.content.filter(d => d.id !== target.id),
      totalElements: prev.totalElements - 1,
    } : prev)
    setDeleteTarget(null)
    try {
      await deleteDossier(target.id)
      toast('success', `Dossier ${target.reference} supprime`)
    } catch (e: unknown) {
      // Revert: put it back
      setData(prev => prev ? {
        ...prev,
        content: [...prev.content, target].sort((a, b) =>
          new Date(b.dateCreation).getTime() - new Date(a.dateCreation).getTime()
        ),
        totalElements: prev.totalElements + 1,
      } : prev)
      toast('error', e instanceof Error ? e.message : 'Erreur de suppression')
    }
  }

  const toggleSelected = useCallback((id: string) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }, [])

  const performBulkStatut = useCallback(async (statut: 'VALIDE' | 'REJETE', motif?: string) => {
    if (selected.size === 0) return
    setBulkBusy(true)
    try {
      const ids = Array.from(selected)
      const results = await bulkChangeStatut(ids, statut, motif)
      const ok = results.filter(r => r.ok).length
      const ko = results.length - ok
      toast(ko === 0 ? 'success' : 'warning', `${ok} OK · ${ko} en erreur`)
      setSelected(new Set())
      setBulkRejectModal(false)
      setBulkRejectMotif('')
      load()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur bulk')
    } finally { setBulkBusy(false) }
  }, [selected, toast, load])

  const handleBulkValider = useCallback(() => performBulkStatut('VALIDE'), [performBulkStatut])

  const handleBulkRejeter = useCallback(() => {
    if (selected.size === 0) return
    // Audit reglementaire : un rejet en lot DOIT etre justifie. Avant ce
    // garde-fou, le motif etait hardcode "Rejet en lot" — pas tracable
    // pour la Cour des comptes ni la procedure CDG.
    setBulkRejectMotif('')
    setBulkRejectModal(true)
  }, [selected.size])

  const confirmBulkReject = useCallback(() => {
    const motif = bulkRejectMotif.trim()
    if (motif.length < 10) {
      toast('warning', 'Motif obligatoire (10 caracteres minimum) pour tracer le rejet')
      return
    }
    performBulkStatut('REJETE', motif)
  }, [bulkRejectMotif, performBulkStatut, toast])

  const prefetchedRef = useRef(new Set<string>())
  const handlePrefetch = useCallback((dossierId: string) => {
    if (prefetchedRef.current.has(dossierId)) return
    prefetchedRef.current.add(dossierId)
    Pages.DossierDetail.preload()
    prefetchDossierDetail(dossierId)
  }, [])

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
  const isEmpty = data && data.content.length === 0 && !hasFilters
  const sorted = useMemo(() => data?.content.slice().sort((a, b) => {
    const va = (a as unknown as Record<string, unknown>)[sortKey]
    const vb = (b as unknown as Record<string, unknown>)[sortKey]
    const cmp = String(va ?? '').localeCompare(String(vb ?? ''), 'fr', { numeric: true })
    return sortDir === 'asc' ? cmp : -cmp
  }), [data, sortKey, sortDir])

  return (
    <div>
      <div className="page-header">
        <h1><FolderOpen size={22} /> Dossiers de paiement</h1>
        <div className="header-actions">
          <button className="btn btn-secondary" onClick={load} aria-label="Rafraichir"><RefreshCw size={15} /></button>
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

      {/* Drop zone */}
      <div
        className={`hero-drop ${dragging ? 'dragging' : ''}`}
        style={{ padding: '14px 20px', marginBottom: 12 }}
        onDragOver={e => { e.preventDefault(); setDragging(true) }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => dropInputRef.current?.click()}
        role="button" tabIndex={0} aria-label="Deposer des fichiers PDF"
        onKeyDown={e => { if (e.key === 'Enter') dropInputRef.current?.click() }}
      >
        <input ref={dropInputRef} type="file" accept=".pdf" multiple hidden onChange={e => {
          const files = Array.from(e.target.files || [])
          if (files.length > 0) handleQuickUpload(files)
        }} />
        {quickUploading ? (
          <Loader2 size={18} className="spin" style={{ color: 'var(--accent)' }} />
        ) : (
          <>
            <Upload size={16} style={{ color: 'var(--ink-40)', display: 'inline', marginRight: 8 }} aria-hidden="true" />
            <span className="inline-hint">Deposez des PDFs ici pour creer un dossier rapidement</span>
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
          <div className="form-grid">
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

      {/* Statut chips + filters */}
      <div className="dossier-filters">
        <div className="dossier-chips">
          {STATUT_CHIPS.map(chip => (
            <button key={chip.value}
              className={`dossier-chip ${filterStatut === chip.value ? 'active' : ''}`}
              onClick={() => { setFilterStatut(chip.value); setPage(0) }}
              style={filterStatut === chip.value && chip.color ? { borderColor: chip.color, color: chip.color } : undefined}
            >
              {chip.color && <span className="dossier-chip-dot" style={{ background: chip.color }} />}
              {chip.label}
            </button>
          ))}
        </div>
        <div className="dossier-filters-right">
          <div className="dossier-search-wrap">
            <Search size={14} className="dossier-search-icon" />
            <input
              className="dossier-search"
              placeholder="Rechercher fournisseur..."
              value={filterFournisseur}
              onChange={e => handleFournisseurChange(e.target.value)}
            />
            {filterFournisseur && (
              <button className="dossier-search-clear" onClick={() => { setFilterFournisseur(''); setDebouncedFournisseur(''); setPage(0) }} aria-label="Effacer">
                <X size={12} />
              </button>
            )}
          </div>
          <button className={`btn btn-secondary btn-sm ${showFilters ? 'active' : ''}`}
            onClick={() => setShowFilters(!showFilters)} aria-label="Plus de filtres">
            <Filter size={14} />
          </button>
          <button className="btn btn-secondary btn-sm" title="Recherche full-text dans les documents"
            onClick={() => setShowDocSearch(true)} aria-label="Rechercher dans les documents">
            <Search size={14} /> Documents
          </button>
        </div>
      </div>

      {selected.size > 0 && (
        <div className="card mb-3" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 12px' }}>
          <span style={{ fontSize: 13 }}>{selected.size} dossier(s) selectionne(s)</span>
          <div style={{ flex: 1 }} />
          <button className="btn btn-secondary btn-sm" disabled={bulkBusy}
            onClick={handleBulkValider}>
            <CheckCircle2 size={14} /> Valider
          </button>
          <button className="btn btn-secondary btn-sm" disabled={bulkBusy}
            onClick={handleBulkRejeter}>
            <XCircle size={14} /> Rejeter
          </button>
          <button className="btn btn-secondary btn-sm" onClick={() => setSelected(new Set())}>
            <X size={14} />
          </button>
        </div>
      )}

      {/* Extended filters */}
      {showFilters && (
        <div className="card mb-3 filter-bar">
          <select className="form-select" value={filterType} onChange={e => { setFilterType(e.target.value); setPage(0) }} style={{ width: 'auto' }}>
            <option value="">Tous les types</option>
            <option value="BC">Bon de commande</option>
            <option value="CONTRACTUEL">Contractuel</option>
          </select>
          {hasFilters && (
            <button className="btn btn-secondary btn-sm" onClick={() => { setFilterStatut(''); setFilterType(''); setFilterFournisseur(''); setDebouncedFournisseur(''); setPage(0) }}>
              <X size={14} /> Effacer les filtres
            </button>
          )}
        </div>
      )}

      {/* Empty state */}
      {isEmpty ? (
        <div className="dossier-empty">
          <div className="dossier-empty-icon">
            <FolderOpen size={40} />
          </div>
          <h2>Aucun dossier de paiement</h2>
          <p>Commencez par deposer vos documents PDF ou creez un dossier manuellement.</p>
          <div className="dossier-empty-actions">
            <button className="btn btn-primary" onClick={() => setShowCreate(true)}>
              <Plus size={15} /> Creer un dossier
            </button>
            <button className="btn btn-secondary" onClick={() => dropInputRef.current?.click()}>
              <Upload size={15} /> Deposer des PDFs
            </button>
          </div>
        </div>
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 28 }}>
                  <input
                    type="checkbox"
                    aria-label="Tout selectionner"
                    checked={!!sorted?.length && sorted.every(d => selected.has(d.id))}
                    onChange={e => {
                      if (!sorted) return
                      setSelected(prev => {
                        const next = new Set(prev)
                        if (e.target.checked) sorted.forEach(d => next.add(d.id))
                        else sorted.forEach(d => next.delete(d.id))
                        return next
                      })
                    }}
                  />
                </th>
                {TABLE_COLUMNS.map(col => (
                  <th key={col.key} style={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => { setSortKey(col.key); setSortDir(prev => sortKey === col.key ? (prev === 'asc' ? 'desc' : 'asc') : 'desc') }}>
                    {col.label} {sortKey === col.key ? (sortDir === 'asc' ? '\u25B2' : '\u25BC') : ''}
                  </th>
                ))}
                <th></th>
              </tr>
            </thead>
            <tbody>
              {sorted?.map(d => {
                const cfg = STATUT_CONFIG[d.statut]
                const isFinalized = d.statut === 'VALIDE'
                return (
                  <tr key={d.id} onMouseEnter={() => handlePrefetch(d.id)}>
                    <td onClick={e => e.stopPropagation()}>
                      <input type="checkbox" aria-label={`Selectionner ${d.reference}`}
                        checked={selected.has(d.id)}
                        onChange={() => toggleSelected(d.id)} />
                    </td>
                    <td><Link to={`/dossiers/${d.id}`}>{d.reference}</Link></td>
                    <td>{d.fournisseur || '\u2014'}</td>
                    <td><span className="tag">{d.type}</span></td>
                    <td className="cell-mono">{fmt(d.montantTtc)} MAD</td>
                    <td>{d.nbDocuments}</td>
                    <td><span className="status-badge" style={{ background: cfg.bg, color: cfg.color }}>{cfg.label}</span></td>
                    <td className="audit-date">{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                    <td>
                      <div style={{ display: 'flex', gap: 4 }}>
                        {isFinalized && (
                          <>
                            <button className="btn btn-secondary btn-sm" title="Tableau de Controle"
                              onClick={e => { e.preventDefault(); openWithAuth(getExportTCUrl(d.id)) }} aria-label="Telecharger TC">
                              <FileText size={12} />
                            </button>
                            <button className="btn btn-secondary btn-sm" title="Ordre de Paiement"
                              onClick={e => { e.preventDefault(); openWithAuth(getExportOPUrl(d.id)) }} aria-label="Telecharger OP">
                              <Download size={12} />
                            </button>
                            <button className="btn btn-secondary btn-sm" title="Excel reporting"
                              onClick={e => { e.preventDefault(); downloadWithAuth(getExportExcelUrl(d.id), `${d.reference}.xlsx`) }} aria-label="Telecharger Excel">
                              <Download size={12} />
                            </button>
                          </>
                        )}
                        <button
                          className="btn btn-danger btn-sm"
                          onClick={(e) => { e.preventDefault(); setDeleteTarget(d) }}
                          aria-label={`Supprimer le dossier ${d.reference}`}
                        >
                          <Trash2 size={12} />
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
              {data?.content.length === 0 && (
                <tr><td colSpan={9} className="empty-text">
                  {hasFilters ? 'Aucun dossier ne correspond aux filtres' : 'Aucun dossier'}
                </td></tr>
              )}
            </tbody>
          </table>

          {data && data.totalPages > 1 && (
            <div className="pagination">
              <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(p => p - 1)} aria-label="Page precedente"><ChevronLeft size={15} /></button>
              <span>Page {page + 1} / {data.totalPages}</span>
              <button className="btn btn-secondary btn-sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)} aria-label="Page suivante"><ChevronRight size={15} /></button>
            </div>
          )}
        </div>
      )}

      <DocumentSearchModal open={showDocSearch} onClose={() => setShowDocSearch(false)} />

      <Modal
        open={bulkRejectModal}
        title={`Rejeter ${selected.size} dossier${selected.size > 1 ? 's' : ''}`}
        message="Le motif sera enregistre dans l'historique de chaque dossier (audit reglementaire MADAEF / Cour des comptes). 10 caracteres minimum."
        confirmLabel="Rejeter"
        confirmColor="var(--danger)"
        onConfirm={confirmBulkReject}
        onCancel={() => { setBulkRejectModal(false); setBulkRejectMotif('') }}
      >
        <div className="form-row" style={{ marginTop: 8 }}>
          <label htmlFor="bulk-motif" style={{ display: 'block', fontSize: 12, fontWeight: 600, marginBottom: 4 }}>
            Motif du rejet <span style={{ color: 'var(--danger)' }}>*</span>
          </label>
          <textarea
            id="bulk-motif"
            className="form-input"
            rows={3}
            value={bulkRejectMotif}
            onChange={e => setBulkRejectMotif(e.target.value)}
            placeholder="Ex : Pieces manquantes, attestation expiree, montants incoherents..."
            disabled={bulkBusy}
            style={{ width: '100%', resize: 'vertical' }}
            autoFocus
          />
          <div style={{ fontSize: 11, color: 'var(--ink-40)', marginTop: 4 }}>
            {bulkRejectMotif.trim().length} / 10 caracteres minimum
          </div>
        </div>
      </Modal>
    </div>
  )
}
