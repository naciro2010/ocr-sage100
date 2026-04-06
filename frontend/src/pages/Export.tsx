import { useState } from 'react'
import { exportCsv, exportJson, exportUbl, exportEdi, batchSync } from '../api/client'
import type { BatchSyncResult } from '../api/types'
import { Download, FileSpreadsheet, FileJson, FileCode, Loader2, CheckCircle, XCircle, Send, Building2 } from 'lucide-react'

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
    if (ids.length === 0) { setError('Saisissez au moins un ID.'); return }
    setError(''); setExporting('csv')
    try { downloadBlob(await exportCsv(ids), 'factures.csv') }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur') }
    finally { setExporting(null) }
  }

  const handleExportJson = async () => {
    const ids = parseIds(idsInput)
    if (ids.length === 0) { setError('Saisissez au moins un ID.'); return }
    setError(''); setExporting('json')
    try { downloadBlob(await exportJson(ids), 'factures.json') }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur') }
    finally { setExporting(null) }
  }

  const handleExportUbl = async () => {
    const id = parseInt(singleId.trim(), 10)
    if (isNaN(id)) { setError('Saisissez un ID valide.'); return }
    setError(''); setExporting('ubl')
    try { downloadBlob(await exportUbl(id), `facture-${id}.xml`) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur') }
    finally { setExporting(null) }
  }

  const handleExportEdi = async () => {
    const id = parseInt(singleId.trim(), 10)
    if (isNaN(id)) { setError('Saisissez un ID valide.'); return }
    setError(''); setExporting('edi')
    try { downloadBlob(await exportEdi(id), `facture-${id}.edi`) }
    catch (e: unknown) { setError(e instanceof Error ? e.message : 'Erreur') }
    finally { setExporting(null) }
  }

  const handleBatchSync = async () => {
    const ids = parseIds(syncIdsInput)
    if (ids.length === 0) { setSyncError('Saisissez au moins un ID.'); return }
    setSyncError(''); setSyncResult(null); setSyncing(true)
    try { setSyncResult(await batchSync(ids, syncErp)) }
    catch (e: unknown) { setSyncError(e instanceof Error ? e.message : 'Erreur') }
    finally { setSyncing(false) }
  }

  return (
    <div>
      <div className="page-header">
        <h1><Download size={22} /> Export</h1>
      </div>

      {error && (
        <div className="result-banner error mb-3">
          <XCircle size={16} /> <span>{error}</span>
        </div>
      )}

      <div className="card">
        <h2><FileSpreadsheet size={14} /> Export par lot (CSV / JSON)</h2>
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
            {exporting === 'csv' ? <Loader2 size={14} className="spin" /> : <FileSpreadsheet size={14} />} CSV
          </button>
          <button className="btn btn-primary" disabled={exporting !== null} onClick={handleExportJson}>
            {exporting === 'json' ? <Loader2 size={14} className="spin" /> : <FileJson size={14} />} JSON
          </button>
        </div>
      </div>

      <div className="card">
        <h2><FileCode size={14} /> Export unitaire (UBL XML / EDI)</h2>
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
            {exporting === 'ubl' ? <Loader2 size={14} className="spin" /> : <FileCode size={14} />} UBL XML
          </button>
          <button className="btn btn-secondary" disabled={exporting !== null} onClick={handleExportEdi}>
            {exporting === 'edi' ? <Loader2 size={14} className="spin" /> : <FileCode size={14} />} EDI
          </button>
        </div>
      </div>

      <div className="card">
        <h2><Send size={14} /> Synchronisation par lot</h2>
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
          <div className="erp-tabs">
            {ERP_OPTIONS.map(o => (
              <button
                key={o.value}
                className={`erp-tab ${syncErp === o.value ? 'active' : ''}`}
                onClick={() => setSyncErp(o.value)}
              >
                <Building2 size={13} /> {o.label}
              </button>
            ))}
          </div>
        </div>
        <button className="btn btn-primary" disabled={syncing} onClick={handleBatchSync}>
          {syncing ? <><Loader2 size={14} className="spin" /> Synchronisation...</> : <><Send size={14} /> Synchroniser</>}
        </button>

        {syncError && (
          <div className="result-banner error mt-2"><XCircle size={16} /> <span>{syncError}</span></div>
        )}

        {syncResult && (
          <div className="mt-2">
            <div className="result-banner success mb-2">
              <CheckCircle size={16} />
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
                    <td>{r.success ? <CheckCircle size={14} color="#10a37f" /> : <XCircle size={14} color="#d94f4f" />}</td>
                    <td className="mono">{r.sageReference || '—'}</td>
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
