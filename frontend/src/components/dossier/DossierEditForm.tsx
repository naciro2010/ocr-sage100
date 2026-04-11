import { memo, useState } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { updateDossier } from '../../api/dossierApi'
import { useToast } from '../Toast'
import { Pencil, Save, X, Loader2 } from 'lucide-react'

interface Props {
  dossier: DossierDetail
  id: string
  onDone: () => void
  onCancel: () => void
}

export default memo(function DossierEditForm({ dossier, id, onDone, onCancel }: Props) {
  const { toast } = useToast()
  const [saving, setSaving] = useState(false)
  const [fields, setFields] = useState({
    fournisseur: dossier.fournisseur || '',
    description: dossier.description || '',
    montantTtc: dossier.montantTtc?.toString() || '',
    montantHt: dossier.montantHt?.toString() || '',
    montantTva: dossier.montantTva?.toString() || '',
    montantNetAPayer: dossier.montantNetAPayer?.toString() || '',
  })

  const handleSave = async () => {
    setSaving(true)
    try {
      const data: Record<string, unknown> = {}
      if (fields.fournisseur) data.fournisseur = fields.fournisseur
      if (fields.description) data.description = fields.description
      if (fields.montantTtc) data.montantTtc = parseFloat(fields.montantTtc)
      if (fields.montantHt) data.montantHt = parseFloat(fields.montantHt)
      if (fields.montantTva) data.montantTva = parseFloat(fields.montantTva)
      if (fields.montantNetAPayer) data.montantNetAPayer = parseFloat(fields.montantNetAPayer)
      await updateDossier(id, data)
      toast('success', 'Dossier mis a jour')
      onDone()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setSaving(false) }
  }

  return (
    <div className="card">
      <h2><Pencil size={14} /> Modifier le dossier</h2>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
        <div>
          <label className="form-label">Fournisseur</label>
          <input className="form-input" value={fields.fournisseur} onChange={e => setFields(f => ({ ...f, fournisseur: e.target.value }))} />
        </div>
        <div>
          <label className="form-label">Description</label>
          <input className="form-input" value={fields.description} onChange={e => setFields(f => ({ ...f, description: e.target.value }))} />
        </div>
        <div>
          <label className="form-label">Montant TTC</label>
          <input className="form-input" type="number" step="0.01" value={fields.montantTtc} onChange={e => setFields(f => ({ ...f, montantTtc: e.target.value }))} />
        </div>
        <div>
          <label className="form-label">Montant HT</label>
          <input className="form-input" type="number" step="0.01" value={fields.montantHt} onChange={e => setFields(f => ({ ...f, montantHt: e.target.value }))} />
        </div>
        <div>
          <label className="form-label">Montant TVA</label>
          <input className="form-input" type="number" step="0.01" value={fields.montantTva} onChange={e => setFields(f => ({ ...f, montantTva: e.target.value }))} />
        </div>
        <div>
          <label className="form-label">Net a payer</label>
          <input className="form-input" type="number" step="0.01" value={fields.montantNetAPayer} onChange={e => setFields(f => ({ ...f, montantNetAPayer: e.target.value }))} />
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn btn-primary" disabled={saving} onClick={handleSave}>
          {saving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Save size={14} /> Sauvegarder</>}
        </button>
        <button className="btn btn-secondary" onClick={onCancel}><X size={14} /> Annuler</button>
      </div>
    </div>
  )
})
