import { useEffect, useState } from 'react'
import { Link, useParams, useNavigate } from 'react-router-dom'
import {
  ArrowLeft, Briefcase, Calendar, User, Wallet,
  ChevronRight, Trash2, AlertTriangle,
} from 'lucide-react'
import { getEngagement, detachDossier, deleteEngagement } from '../api/engagementApi'
import { getDossierSnapshot } from '../api/dossierApi'
import * as Pages from '../routes/lazyPages'
import type { EngagementResponse } from '../api/engagementTypes'
import {
  TYPE_CONFIG, STATUT_ENG_CONFIG, fmtMad, fmtDate, consumptionColor,
} from '../api/engagementTypes'

export default function EngagementDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [engagement, setEngagement] = useState<EngagementResponse | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!id) return
    const ctrl = new AbortController()
    getEngagement(id, ctrl.signal)
      .then(setEngagement)
      .catch(e => {
        if (ctrl.signal.aborted) return
        setError(e instanceof Error ? e.message : 'Erreur de chargement')
      })
    return () => ctrl.abort()
  }, [id])

  async function handleDetach(dossierId: string) {
    if (!id) return
    if (!confirm('Detacher ce dossier de l\'engagement ?')) return
    setBusy(true)
    try {
      await detachDossier(dossierId)
      const fresh = await getEngagement(id)
      setEngagement(fresh)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur')
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete() {
    if (!id || !engagement) return
    if (!confirm(`Supprimer definitivement l'engagement ${engagement.reference} ?`)) return
    setBusy(true)
    try {
      await deleteEngagement(id)
      navigate('/engagements')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur')
      setBusy(false)
    }
  }

  if (error) return <div className="alert alert-error mb-3">{error}</div>
  if (!engagement) {
    return (
      <div className="skeleton">
        <div className="skeleton-line h-lg w-40" />
        <div className="skeleton-card" style={{ height: 200 }} />
        <div className="skeleton-card" style={{ height: 320 }} />
      </div>
    )
  }

  const tc = TYPE_CONFIG[engagement.type]
  const sc = STATUT_ENG_CONFIG[engagement.statut]
  const TypeIcon = tc.icon
  const StatIcon = sc.icon
  const taux = engagement.tauxConsommation || 0
  const tauxColor = consumptionColor(taux)

  return (
    <div>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Link to="/engagements" className="btn btn-secondary btn-sm">
            <ArrowLeft size={14} /> Retour
          </Link>
          <h1 style={{ margin: 0 }}>
            <Briefcase size={18} /> {engagement.reference}
          </h1>
          <span className="status-badge" style={{ background: tc.bg, color: tc.color, gap: 4 }}>
            <TypeIcon size={11} style={{ verticalAlign: 'middle' }} /> {tc.label}
          </span>
          <span className="status-badge" style={{ background: sc.bg, color: sc.color, gap: 4 }}>
            <StatIcon size={10} style={{ verticalAlign: 'middle' }} /> {sc.label}
          </span>
        </div>
        <div className="header-actions">
          <button className="btn btn-danger btn-sm" onClick={handleDelete} disabled={busy || engagement.dossiers.length > 0}
            title={engagement.dossiers.length > 0 ? 'Detacher les dossiers avant de supprimer' : 'Supprimer l\'engagement'}>
            <Trash2 size={14} /> Supprimer
          </button>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <h2 style={{ marginBottom: 12 }}>Informations Detaillees</h2>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16 }}>
          <Field label="Objet" value={engagement.objet || '—'} wide />
          <Field label="Fournisseur" value={engagement.fournisseur || '—'} icon={<User size={12} />} />
          <Field label="Date document" value={fmtDate(engagement.dateDocument)} icon={<Calendar size={12} />} />
          <Field label="Date signature" value={fmtDate(engagement.dateSignature)} icon={<Calendar size={12} />} />
          <Field label="Date notification" value={fmtDate(engagement.dateNotification)} icon={<Calendar size={12} />} />
        </div>

        <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--ink-05)' }}>
          <div style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', color: 'var(--ink-40)', marginBottom: 10 }}>
            Montants
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 16 }}>
            <Field label="Montant HT" value={`${fmtMad(engagement.montantHt)} MAD`} mono />
            <Field label={`TVA (${engagement.tauxTva ?? '-'}%)`} value={`${fmtMad(engagement.montantTva)} MAD`} mono />
            <Field label="Montant TTC" value={`${fmtMad(engagement.montantTtc)} MAD`} mono strong />
            <Field label="Consomme" value={`${fmtMad(engagement.montantConsomme)} MAD (${taux.toFixed(1)}%)`} mono color={tauxColor} />
          </div>
          <div style={{ marginTop: 10, height: 6, background: 'var(--ink-05)', borderRadius: 3, overflow: 'hidden' }}>
            <div style={{ width: `${Math.min(taux, 100)}%`, height: '100%', background: tauxColor, transition: 'width 0.4s ease' }} />
          </div>
        </div>

        {engagement.marche && <MarcheSection m={engagement.marche} />}
        {engagement.bonCommande && <BcSection bc={engagement.bonCommande} />}
        {engagement.contrat && <ContratSection c={engagement.contrat} />}
      </div>

      <div className="card">
        <div className="card-flex" style={{ marginBottom: 12 }}>
          <h2 style={{ marginBottom: 0 }}>
            <Wallet size={14} /> Dossiers rattaches ({engagement.dossiers.length})
          </h2>
        </div>
        {engagement.dossiers.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 24, color: 'var(--ink-40)' }}>
            <AlertTriangle size={28} style={{ color: 'var(--ink-20)', marginBottom: 8 }} />
            <div style={{ fontSize: 12 }}>Aucun dossier rattache a cet engagement.</div>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Reference</th>
                <th>Statut</th>
                <th>Fournisseur</th>
                <th>Montant TTC</th>
                <th>Cree le</th>
                <th aria-label="actions"></th>
              </tr>
            </thead>
            <tbody>
              {engagement.dossiers.map(d => {
                const prefetch = () => {
                  Pages.DossierDetail.preload()
                  void getDossierSnapshot(d.id).catch(() => {})
                }
                return (
                <tr key={d.id} onMouseEnter={prefetch} onFocus={prefetch}>
                  <td className="cell-mono" style={{ fontWeight: 500 }}>
                    <Link to={`/dossiers/${d.id}`} style={{ color: 'inherit' }}>{d.reference}</Link>
                  </td>
                  <td>
                    <span className="status-badge">{d.statut}</span>
                  </td>
                  <td>{d.fournisseur || '—'}</td>
                  <td className="cell-mono" style={{ textAlign: 'right' }}>{fmtMad(d.montantTtc)}</td>
                  <td className="audit-date">{new Date(d.dateCreation).toLocaleDateString('fr-FR')}</td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    <Link to={`/dossiers/${d.id}`} className="btn btn-secondary btn-sm" aria-label={`Detail ${d.reference}`}>
                      <ChevronRight size={14} />
                    </Link>
                    <button className="btn btn-secondary btn-sm" onClick={() => handleDetach(d.id)} disabled={busy}
                      title="Detacher le dossier de l'engagement">
                      <Trash2 size={12} />
                    </button>
                  </td>
                </tr>
              )})}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

