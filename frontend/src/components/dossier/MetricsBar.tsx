import { memo } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { Banknote, ShieldCheck, CheckCircle, Loader2 } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  nbConformes: number
  fmt: (n: number | null | undefined) => string
  hasProcessing?: boolean
}

export default memo(function MetricsBar({ dossier, nbConformes, fmt, hasProcessing }: Props) {
  const nbTotal = dossier.resultatsValidation.length
  // Couleurs semantiques strictes (CLAUDE.md / ux-finance-designer) :
  //   rouge  = au moins un controle NON_CONFORME (verdict bloquant)
  //   ambre  = aucun NOK mais des AVERTISSEMENT
  //   vert   = tous CONFORME ou NON_APPLICABLE
  // Avant ce fix, l'ambre etait affiche meme en presence de NOK, ce qui
  // sous-evaluait la criticite et pouvait pousser a valider un dossier KO.
  const nbNonConformes = dossier.resultatsValidation.filter(r => r.statut === 'NON_CONFORME').length
  const checksColor = nbNonConformes > 0 ? 'var(--danger)'
    : nbConformes < nbTotal ? 'var(--warning)'
    : 'var(--success)'
  const hasDocs = dossier.documents.length > 0
  const allExtracted = hasDocs && dossier.documents.every(d => d.statutExtraction === 'EXTRAIT')
  const hasValidation = nbTotal > 0
  const isFinal = dossier.statut === 'VALIDE' || dossier.statut === 'REJETE'

  const steps = [
    { label: 'Upload', done: hasDocs, active: !hasDocs },
    { label: 'Extraction', done: allExtracted, active: hasDocs && !allExtracted },
    { label: 'Verification', done: hasValidation, active: allExtracted && !hasValidation },
    { label: 'Decision', done: isFinal, active: hasValidation && !isFinal },
  ]

  return (
    <div className="metrics-compact">
      {/* Montant principal */}
      <div className="metrics-amount-card">
        <div className="metrics-amount-row">
          <div>
            <div className="stat-label">Montant TTC</div>
            <div className="metrics-amount">{fmt(dossier.montantTtc)}</div>
          </div>
          {(dossier.montantNetAPayer || dossier.montantHt) && (
            <div className="metrics-secondary">
              <div className="stat-label">{dossier.montantNetAPayer ? 'Net a payer' : 'Montant HT'}</div>
              <div className="metrics-amount-sm">{fmt(dossier.montantNetAPayer ?? dossier.montantHt)}</div>
            </div>
          )}
          {nbTotal > 0 && (
            <div className="metrics-checks">
              <Banknote size={14} style={{ color: 'var(--accent)', opacity: 0.5 }} aria-hidden="true" />
              <span className="metrics-checks-label">{dossier.documents.length} docs</span>
              <span className="metrics-separator" aria-hidden="true">&middot;</span>
              <ShieldCheck size={14} style={{ color: checksColor, opacity: 0.85 }} aria-hidden="true" />
              <span className="metrics-checks-label" style={{ color: checksColor, fontWeight: nbNonConformes > 0 ? 600 : 500 }}>
                {nbConformes}/{nbTotal} OK{nbNonConformes > 0 ? ` · ${nbNonConformes} bloquant${nbNonConformes > 1 ? 's' : ''}` : ''}
              </span>
            </div>
          )}
        </div>
      </div>

      {/* Workflow compact */}
      <div className="timeline">
        {steps.map((step, i) => (
          <div key={step.label} style={{ display: 'flex', alignItems: 'center' }}>
            <div className={`timeline-step ${step.done ? 'done' : step.active ? 'active' : ''}`}>
              {step.done ? <CheckCircle size={12} /> : step.active ? <Loader2 size={12} className={step.active && i === 1 && hasProcessing ? 'spin' : ''} /> : null}
              {step.label}
            </div>
            {i < steps.length - 1 && <div className={`timeline-connector ${step.done ? 'done' : ''}`} />}
          </div>
        ))}
      </div>
    </div>
  )
})
