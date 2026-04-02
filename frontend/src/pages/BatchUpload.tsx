import { useState, useRef } from 'react'
import { batchUpload } from '../api/client'
import type { BatchResult } from '../api/types'
import { Upload, Files, CheckCircle, XCircle, Loader2, Trash2 } from 'lucide-react'

interface FileEntry {
  file: File
  status: 'pending' | 'uploading' | 'success' | 'error'
  error?: string
  invoiceId?: number
}

export default function BatchUpload() {
  const [files, setFiles] = useState<FileEntry[]>([])
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [result, setResult] = useState<BatchResult | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const ACCEPTED_TYPES = ['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']

  const addFiles = (newFiles: FileList | File[]) => {
    const entries: FileEntry[] = Array.from(newFiles)
      .filter(f => ACCEPTED_TYPES.includes(f.type) || /\.(pdf|png|jpe?g|tiff?)$/i.test(f.name))
      .map(file => ({ file, status: 'pending' as const }))
    setFiles(prev => [...prev, ...entries])
    setResult(null)
  }

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault()
    setDragOver(false)
    if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files)
  }

  const removeFile = (index: number) => setFiles(prev => prev.filter((_, i) => i !== index))

  const handleUpload = async () => {
    if (files.length === 0) return
    setUploading(true); setResult(null)
    setFiles(prev => prev.map(f => ({ ...f, status: 'uploading' as const })))

    try {
      const batchResult = await batchUpload(files.map(f => f.file))
      setResult(batchResult)
      setFiles(prev => prev.map((entry, idx) => {
        const itemResult = batchResult.results[idx]
        if (!itemResult) return { ...entry, status: 'error' as const, error: 'Pas de resultat' }
        return {
          ...entry,
          status: itemResult.success ? 'success' as const : 'error' as const,
          invoiceId: itemResult.invoiceId,
          error: itemResult.error,
        }
      }))
    } catch (e: unknown) {
      setFiles(prev => prev.map(entry => ({
        ...entry,
        status: 'error' as const,
        error: e instanceof Error ? e.message : 'Erreur inconnue',
      })))
    } finally {
      setUploading(false)
    }
  }

  const statusIcon = (status: FileEntry['status']) => {
    switch (status) {
      case 'pending': return <Files size={16} color="#6b7280" />
      case 'uploading': return <Loader2 size={16} className="spin" color="#f59e0b" />
      case 'success': return <CheckCircle size={16} color="#059669" />
      case 'error': return <XCircle size={16} color="#ef4444" />
    }
  }

  return (
    <div>
      <div className="page-header">
        <h1><Files size={24} /> Batch Upload</h1>
      </div>

      <div className="card">
        <div
          className={`drop-zone ${dragOver ? 'drag-over' : ''} ${files.length > 0 ? 'has-file' : ''}`}
          onDragOver={e => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          onClick={() => inputRef.current?.click()}
        >
          <input
            ref={inputRef}
            type="file"
            accept=".pdf,.png,.jpg,.jpeg,.tiff,.tif"
            multiple
            hidden
            onChange={e => { if (e.target.files?.length) addFiles(e.target.files); e.target.value = '' }}
          />
          <Upload size={48} className="drop-icon" />
          <p className="drop-text">Glissez-deposez vos fichiers ici ou cliquez pour selectionner</p>
          <p className="drop-hint">PDF, PNG, JPG, TIFF — plusieurs fichiers acceptes</p>
        </div>

        {files.length > 0 && (
          <>
            <div className="mt-3">
              <h3 className="mb-2" style={{ fontSize: '15px', fontWeight: 700 }}>
                {files.length} fichier{files.length > 1 ? 's' : ''} selectionne{files.length > 1 ? 's' : ''}
              </h3>
              <table className="invoice-table">
                <thead>
                  <tr>
                    <th style={{ width: 40 }}>Statut</th>
                    <th>Fichier</th>
                    <th>Taille</th>
                    <th>Resultat</th>
                    <th style={{ width: 60 }}></th>
                  </tr>
                </thead>
                <tbody>
                  {files.map((entry, idx) => (
                    <tr key={idx}>
                      <td>{statusIcon(entry.status)}</td>
                      <td className="cell-filename">{entry.file.name}</td>
                      <td>{(entry.file.size / 1024).toFixed(1)} Ko</td>
                      <td>
                        {entry.status === 'success' && entry.invoiceId && (
                          <span style={{ color: '#059669', fontWeight: 600 }}>Facture #{entry.invoiceId}</span>
                        )}
                        {entry.status === 'error' && entry.error && (
                          <span style={{ color: '#ef4444' }}>{entry.error}</span>
                        )}
                        {(entry.status === 'pending' || entry.status === 'uploading') && <span className="text-muted">—</span>}
                      </td>
                      <td>
                        {entry.status === 'pending' && (
                          <button
                            className="btn btn-secondary"
                            style={{ padding: '4px 8px', fontSize: '12px' }}
                            onClick={e => { e.stopPropagation(); removeFile(idx) }}
                          >
                            <Trash2 size={12} />
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="upload-actions mt-3">
              <button
                className="btn btn-primary"
                disabled={uploading || files.every(f => f.status === 'success')}
                onClick={handleUpload}
              >
                {uploading ? <><Loader2 size={16} className="spin" /> Traitement en cours...</> : <><Upload size={16} /> Envoyer tous les fichiers</>}
              </button>
              {!uploading && (
                <button className="btn btn-secondary" onClick={() => { setFiles([]); setResult(null) }}>
                  <Trash2 size={16} /> Tout effacer
                </button>
              )}
            </div>
          </>
        )}

        {result && (
          <div className={`result-banner ${result.failed === 0 ? 'success' : result.successful === 0 ? 'error' : 'success'} mt-2`}>
            {result.failed === 0 ? <CheckCircle size={18} /> : <XCircle size={18} />}
            <span>
              {result.successful}/{result.totalFiles} fichier{result.totalFiles > 1 ? 's' : ''} traite{result.totalFiles > 1 ? 's' : ''}
              {result.failed > 0 && ` — ${result.failed} echoue${result.failed > 1 ? 's' : ''}`}
            </span>
          </div>
        )}
      </div>
    </div>
  )
}
