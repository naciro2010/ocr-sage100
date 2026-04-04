import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getInvoice, syncToSage, updateInvoice } from '../api/client'
import type { Invoice, InvoiceUpdateRequest } from '../api/types'
import StatusBadge from '../components/StatusBadge'
import {
  ArrowLeft,
  RefreshCw,
  Send,
  FileText,
  Building2,
  Banknote,
  AlertCircle,
  Loader2,
  CreditCard,
  ScanLine,
  Cpu,
  Layers,
  Pencil,
  Save,
  X,
  Eye,
  CheckCircle,
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
    } finally {
      setSyncing(false)
    }
  }

  const handleSave = async () => {
    if (!id) return
    setSaving(true); setSaveMsg(null)
    try {
      const updated = await updateInvoice(Number(id), form)
      setInvoice(updated); initForm(updated)
      setEditing(false)
      setSaveMsg({ ok: true, text: 'Facture mise a jour avec succes !' })
    } catch (e: unknown) {
      setSaveMsg({ ok: false, text: e instanceof Error ? e.message : 'Erreur de sauvegarde' })
    } finally {
      setSaving(false)
    }
  }

  const handleCancel = () => {
    if (invoice) initForm(invoice)
    setEditing(false); setSaveMsg(null)
  }

  const setField = (key: keyof InvoiceUpdateRequest, value: string | number | undefined) => {
    setForm(prev => ({ ...prev, [key]: value }))
  }

  if (error) {
    return (
      <div className="card error-card">
        <AlertCircle size={20} /> {error}
      </div>
    )
  }

  if (!invoice) return <div className="loading">Chargement...</div>

  const fmt = (n: number | null) =>
    n != null
      ? n.toLocaleString('fr-FR', { minimumFractionDigits: 2 })
      : '—'

  // Edit field helper
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
            className="form-input form-input-sm"
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
          <Link to="/invoices" className="back-link">
            <ArrowLeft size={20} />
          </Link>
          Facture #{invoice.id}
        </h1>
        <div className="header-actions">
          {!editing ? (
            <>
              <button className="btn btn-secondary" onClick={load}>
                <RefreshCw size={16} /> Rafraichir
              </button>
              <button className="btn btn-primary" onClick={() => setEditing(true)}>
                <Pencil size={16} /> Corriger
              </button>
              {invoice.rawText && (
                <button className="btn btn-secondary" onClick={() => setShowRawText(!showRawText)}>
                  <Eye size={16} /> {showRawText ? 'Masquer texte OCR' : 'Voir texte OCR'}
                </button>
              )}
              {invoice.status === 'READY_FOR_SAGE' && (
                <button className="btn btn-primary" onClick={handleSync} disabled={syncing}>
                  {syncing ? (
                    <><Loader2 size={16} className="spin" /> Sync en cours...</>
                  ) : (
                    <><Send size={16} /> Synchroniser Sage</>
                  )}
                </button>
              )}
            </>
          ) : (
            <>
              <button className="btn btn-secondary" onClick={handleCancel}>
                <X size={16} /> Annuler
              </button>
              <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? (
                  <><Loader2 size={16} className="spin" /> Sauvegarde...</>
                ) : (
                  <><Save size={16} /> Sauvegarder</>
                )}
              </button>
            </>
          )}
        </div>
      </div>

      {saveMsg && (
        <div className={`result-banner ${saveMsg.ok ? 'success' : 'error'} mb-3`}>
          {saveMsg.ok ? <CheckCircle size={18} /> : <AlertCircle size={18} />}
          <span>{saveMsg.text}</span>
        </div>
      )}

      {editing && (
        <div className="card" style={{ background: '#fffbeb', borderColor: '#fbbf24' }}>
          <p style={{ fontSize: '13px', color: '#92400e', fontWeight: 600, margin: 0 }}>
            <Pencil size={14} style={{ verticalAlign: 'middle', marginRight: 6 }} />
            Mode correction — modifiez les champs puis cliquez "Sauvegarder". La validation sera re-executee automatiquement.
          </p>
        </div>
      )}

      {/* Raw OCR text */}
      {showRawText && invoice.rawText && (
        <div className="card" style={{ gridColumn: '1 / -1' }}>
          <h2><FileText size={16} /> Texte OCR brut</h2>
          <pre className="raw-text-box">{invoice.rawText}</pre>
        </div>
      )}

      <div className="detail-grid">
        {/* General info */}
        <div className="card">
          <h2><FileText size={16} /> Informations generales</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt>Fichier</dt>
              <dd>{invoice.fileName}</dd>
            </div>
            <div className="detail-row">
              <dt>Statut</dt>
              <dd><StatusBadge status={invoice.status} /></dd>
            </div>
            <EditField label="N° Facture" field="invoiceNumber" />
            <EditField label="Date facture" field="invoiceDate" type="date" />
            <div className="detail-row">
              <dt>Creee le</dt>
              <dd>{new Date(invoice.createdAt).toLocaleString('fr-FR')}</dd>
            </div>
          </dl>
        </div>

        {/* OCR Processing info */}
        <div className="card">
          <h2><ScanLine size={16} /> Traitement OCR</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt><Cpu size={16} /> Moteur OCR</dt>
              <dd>
                <span className={`ocr-engine-badge ${(invoice.ocrEngine || 'TIKA').toLowerCase()}`}>
                  {invoice.ocrEngine === 'TESSERACT' ? 'Tesseract + Preprocessing'
                    : invoice.ocrEngine === 'TIKA_PLUS_TESSERACT' ? 'Tika + Tesseract'
                    : 'Apache Tika'}
                </span>
              </dd>
            </div>
            {invoice.ocrPageCount != null && (
              <div className="detail-row">
                <dt><Layers size={16} /> Pages traitees</dt>
                <dd>{invoice.ocrPageCount} page{invoice.ocrPageCount > 1 ? 's' : ''}</dd>
              </div>
            )}
            {invoice.ocrConfidence != null && invoice.ocrConfidence > 0 && (
              <div className="detail-row">
                <dt>Confiance OCR</dt>
                <dd>
                  <div className="confidence-bar">
                    <div
                      className="confidence-fill"
                      style={{
                        width: `${Math.min(100, invoice.ocrConfidence)}%`,
                        background: invoice.ocrConfidence >= 70 ? 'var(--success)'
                          : invoice.ocrConfidence >= 40 ? 'var(--warning)'
                          : 'var(--danger)',
                      }}
                    />
                  </div>
                  <span className="confidence-value">{invoice.ocrConfidence.toFixed(0)}%</span>
                </dd>
              </div>
            )}
            <div className="detail-row">
              <dt>Preprocessing</dt>
              <dd>
                {invoice.ocrEngine === 'TESSERACT' || invoice.ocrEngine === 'TIKA_PLUS_TESSERACT' ? (
                  <div className="preprocessing-tags">
                    <span className="preprocess-tag">Deskew</span>
                    <span className="preprocess-tag">Binarisation</span>
                    <span className="preprocess-tag">Debruitage</span>
                    <span className="preprocess-tag">Auto-scale</span>
                  </div>
                ) : (
                  <span className="text-muted">Non applique (PDF natif)</span>
                )}
              </dd>
            </div>
          </dl>
        </div>

        {/* Supplier info */}
        <div className="card">
          <h2><Building2 size={16} /> Fournisseur</h2>
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
          <h2><Banknote size={16} /> Montants</h2>
          <dl className="detail-list">
            <EditField label="Montant HT" field="amountHt" type="number" />
            <EditField label="Remise %" field="discountPercent" type="number" />
            <EditField label="Remise montant" field="discountAmount" type="number" />
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
            <EditField label="Devise" field="currency" />
          </dl>
        </div>

        {/* Payment info */}
        <div className="card">
          <h2><CreditCard size={16} /> Paiement</h2>
          <dl className="detail-list">
            <EditField label="Mode de paiement" field="paymentMethod" />
            <EditField label="Echeance" field="paymentDueDate" type="date" />
            <EditField label="Banque" field="bankName" />
            <EditField label="RIB" field="bankRib" mono />
            <div className="detail-row">
              <dt>Sage</dt>
              <dd>{invoice.sageSynced ? `Oui — ${invoice.sageReference}` : 'Non synchronise'}</dd>
            </div>
          </dl>
        </div>

        {/* Client info */}
        <div className="card">
          <h2><Building2 size={16} /> Client (votre entreprise)</h2>
          <dl className="detail-list">
            <EditField label="Nom" field="clientName" />
            <EditField label="ICE" field="clientIce" mono />
          </dl>
        </div>

        {/* Line items */}
        {invoice.lineItems.length > 0 && (
          <div className="card" style={{ gridColumn: '1 / -1' }}>
            <h2>Lignes de facture ({invoice.lineItems.length})</h2>
            <table className="invoice-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Description</th>
                  <th>Qte</th>
                  <th>Unite</th>
                  <th>P.U. HT</th>
                  <th>TVA %</th>
                  <th>Total HT</th>
                  <th>Total TTC</th>
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
          <div className="card error-card" style={{ gridColumn: '1 / -1' }}>
            <h2><AlertCircle size={18} /> Erreur</h2>
            <p>{invoice.errorMessage}</p>
          </div>
        )}
      </div>
    </div>
  )
}
