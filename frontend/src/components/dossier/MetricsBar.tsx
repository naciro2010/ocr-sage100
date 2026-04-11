import { memo } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { Banknote, FolderOpen, FileCheck } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  nbConformes: number
  fmt: (n: number | null | undefined) => string
}

export default memo(function MetricsBar({ dossier, nbConformes, fmt }: Props) {
  return (
    <div className="stats-grid">
      <div className="stat-card">
        <div className="stat-icon teal"><Banknote size={18} /></div>
        <div className="stat-value">{fmt(dossier.montantTtc)}</div>
        <div className="stat-label">Montant TTC</div>
      </div>
      <div className="stat-card">
        <div className="stat-icon blue"><Banknote size={18} /></div>
        <div className="stat-value">{fmt(dossier.montantNetAPayer ?? dossier.montantHt)}</div>
        <div className="stat-label">{dossier.montantNetAPayer ? 'Net a payer' : 'Montant HT'}</div>
      </div>
      <div className="stat-card">
        <div className="stat-icon amber"><FolderOpen size={18} /></div>
        <div className="stat-value">{dossier.documents.length}</div>
        <div className="stat-label">Documents</div>
      </div>
      <div className="stat-card">
        <div className="stat-icon green"><FileCheck size={18} /></div>
        <div className="stat-value">{nbConformes}/{dossier.resultatsValidation.length}</div>
        <div className="stat-label">Checks conformes</div>
      </div>
    </div>
  )
})
