import type { DocumentInfo } from '../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../api/dossierTypes'
import { FileText, ScanLine, Brain, CheckCircle, AlertTriangle, Loader2, XCircle } from 'lucide-react'

const STEPS = [
  { key: 'upload', label: 'Upload', icon: FileText },
  { key: 'ocr', label: 'OCR', icon: ScanLine },
  { key: 'classify', label: 'Classification', icon: Brain },
  { key: 'extract', label: 'Extraction', icon: CheckCircle },
]

function getStepState(doc: DocumentInfo) {
  const statut = doc.statutExtraction
  const hasText = doc.donneesExtraites != null || (statut === 'EXTRAIT')

  if (statut === 'EN_ATTENTE') return { current: 0, error: false }
  if (statut === 'EN_COURS') return { current: 2, error: false }
  if (statut === 'ERREUR') return { current: 2, error: true }
  if (statut === 'EXTRAIT' && hasText) return { current: 4, error: false }
  if (statut === 'EXTRAIT') return { current: 3, error: false }
  return { current: 0, error: false }
}

interface Props {
  doc: DocumentInfo
}

export default function DocumentPipeline({ doc }: Props) {
  const { current, error } = getStepState(doc)
  const typeLabel = TYPE_DOCUMENT_LABELS[doc.typeDocument] || doc.typeDocument

  return (
    <div className="doc-pipeline">
      <div className="doc-pipeline-header">
        <span className="doc-pipeline-name" title={doc.nomFichier}>{doc.nomFichier}</span>
        <span className="doc-pipeline-type">{typeLabel}</span>
      </div>

      <div className="doc-pipeline-steps">
        {STEPS.map((step, i) => {
          const isDone = i < current
          const isActive = i === current && !error
          const isError = i === current && error
          const Icon = step.icon

          return (
            <div key={step.key} className="doc-pipeline-step-wrap">
              {/* Connector */}
              {i > 0 && (
                <div className={`doc-pipeline-connector ${isDone ? 'done' : isActive ? 'active' : ''}`}>
                  {isActive && <div className="doc-pipeline-pulse" />}
                </div>
              )}

              {/* Step */}
              <div className={`doc-pipeline-step ${isDone ? 'done' : isActive ? 'active' : isError ? 'error' : 'pending'}`}>
                <div className="doc-pipeline-icon">
                  {isDone && <CheckCircle size={12} />}
                  {isActive && <Loader2 size={12} className="spin" />}
                  {isError && <XCircle size={12} />}
                  {!isDone && !isActive && !isError && <Icon size={12} />}
                </div>
                <span className="doc-pipeline-label">{step.label}</span>
              </div>
            </div>
          )
        })}
      </div>

      {/* Error message */}
      {error && doc.erreurExtraction && (
        <div className="doc-pipeline-error">
          <AlertTriangle size={10} /> {doc.erreurExtraction}
        </div>
      )}
    </div>
  )
}