function Field({ label, value, icon, wide, mono, strong, color }: {
  label: string
  value: string
  icon?: React.ReactNode
  wide?: boolean
  mono?: boolean
  strong?: boolean
  color?: string
}) {
  return (
    <div style={{ gridColumn: wide ? '1 / -1' : undefined }}>
      <div style={{ fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase', fontWeight: 600, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
        {icon} {label}
      </div>
      <div style={{
        fontSize: strong ? 15 : 13,
        fontWeight: strong ? 600 : 500,
        fontFamily: mono ? 'var(--font-mono)' : undefined,
        color: color || 'var(--ink-100)',
      }}>
        {value}
      </div>
    </div>
  )
}

function MarcheSection({ m }: { m: NonNullable<EngagementResponse['marche']> }) {
  return (
    <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--ink-05)' }}>
      <div style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', color: 'var(--ink-40)', marginBottom: 10 }}>
        Specifiques Marche
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
        <Field label="Numero AO" value={m.numeroAo || '—'} mono />
        <Field label="Date AO" value={fmtDate(m.dateAo)} />
        <Field label="Categorie" value={m.categorie || '—'} />
        <Field label="Delai execution" value={m.delaiExecutionMois ? `${m.delaiExecutionMois} mois` : '—'} />
        <Field label="Retenue garantie" value={m.retenueGarantiePct != null ? `${m.retenueGarantiePct}%` : '—'} />
        <Field label="Caution definitive" value={m.cautionDefinitivePct != null ? `${m.cautionDefinitivePct}%` : '—'} />
        <Field label="Penalite retard/jour" value={m.penalitesRetardJourPct != null ? `${m.penalitesRetardJourPct}` : '—'} />
        <Field label="Revision de prix" value={m.revisionPrixAutorisee ? 'Autorisee' : 'Non autorisee'} />
      </div>
    </div>
  )
}

function BcSection({ bc }: { bc: NonNullable<EngagementResponse['bonCommande']> }) {
  return (
    <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--ink-05)' }}>
      <div style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', color: 'var(--ink-40)', marginBottom: 10 }}>
        Specifiques Bon de commande cadre
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
        <Field label="Plafond montant" value={bc.plafondMontant != null ? `${fmtMad(bc.plafondMontant)} MAD` : '—'} mono />
        <Field label="Validite fin" value={fmtDate(bc.dateValiditeFin)} />
        <Field label="Seuil anti-fractionnement" value={bc.seuilAntiFractionnement != null ? `${fmtMad(bc.seuilAntiFractionnement)} MAD` : '—'} mono />
      </div>
    </div>
  )
}

function ContratSection({ c }: { c: NonNullable<EngagementResponse['contrat']> }) {
  return (
    <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--ink-05)' }}>
      <div style={{ fontSize: 11, fontWeight: 600, textTransform: 'uppercase', color: 'var(--ink-40)', marginBottom: 10 }}>
        Specifiques Contrat
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
        <Field label="Periodicite" value={c.periodicite || '—'} />
        <Field label="Date debut" value={fmtDate(c.dateDebut)} />
        <Field label="Date fin" value={fmtDate(c.dateFin)} />
        <Field label="Reconduction tacite" value={c.reconductionTacite ? 'Oui' : 'Non'} />
        <Field label="Preavis resiliation" value={c.preavisResiliationJours ? `${c.preavisResiliationJours} j` : '—'} />
        <Field label="Indice revision" value={c.indiceRevision || '—'} />
      </div>
    </div>
  )
}
