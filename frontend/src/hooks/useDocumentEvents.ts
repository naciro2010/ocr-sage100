import { useEffect, useRef, useState } from 'react'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

export interface DocProgress {
  documentId: string
  nomFichier: string
  step: string
  statut: string
  detail?: string
}

export function useDocumentEvents(dossierId: string | undefined, onUpdate: () => void) {
  const [progress, setProgress] = useState<Record<string, DocProgress>>({})
  const esRef = useRef<EventSource | null>(null)

  useEffect(() => {
    if (!dossierId) return

    const url = `${API_URL}/api/dossiers/${dossierId}/events`
    const es = new EventSource(url)
    esRef.current = es

    es.addEventListener('progress', (e) => {
      try {
        const data: DocProgress = JSON.parse(e.data)
        setProgress(prev => ({ ...prev, [data.documentId]: data }))

        // Refresh dossier data when a document is done or errored
        if (data.statut === 'done' || data.statut === 'error') {
          onUpdate()
        }
      } catch {}
    })

    es.onerror = () => {
      // Reconnect silently — SSE auto-reconnects
    }

    return () => {
      es.close()
      esRef.current = null
    }
  }, [dossierId])

  return progress
}
