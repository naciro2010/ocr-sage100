import { useState, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { uploadInvoice } from '../api/client'
import { Upload as UploadIcon, FileUp, CheckCircle, AlertCircle, Loader2, ScanLine, Cpu, Brain, ShieldCheck } from 'lucide-react'

export default function Upload() {
  const [file, setFile] = useState<File | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  const handleFile = (f: File) => {
    setFile(f)
    setResult(null)
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    if (e.dataTransfer.files[0]) handleFile(e.dataTransfer.files[0])
  }

  const handleSubmit = async () => {
    if (!file) return
    setUploading(true)
    setResult(null)
    try {
      const invoice = await uploadInvoice(file)
      const engineLabel = invoice.ocrEngine === 'TESSERACT' ? 'Tesseract + Preprocessing'
        : invoice.ocrEngine === 'TIKA_PLUS_TESSERACT' ? 'Tika + Tesseract'
        : 'Tika'
      const aiLabel = invoice.aiUsed ? ' + IA Claude' : ''
      setResult({
        success: true,
        message: `Facture traitee. Moteur : ${engineLabel}${aiLabel} | Statut : ${invoice.status}`,
      })
      setTimeout(() => navigate(`/invoices/${invoice.id}`), 1500)
    } catch (e: unknown) {
      setResult({
        success: false,
        message: e instanceof Error ? e.message : 'Erreur inconnue',
      })
    } finally {
      setUploading(false)
    }
  }

  return (
    <div>
      <div className="page-header">
        <h1><UploadIcon size={22} /> Upload de facture</h1>
      </div>

      <div className="card">
        <div
          className={`drop-zone ${dragOver ? 'drag-over' : ''} ${file ? 'has-file' : ''}`}
          onDragOver={e => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          onClick={() => inputRef.current?.click()}
        >
          <input
            ref={inputRef}
            type="file"
            accept=".pdf,.png,.jpg,.jpeg,.tiff,.tif"
            hidden
            onChange={e => e.target.files?.[0] && handleFile(e.target.files[0])}
          />
          {file ? (
            <>
              <FileUp size={40} className="drop-icon" />
              <p className="drop-filename">{file.name}</p>
              <p className="drop-size">{(file.size / 1024).toFixed(1)} Ko</p>
            </>
          ) : (
            <>
              <UploadIcon size={40} className="drop-icon" />
              <p className="drop-text">Glissez-deposez un fichier ou cliquez pour selectionner</p>
              <p className="drop-hint">PDF, PNG, JPG, TIFF — max 50 Mo</p>
            </>
          )}
        </div>

        <div className="upload-actions">
          <button className="btn btn-primary" disabled={!file || uploading} onClick={handleSubmit}>
            {uploading ? <><Loader2 size={14} className="spin" /> Traitement en cours...</> : <><FileUp size={14} /> Envoyer et traiter</>}
          </button>
        </div>

        {uploading && (
          <div className="ocr-steps-container">
            <div className="ocr-step active">
              <Loader2 size={14} className="spin" />
              <span>Pipeline OCR en cours...</span>
            </div>
            <div className="ocr-step pending">
              <ScanLine size={14} />
              <span>1. Extraction texte natif (Tika)</span>
            </div>
            <div className="ocr-step pending">
              <Cpu size={14} />
              <span>2. OCR deep learning (PaddleOCR / Tesseract)</span>
            </div>
            <div className="ocr-step pending">
              <Brain size={14} />
              <span>3. Extraction structuree (Regex + IA optionnelle)</span>
            </div>
            <div className="ocr-step pending">
              <ShieldCheck size={14} />
              <span>4. Validation metier marocaine</span>
            </div>
          </div>
        )}

        {result && (
          <div className={`result-banner ${result.success ? 'success' : 'error'}`}>
            {result.success ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
            <span>{result.message}</span>
          </div>
        )}
      </div>
    </div>
  )
}
