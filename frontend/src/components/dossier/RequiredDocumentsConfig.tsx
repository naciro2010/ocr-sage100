import { useEffect, useState, useCallback } from 'react'
import { ClipboardCheck, RefreshCw } from 'lucide-react'
import { getRequiredDocuments, updateRequiredDocuments, type RequiredDocumentsResponse } from '../../api/dossierApi'
import { TYPE_DOCUMENT_LABELS, type TypeDocument } from '../../api/dossierTypes'
import { useToast } from '../Toast'

interface Props {
  dossierId: string
  onChanged?: () => void
}

// INCONNU / FORMULAIRE_FOURNISSEUR sont transitoires, exclus du choix.
const SELECTABLE: TypeDocument[] = [
  'FACTURE', 'BON_COMMANDE', 'CONTRAT_AVENANT', 'ORDRE_PAIEMENT',
  'CHECKLIST_AUTOCONTROLE', 'CHECKLIST_PIECES', 'TABLEAU_CONTROLE',
  'PV_RECEPTION', 'ATTESTATION_FISCALE'
]

export default function RequiredDocumentsConfig({ dossierId, onChanged }: Props) {
  const { toast } = useToast()
  const [config, setConfig] = useState<RequiredDocumentsResponse | null>(null)
  const [pending, setPending] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)

  const load = useCallback(() => {
    getRequiredDocuments(dossierId)
      .then(res => {
        setConfig(res)
        setPending(new Set(res.selected))
      })
      .catch(e => toast('error', e instanceof Error ? e.message : 'Erreur chargement pieces obligatoires'))
  }, [dossierId, toast])

  useEffect(() => { load() }, [load])

  const toggle = (t: string) => {
    setPending(prev => {
      const next = new Set(prev)
      if (next.has(t)) next.delete(t); else next.add(t)
      return next
    })
  }

  const save = async () => {
    setSaving(true)
    try {
      const defaults = new Set(config?.defaults.map(d => d.type) || [])
      const selection = Array.from(pending)
      // Si la selection = defaults exacts, on revient en mode "defaut" (null).
      const sameAsDefault = defaults.size === selection.length && selection.every(t => defaults.has(t))
      const res = await updateRequiredDocuments(dossierId, sameAsDefault ? null : selection)
      setConfig(res)
      setPending(new Set(res.selected))
      toast('success', res.isCustom ? 'Pieces personnalisees enregistrees' : 'Retour aux pieces par defaut')
      onChanged?.()
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally {
      setSaving(false)
    }
  }

  const resetToDefaults = async () => {
    setSaving(true)
    try {
      const res = await updateRequiredDocuments(dossierId, null)
      setConfig(res)
      setPending(new Set(res.selected))
      toast('info', 'Liste reinitialisee (defaut selon le type de dossier)')
      onChanged?.()
    } catch (e) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally {
      setSaving(false)
    }
  }

  if (!config) return null

  const dirty = config.selected.length !== pending.size ||
    config.selected.some(t => !pending.has(t))

  return (
    <div className="card" style={{ padding: '12px 18px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h2 style={{ marginBottom: 0 }}>
          <ClipboardCheck size={14} /> Pieces a verifier (completude R20)
          {config.isCustom && (
            <span className="tag" style={{ marginLeft: 8, fontSize: 10, background: 'var(--teal-50)', color: 'var(--teal-700)' }}>
              personnalisee
            </span>
          )}
        </h2>
        <button
          className="btn btn-secondary btn-sm"
          onClick={resetToDefaults}
          disabled={saving || !config.isCustom}
          title={config.isCustom ? 'Revenir a la liste par defaut' : 'Deja sur la liste par defaut'}
        >
          <RefreshCw size={12} /> Defaut
        </button>
      </div>
      <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 10 }}>
        Selectionnez les pieces exigees pour ce dossier. La completude (R20) evaluera uniquement les pieces cochees.
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 6, marginBottom: 10 }}>
        {SELECTABLE.map(t => {
          const checked = pending.has(t)
          return (
            <label key={t}
              style={{
                display: 'flex', alignItems: 'center', gap: 6,
                padding: '6px 8px', borderRadius: 4,
                background: checked ? 'var(--teal-50)' : 'var(--ink-02)',
                border: `1px solid ${checked ? 'var(--teal-200)' : 'var(--ink-05)'}`,
                cursor: 'pointer', fontSize: 12
              }}>
              <input
                type="checkbox"
                checked={checked}
                onChange={() => toggle(t)}
                disabled={saving}
              />
              <span>{TYPE_DOCUMENT_LABELS[t]}</span>
            </label>
          )
        })}
      </div>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 6 }}>
        {dirty && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => setPending(new Set(config.selected))}
            disabled={saving}
          >
            Annuler
          </button>
        )}
        <button
          className="btn btn-primary btn-sm"
          onClick={save}
          disabled={saving || !dirty || pending.size === 0}
        >
          {saving ? 'Enregistrement...' : 'Enregistrer'}
        </button>
      </div>
    </div>
  )
}
