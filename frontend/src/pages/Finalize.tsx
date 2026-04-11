import { useEffect, useState, useRef, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getDossier, finalizeDossier, getExportTCUrl, getExportOPUrl, openWithAuth, getDocumentFileUrl } from '../api/dossierApi'
import type { DossierDetail, DocumentInfo } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import {
  ArrowLeft, ShieldCheck, FileText, Download, Loader2,
  CheckCircle, Pencil, Send, X, Plus, SkipForward, Eye, XCircle
} from 'lucide-react'

interface ControlPoint {
  description: string
  observation: string
  commentaire: string
  skip: boolean
  source: 'autocontrole' | 'systeme' | 'manuel'
}

const STORAGE_KEY_SIG = 'recondoc_signature'
const STORAGE_KEY_NAME = 'recondoc_signataire'

// Default 10 checklist points (CCF-EN-04-V02) used as fallback
const DEFAULT_CHECKLIST = [
  'Concordance facture / modalites contractuelles / livrables',
  'Verification arithmetique des montants',
  'Respect du delai d\'execution des prestations',
  'Modifications dans la consistance des prestations (avenants)',
  'Application des retenues et penalites de retard',
  'Signatures et visas des personnes habilitees',
  'Conformite reglementaire facture (ICE, IF, RC, CNSS)',
  'Conformite du RIB contractuel avec le RIB facture',
  'Conformite du BL / PV de reception vs facture',
  'Conformite des habilitations des signataires des receptions',
]

