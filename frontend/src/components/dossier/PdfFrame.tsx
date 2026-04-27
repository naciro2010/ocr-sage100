import { useCallback, useEffect, useState } from 'react'
import { ZoomIn, ZoomOut, Maximize2, RotateCcw, Move } from 'lucide-react'

/**
 * Viewer PDF avec toolbar zoom + pan.
 *
 * Pourquoi un wrapper iframe et pas pdf.js ?
 * - Le viewer PDF interne du navigateur (Chrome, Edge, Firefox) re-rasterise
 *   le PDF a chaque changement de zoom : on garde une nettete parfaite, sans
 *   ajouter ~600 KB de pdf.js au bundle.
 * - Changer le hash `#zoom=N` ne recharge PAS l'iframe : le viewer interne
 *   reagit en place, sans flash.
 * - Le pan (deplacement) est natif : clic-glisser dans le PDF + scroll.
 *
 * Limites :
 * - Le hash `#zoom=` est respecte par Chrome/Edge/Firefox. Safari l'ignore
 *   silencieusement (utilise son propre viewer Quick Look) — sur Safari les
 *   boutons restent visibles mais sans effet.
 */

const ZOOM_STEPS = [25, 50, 75, 100, 125, 150, 200, 300, 400] as const
const DEFAULT_ZOOM: ZoomMode = 'page-width'

type ZoomMode = number | 'page-width' | 'page-fit'

interface Props {
  blobUrl: string
  title: string
  /** Permet de re-utiliser une cle differente pour forcer un reload propre quand le doc change. */
  docId?: string
}

function nextZoomIn(z: ZoomMode): ZoomMode {
  if (typeof z !== 'number') return 125
  const i = ZOOM_STEPS.findIndex(s => s > z)
  return i === -1 ? ZOOM_STEPS[ZOOM_STEPS.length - 1] : ZOOM_STEPS[i]
}
function nextZoomOut(z: ZoomMode): ZoomMode {
  if (typeof z !== 'number') return 75
  const reversed = [...ZOOM_STEPS].reverse()
  const i = reversed.findIndex(s => s < z)
  return i === -1 ? ZOOM_STEPS[0] : reversed[i]
}
function zoomLabel(z: ZoomMode): string {
  if (z === 'page-width') return 'Largeur'
  if (z === 'page-fit') return 'Page'
  return `${z}%`
}

export default function PdfFrame({ blobUrl, title, docId }: Props) {
  const [zoom, setZoom] = useState<ZoomMode>(DEFAULT_ZOOM)
  // Reset le zoom quand on change de document : sinon un zoom 300% reste
  // applique au document suivant, ce qui est desorientant. On utilise le
  // pattern "store previous prop in state" recommande par React docs pour
  // eviter setState dans useEffect (https://react.dev/reference/react/useState#storing-information-from-previous-renders).
  const [prevDocId, setPrevDocId] = useState<string | undefined>(docId)
  if (docId !== prevDocId) {
    setPrevDocId(docId)
    setZoom(DEFAULT_ZOOM)
  }

  const zoomIn = useCallback(() => setZoom(z => nextZoomIn(z)), [])
  const zoomOut = useCallback(() => setZoom(z => nextZoomOut(z)), [])
  const fitWidth = useCallback(() => setZoom('page-width'), [])
  const fitPage = useCallback(() => setZoom('page-fit'), [])
  const reset = useCallback(() => setZoom(DEFAULT_ZOOM), [])

  // Raccourcis clavier dans le scope du viewer : `+`, `-`, `0` (reset),
  // `f` (fit page), `w` (fit width). Pas de Cmd/Ctrl pour ne pas entrer en
  // conflit avec le zoom navigateur.
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

  // `view=` decrit la vue initiale, `zoom=` la facteur d'echelle. Les deux
  // sont compris par le viewer PDF de Chrome/Edge/Firefox (PDF Open Parameters).
  const viewParam = zoom === 'page-fit' ? 'Fit' : 'FitH'
  const zoomParam = typeof zoom === 'number' ? `&zoom=${zoom}` : `&zoom=${zoom}`
  const src = `${blobUrl}#view=${viewParam}${zoomParam}&pagemode=none`

  return (
    <div className="pdf-frame">
      <div className="pdf-frame-toolbar" role="toolbar" aria-label="Controles zoom du PDF">
        <button type="button" className="pdf-frame-btn"
          onClick={zoomOut} title="Reduire (-)" aria-label="Reduire le zoom">
          <ZoomOut size={13} />
        </button>
        <span className="pdf-frame-zoom-label" aria-live="polite">{zoomLabel(zoom)}</span>
        <button type="button" className="pdf-frame-btn"
          onClick={zoomIn} title="Agrandir (+)" aria-label="Augmenter le zoom">
          <ZoomIn size={13} />
        </button>
        <span className="pdf-frame-sep" aria-hidden="true" />
        <button type="button" className="pdf-frame-btn"
          onClick={fitWidth} title="Largeur de page (W)" aria-label="Ajuster a la largeur">
          <Move size={13} style={{ transform: 'rotate(90deg)' }} />
          <span className="pdf-frame-btn-label">Largeur</span>
        </button>
        <button type="button" className="pdf-frame-btn"
          onClick={fitPage} title="Page entiere (F)" aria-label="Ajuster a la page entiere">
          <Maximize2 size={13} />
          <span className="pdf-frame-btn-label">Page</span>
        </button>
        <button type="button" className="pdf-frame-btn pdf-frame-btn-reset"
          onClick={reset} title="Reinitialiser (0)" aria-label="Reinitialiser le zoom">
          <RotateCcw size={12} />
        </button>
        <span className="pdf-frame-hint" aria-hidden="true">
          Glisser pour deplacer · Ctrl + molette pour zoomer
        </span>
      </div>
      <iframe
        key={blobUrl}
        src={src}
        title={title}
        className="pdf-frame-iframe"
      />
    </div>
  )
}
