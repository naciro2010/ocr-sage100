import { useCallback, useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent } from 'react'
import { ZoomIn, ZoomOut, Maximize2, RotateCcw, Move, ScanSearch, Minimize2 } from 'lucide-react'

// Safari ignore `#zoom=` (Quick Look maison) ; les boutons restent visibles
// mais l'effet zoom est nul. Acceptable : cible MADAEF = Chrome/Edge.

const ZOOM_STEPS = [25, 50, 75, 100, 125, 150, 200, 300, 400] as const
type ZoomMode = number | 'page-width' | 'page-fit'
const DEFAULT_ZOOM: ZoomMode = 'page-width'

const LENS_RADIUS = 140
const LENS_ZOOM = 2.4

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
  const [fullscreen, setFullscreen] = useState(false)
  const [loupe, setLoupe] = useState(false)
  const [stageSize, setStageSize] = useState<{ w: number; h: number }>({ w: 0, h: 0 })
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const stageRef = useRef<HTMLDivElement>(null)

  const zoomIn = useCallback(() => setZoom(nextZoomIn), [])
  const zoomOut = useCallback(() => setZoom(nextZoomOut), [])
  const fitWidth = useCallback(() => setZoom('page-width'), [])
  const fitPage = useCallback(() => setZoom('page-fit'), [])
  const reset = useCallback(() => setZoom(DEFAULT_ZOOM), [])
  const toggleFullscreen = useCallback(() => setFullscreen(v => !v), [])
  const toggleLoupe = useCallback(() => {
    setLoupe(v => {
      if (v) setPos(null)
      return !v
    })
  }, [])

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
        case 'm': toggleLoupe(); e.preventDefault(); break
        case 'e': toggleFullscreen(); e.preventDefault(); break
        case 'Escape':
          if (fullscreen) { setFullscreen(false); e.preventDefault() }
          else if (loupe) { setLoupe(false); setPos(null); e.preventDefault() }
          break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [zoomIn, zoomOut, reset, fitPage, fitWidth, toggleLoupe, toggleFullscreen, fullscreen, loupe])

  // Mesure la taille du stage pour positionner correctement la loupe (le clone
  // iframe doit avoir les memes dimensions que l'iframe principale pour que les
  // coordonnees du curseur se transposent).
  useEffect(() => {
    if (!stageRef.current) return
    const el = stageRef.current
    const measure = () => setStageSize({ w: el.clientWidth, h: el.clientHeight })
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [fullscreen])

  const onStageMouseMove = useCallback((e: ReactMouseEvent<HTMLDivElement>) => {
    if (!loupe || !stageRef.current) return
    const rect = stageRef.current.getBoundingClientRect()
    setPos({ x: e.clientX - rect.left, y: e.clientY - rect.top })
  }, [loupe])

  const onStageMouseLeave = useCallback(() => {
    if (loupe) setPos(null)
  }, [loupe])

  const viewParam = zoom === 'page-fit' ? 'Fit' : 'FitH'
  const src = `${blobUrl}#view=${viewParam}&zoom=${zoom}&pagemode=none`

  const showLens = loupe && pos && stageSize.w > 0
  const lensX = pos ? pos.x - LENS_RADIUS : 0
  const lensY = pos ? pos.y - LENS_RADIUS : 0

  return (
    <div className={`pdf-frame ${fullscreen ? 'pdf-frame-fullscreen' : ''} ${loupe ? 'pdf-frame-loupe-on' : ''}`}>
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
        <span className="pdf-frame-sep" aria-hidden="true" />
        <button type="button"
          className={`btn btn-sm pdf-frame-toggle ${loupe ? 'pdf-frame-toggle-on' : 'btn-secondary'}`}
          onClick={toggleLoupe}
          aria-pressed={loupe}
          title="Loupe au survol (M)"
          aria-label={loupe ? 'Desactiver la loupe' : 'Activer la loupe'}>
          <ScanSearch size={13} />
          <span>Loupe</span>
        </button>
        <button type="button"
          className={`btn btn-sm pdf-frame-toggle ${fullscreen ? 'pdf-frame-toggle-on' : 'btn-secondary'}`}
          onClick={toggleFullscreen}
          aria-pressed={fullscreen}
          title={fullscreen ? 'Quitter le plein ecran (Echap)' : 'Plein ecran (E)'}
          aria-label={fullscreen ? 'Quitter le plein ecran' : 'Plein ecran'}>
          {fullscreen ? <Minimize2 size={13} /> : <Maximize2 size={13} />}
          <span>{fullscreen ? 'Reduire' : 'Plein ecran'}</span>
        </button>
        <span className="pdf-frame-hint" aria-hidden="true">
          {loupe ? 'Survolez le PDF pour magnifier' : 'Glisser pour deplacer · Ctrl + molette pour zoomer'}
        </span>
      </div>

      <div
        ref={stageRef}
        className="pdf-frame-stage"
        onMouseMove={onStageMouseMove}
        onMouseLeave={onStageMouseLeave}
      >
        <iframe key={blobUrl} src={src} title={title} className="pdf-frame-iframe" />

        {loupe && (
          <div className="pdf-frame-catcher" aria-hidden="true" />
        )}

        {showLens && (
          <div
            className="pdf-frame-lens"
            style={{
              left: `${lensX}px`,
              top: `${lensY}px`,
              width: `${LENS_RADIUS * 2}px`,
              height: `${LENS_RADIUS * 2}px`,
            }}
            aria-hidden="true"
          >
            <div
              className="pdf-frame-lens-inner"
              style={{
                left: `${-lensX}px`,
                top: `${-lensY}px`,
                width: `${stageSize.w}px`,
                height: `${stageSize.h}px`,
                transform: `scale(${LENS_ZOOM})`,
                transformOrigin: `${pos!.x}px ${pos!.y}px`,
              }}
            >
              <iframe
                src={src}
                title={`${title} (loupe)`}
                className="pdf-frame-lens-iframe"
                tabIndex={-1}
                aria-hidden="true"
              />
            </div>
            <div className="pdf-frame-lens-glass" aria-hidden="true" />
          </div>
        )}

        {fullscreen && (
          <div className="pdf-frame-fs-hint" aria-hidden="true">
            <kbd>Echap</kbd> pour quitter
          </div>
        )}
      </div>
    </div>
  )
}
