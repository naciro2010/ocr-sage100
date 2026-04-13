import { useEffect, useRef, useState, useCallback } from 'react'

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
  const onUpdateRef = useRef(onUpdate)

  useEffect(() => {
    onUpdateRef.current = onUpdate
  }, [onUpdate])

  const handleProgress = useCallback((e: MessageEvent) => {
    try {
      const data: DocProgress = JSON.parse(e.data)
      setProgress(prev => ({ ...prev, [data.documentId]: data }))
      if (data.statut === 'done' || data.statut === 'error') {
        onUpdateRef.current()
      }
    } catch (err) {
      console.warn('SSE parse error:', err)
    }
  }, [])

  useEffect(() => {
    if (!dossierId) return

    const url = `${API_URL}/api/dossiers/${dossierId}/events`
    const es = new EventSource(url)
    esRef.current = es

    es.addEventListener('progress', handleProgress)

    return () => {
      es.close()
      esRef.current = null
    }
  }, [dossierId, handleProgress])

  return progress
}
