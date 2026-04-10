import type { DocumentInfo } from '../api/dossierTypes'
import { TYPE_DOCUMENT_LABELS } from '../api/dossierTypes'
import type { DocProgress } from '../hooks/useDocumentEvents'
import { FileText, ScanLine, Brain, CheckCircle, AlertTriangle, Loader2, XCircle } from 'lucide-react'

const STEPS = [
  { key: 'upload', label: 'Upload', icon: FileText },
  { key: 'ocr', label: 'OCR', icon: ScanLine },
  { key: 'classify', label: 'Classification', icon: Brain },
  { key: 'extract', label: 'Extraction', icon: CheckCircle },
]

const STEP_INDEX: Record<string, number> = { upload: 0, ocr: 1, classify: 2, extract: 3 }

function getStepState(doc: DocumentInfo, liveProgress?: DocProgress) {
  // Use live SSE progress if available
  if (liveProgress) {
    const idx = STEP_INDEX[liveProgress.step] ?? 0
    if (liveProgress.statut === 'active') return { current: idx, error: false, detail: liveProgress.detail }
    if (liveProgress.statut === 'done') return { current: idx + 1, error: false, detail: liveProgress.detail }
    if (liveProgress.statut === 'error') return { current: idx, error: true, detail: liveProgress.detail }
  }

  // Fallback to document status
  const statut = doc.statutExtraction
  if (statut === 'EN_ATTENTE') return { current: 0, error: false }
  if (statut === 'EN_COURS') return { current: 2, error: false }
  if (statut === 'ERREUR') return { current: 2, error: true, detail: doc.erreurExtraction }
  if (statut === 'EXTRAIT') return { current: 4, error: false }
  return { current: 0, error: false }
}

interface Props {
  doc: DocumentInfo
  liveProgress?: DocProgress
}

export default function DocumentPipeline({ doc, liveProgress }: Props) {
  const { current, error, detail } = getStepState(doc, liveProgress)
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

      {/* Live detail or error */}
      {detail && !error && (
        <div style={{ marginTop: 6, fontSize: 10, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: 4 }}>
          {current < 4 && <Loader2 size={9} className="spin" />}
          {detail}
        </div>
      )}
      {error && (detail || doc.erreurExtraction) && (
        <div className="doc-pipeline-error">
          <AlertTriangle size={10} /> {detail || doc.erreurExtraction}
        </div>
      )}
    </div>
  )
}
