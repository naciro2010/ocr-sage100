import { useEffect, useRef, useState, useCallback } from 'react'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

export interface DocProgress {
  documentId: string
  nomFichier: string
  step: string
  statut: string
  detail?: string
}

const COALESCE_MS = 250

export function useDocumentEvents(dossierId: string | undefined, onUpdate: () => void) {
  const [progress, setProgress] = useState<Record<string, DocProgress>>({})
  const esRef = useRef<EventSource | null>(null)
  const onUpdateRef = useRef(onUpdate)
  const coalesceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    onUpdateRef.current = onUpdate
  }, [onUpdate])

  const scheduleUpdate = useCallback(() => {
    if (coalesceTimer.current) return
    coalesceTimer.current = setTimeout(() => {
      coalesceTimer.current = null
      onUpdateRef.current()
    }, COALESCE_MS)
  }, [])

  const handleProgress = useCallback((e: MessageEvent) => {
    try {
      const data: DocProgress = JSON.parse(e.data)
      setProgress(prev => ({ ...prev, [data.documentId]: data }))
      if (data.statut === 'done' || data.statut === 'error') {
        scheduleUpdate()
      }
    } catch (err) {
      console.warn('SSE parse error:', err)
    }
  }, [scheduleUpdate])

  useEffect(() => {
    if (!dossierId) return

    const url = `${API_URL}/api/dossiers/${dossierId}/events`
    const es = new EventSource(url)
    esRef.current = es

    es.addEventListener('progress', handleProgress)

    return () => {
      es.close()
      esRef.current = null
      if (coalesceTimer.current) {
        clearTimeout(coalesceTimer.current)
        coalesceTimer.current = null
      }
    }
  }, [dossierId, handleProgress])

  return progress
}
