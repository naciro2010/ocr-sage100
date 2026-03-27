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
                <><Send size={16} /> Synchroniser Sage 100</>
              )}
            </button>
          )}
        </div>
      </div>

      <div className="detail-grid">
        <div className="card">
          <h2>Informations générales</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt><FileText size={16} /> Fichier</dt>
              <dd>{invoice.fileName}</dd>
            </div>
            <div className="detail-row">
              <dt>Statut</dt>
              <dd><StatusBadge status={invoice.status} /></dd>
            </div>
            <div className="detail-row">
              <dt><Calendar size={16} /> Créée le</dt>
              <dd>{new Date(invoice.createdAt).toLocaleString('fr-FR')}</dd>
            </div>
            <div className="detail-row">
              <dt>Mise à jour</dt>
              <dd>{new Date(invoice.updatedAt).toLocaleString('fr-FR')}</dd>
            </div>
          </dl>
        </div>

        <div className="card">
          <h2>Données extraites</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt><Building2 size={16} /> Fournisseur</dt>
              <dd>{invoice.supplierName || '—'}</dd>
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
              <dt><Banknote size={16} /> Montant HT</dt>
              <dd>{fmt(invoice.amountHt)} {invoice.currency}</dd>
            </div>
            <div className="detail-row">
              <dt>TVA</dt>
              <dd>{fmt(invoice.amountTva)} {invoice.currency}</dd>
            </div>
            <div className="detail-row highlight">
              <dt>Montant TTC</dt>
              <dd>{fmt(invoice.amountTtc)} {invoice.currency}</dd>
            </div>
          </dl>
        </div>

        <div className="card">
          <h2>Sage 100</h2>
          <dl className="detail-list">
            <div className="detail-row">
              <dt>Synchronisé</dt>
              <dd>{invoice.sageSynced ? 'Oui' : 'Non'}</dd>
            </div>
            {invoice.sageReference && (
              <div className="detail-row">
                <dt>Référence Sage</dt>
                <dd>{invoice.sageReference}</dd>
              </div>
            )}
          </dl>
        </div>

        {invoice.errorMessage && (
          <div className="card error-card">
            <h2><AlertCircle size={18} /> Erreur</h2>
            <p>{invoice.errorMessage}</p>
          </div>
        )}
      </div>
    </div>
  )
}
