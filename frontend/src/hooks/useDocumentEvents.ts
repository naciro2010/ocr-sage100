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
const POLL_INTERVAL = 3000
const MAX_POLL_COUNT = 60

export function useDocumentEvents(dossierId: string | undefined, onUpdate: () => void) {
  const [progress, setProgress] = useState<Record<string, DocProgress>>({})
  const esRef = useRef<EventSource | null>(null)
  const onUpdateRef = useRef(onUpdate)
  const coalesceTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)
  const hasProcessingRef = useRef(false)
  const pollCountRef = useRef(0)
  const sseConnected = useRef(false)

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
      sseConnected.current = true
      setProgress(prev => ({ ...prev, [data.documentId]: data }))
      if (data.statut === 'done' || data.statut === 'error') {
        scheduleUpdate()
      }
    } catch (err) {
      console.warn('SSE parse error:', err)
    }
  }, [scheduleUpdate])

  const startPolling = useCallback(() => {
    if (pollTimer.current) return
    pollCountRef.current = 0
    pollTimer.current = setInterval(() => {
      pollCountRef.current++
      if (pollCountRef.current > MAX_POLL_COUNT) {
        if (pollTimer.current) { clearInterval(pollTimer.current); pollTimer.current = null }
        return
      }
      onUpdateRef.current()
    }, POLL_INTERVAL)
  }, [])

  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current)
      pollTimer.current = null
    }
  }, [])

  const setHasProcessing = useCallback((processing: boolean) => {
    if (processing && !hasProcessingRef.current) {
      hasProcessingRef.current = true
      setTimeout(() => {
        if (!sseConnected.current) startPolling()
      }, 2000)
    } else if (!processing && hasProcessingRef.current) {
      hasProcessingRef.current = false
      stopPolling()
    }
  }, [startPolling, stopPolling])

  useEffect(() => {
    if (!dossierId) return

    const url = `${API_URL}/api/dossiers/${dossierId}/events`
    const es = new EventSource(url)
    esRef.current = es
    sseConnected.current = false

    es.addEventListener('progress', handleProgress)

    es.onerror = () => {
      sseConnected.current = false
      if (hasProcessingRef.current) startPolling()
    }

    es.onopen = () => {
      sseConnected.current = true
    }

    return () => {
      es.close()
      esRef.current = null
      stopPolling()
      if (coalesceTimer.current) {
        clearTimeout(coalesceTimer.current)
        coalesceTimer.current = null
      }
    }
  }, [dossierId, handleProgress, startPolling, stopPolling])

  return { progress, setHasProcessing }
}
