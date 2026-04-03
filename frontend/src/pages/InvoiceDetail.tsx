import { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getInvoice, syncToSage } from '../api/client'
import type { Invoice } from '../api/types'
import StatusBadge from '../components/StatusBadge'
import {
  ArrowLeft,
  RefreshCw,
  Send,
  FileText,
  Building2,
  Calendar,
  Hash,
  Banknote,
  AlertCircle,
  Loader2,
  CreditCard,
  MapPin,
  ShieldCheck,
  ScanLine,
  Cpu,
  Layers,
} from 'lucide-react'

export default function InvoiceDetail() {
  const { id } = useParams<{ id: string }>()
  const [invoice, setInvoice] = useState<Invoice | null>(null)
  const [error, setError] = useState('')
  const [syncing, setSyncing] = useState(false)

  const load = () => {
    if (!id) return
    getInvoice(Number(id))
      .then(setInvoice)
      .catch(e => setError(e.message))
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

  const hasSupplierFiscal = invoice.supplierIce || invoice.supplierIf || invoice.supplierRc || invoice.supplierPatente

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
          <button className="btn btn-secondary" onClick={load}>
            <RefreshCw size={16} /> Rafraîchir
          </button>
          {invoice.status === 'READY_FOR_SAGE' && (
            <button className="btn btn-primary" onClick={handleSync} disabled={syncing}>
              {syncing ? (
                <><Loader2 size={16} className="spin" /> Sync en cours...</>
              ) : (
                <><Send size={16} /> Synchroniser Sage 1000</>
              )}
            </button>
          )}
        </div>
      </div>

      <div className="detail-grid">
        {/* General info */}
        <div className="card">
          <h2><FileText size={16} /> Informations générales</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt>Fichier</dt>
              <dd>{invoice.fileName}</dd>
            </div>
            <div className="detail-row">
              <dt>Statut</dt>
              <dd><StatusBadge status={invoice.status} /></dd>
            </div>
            <div className="detail-row">
              <dt><Hash size={16} /> N° Facture</dt>
              <dd>{invoice.invoiceNumber || '—'}</dd>
            </div>
            <div className="detail-row">
              <dt><Calendar size={16} /> Date facture</dt>
              <dd>
                {invoice.invoiceDate
                  ? new Date(invoice.invoiceDate).toLocaleDateString('fr-FR')
                  : '—'}
              </dd>
            </div>
            <div className="detail-row">
              <dt>Créée le</dt>
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
            <div className="detail-row">
              <dt>Raison sociale</dt>
              <dd>{invoice.supplierName || '—'}</dd>
            </div>
            {invoice.supplierAddress && (
              <div className="detail-row">
                <dt><MapPin size={16} /> Adresse</dt>
                <dd>{invoice.supplierAddress}{invoice.supplierCity ? `, ${invoice.supplierCity}` : ''}</dd>
              </div>
            )}
            {hasSupplierFiscal && (
              <>
                {invoice.supplierIce && (
                  <div className="detail-row">
                    <dt><ShieldCheck size={16} /> ICE</dt>
                    <dd className="mono">{invoice.supplierIce}</dd>
                  </div>
                )}
                {invoice.supplierIf && (
                  <div className="detail-row">
                    <dt>IF</dt>
                    <dd className="mono">{invoice.supplierIf}</dd>
                  </div>
                )}
                {invoice.supplierRc && (
                  <div className="detail-row">
                    <dt>RC</dt>
                    <dd className="mono">{invoice.supplierRc}</dd>
                  </div>
                )}
                {invoice.supplierPatente && (
                  <div className="detail-row">
                    <dt>Patente</dt>
                    <dd className="mono">{invoice.supplierPatente}</dd>
                  </div>
                )}
              </>
            )}
          </dl>
        </div>

        {/* Amounts */}
        <div className="card">
          <h2><Banknote size={16} /> Montants</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt>Montant HT</dt>
              <dd>{fmt(invoice.amountHt)} {invoice.currency}</dd>
            </div>
            {invoice.discountAmount != null && (
              <div className="detail-row">
                <dt>Remise{invoice.discountPercent != null ? ` (${invoice.discountPercent}%)` : ''}</dt>
                <dd>-{fmt(invoice.discountAmount)} {invoice.currency}</dd>
              </div>
            )}
            <div className="detail-row">
              <dt>TVA{invoice.tvaRate != null ? ` (${invoice.tvaRate}%)` : ''}</dt>
              <dd>{fmt(invoice.amountTva)} {invoice.currency}</dd>
            </div>
            <div className="detail-row highlight">
              <dt>Montant TTC</dt>
              <dd>{fmt(invoice.amountTtc)} {invoice.currency}</dd>
            </div>
          </dl>
        </div>

        {/* Payment info */}
        <div className="card">
          <h2><CreditCard size={16} /> Paiement & Sage 1000</h2>
          <dl className="detail-list">
            {invoice.paymentMethod && (
              <div className="detail-row">
                <dt>Mode de paiement</dt>
                <dd>{invoice.paymentMethod}</dd>
              </div>
            )}
            {invoice.paymentDueDate && (
              <div className="detail-row">
                <dt>Échéance</dt>
                <dd>{new Date(invoice.paymentDueDate).toLocaleDateString('fr-FR')}</dd>
              </div>
            )}
            {invoice.bankName && (
              <div className="detail-row">
                <dt>Banque</dt>
                <dd>{invoice.bankName}</dd>
              </div>
            )}
            {invoice.bankRib && (
              <div className="detail-row">
                <dt>RIB</dt>
                <dd className="mono">{invoice.bankRib}</dd>
              </div>
            )}
            <div className="detail-row">
              <dt>Sage 1000</dt>
              <dd>{invoice.sageSynced ? `Oui — ${invoice.sageReference}` : 'Non synchronisé'}</dd>
            </div>
          </dl>
        </div>

        {/* Client info */}
        {(invoice.clientName || invoice.clientIce) && (
          <div className="card">
            <h2>Client (votre entreprise)</h2>
            <dl className="detail-list">
              {invoice.clientName && (
                <div className="detail-row">
                  <dt>Nom</dt>
                  <dd>{invoice.clientName}</dd>
                </div>
              )}
              {invoice.clientIce && (
                <div className="detail-row">
                  <dt>ICE</dt>
                  <dd className="mono">{invoice.clientIce}</dd>
                </div>
              )}
            </dl>
          </div>
        )}

        {/* Line items */}
        {invoice.lineItems.length > 0 && (
          <div className="card" style={{ gridColumn: '1 / -1' }}>
            <h2>Lignes de facture ({invoice.lineItems.length})</h2>
            <table className="invoice-table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Description</th>
                  <th>Qté</th>
                  <th>Unité</th>
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
