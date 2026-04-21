import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Briefcase, Upload, FileText, CheckCircle,
  AlertTriangle, RefreshCw, Sparkles,
} from 'lucide-react'
import { uploadEngagementDocument } from '../api/engagementApi'
import type { UploadEngagementResponse } from '../api/engagementApi'
import { TYPE_CONFIG } from '../api/engagementTypes'

type UploadState =
  | { kind: 'idle' }
  | { kind: 'uploading'; filename: string }
  | { kind: 'success'; result: UploadEngagementResponse }
  | { kind: 'error'; message: string }

export default function EngagementUpload() {
  const navigate = useNavigate()
  const fileInput = useRef<HTMLInputElement>(null)
  const [state, setState] = useState<UploadState>({ kind: 'idle' })
  const [dragOver, setDragOver] = useState(false)

  async function handleFiles(files: FileList | null) {
    if (!files || files.length === 0) return
    const file = files[0]
    if (!file.name.toLowerCase().match(/\.(pdf|png|jpe?g|tiff?)$/)) {
      setState({ kind: 'error', message: 'Format non supporte (PDF/PNG/JPG/TIFF uniquement)' })
      return
    }
    setState({ kind: 'uploading', filename: file.name })
    try {
      const result = await uploadEngagementDocument(file)
      setState({ kind: 'success', result })
    } catch (e) {
      setState({ kind: 'error', message: e instanceof Error ? e.message : 'Erreur a l\'upload' })
    }
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    setDragOver(false)
    handleFiles(e.dataTransfer.files)
  }

  function reset() { setState({ kind: 'idle' }) }

  return (
    <div>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Link to="/engagements" className="btn btn-secondary btn-sm">
            <ArrowLeft size={14} /> Retour
          </Link>
          <h1 style={{ margin: 0 }}>
            <Briefcase size={18} /> Nouvel engagement <span style={{ color: 'var(--ink-40)', fontWeight: 400 }}>depuis document</span>
          </h1>
        </div>
      </div>

      {state.kind === 'idle' && <DropZone
        dragOver={dragOver}
        onDragOver={() => setDragOver(true)}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        onPick={() => fileInput.current?.click()}
        onSelect={e => handleFiles(e.target.files)}
        inputRef={fileInput}
      />}

      {state.kind === 'uploading' && <UploadingPanel filename={state.filename} />}

      {state.kind === 'error' && <ErrorPanel message={state.message} onRetry={reset} />}

      {state.kind === 'success' && <SuccessPanel result={state.result}
        onNavigate={() => navigate(`/engagements/${state.result.engagementId}`)}
        onAnother={reset}
      />}
    </div>
  )
}

function DropZone({ dragOver, onDragOver, onDragLeave, onDrop, onPick, onSelect, inputRef }: {
  dragOver: boolean
  onDragOver: () => void
  onDragLeave: () => void
  onDrop: (e: React.DragEvent) => void
  onPick: () => void
  onSelect: (e: React.ChangeEvent<HTMLInputElement>) => void
  inputRef: React.RefObject<HTMLInputElement | null>
}) {
  return (
    <div className="card">
      <div
        onClick={onPick}
        onDragOver={e => { e.preventDefault(); onDragOver() }}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
        role="button"
        tabIndex={0}
        style={{
          border: `2px dashed ${dragOver ? 'var(--accent)' : 'var(--ink-20)'}`,
          background: dragOver ? 'var(--accent-10)' : 'var(--ink-02)',
          borderRadius: 8,
          padding: 48,
          textAlign: 'center',
          cursor: 'pointer',
          transition: 'all 0.2s ease',
        }}
      >
        <Upload size={40} style={{ color: 'var(--accent)', marginBottom: 12 }} />
        <h2 style={{ margin: '0 0 6px' }}>Deposez votre document contractuel</h2>
        <p style={{ color: 'var(--ink-60)', margin: 0 }}>
          Marche public, bon de commande cadre ou contrat cadre.
        </p>
        <p style={{ color: 'var(--ink-40)', margin: '12px 0 0', fontSize: 12 }}>
          PDF, PNG, JPG, TIFF &middot; Cliquez ou glissez-deposez
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.png,.jpg,.jpeg,.tiff,.tif"
          style={{ display: 'none' }}
          onChange={onSelect}
        />
      </div>

      <div style={{ marginTop: 20, padding: 14, background: 'var(--ink-02)', borderRadius: 6, fontSize: 12 }}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <Sparkles size={14} style={{ color: 'var(--accent)', flexShrink: 0, marginTop: 2 }} />
          <div>
            <strong>Comment ca marche ?</strong>
            <ol style={{ margin: '4px 0 0 16px', padding: 0, color: 'var(--ink-60)' }}>
              <li>OCR du document (texte extrait du PDF)</li>
              <li>Classification automatique (marche / BC cadre / contrat)</li>
              <li>Extraction IA des champs (reference, montants, dates, etc.)</li>
              <li>Creation (ou mise a jour) de l'engagement correspondant</li>
            </ol>
          </div>
        </div>
      </div>

      <div style={{ marginTop: 14, textAlign: 'center' }}>
        <Link to="/engagements/manuel" className="btn btn-text" style={{ color: 'var(--ink-40)' }}>
          Creer sans document (formulaire manuel)
        </Link>
      </div>
    </div>
  )
}

