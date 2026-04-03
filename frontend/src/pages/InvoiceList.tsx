import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listInvoices } from '../api/client'
import type { Invoice, Page } from '../api/types'
import { FileText, ChevronLeft, ChevronRight, RefreshCw, ScanLine } from 'lucide-react'
import StatusBadge from '../components/StatusBadge'

export default function InvoiceList() {
  const [data, setData] = useState<Page<Invoice> | null>(null)
  const [page, setPage] = useState(0)
  const [error, setError] = useState('')

  const load = () => {
    listInvoices(page)
      .then(setData)
      .catch(e => setError(e.message))
  }

  useEffect(load, [page])

  return (
    <div>
      <div className="page-header">
        <h1><FileText size={24} /> Factures</h1>
        <button className="btn btn-secondary" onClick={load}>
          <RefreshCw size={16} /> Rafraîchir
        </button>
      </div>

      {error && <div className="card error-card">{error}</div>}

      <div className="card">
        <table className="invoice-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Fichier</th>
              <th>Fournisseur</th>
              <th>N° Facture</th>
              <th>Montant TTC</th>
              <th><ScanLine size={12} /> OCR</th>
              <th>Statut</th>
              <th>Date</th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(inv => (
              <tr key={inv.id}>
                <td>
                  <Link to={`/invoices/${inv.id}`}>#{inv.id}</Link>
                </td>
                <td className="cell-filename">{inv.fileName}</td>
                <td>{inv.supplierName || '—'}</td>
                <td>{inv.invoiceNumber || '—'}</td>
                <td className="cell-amount">
                  {inv.amountTtc != null
                    ? `${inv.amountTtc.toLocaleString('fr-FR', { minimumFractionDigits: 2 })} ${inv.currency}`
                    : '—'}
                </td>
                <td>
                  <span className={`ocr-engine-badge ${(inv.ocrEngine || 'tika').toLowerCase()}`}>
                    {inv.ocrEngine === 'TESSERACT' ? 'Tesseract'
                      : inv.ocrEngine === 'TIKA_PLUS_TESSERACT' ? 'Tika+Tess'
                      : 'Tika'}
                  </span>
                </td>
                <td><StatusBadge status={inv.status} /></td>
                <td>{new Date(inv.createdAt).toLocaleDateString('fr-FR')}</td>
              </tr>
            ))}
            {data?.content.length === 0 && (
              <tr>
                <td colSpan={8} className="empty-text">
                  Aucune facture. Commencez par en uploader une !
                </td>
              </tr>
            )}
          </tbody>
        </table>

        {data && data.totalPages > 1 && (
          <div className="pagination">
            <button
              className="btn btn-secondary"
              disabled={page === 0}
              onClick={() => setPage(p => p - 1)}
            >
              <ChevronLeft size={16} /> Précédent
            </button>
            <span>
              Page {page + 1} / {data.totalPages}
            </span>
            <button
              className="btn btn-secondary"
              disabled={page >= data.totalPages - 1}
              onClick={() => setPage(p => p + 1)}
            >
              Suivant <ChevronRight size={16} />
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
