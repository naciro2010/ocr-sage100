import { useCallback, useEffect, useState } from 'react'
import { ZoomIn, ZoomOut, Maximize2, RotateCcw, Move } from 'lucide-react'

// Safari ignore `#zoom=` (Quick Look maison) ; les boutons restent visibles
// mais l'effet zoom est nul. Acceptable : cible MADAEF = Chrome/Edge.

const ZOOM_STEPS = [25, 50, 75, 100, 125, 150, 200, 300, 400] as const
type ZoomMode = number | 'page-width' | 'page-fit'
const DEFAULT_ZOOM: ZoomMode = 'page-width'

interface Props {
  blobUrl: string
  title: string
}

function nextZoomIn(z: ZoomMode): ZoomMode {
  if (typeof z !== 'number') return 125
  return ZOOM_STEPS.find(s => s > z) ?? ZOOM_STEPS[ZOOM_STEPS.length - 1]
}
function nextZoomOut(z: ZoomMode): ZoomMode {
  if (typeof z !== 'number') return 75
  return ZOOM_STEPS.findLast(s => s < z) ?? ZOOM_STEPS[0]
}
function zoomLabel(z: ZoomMode): string {
  if (z === 'page-width') return 'Largeur'
  if (z === 'page-fit') return 'Page'
  return `${z}%`
}

export default function PdfFrame({ blobUrl, title }: Props) {
  const [zoom, setZoom] = useState<ZoomMode>(DEFAULT_ZOOM)

  const zoomIn = useCallback(() => setZoom(nextZoomIn), [])
  const zoomOut = useCallback(() => setZoom(nextZoomOut), [])
  const fitWidth = useCallback(() => setZoom('page-width'), [])
  const fitPage = useCallback(() => setZoom('page-fit'), [])
  const reset = useCallback(() => setZoom(DEFAULT_ZOOM), [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      if (target && /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return
      if (target?.isContentEditable) return
      if (e.metaKey || e.ctrlKey || e.altKey) return
      switch (e.key) {
        case '+': case '=': zoomIn(); e.preventDefault(); break
        case '-': zoomOut(); e.preventDefault(); break
        case '0': reset(); e.preventDefault(); break
        case 'f': fitPage(); e.preventDefault(); break
        case 'w': fitWidth(); e.preventDefault(); break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoomIn, zoomOut, reset, fitPage, fitWidth])

  const viewParam = zoom === 'page-fit' ? 'Fit' : 'FitH'
  const src = `${blobUrl}#view=${viewParam}&zoom=${zoom}&pagemode=none`

  return (
    <div className="pdf-frame">
      <div className="pdf-frame-toolbar" role="toolbar" aria-label="Controles zoom du PDF">
        <button type="button" className="btn btn-secondary btn-sm"
          onClick={zoomOut} title="Reduire (-)" aria-label="Reduire le zoom">
          <ZoomOut size={13} />
        </button>
        <span className="pdf-frame-zoom-label" aria-live="polite">{zoomLabel(zoom)}</span>
        <button type="button" className="btn btn-secondary btn-sm"
          onClick={zoomIn} title="Agrandir (+)" aria-label="Augmenter le zoom">
          <ZoomIn size={13} />
        </button>
        <span className="pdf-frame-sep" aria-hidden="true" />
        <button type="button" className="btn btn-secondary btn-sm"
          onClick={fitWidth} title="Largeur de page (W)" aria-label="Ajuster a la largeur">
          <Move size={13} style={{ transform: 'rotate(90deg)' }} />
          <span>Largeur</span>
        </button>
        <button type="button" className="btn btn-secondary btn-sm"
          onClick={fitPage} title="Page entiere (F)" aria-label="Ajuster a la page entiere">
          <Maximize2 size={13} />
          <span>Page</span>
        </button>
        <button type="button" className="btn btn-secondary btn-sm"
          onClick={reset} title="Reinitialiser (0)" aria-label="Reinitialiser le zoom">
          <RotateCcw size={12} />
        </button>
        <span className="pdf-frame-hint" aria-hidden="true">
          Glisser pour deplacer · Ctrl + molette pour zoomer
        </span>
      </div>
      <iframe key={blobUrl} src={src} title={title} className="pdf-frame-iframe" />
    </div>
  )
}
