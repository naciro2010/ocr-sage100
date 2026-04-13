import { memo } from 'react'
import { Link } from 'react-router-dom'
import type { DossierDetail } from '../../api/dossierTypes'
import { STATUT_CONFIG } from '../../api/dossierTypes'
import {
  ArrowLeft, RefreshCw, Loader2, ShieldCheck, CheckCircle, Ban,
  FileText, Pencil, Columns2, Copy
} from 'lucide-react'

interface Props {
  dossier: DossierDetail
  id: string
  hasProcessing: boolean
  validating: boolean
  actionLoading?: boolean
  editing: boolean
  nbNonConformes: number
  showCompare: boolean
  onLoad: () => void
  onStartEdit: () => void
  onToggleCompare: () => void
  onValidate: () => void
  onValider: () => void
  onRejeter: () => void
  onReouvrir: () => void
  onCopyRef: () => void
}

export default memo(function DossierHeader({
  dossier, id, hasProcessing, validating, actionLoading, editing, nbNonConformes, showCompare,
  onLoad, onStartEdit, onToggleCompare, onValidate, onValider, onRejeter, onReouvrir, onCopyRef,
}: Props) {
  const cfg = STATUT_CONFIG[dossier.statut]

  return (
    <>
      <div className="page-header">
        <h1>
          <Link to="/dossiers" className="back-link" aria-label="Retour aux dossiers"><ArrowLeft size={20} /></Link>
          <span onClick={onCopyRef} style={{ cursor: 'pointer' }} title="Cliquer pour copier">{dossier.reference}</span>
          <button className="btn btn-secondary btn-sm" onClick={onCopyRef} aria-label="Copier la reference" style={{ padding: '2px 6px', border: 'none', background: 'transparent' }}>
            <Copy size={14} style={{ color: 'var(--ink-40)' }} />
          </button>
        </h1>
        <div className="header-actions">
          {hasProcessing && <span style={{ fontSize: 11, color: 'var(--warning)', display: 'flex', alignItems: 'center', gap: 4 }} role="status"><Loader2 size={14} className="spin" /> Extraction en cours...</span>}
          <button className="btn btn-secondary" onClick={onLoad} aria-label="Rafraichir"><RefreshCw size={15} /></button>
          {dossier.statut === 'BROUILLON' && !editing && (
            <button className="btn btn-secondary" onClick={onStartEdit}><Pencil size={15} /> Modifier</button>
          )}
          <button className="btn btn-secondary" onClick={onToggleCompare}>
            <Columns2 size={15} /> {showCompare ? 'Masquer' : 'Comparer'}
          </button>
          <button className="btn btn-primary" onClick={onValidate} disabled={validating}>
            {validating ? <><Loader2 size={15} className="spin" /> Verification...</> : <><ShieldCheck size={15} /> Verifier</>}
          </button>
          {dossier.statut !== 'VALIDE' && (
            <button className="btn btn-success" onClick={onValider} disabled={actionLoading}
              title={nbNonConformes > 0 ? `${nbNonConformes} controle(s) non conforme(s)` : ''}>
              {actionLoading ? <Loader2 size={15} className="spin" /> : <CheckCircle size={15} />} Valider
            </button>
          )}
          {dossier.statut !== 'REJETE' && (
            <button className="btn btn-danger" onClick={onRejeter} disabled={actionLoading}>
              <Ban size={15} /> Rejeter
            </button>
          )}
          {dossier.resultatsValidation.length > 0 && dossier.statut !== 'VALIDE' && (
            <Link to={`/dossiers/${id}/finalize`} className="btn btn-primary" style={{ textDecoration: 'none' }}>
              <FileText size={15} /> Finaliser
            </Link>
          )}
          {(dossier.statut === 'VALIDE' || dossier.statut === 'REJETE') && (
            <button className="btn btn-secondary" onClick={onReouvrir}>Reouvrir</button>
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-flex">
          <div className="card-flex-start">
            <span className="status-badge" style={{ background: cfg.bg, color: cfg.color, fontSize: 12 }}>{cfg.label}</span>
            <span className="tag">{dossier.type === 'BC' ? 'Bon de commande' : 'Contractuel'}</span>
            {dossier.fournisseur && <strong style={{ fontSize: 15 }}>{dossier.fournisseur}</strong>}
          </div>
          {dossier.description && <span style={{ fontSize: 13, color: 'var(--ink-50)' }}>{dossier.description}</span>}
        </div>
      </div>
    </>
  )
})
