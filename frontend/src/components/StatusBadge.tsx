const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  UPLOADED: { label: 'Uploadee', color: '#7a7a7a' },
  OCR_IN_PROGRESS: { label: 'OCR en cours', color: '#d4940a' },
  OCR_COMPLETED: { label: 'OCR termine', color: '#4a6fa5' },
  AI_EXTRACTION_IN_PROGRESS: { label: 'Extraction IA', color: '#d4940a' },
  EXTRACTED: { label: 'Extraite', color: '#7c5cbf' },
  VALIDATION_FAILED: { label: 'Validation echouee', color: '#d94f4f' },
  READY_FOR_SAGE: { label: 'Prete Sage', color: '#10a37f' },
  SAGE_SYNCED: { label: 'Synchronisee', color: '#0d8c6c' },
  SAGE_SYNC_FAILED: { label: 'Sync echouee', color: '#d94f4f' },
  ERROR: { label: 'Erreur', color: '#c04040' },
}

export default function StatusBadge({ status }: { status: string }) {
  const config = STATUS_CONFIG[status] || { label: status, color: '#7a7a7a' }
  return (
    <span
      className="status-badge"
      style={{ backgroundColor: config.color + '14', color: config.color, borderColor: config.color + '40' }}
    >
      {config.label}
    </span>
  )
}
