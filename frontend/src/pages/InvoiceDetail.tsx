import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getInvoice, syncToSage, updateInvoice, getInvoiceFileUrl } from '../api/client'
import type { Invoice, InvoiceUpdateRequest } from '../api/types'
import StatusBadge from '../components/StatusBadge'
import {
  ArrowLeft, RefreshCw, Send, FileText, Building2, Banknote,
  AlertCircle, Loader2, CreditCard, ScanLine, Cpu, Layers,
  Pencil, Save, X, CheckCircle, Brain, ZoomIn, ZoomOut, Eye
} from 'lucide-react'

export default function InvoiceDetail() {
  const { id } = useParams<{ id: string }>()
  const [invoice, setInvoice] = useState<Invoice | null>(null)
  const [error, setError] = useState('')
  const [syncing, setSyncing] = useState(false)
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveMsg, setSaveMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [showRawText, setShowRawText] = useState(false)
  const [form, setForm] = useState<InvoiceUpdateRequest>({})
  const [previewZoom, setPreviewZoom] = useState(100)
  const [showPreview, setShowPreview] = useState(true)

  const load = () => {
    if (!id) return
    getInvoice(Number(id))
      .then(inv => { setInvoice(inv); initForm(inv) })
      .catch(e => setError(e.message))
  }

  const initForm = (inv: Invoice) => {
    setForm({
      supplierName: inv.supplierName || '',
      supplierIce: inv.supplierIce || '',
      supplierIf: inv.supplierIf || '',
      supplierRc: inv.supplierRc || '',
      supplierPatente: inv.supplierPatente || '',
      supplierCnss: inv.supplierCnss || '',
      supplierAddress: inv.supplierAddress || '',
      supplierCity: inv.supplierCity || '',
      clientName: inv.clientName || '',
      clientIce: inv.clientIce || '',
      invoiceNumber: inv.invoiceNumber || '',
      invoiceDate: inv.invoiceDate || '',
      amountHt: inv.amountHt ?? undefined,
      tvaRate: inv.tvaRate ?? undefined,
      amountTva: inv.amountTva ?? undefined,
      amountTtc: inv.amountTtc ?? undefined,
      discountAmount: inv.discountAmount ?? undefined,
      discountPercent: inv.discountPercent ?? undefined,
      currency: inv.currency || 'MAD',
      paymentMethod: inv.paymentMethod || '',
      paymentDueDate: inv.paymentDueDate || '',
      bankName: inv.bankName || '',
      bankRib: inv.bankRib || '',
    })
  }

  useEffect(load, [id])

  const handleSync = async () => {
    if (!id) return
    setSyncing(true)
    try {
      const updated = await syncToSage(Number(id))
      setInvoice(updated)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Sync failed')
    } finally { setSyncing(false) }
  }

  const handleSave = async () => {
    if (!id) return
    setSaving(true); setSaveMsg(null)
    try {
      const updated = await updateInvoice(Number(id), form)
      setInvoice(updated); initForm(updated)
      setEditing(false)
      setSaveMsg({ ok: true, text: 'Facture mise a jour.' })
    } catch (e: unknown) {
      setSaveMsg({ ok: false, text: e instanceof Error ? e.message : 'Erreur de sauvegarde' })
    } finally { setSaving(false) }
  }

  const handleCancel = () => {
    if (invoice) initForm(invoice)
    setEditing(false); setSaveMsg(null)
  }

  const setField = (key: keyof InvoiceUpdateRequest, value: string | number | undefined) => {
    setForm(prev => ({ ...prev, [key]: value }))
  }

  if (error) {
    return <div className="card error-card"><AlertCircle size={18} /> {error}</div>
  }
  if (!invoice) return <div className="loading">Chargement...</div>

  const fmt = (n: number | null) =>
    n != null ? n.toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '—'

  const fileUrl = id ? getInvoiceFileUrl(Number(id)) : ''
  const isPdf = invoice.fileName.toLowerCase().endsWith('.pdf')
  const isImage = /\.(png|jpe?g|tiff?|bmp|gif|webp)$/i.test(invoice.fileName)

  const EditField = ({ label, field, type = 'text', mono = false }: {
    label: string; field: keyof InvoiceUpdateRequest; type?: string; mono?: boolean
  }) => {
    const val = form[field]
    return editing ? (
      <div className="detail-row">
        <dt>{label}</dt>
        <dd>
          <input
            type={type}
            className={`form-input form-input-sm${mono ? ' mono-input' : ''}`}
            value={val ?? ''}
            onChange={e => setField(field, type === 'number' ? (e.target.value ? Number(e.target.value) : undefined) : e.target.value)}
          />
        </dd>
      </div>
    ) : (
      <div className="detail-row">
        <dt>{label}</dt>
        <dd className={mono ? 'mono' : ''}>{val != null && val !== '' ? String(val) : '—'}</dd>
      </div>
    )
  }

  return (
    <div>
      <div className="page-header">
        <h1>
          <Link to="/invoices" className="back-link"><ArrowLeft size={18} /></Link>
          Facture #{invoice.id}
        </h1>
        <div className="header-actions">
          {!editing ? (
            <>
              <button className="btn btn-secondary" onClick={load}><RefreshCw size={14} /> Rafraichir</button>
              <button className="btn btn-secondary" onClick={() => setShowPreview(!showPreview)}>
                <Eye size={14} /> {showPreview ? 'Masquer apercu' : 'Apercu'}
              </button>
              <button className="btn btn-primary" onClick={() => setEditing(true)}><Pencil size={14} /> Corriger</button>
              {invoice.rawText && (
                <button className="btn btn-secondary" onClick={() => setShowRawText(!showRawText)}>
                  <FileText size={14} /> {showRawText ? 'Masquer OCR' : 'Texte OCR'}
                </button>
              )}
              {invoice.status === 'READY_FOR_SAGE' && (
                <button className="btn btn-primary" onClick={handleSync} disabled={syncing}>
                  {syncing ? <><Loader2 size={14} className="spin" /> Sync...</> : <><Send size={14} /> Synchroniser Sage</>}
                </button>
              )}
            </>
          ) : (
            <>
              <button className="btn btn-secondary" onClick={handleCancel}><X size={14} /> Annuler</button>
              <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Save size={14} /> Sauvegarder</>}
              </button>
            </>
          )}
        </div>
      </div>

      {saveMsg && (
        <div className={`result-banner ${saveMsg.ok ? 'success' : 'error'} mb-3`}>
          {saveMsg.ok ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
          <span>{saveMsg.text}</span>
        </div>
      )}

      {editing && (
        <div className="card" style={{ background: 'var(--warning-light)', borderColor: '#e5c87a' }}>
          <p style={{ fontSize: 12, color: '#7a5c0a', fontWeight: 600, margin: 0 }}>
            <Pencil size={12} style={{ verticalAlign: -1, marginRight: 6 }} />
            Mode correction — modifiez les champs puis cliquez "Sauvegarder".
          </p>
        </div>
      )}

      {/* ===== SPLIT VIEW: Document + Data ===== */}
      <div className="split-view">

        {/* LEFT: Document preview */}
        {showPreview && (
          <div className="split-preview">
            <div className="preview-toolbar">
              <span className="preview-title"><FileText size={13} /> {invoice.fileName}</span>
              <div className="preview-zoom">
                <button onClick={() => setPreviewZoom(z => Math.max(50, z - 25))} className="zoom-btn"><ZoomOut size={14} /></button>
                <span className="zoom-value">{previewZoom}%</span>
                <button onClick={() => setPreviewZoom(z => Math.min(200, z + 25))} className="zoom-btn"><ZoomIn size={14} /></button>
              </div>
            </div>
            <div className="preview-container">
              {isPdf ? (
                <iframe
                  src={`${fileUrl}#toolbar=1&navpanes=0&view=FitH`}
                  title="Apercu facture"
                  className="preview-iframe"
                  style={{ transform: `scale(${previewZoom / 100})`, transformOrigin: 'top left', width: `${10000 / previewZoom}%`, height: `${10000 / previewZoom}%` }}
                />
              ) : isImage ? (
                <img
                  src={fileUrl}
                  alt="Apercu facture"
                  className="preview-image"
                  style={{ transform: `scale(${previewZoom / 100})`, transformOrigin: 'top left' }}
                />
              ) : (
                <div className="preview-unsupported">
                  <FileText size={40} />
                  <p>Apercu non disponible pour ce format</p>
                </div>
              )}
            </div>
          </div>
        )}

        {/* RIGHT: Extracted data */}
        <div className="split-data" style={showPreview ? undefined : { maxWidth: '100%', flex: '1 1 100%' }}>

          {showRawText && invoice.rawText && (
            <div className="card">
              <h2><FileText size={14} /> Texte OCR brut</h2>
              <pre className="raw-text-box">{invoice.rawText}</pre>
            </div>
          )}

          <div className="detail-grid">
            {/* General */}
            <div className="card">
              <h2><FileText size={14} /> Informations generales</h2>
              <dl className="detail-list">
                <div className="detail-row"><dt>Statut</dt><dd><StatusBadge status={invoice.status} /></dd></div>
                <EditField label="N. Facture" field="invoiceNumber" />
                <EditField label="Date facture" field="invoiceDate" type="date" />
                <div className="detail-row"><dt>Creee le</dt><dd>{new Date(invoice.createdAt).toLocaleString('fr-FR')}</dd></div>
              </dl>
            </div>

            {/* OCR */}
            <div className="card">
              <h2><ScanLine size={14} /> Traitement OCR</h2>
              <dl className="detail-list">
                <div className="detail-row">
                  <dt><Cpu size={14} /> Moteur</dt>
                  <dd style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span className={`ocr-engine-badge ${(invoice.ocrEngine || 'TIKA').toLowerCase()}`}>
                      {invoice.ocrEngine === 'PADDLEOCR' ? 'PaddleOCR'
                        : invoice.ocrEngine === 'TESSERACT' ? 'Tesseract'
                        : invoice.ocrEngine === 'TIKA_PLUS_TESSERACT' ? 'Tika+Tesseract'
                        : 'Tika'}
                    </span>
                    {invoice.aiUsed && <span className="ai-badge"><Brain size={10} /> IA</span>}
                  </dd>
                </div>
                {invoice.ocrPageCount != null && (
                  <div className="detail-row"><dt><Layers size={14} /> Pages</dt><dd>{invoice.ocrPageCount}</dd></div>
                )}
                {invoice.ocrConfidence != null && invoice.ocrConfidence > 0 && (
                  <div className="detail-row">
                    <dt>Confiance</dt>
                    <dd>
                      <div className="confidence-bar">
                        <div className="confidence-fill" style={{
                          width: `${Math.min(100, invoice.ocrConfidence)}%`,
                          background: invoice.ocrConfidence >= 70 ? 'var(--success)' : invoice.ocrConfidence >= 40 ? 'var(--warning)' : 'var(--danger)',
                        }} />
                      </div>
                      <span className="confidence-value">{invoice.ocrConfidence.toFixed(0)}%</span>
                    </dd>
                  </div>
                )}
              </dl>
            </div>

            {/* Supplier */}
            <div className="card">
              <h2><Building2 size={14} /> Fournisseur</h2>
              <dl className="detail-list">
                <EditField label="Raison sociale" field="supplierName" />
                <EditField label="Adresse" field="supplierAddress" />
                <EditField label="Ville" field="supplierCity" />
                <EditField label="ICE" field="supplierIce" mono />
                <EditField label="IF" field="supplierIf" mono />
                <EditField label="RC" field="supplierRc" mono />
                <EditField label="Patente" field="supplierPatente" mono />
                <EditField label="CNSS" field="supplierCnss" mono />
              </dl>
            </div>

            {/* Amounts */}
            <div className="card">
              <h2><Banknote size={14} /> Montants</h2>
              <dl className="detail-list">
                <EditField label="Montant HT" field="amountHt" type="number" />
                <EditField label="Taux TVA %" field="tvaRate" type="number" />
                <EditField label="Montant TVA" field="amountTva" type="number" />
                {editing ? (
                  <EditField label="Montant TTC" field="amountTtc" type="number" />
                ) : (
                  <div className="detail-row highlight">
                    <dt>Montant TTC</dt>
                    <dd>{fmt(invoice.amountTtc)} {invoice.currency}</dd>
                  </div>
                )}
                <EditField label="Remise %" field="discountPercent" type="number" />
                <EditField label="Devise" field="currency" />
              </dl>
            </div>

            {/* Payment */}
            <div className="card">
              <h2><CreditCard size={14} /> Paiement</h2>
              <dl className="detail-list">
                <EditField label="Mode" field="paymentMethod" />
                <EditField label="Echeance" field="paymentDueDate" type="date" />
                <EditField label="Banque" field="bankName" />
                <EditField label="RIB" field="bankRib" mono />
                <div className="detail-row">
                  <dt>Sage</dt>
                  <dd>{invoice.sageSynced ? `Synchro — ${invoice.sageReference}` : 'Non synchronise'}</dd>
                </div>
              </dl>
            </div>

            {/* Client */}
            <div className="card">
              <h2><Building2 size={14} /> Client</h2>
              <dl className="detail-list">
                <EditField label="Nom" field="clientName" />
                <EditField label="ICE" field="clientIce" mono />
              </dl>
            </div>
          </div>

          {/* Line items */}
          {invoice.lineItems.length > 0 && (
            <div className="card">
              <h2>Lignes de facture ({invoice.lineItems.length})</h2>
              <table className="invoice-table">
                <thead>
                  <tr>
                    <th>#</th><th>Description</th><th>Qte</th><th>Unite</th>
                    <th>P.U. HT</th><th>TVA %</th><th>Total HT</th><th>Total TTC</th>
                  </tr>
                </thead>
                <tbody>
                  {invoice.lineItems.map(line => (
                    <tr key={line.id}>
                      <td>{line.lineNumber}</td>
                      <td>{line.description || '—'}</td>
                      <td className="cell-amount">{line.quantity ?? '—'}</td>
                      <td>{line.unit || '—'}</td>
                      <td className="cell-amount">{fmt(line.unitPriceHt)}</td>
                      <td className="cell-amount">{line.tvaRate != null ? `${line.tvaRate}%` : '—'}</td>
                      <td className="cell-amount">{fmt(line.totalHt)}</td>
                      <td className="cell-amount">{fmt(line.totalTtc)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {invoice.errorMessage && (
            <div className="card error-card">
              <h2><AlertCircle size={16} /> Erreur</h2>
              <p>{invoice.errorMessage}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
