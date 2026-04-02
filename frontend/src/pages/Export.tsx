import { useState } from 'react'
import { exportCsv, exportJson, exportUbl, exportEdi, batchSync } from '../api/client'
import type { BatchSyncResult } from '../api/types'
import { Download, FileSpreadsheet, FileJson, FileCode, Loader2, CheckCircle, XCircle, Send } from 'lucide-react'

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const ERP_OPTIONS = [
  { value: 'SAGE_1000', label: 'Sage 1000' },
  { value: 'SAGE_X3', label: 'Sage X3' },
  { value: 'SAGE_50', label: 'Sage 50' },
]

export default function Export() {
  const [idsInput, setIdsInput] = useState('')
  const [singleId, setSingleId] = useState('')
  const [exporting, setExporting] = useState<string | null>(null)
  const [error, setError] = useState('')

  const [syncIdsInput, setSyncIdsInput] = useState('')
  const [syncErp, setSyncErp] = useState('SAGE_1000')
  const [syncing, setSyncing] = useState(false)
  const [syncResult, setSyncResult] = useState<BatchSyncResult | null>(null)
  const [syncError, setSyncError] = useState('')

  const parseIds = (input: string): number[] =>
    input.split(/[,\s]+/).map(s => parseInt(s.trim(), 10)).filter(n => !isNaN(n))

  const handleExportCsv = async () => {
    const ids = parseIds(idsInput)
    if (ids.length === 0) { setError('Saisissez au moins un ID de facture.'); return }
    setError(''); setExporting('csv')
    try { downloadBlob(await exportCsv(ids), 'factures.csv') }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur export CSV') }
    finally { setExporting(null) }
  }

  const handleExportJson = async () => {
    const ids = parseIds(idsInput)
    if (ids.length === 0) { setError('Saisissez au moins un ID de facture.'); return }
    setError(''); setExporting('json')
    try { downloadBlob(await exportJson(ids), 'factures.json') }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur export JSON') }
    finally { setExporting(null) }
  }

  const handleExportUbl = async () => {
    const id = parseInt(singleId.trim(), 10)
    if (isNaN(id)) { setError('Saisissez un ID de facture valide.'); return }
    setError(''); setExporting('ubl')
    try { downloadBlob(await exportUbl(id), `facture-${id}.xml`) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur export UBL') }
    finally { setExporting(null) }
  }

  const handleExportEdi = async () => {
    const id = parseInt(singleId.trim(), 10)
    if (isNaN(id)) { setError('Saisissez un ID de facture valide.'); return }
    setError(''); setExporting('edi')
    try { downloadBlob(await exportEdi(id), `facture-${id}.edi`) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur export EDI') }
    finally { setExporting(null) }
  }

  const handleBatchSync = async () => {
    const ids = parseIds(syncIdsInput)
    if (ids.length === 0) { setSyncError('Saisissez au moins un ID de facture.'); return }
    setSyncError(''); setSyncResult(null); setSyncing(true)
    try { setSyncResult(await batchSync(ids, syncErp)) }
    catch (e: unknown) { setSyncError(e instanceof Error ? e.message : 'Erreur de synchronisation') }
    finally { setSyncing(false) }
  }

  return (
    <div>
      <div className="page-header">
        <h1><Download size={24} /> Export</h1>
      </div>

      {error && (
        <div className="result-banner error mb-3">
          <XCircle size={18} /> <span>{error}</span>
        </div>
      )}

      <div className="card">
        <h2><FileSpreadsheet size={16} /> Export par lot (CSV / JSON)</h2>
        <div className="form-group">
          <label className="form-label">IDs des factures</label>
          <input
            type="text"
            className="form-input"
            placeholder="Ex : 1, 2, 3, 10"
            value={idsInput}
            onChange={e => setIdsInput(e.target.value)}
          />
        </div>
        <div className="flex-gap">
          <button className="btn btn-primary" disabled={exporting !== null} onClick={handleExportCsv}>
            {exporting === 'csv' ? <Loader2 size={16} className="spin" /> : <FileSpreadsheet size={16} />} CSV
          </button>
          <button className="btn btn-primary" disabled={exporting !== null} onClick={handleExportJson}>
            {exporting === 'json' ? <Loader2 size={16} className="spin" /> : <FileJson size={16} />} JSON
          </button>
        </div>
      </div>

      <div className="card">
        <h2><FileCode size={16} /> Export unitaire (UBL XML / EDI)</h2>
        <div className="form-group">
          <label className="form-label">ID de la facture</label>
          <input
            type="text"
            className="form-input"
            placeholder="Ex : 5"
            value={singleId}
            onChange={e => setSingleId(e.target.value)}
          />
        </div>
        <div className="flex-gap">
          <button className="btn btn-secondary" disabled={exporting !== null} onClick={handleExportUbl}>
            {exporting === 'ubl' ? <Loader2 size={16} className="spin" /> : <FileCode size={16} />} UBL XML
          </button>
          <button className="btn btn-secondary" disabled={exporting !== null} onClick={handleExportEdi}>
            {exporting === 'edi' ? <Loader2 size={16} className="spin" /> : <FileCode size={16} />} EDI
          </button>
        </div>
      </div>

      <div className="card">
        <h2><Send size={16} /> Synchronisation par lot</h2>
        <div className="form-group">
          <label className="form-label">IDs des factures</label>
          <input
            type="text"
            className="form-input"
            placeholder="IDs des factures : 1, 2, 3"
            value={syncIdsInput}
            onChange={e => setSyncIdsInput(e.target.value)}
          />
        </div>
        <div className="form-group">
          <label className="form-label">ERP cible</label>
          <select className="form-select" value={syncErp} onChange={e => setSyncErp(e.target.value)}>
            {ERP_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <button className="btn btn-primary" disabled={syncing} onClick={handleBatchSync}>
          {syncing ? <><Loader2 size={16} className="spin" /> Synchronisation...</> : <><Send size={16} /> Synchroniser</>}
        </button>

        {syncError && (
          <div className="result-banner error mt-2"><XCircle size={18} /> <span>{syncError}</span></div>
        )}

        {syncResult && (
          <div className="mt-2">
            <div className="result-banner success mb-2">
              <CheckCircle size={18} />
              <span>
                {syncResult.synced}/{syncResult.totalInvoices} factures synchronisees
                {syncResult.failed > 0 && ` (${syncResult.failed} echouee${syncResult.failed > 1 ? 's' : ''})`}
              </span>
            </div>
            <table className="invoice-table">
              <thead>
                <tr>
                  <th>ID Facture</th>
                  <th>Statut</th>
                  <th>Reference Sage</th>
                  <th>Erreur</th>
                </tr>
              </thead>
              <tbody>
                {syncResult.results.map(r => (
                  <tr key={r.invoiceId}>
                    <td>#{r.invoiceId}</td>
                    <td>{r.success ? <CheckCircle size={16} color="#059669" /> : <XCircle size={16} color="#ef4444" />}</td>
                    <td>{r.sageReference || '—'}</td>
                    <td>{r.error || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