export default function Finalize() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()
  const [dossier, setDossier] = useState<DossierDetail | null>(null)
  const [points, setPoints] = useState<ControlPoint[]>([])
  const [signataire, setSignataire] = useState(() => localStorage.getItem(STORAGE_KEY_NAME) || '')
  const [commentaire, setCommentaire] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<{ tcDocId: string; opDocId: string; reference?: string } | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const isDrawingRef = useRef(false)
  const [hasSigned, setHasSigned] = useState(false)
  const [showDocViewer, setShowDocViewer] = useState(false)
  const [pdfBlobUrl, setPdfBlobUrl] = useState<string | null>(null)
  const [loadingPdf, setLoadingPdf] = useState(false)

  // Load saved signature
  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY_SIG)
    if (saved && canvasRef.current) {
      const img = new Image()
      img.onload = () => {
        const ctx = canvasRef.current?.getContext('2d')
        if (ctx) { ctx.drawImage(img, 0, 0); setHasSigned(true) }
      }
      img.src = saved
    }
  }, [dossier])

  useEffect(() => {
    if (!id) return
    getDossier(id).then(d => {
      setDossier(d)
      buildPoints(d)
    }).catch(() => toast('error', 'Dossier introuvable'))
  }, [id])

  // Build control points from autocontrole + validation results
  const buildPoints = (d: DossierDetail) => {
    const pts: ControlPoint[] = []
    const checklistData = d.checklistAutocontrole
    const extractedPoints = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []

    if (extractedPoints.length > 0) {
      // Use actual autocontrole points from uploaded document
      for (const pt of extractedPoints) {
        const desc = String(pt.description || `Point ${pt.numero || ''}`)
        // Find matching validation result or correction
        const match = d.resultatsValidation.find(r =>
          r.libelle.toLowerCase().includes(desc.substring(0, 20).toLowerCase()) ||
          desc.toLowerCase().includes(r.libelle.substring(0, 20).toLowerCase())
        )

        let obs: string
        if (match) {
          // Use corrected status if available, otherwise validation result
          obs = match.statut === 'CONFORME' ? 'Conforme'
            : match.statut === 'NON_CONFORME' ? 'Non conforme' : 'NA'
        } else if (pt.estValide === true) {
          obs = 'Conforme'
        } else if (pt.estValide === false) {
          obs = 'Non conforme'
        } else {
          obs = 'NA'
        }

        pts.push({
          description: desc,
          observation: obs,
          commentaire: match?.commentaire || (pt.observation != null ? String(pt.observation) : ''),
          skip: false,
          source: 'autocontrole',
        })
      }
    } else {
      // Fallback: use default checklist points
      for (const desc of DEFAULT_CHECKLIST) {
        const match = d.resultatsValidation.find(r =>
          r.libelle.toLowerCase().includes(desc.substring(0, 20).toLowerCase()) ||
          desc.toLowerCase().includes(r.libelle.substring(0, 20).toLowerCase())
        )
        const obs = match
          ? (match.statut === 'CONFORME' ? 'Conforme' : match.statut === 'NON_CONFORME' ? 'Non conforme' : 'NA')
          : 'NA'
        pts.push({ description: desc, observation: obs, commentaire: match?.detail || '', skip: false, source: 'systeme' })
      }
    }

    setPoints(pts)
  }

  // Find the autocontrole document for viewing
  const autocontroleDoc: DocumentInfo | undefined = dossier?.documents.find(
    d => d.typeDocument === 'CHECKLIST_AUTOCONTROLE'
  )

  const loadAutocontrolePdf = async () => {
    if (!id || !autocontroleDoc) return
    if (showDocViewer) { setShowDocViewer(false); if (pdfBlobUrl) { URL.revokeObjectURL(pdfBlobUrl); setPdfBlobUrl(null) }; return }
    setLoadingPdf(true)
    try {
      const res = await fetch(getDocumentFileUrl(id, autocontroleDoc.id), {
        headers: { 'Authorization': `Basic ${localStorage.getItem('recondoc_auth') || ''}` }
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const blob = await res.blob()
      setPdfBlobUrl(URL.createObjectURL(blob))
      setShowDocViewer(true)
    } catch { toast('error', 'Impossible de charger le document') }
    finally { setLoadingPdf(false) }
  }

  // Canvas signature handlers
  const getPos = (e: React.MouseEvent | React.TouchEvent) => {
    const rect = canvasRef.current!.getBoundingClientRect()
    if ('touches' in e) {
      const t = e.touches[0]
      return { x: t.clientX - rect.left, y: t.clientY - rect.top }
    }
    return { x: (e as React.MouseEvent).clientX - rect.left, y: (e as React.MouseEvent).clientY - rect.top }
  }

  const startDraw = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault()
    const ctx = canvasRef.current?.getContext('2d'); if (!ctx) return
    const { x, y } = getPos(e)
    ctx.beginPath(); ctx.moveTo(x, y)
    isDrawingRef.current = true
  }, [])

  const draw = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    e.preventDefault()
    if (!isDrawingRef.current) return
    const ctx = canvasRef.current?.getContext('2d'); if (!ctx) return
    const { x, y } = getPos(e)
    ctx.lineWidth = 2; ctx.lineCap = 'round'; ctx.strokeStyle = '#0a0f1a'
    ctx.lineTo(x, y); ctx.stroke()
    setHasSigned(true)
  }, [])

  const stopDraw = useCallback(() => {
    isDrawingRef.current = false
    if (canvasRef.current && hasSigned) {
      localStorage.setItem(STORAGE_KEY_SIG, canvasRef.current.toDataURL('image/png'))
    }
  }, [hasSigned])

  const clearSignature = () => {
    const ctx = canvasRef.current?.getContext('2d'); if (!ctx) return
    ctx.clearRect(0, 0, canvasRef.current!.width, canvasRef.current!.height)
    setHasSigned(false)
    localStorage.removeItem(STORAGE_KEY_SIG)
  }

  const updatePoint = (i: number, field: keyof ControlPoint, value: string | boolean) => {
    setPoints(prev => prev.map((p, j) => j === i ? { ...p, [field]: value } : p))
  }

  const handleSubmit = async () => {
    if (!id || !signataire.trim()) { toast('warning', 'Nom du signataire requis'); return }
    localStorage.setItem(STORAGE_KEY_NAME, signataire.trim())
    if (hasSigned && canvasRef.current) {
      localStorage.setItem(STORAGE_KEY_SIG, canvasRef.current.toDataURL('image/png'))
    }
    setSubmitting(true)
    try {
      const activePoints = points.filter(p => !p.skip)
      const signatureBase64 = hasSigned ? canvasRef.current?.toDataURL('image/png') : undefined
      const res = await finalizeDossier(id, {
        points: activePoints.map(p => ({ description: p.description, observation: p.observation, commentaire: p.commentaire })),
        signataire: signataire.trim(),
        signatureBase64,
        commentaireGeneral: commentaire || undefined,
      })
      setResult(res)
      toast('success', `TC + OP generes pour ${res.reference}`)
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setSubmitting(false) }
  }

  if (!dossier) return <div className="loading">Chargement...</div>
  const fmt = (n: number | null | undefined) => n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '\u2014'
  const activeCount = points.filter(p => !p.skip).length
  const conformeCount = points.filter(p => !p.skip && p.observation === 'Conforme').length
  const nonConformeCount = points.filter(p => !p.skip && p.observation === 'Non conforme').length
  const hasAutocontrole = points.some(p => p.source === 'autocontrole')

  return (
    <div>
      <div className="page-header">
        <h1>
          <Link to={`/dossiers/${id}`} className="back-link"><ArrowLeft size={18} /></Link>
          Finalisation — {dossier.reference}
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ fontSize: 11, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)' }}>
            {dossier.fournisseur} | {dossier.type === 'BC' ? 'Bon de commande' : 'Contractuel'}
          </div>
          {autocontroleDoc && (
            <button className="btn btn-secondary btn-sm" disabled={loadingPdf} onClick={loadAutocontrolePdf}>
              {loadingPdf ? <Loader2 size={12} className="spin" /> : showDocViewer ? <XCircle size={12} /> : <Eye size={12} />}
              {showDocViewer ? 'Masquer' : 'Voir autocontrole'}
            </button>
          )}
        </div>
      </div>

      {/* Autocontrole document viewer */}
      {showDocViewer && pdfBlobUrl && (
        <div className="card" style={{ padding: 0, overflow: 'hidden', marginBottom: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 14px', background: 'var(--ink-02)', borderBottom: '1px solid var(--ink-05)' }}>
            <span style={{ fontSize: 11, fontWeight: 600 }}>
              <FileText size={12} style={{ marginRight: 4 }} />
              {autocontroleDoc?.nomFichier}
            </span>
            <button className="btn btn-secondary btn-sm" onClick={() => { setShowDocViewer(false); if (pdfBlobUrl) { URL.revokeObjectURL(pdfBlobUrl); setPdfBlobUrl(null) } }}>
              <X size={12} />
            </button>
          </div>
          <iframe src={pdfBlobUrl} title="Autocontrole" style={{ width: '100%', height: 400, border: 'none' }} />
        </div>
      )}

      {/* Source badge */}
      {hasAutocontrole && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, padding: '8px 14px', background: 'var(--success-bg)', borderRadius: 6, border: '1px solid rgba(16,185,129,0.1)' }}>
          <CheckCircle size={14} style={{ color: 'var(--success)' }} />
          <span style={{ fontSize: 11, color: 'var(--accent-deep)' }}>
            Points pre-remplis depuis la checklist d'autocontrole + resultats de verification
          </span>
        </div>
      )}

      {/* Checklist / TC */}
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <h2 style={{ marginBottom: 0 }}>
            <ShieldCheck size={14} /> Tableau de Controle
            <span style={{ fontWeight: 500, fontSize: 11, color: 'var(--ink-40)', marginLeft: 8, textTransform: 'none', letterSpacing: 0 }}>
              {conformeCount} conformes, {nonConformeCount} non conformes / {activeCount} points
            </span>
          </h2>
          <button className="btn btn-secondary btn-sm"
            onClick={() => setPoints(prev => [...prev, { description: '', observation: 'NA', commentaire: '', skip: false, source: 'manuel' }])}>
            <Plus size={11} /> Point
          </button>
        </div>

        <table className="data-table" style={{ fontSize: 12 }}>
          <thead>
            <tr>
              <th style={{ width: 30 }}>#</th>
              <th>Element controle</th>
              <th style={{ width: 110 }}>Observation</th>
              <th>Commentaire</th>
              <th style={{ width: 60 }}>Source</th>
              <th style={{ width: 40 }}></th>
            </tr>
          </thead>
          <tbody>
            {points.map((pt, i) => (
              <tr key={i} style={{ opacity: pt.skip ? 0.35 : 1 }}>
                <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)' }}>
                  {i + 1}
                </td>
                <td>
                  {pt.skip ? (
                    <span style={{ textDecoration: 'line-through', color: 'var(--ink-30)' }}>{pt.description}</span>
                  ) : pt.description ? (
                    <span style={{ fontWeight: 500 }}>{pt.description}</span>
                  ) : (
                    <input className="form-input" placeholder="Description du point..." value={pt.description}
                      onChange={e => updatePoint(i, 'description', e.target.value)} style={{ fontSize: 11, padding: '3px 6px' }} />
                  )}
                </td>
                <td>
                  {!pt.skip && (
                    <select className="form-select" value={pt.observation}
                      onChange={e => updatePoint(i, 'observation', e.target.value)}
                      style={{
                        fontSize: 10, padding: '2px 4px', width: '100%',
                        color: pt.observation === 'Conforme' ? 'var(--success)' : pt.observation === 'Non conforme' ? 'var(--danger)' : 'var(--ink-40)',
                        fontWeight: 700
                      }}>
                      <option value="Conforme">Conforme</option>
                      <option value="NA">NA</option>
                      <option value="Non conforme">Non conforme</option>
                    </select>
                  )}
                </td>
                <td>
                  {!pt.skip && (
                    <input className="form-input" placeholder="..." value={pt.commentaire}
                      onChange={e => updatePoint(i, 'commentaire', e.target.value)}
                      style={{ fontSize: 10, padding: '2px 6px' }} />
                  )}
                </td>
                <td>
                  <span className="tag" style={{
                    fontSize: 8,
                    background: pt.source === 'autocontrole' ? 'var(--success-bg)' : pt.source === 'systeme' ? 'var(--info-bg)' : 'var(--ink-05)',
                    color: pt.source === 'autocontrole' ? 'var(--success)' : pt.source === 'systeme' ? 'var(--info)' : 'var(--ink-40)',
                  }}>
                    {pt.source === 'autocontrole' ? 'Doc' : pt.source === 'systeme' ? 'Sys' : 'Manuel'}
                  </span>
                </td>
                <td>
                  <button className="btn btn-secondary btn-sm" title={pt.skip ? 'Inclure' : 'Exclure'}
                    onClick={() => updatePoint(i, 'skip', !pt.skip)} style={{ padding: '2px 4px' }}>
                    {pt.skip ? <CheckCircle size={12} /> : <SkipForward size={12} />}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* OP Summary */}
      <div className="card">
        <h2><FileText size={14} /> Ordre de Paiement</h2>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 10, marginBottom: 12 }}>
          <div>
            <div className="form-label">Beneficiaire</div>
            <div style={{ fontSize: 13, fontWeight: 700 }}>{dossier.fournisseur || '\u2014'}</div>
          </div>
          <div>
            <div className="form-label">Montant TTC</div>
            <div style={{ fontSize: 13, fontFamily: 'var(--font-mono)', fontWeight: 600 }}>{fmt(dossier.montantTtc)}</div>
          </div>
          <div>
            <div className="form-label">Montant HT</div>
            <div style={{ fontSize: 13, fontFamily: 'var(--font-mono)' }}>{fmt(dossier.montantHt)}</div>
          </div>
          <div>
            <div className="form-label">Net a payer</div>
            <div style={{ fontSize: 13, fontFamily: 'var(--font-mono)', fontWeight: 700, color: 'var(--accent-deep)' }}>{fmt(dossier.montantNetAPayer || dossier.montantTtc)} MAD</div>
          </div>
        </div>
        <div className="form-label">Synthese du controleur financier</div>
        <textarea className="form-input" rows={2} value={commentaire} onChange={e => setCommentaire(e.target.value)}
          placeholder="Ex: Montant facture conforme au BC. Bon service fait atteste par..." style={{ fontSize: 11 }} />
      </div>

      {/* Signature */}
      <div className="card">
        <h2><Pencil size={14} /> Signature electronique</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, alignItems: 'start' }}>
          <div>
            <div className="form-label">Nom et prenom du signataire</div>
            <input className="form-input" value={signataire} onChange={e => setSignataire(e.target.value)} placeholder="Ex: EL HARRAK Siham" />
            <div style={{ fontSize: 10, color: 'var(--ink-30)', marginTop: 6, fontFamily: 'var(--font-mono)' }}>
              Date : {new Date().toLocaleDateString('fr-FR')}
            </div>
          </div>
          <div>
            <div className="form-label">Signature</div>
            <div style={{ position: 'relative', display: 'inline-block' }}>
              <canvas ref={canvasRef} width={300} height={100}
                style={{ border: '1px solid var(--ink-10)', borderRadius: 6, background: '#fff', cursor: 'crosshair', display: 'block' }}
                onMouseDown={startDraw} onMouseMove={draw} onMouseUp={stopDraw} onMouseLeave={stopDraw}
                onTouchStart={startDraw} onTouchMove={draw} onTouchEnd={stopDraw} />
              {hasSigned && (
                <button onClick={clearSignature} title="Effacer"
                  style={{ position: 'absolute', top: 4, right: 4, background: 'rgba(255,255,255,0.8)', border: 'none', borderRadius: 4, cursor: 'pointer', padding: 2 }}>
                  <X size={14} style={{ color: 'var(--danger)' }} />
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Submit */}
      {!result && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={submitting || !signataire.trim()}
            style={{ padding: '10px 20px', fontSize: 13 }}>
            {submitting ? <><Loader2 size={15} className="spin" /> Generation en cours...</> : <><Send size={15} /> Signer et generer les documents</>}
          </button>
          <Link to={`/dossiers/${id}`} className="btn btn-secondary" style={{ textDecoration: 'none', padding: '10px 16px' }}>
            Annuler
          </Link>
        </div>
      )}

      {/* Result */}
      {result && id && (
        <div className="card" style={{ borderColor: 'var(--accent)', borderWidth: 2 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
            <CheckCircle size={20} style={{ color: 'var(--success)' }} />
            <div>
              <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--ink)' }}>Documents generes avec succes</div>
              <div style={{ fontSize: 11, color: 'var(--ink-40)' }}>Signe par {signataire} le {new Date().toLocaleDateString('fr-FR')}</div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <button className="btn btn-secondary" onClick={() => openWithAuth(getExportTCUrl(id))}>
              <Download size={14} /> Tableau de Controle
            </button>
            <button className="btn btn-secondary" onClick={() => openWithAuth(getExportOPUrl(id))}>
              <Download size={14} /> Ordre de Paiement
            </button>
            <Link to={`/dossiers/${id}`} className="btn btn-primary" style={{ textDecoration: 'none' }}>
              Retour au dossier
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}
