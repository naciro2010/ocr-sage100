import { useEffect, useState, useRef, useCallback } from 'react'
import { useParams, Link } from 'react-router-dom'
import { getDossier, finalizeDossier, getExportTCUrl, getExportOPUrl } from '../api/dossierApi'
import type { DossierDetail } from '../api/dossierTypes'
import { useToast } from '../components/Toast'
import {
  ArrowLeft, ShieldCheck, FileText, Download, Loader2,
  CheckCircle, Pencil, Send
} from 'lucide-react'

interface ControlPoint {
  description: string
  observation: string
  commentaire: string
}

export default function Finalize() {
  const { id } = useParams<{ id: string }>()
  const { toast } = useToast()
  const [dossier, setDossier] = useState<DossierDetail | null>(null)
  const [points, setPoints] = useState<ControlPoint[]>([])
  const [signataire, setSignataire] = useState('')
  const [commentaire, setCommentaire] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<{ tcDocId: string; opDocId: string } | null>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const isDrawingRef = useRef(false)
  const [hasSigned, setHasSigned] = useState(false)

  useEffect(() => {
    if (!id) return
    getDossier(id).then(d => {
      setDossier(d)
      // Pre-fill points from validation results
      const pts = d.resultatsValidation.map(r => ({
        description: r.libelle,
        observation: r.statut === 'CONFORME' ? 'Conforme' : r.statut === 'NON_CONFORME' ? 'Non conforme' : 'NA',
        commentaire: r.detail || ''
      }))
      if (pts.length === 0) {
        // Default 10 points if no validation results
        setPoints([
          { description: 'Concordance facture / modalites contractuelles / livrables', observation: 'Conforme', commentaire: '' },
          { description: 'Verification arithmetique des montants', observation: 'Conforme', commentaire: '' },
          { description: 'Respect du delai d\'execution', observation: 'NA', commentaire: '' },
          { description: 'Modifications / avenants (plafonds et variations)', observation: 'NA', commentaire: '' },
          { description: 'Application des retenues et penalites', observation: 'NA', commentaire: '' },
          { description: 'Signatures et visas des personnes habilitees', observation: 'Conforme', commentaire: '' },
          { description: 'Conformite reglementaire (ICE, IF, RC)', observation: 'Conforme', commentaire: '' },
          { description: 'Conformite RIB contractuel vs facture', observation: 'Conforme', commentaire: '' },
          { description: 'Conformite BL / PV de reception', observation: 'NA', commentaire: '' },
          { description: 'Habilitations des signataires des receptions', observation: 'Conforme', commentaire: '' },
        ])
      } else {
        setPoints(pts)
      }
    }).catch(() => toast('error', 'Dossier introuvable'))
  }, [id])

  // Canvas signature
  const startDraw = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current; if (!canvas) return
    const ctx = canvas.getContext('2d'); if (!ctx) return
    const rect = canvas.getBoundingClientRect()
    ctx.beginPath()
    ctx.moveTo(e.clientX - rect.left, e.clientY - rect.top)
    isDrawingRef.current = true
  }, [])

  const draw = useCallback((e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDrawingRef.current) return
    const canvas = canvasRef.current; if (!canvas) return
    const ctx = canvas.getContext('2d'); if (!ctx) return
    const rect = canvas.getBoundingClientRect()
    ctx.lineWidth = 2; ctx.lineCap = 'round'; ctx.strokeStyle = '#0a0f1a'
    ctx.lineTo(e.clientX - rect.left, e.clientY - rect.top)
    ctx.stroke()
    setHasSigned(true)
  }, [])

  const clearSignature = () => {
    const canvas = canvasRef.current; if (!canvas) return
    const ctx = canvas.getContext('2d'); if (!ctx) return
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    setHasSigned(false)
  }

  const handleSubmit = async () => {
    if (!id || !signataire.trim()) { toast('warning', 'Nom du signataire requis'); return }
    setSubmitting(true)
    try {
      const signatureBase64 = hasSigned ? canvasRef.current?.toDataURL('image/png') : undefined
      const res = await finalizeDossier(id, {
        points,
        signataire: signataire.trim(),
        signatureBase64,
        commentaireGeneral: commentaire || undefined,
      })
      setResult(res)
      toast('success', `Dossier ${res.reference} finalise — TC + OP generes`)
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur de finalisation')
    } finally { setSubmitting(false) }
  }

  if (!dossier) return <div className="loading">Chargement...</div>

  const fmt = (n: number | null | undefined) => n != null ? Number(n).toLocaleString('fr-FR', { minimumFractionDigits: 2 }) : '—'

  return (
    <div>
      <div className="page-header">
        <h1>
          <Link to={`/dossiers/${id}`} className="back-link"><ArrowLeft size={20} /></Link>
          Finalisation — {dossier.reference}
        </h1>
      </div>

      {/* Section 1: Control points */}
      <div className="card">
        <h2><ShieldCheck size={14} /> Points de controle</h2>
        <div style={{ fontSize: 12, color: 'var(--ink-40)', marginBottom: 12 }}>
          Confirmez chaque point avant la generation des documents. Vous pouvez ajuster les observations et ajouter des commentaires.
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {points.map((pt, i) => (
            <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '8px 10px', borderRadius: 6, background: i % 2 === 0 ? 'var(--ink-02)' : 'transparent' }}>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, fontWeight: 700, color: 'var(--ink-30)', width: 30, flexShrink: 0, paddingTop: 4 }}>
                {i + 1}.
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--ink)', marginBottom: 4 }}>{pt.description}</div>
                <input
                  className="form-input"
                  placeholder="Commentaire..."
                  value={pt.commentaire}
                  onChange={e => setPoints(prev => prev.map((p, j) => j === i ? { ...p, commentaire: e.target.value } : p))}
                  style={{ fontSize: 11, padding: '4px 8px' }}
                />
              </div>
              <select
                className="form-select"
                value={pt.observation}
                onChange={e => setPoints(prev => prev.map((p, j) => j === i ? { ...p, observation: e.target.value } : p))}
                style={{ fontSize: 11, width: 'auto', padding: '4px 8px', flexShrink: 0 }}
              >
                <option value="Conforme">Conforme</option>
                <option value="NA">NA</option>
                <option value="Non conforme">Non conforme</option>
              </select>
            </div>
          ))}
        </div>

        <button className="btn btn-secondary btn-sm" style={{ marginTop: 10 }}
          onClick={() => setPoints([...points, { description: '', observation: 'NA', commentaire: '' }])}>
          + Ajouter un point
        </button>
      </div>

      {/* Section 2: OP summary */}
      <div className="card">
        <h2><FileText size={14} /> Resume Ordre de Paiement</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div>
            <div className="form-label">Beneficiaire</div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>{dossier.fournisseur || '—'}</div>
          </div>
          <div>
            <div className="form-label">Montant TTC</div>
            <div style={{ fontSize: 13, fontWeight: 600, fontFamily: 'var(--font-mono)' }}>{fmt(dossier.montantTtc)} MAD</div>
          </div>
          <div>
            <div className="form-label">Montant HT</div>
            <div style={{ fontSize: 13, fontFamily: 'var(--font-mono)' }}>{fmt(dossier.montantHt)}</div>
          </div>
          <div>
            <div className="form-label">Net a payer</div>
            <div style={{ fontSize: 13, fontWeight: 600, fontFamily: 'var(--font-mono)', color: 'var(--accent-deep)' }}>{fmt(dossier.montantNetAPayer || dossier.montantTtc)} MAD</div>
          </div>
        </div>
        <div style={{ marginTop: 12 }}>
          <div className="form-label">Commentaire / Synthese controleur</div>
          <textarea
            className="form-input"
            rows={3}
            value={commentaire}
            onChange={e => setCommentaire(e.target.value)}
            placeholder="Synthese du controleur financier..."
            style={{ fontSize: 12, resize: 'vertical' }}
          />
        </div>
      </div>

      {/* Section 3: Signature */}
      <div className="card">
        <h2><Pencil size={14} /> Signature electronique</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <div>
            <div className="form-label">Nom du signataire</div>
            <input className="form-input" value={signataire} onChange={e => setSignataire(e.target.value)} placeholder="Ex: EL HARRAK Siham" />
            <div style={{ fontSize: 10, color: 'var(--ink-30)', marginTop: 4 }}>
              Date : {new Date().toLocaleDateString('fr-FR')}
            </div>
          </div>
          <div>
            <div className="form-label">Dessinez votre signature</div>
            <canvas
              ref={canvasRef}
              width={280} height={100}
              style={{
                border: '1px solid var(--ink-10)', borderRadius: 6,
                background: '#fff', cursor: 'crosshair', display: 'block',
              }}
              onMouseDown={startDraw}
              onMouseMove={draw}
              onMouseUp={() => { isDrawingRef.current = false }}
              onMouseLeave={() => { isDrawingRef.current = false }}
            />
            {hasSigned && (
              <button className="btn btn-secondary btn-sm" style={{ marginTop: 6 }} onClick={clearSignature}>
                Effacer
              </button>
            )}
          </div>
        </div>

        <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
          <button className="btn btn-primary" onClick={handleSubmit} disabled={submitting || !signataire.trim()}>
            {submitting ? <><Loader2 size={14} className="spin" /> Generation...</> : <><Send size={14} /> Signer et generer TC + OP</>}
          </button>
        </div>
      </div>

      {/* Section 4: Generated documents */}
      {result && id && (
        <div className="card" style={{ background: 'var(--success-bg)', borderColor: 'rgba(16,185,129,0.15)' }}>
          <h2 style={{ color: 'var(--success)' }}><CheckCircle size={14} /> Documents generes</h2>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <a href={getExportTCUrl(id)} target="_blank" rel="noopener noreferrer" className="btn btn-secondary" style={{ textDecoration: 'none' }}>
              <Download size={14} /> Tableau de Controle (PDF)
            </a>
            <a href={getExportOPUrl(id)} target="_blank" rel="noopener noreferrer" className="btn btn-secondary" style={{ textDecoration: 'none' }}>
              <Download size={14} /> Ordre de Paiement (PDF)
            </a>
            <Link to={`/dossiers/${id}`} className="btn btn-primary" style={{ textDecoration: 'none' }}>
              Retour au dossier
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}
