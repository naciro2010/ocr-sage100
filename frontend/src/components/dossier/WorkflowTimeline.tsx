import { memo } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { CheckCircle, XCircle, Loader2 } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  hasProcessing: boolean
}

export default memo(function WorkflowTimeline({ dossier, hasProcessing }: Props) {
  const hasDocs = dossier.documents.length > 0
  const allExtracted = hasDocs && dossier.documents.every(d => d.statutExtraction === 'EXTRAIT')
  const hasErrors = dossier.documents.some(d => d.statutExtraction === 'ERREUR')
  const hasValidation = dossier.resultatsValidation.length > 0
  const isValidated = dossier.statut === 'VALIDE'
  const isRejected = dossier.statut === 'REJETE'

  const steps = [
    { label: 'Upload', done: hasDocs, active: !hasDocs, error: false },
    { label: 'Extraction', done: allExtracted, active: hasDocs && !allExtracted, error: hasErrors },
    { label: 'Verification', done: hasValidation, active: allExtracted && !hasValidation, error: false },
    { label: 'Decision', done: isValidated || isRejected, active: hasValidation && !isValidated && !isRejected, error: isRejected },
  ]

  return (
    <div className="timeline">
      {steps.map((step, i) => (
        <div key={step.label} style={{ display: 'flex', alignItems: 'center' }}>
          <div className={`timeline-step ${step.error ? 'error' : step.done ? 'done' : step.active ? 'active' : ''}`}>
            {step.done ? <CheckCircle size={12} /> : step.error ? <XCircle size={12} /> : step.active ? <Loader2 size={12} className={step.active && i === 1 && hasProcessing ? 'spin' : ''} /> : null}
            {step.label}
          </div>
          {i < steps.length - 1 && <div className={`timeline-connector ${step.done ? 'done' : ''}`} />}
        </div>
      ))}
    </div>
  )
})
