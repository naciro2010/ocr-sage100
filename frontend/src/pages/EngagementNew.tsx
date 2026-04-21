import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowLeft, Briefcase, Save } from 'lucide-react'
import { createEngagement } from '../api/engagementApi'
import type { CreateEngagementRequest, TypeEngagement, CategorieMarche, PeriodiciteContrat } from '../api/engagementTypes'
import { TYPE_CONFIG, TYPE_OPTIONS } from '../api/engagementTypes'

export default function EngagementNew() {
  const navigate = useNavigate()
  const [type, setType] = useState<TypeEngagement>('MARCHE')
  const [form, setForm] = useState<CreateEngagementRequest>({ type: 'MARCHE', reference: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  function update<K extends keyof CreateEngagementRequest>(key: K, value: CreateEngagementRequest[K]) {
    setForm(prev => ({ ...prev, [key]: value }))
  }

  function updateType(newType: TypeEngagement) {
    setType(newType)
    setForm(prev => ({ ...prev, type: newType }))
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!form.reference.trim()) {
      setError('La reference est obligatoire')
      return
    }
    setBusy(true)
    setError('')
    try {
      const saved = await createEngagement(form)
      navigate(`/engagements/${saved.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Erreur a la creation')
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Link to="/engagements" className="btn btn-secondary btn-sm">
            <ArrowLeft size={14} /> Retour
          </Link>
          <h1 style={{ margin: 0 }}><Briefcase size={18} /> Nouvel engagement</h1>
        </div>
      </div>

      {error && <div className="alert alert-error mb-3">{error}</div>}

      <form onSubmit={handleSubmit}>
        <div className="card" style={{ marginBottom: 12 }}>
          <h2 style={{ marginBottom: 12 }}>Type</h2>
          <div className="dossier-chips">
            {TYPE_OPTIONS.map(t => (
              <button key={t} type="button"
                className={`dossier-chip ${type === t ? 'active' : ''}`}
                onClick={() => updateType(t)}
                aria-pressed={type === t}
              >
                {TYPE_CONFIG[t].label}
              </button>
            ))}
          </div>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <h2 style={{ marginBottom: 12 }}>Informations generales</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: 16 }}>
            <FormField label="Reference *" required>
              <input type="text" className="input" value={form.reference}
                onChange={e => update('reference', e.target.value)} required />
            </FormField>
            <FormField label="Objet">
              <input type="text" className="input" value={form.objet || ''}
                onChange={e => update('objet', e.target.value || null)} />
            </FormField>
            <FormField label="Fournisseur">
              <input type="text" className="input" value={form.fournisseur || ''}
                onChange={e => update('fournisseur', e.target.value || null)} />
            </FormField>
            <FormField label="Statut">
              <select className="input" value={form.statut || 'ACTIF'}
                onChange={e => update('statut', e.target.value as CreateEngagementRequest['statut'])}>
                <option value="ACTIF">Actif</option>
                <option value="SUSPENDU">Suspendu</option>
                <option value="CLOTURE">Cloture</option>
              </select>
            </FormField>
          </div>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <h2 style={{ marginBottom: 12 }}>Dates</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16 }}>
            <FormField label="Date document">
              <input type="date" className="input" value={form.dateDocument || ''}
                onChange={e => update('dateDocument', e.target.value || null)} />
            </FormField>
            <FormField label="Date signature">
              <input type="date" className="input" value={form.dateSignature || ''}
                onChange={e => update('dateSignature', e.target.value || null)} />
            </FormField>
            <FormField label="Date notification">
              <input type="date" className="input" value={form.dateNotification || ''}
                onChange={e => update('dateNotification', e.target.value || null)} />
            </FormField>
          </div>
        </div>

        <div className="card" style={{ marginBottom: 12 }}>
          <h2 style={{ marginBottom: 12 }}>Montants</h2>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 16 }}>
            <FormField label="Montant HT (MAD)">
              <input type="number" step="0.01" className="input" value={form.montantHt ?? ''}
                onChange={e => update('montantHt', e.target.value ? Number(e.target.value) : null)} />
            </FormField>
            <FormField label="TVA (MAD)">
              <input type="number" step="0.01" className="input" value={form.montantTva ?? ''}
                onChange={e => update('montantTva', e.target.value ? Number(e.target.value) : null)} />
            </FormField>
            <FormField label="Taux TVA (%)">
              <input type="number" step="0.1" className="input" value={form.tauxTva ?? ''}
                onChange={e => update('tauxTva', e.target.value ? Number(e.target.value) : null)} />
            </FormField>
            <FormField label="Montant TTC (MAD)">
              <input type="number" step="0.01" className="input" value={form.montantTtc ?? ''}
                onChange={e => update('montantTtc', e.target.value ? Number(e.target.value) : null)} />
            </FormField>
          </div>
        </div>

        {type === 'MARCHE' && (
          <div className="card" style={{ marginBottom: 12 }}>
            <h2 style={{ marginBottom: 12 }}>Specifiques Marche</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16 }}>
              <FormField label="Numero AO"><input type="text" className="input" value={form.numeroAo || ''}
                onChange={e => update('numeroAo', e.target.value || null)} /></FormField>
              <FormField label="Date AO"><input type="date" className="input" value={form.dateAo || ''}
                onChange={e => update('dateAo', e.target.value || null)} /></FormField>
              <FormField label="Categorie">
                <select className="input" value={form.categorie || ''}
                  onChange={e => update('categorie', (e.target.value || null) as CategorieMarche | null)}>
                  <option value="">—</option>
                  <option value="TRAVAUX">Travaux</option>
                  <option value="FOURNITURES">Fournitures</option>
                  <option value="SERVICES">Services</option>
                </select>
              </FormField>
              <FormField label="Delai execution (mois)"><input type="number" className="input" value={form.delaiExecutionMois ?? ''}
                onChange={e => update('delaiExecutionMois', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Retenue garantie (%)"><input type="number" step="0.01" className="input" value={form.retenueGarantiePct ?? ''}
                onChange={e => update('retenueGarantiePct', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Caution definitive (%)"><input type="number" step="0.01" className="input" value={form.cautionDefinitivePct ?? ''}
                onChange={e => update('cautionDefinitivePct', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Penalite retard/jour (%)"><input type="number" step="0.0001" className="input" value={form.penalitesRetardJourPct ?? ''}
                onChange={e => update('penalitesRetardJourPct', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Revision de prix">
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
                  <input type="checkbox" checked={form.revisionPrixAutorisee || false}
                    onChange={e => update('revisionPrixAutorisee', e.target.checked)} />
                  Autorisee par le CPS
                </label>
              </FormField>
            </div>
          </div>
        )}

        {type === 'BON_COMMANDE' && (
          <div className="card" style={{ marginBottom: 12 }}>
            <h2 style={{ marginBottom: 12 }}>Specifiques Bon de commande cadre</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16 }}>
              <FormField label="Plafond montant (MAD)"><input type="number" step="0.01" className="input" value={form.plafondMontant ?? ''}
                onChange={e => update('plafondMontant', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Validite fin"><input type="date" className="input" value={form.dateValiditeFin || ''}
                onChange={e => update('dateValiditeFin', e.target.value || null)} /></FormField>
              <FormField label="Seuil anti-fractionnement (MAD)"><input type="number" step="0.01" className="input" value={form.seuilAntiFractionnement ?? 200000}
                onChange={e => update('seuilAntiFractionnement', e.target.value ? Number(e.target.value) : null)} /></FormField>
            </div>
          </div>
        )}

        {type === 'CONTRAT' && (
          <div className="card" style={{ marginBottom: 12 }}>
            <h2 style={{ marginBottom: 12 }}>Specifiques Contrat</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 16 }}>
              <FormField label="Periodicite">
                <select className="input" value={form.periodicite || ''}
                  onChange={e => update('periodicite', (e.target.value || null) as PeriodiciteContrat | null)}>
                  <option value="">—</option>
                  <option value="MENSUEL">Mensuel</option>
                  <option value="TRIMESTRIEL">Trimestriel</option>
                  <option value="SEMESTRIEL">Semestriel</option>
                  <option value="ANNUEL">Annuel</option>
                </select>
              </FormField>
              <FormField label="Date debut"><input type="date" className="input" value={form.dateDebut || ''}
                onChange={e => update('dateDebut', e.target.value || null)} /></FormField>
              <FormField label="Date fin"><input type="date" className="input" value={form.dateFin || ''}
                onChange={e => update('dateFin', e.target.value || null)} /></FormField>
              <FormField label="Reconduction tacite">
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
                  <input type="checkbox" checked={form.reconductionTacite || false}
                    onChange={e => update('reconductionTacite', e.target.checked)} />
                  Autorisee
                </label>
              </FormField>
              <FormField label="Preavis resiliation (j)"><input type="number" className="input" value={form.preavisResiliationJours ?? ''}
                onChange={e => update('preavisResiliationJours', e.target.value ? Number(e.target.value) : null)} /></FormField>
              <FormField label="Indice revision"><input type="text" className="input" value={form.indiceRevision || ''}
                onChange={e => update('indiceRevision', e.target.value || null)} /></FormField>
            </div>
          </div>
        )}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Link to="/engagements" className="btn btn-secondary">Annuler</Link>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            <Save size={14} /> {busy ? 'Creation...' : 'Creer l\'engagement'}
          </button>
        </div>
      </form>
    </div>
  )
}

function FormField({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label style={{
        fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase',
        fontWeight: 600, marginBottom: 4, display: 'block'
      }}>
        {label}{required && <span style={{ color: 'var(--danger)' }}> *</span>}
      </label>
      {children}
    </div>
  )
}