function UploadingPanel({ filename }: { filename: string }) {
  return (
    <div className="card" style={{ textAlign: 'center', padding: 40 }}>
      <div style={{ position: 'relative', display: 'inline-block', marginBottom: 16 }}>
        <RefreshCw size={32} style={{ color: 'var(--accent)', animation: 'spin 1s linear infinite' }} />
      </div>
      <h2 style={{ margin: '0 0 8px' }}>Extraction en cours...</h2>
      <p style={{ color: 'var(--ink-60)', margin: 0 }}>
        <FileText size={14} style={{ display: 'inline', verticalAlign: 'middle', marginRight: 4 }} />
        {filename}
      </p>
      <p style={{ color: 'var(--ink-40)', margin: '8px 0 0', fontSize: 12 }}>
        OCR + classification + extraction Claude. Peut prendre 10-30 secondes.
      </p>
    </div>
  )
}

function ErrorPanel({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="card" style={{ textAlign: 'center', padding: 40 }}>
      <AlertTriangle size={40} style={{ color: 'var(--danger)', marginBottom: 12 }} />
      <h2 style={{ margin: '0 0 8px', color: 'var(--danger)' }}>Erreur d'extraction</h2>
      <p style={{ color: 'var(--ink-60)', margin: '0 0 16px', maxWidth: 500, marginLeft: 'auto', marginRight: 'auto' }}>{message}</p>
      <button className="btn btn-primary" onClick={onRetry}>
        <Upload size={14} /> Reessayer
      </button>
    </div>
  )
}

function SuccessPanel({ result, onNavigate, onAnother }: {
  result: UploadEngagementResponse
  onNavigate: () => void
  onAnother: () => void
}) {
  const tc = TYPE_CONFIG[result.type]
  const TypeIcon = tc.icon
  const confPct = result.confidence != null ? Math.round(result.confidence * 100) : null

  return (
    <div className="card" style={{ padding: 32 }}>
      <div style={{ textAlign: 'center', marginBottom: 20 }}>
        <CheckCircle size={40} style={{ color: 'var(--success)', marginBottom: 8 }} />
        <h2 style={{ margin: '0 0 4px', color: 'var(--success)' }}>
          {result.created ? 'Engagement cree' : 'Engagement mis a jour'}
        </h2>
        <p style={{ color: 'var(--ink-60)', margin: 0 }}>
          {result.created
            ? 'Un nouvel engagement a ete cree a partir de ce document.'
            : 'Un engagement avec la meme reference existait deja, il a ete enrichi avec les donnees extraites.'}
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16, padding: 20, background: 'var(--ink-02)', borderRadius: 6, marginBottom: 16 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase', fontWeight: 600, marginBottom: 4 }}>Type</div>
          <span className="status-badge" style={{ background: tc.bg, color: tc.color, gap: 4 }}>
            <TypeIcon size={11} /> {tc.label}
          </span>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase', fontWeight: 600, marginBottom: 4 }}>Reference</div>
          <div style={{ fontFamily: 'var(--font-mono)', fontWeight: 500 }}>{result.reference}</div>
        </div>
        {confPct !== null && (
          <div>
            <div style={{ fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase', fontWeight: 600, marginBottom: 4 }}>Confiance extraction</div>
            <div style={{
              fontWeight: 600,
              color: confPct > 80 ? 'var(--success)' : confPct > 60 ? 'var(--warning)' : 'var(--danger)',
            }}>
              {confPct}%
            </div>
          </div>
        )}
      </div>

      {result.warnings.length > 0 && (
        <div style={{ padding: 12, background: 'var(--warning-bg)', borderRadius: 6, marginBottom: 16 }}>
          <div style={{ display: 'flex', gap: 6, alignItems: 'flex-start' }}>
            <AlertTriangle size={14} style={{ color: 'var(--warning)', marginTop: 2, flexShrink: 0 }} />
            <div style={{ fontSize: 12 }}>
              <strong>Avertissements d'extraction :</strong>
              <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </div>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'center', gap: 8 }}>
        <button className="btn btn-secondary" onClick={onAnother}>
          <Upload size={14} /> Un autre document
        </button>
        <button className="btn btn-primary" onClick={onNavigate}>
          Voir l'engagement
        </button>
      </div>
    </div>
  )
}
