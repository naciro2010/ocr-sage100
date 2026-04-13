import { memo, useState, useMemo } from 'react'
import type { DossierDetail } from '../../api/dossierTypes'
import { Columns2 } from 'lucide-react'

interface Props {
  dossier: DossierDetail
}

export default memo(function CompareView({ dossier }: Props) {
  const docTypes = useMemo(() => [
    ...(dossier.factures || []).map((f, i) => ({
      key: `FACTURE_${i}`,
      label: `Facture${(dossier.factures?.length || 0) > 1 ? ` ${i + 1}` : ''} ${f.numeroFacture ? `(${f.numeroFacture})` : ''}`.trim(),
      data: f
    })),
    ...(dossier.facture && (!dossier.factures || dossier.factures.length === 0) ? [{ key: 'FACTURE', label: 'Facture', data: dossier.facture }] : []),
    { key: 'BON_COMMANDE', label: 'Bon de commande', data: dossier.bonCommande },
    { key: 'CONTRAT_AVENANT', label: 'Contrat / Avenant', data: dossier.contratAvenant },
    { key: 'ORDRE_PAIEMENT', label: 'Ordre de paiement', data: dossier.ordrePaiement },
    { key: 'CHECKLIST', label: 'Checklist autocontrole', data: dossier.checklistAutocontrole },
    { key: 'TABLEAU_CONTROLE', label: 'Tableau de controle', data: dossier.tableauControle },
    { key: 'PV_RECEPTION', label: 'PV de reception', data: dossier.pvReception },
    { key: 'ATTESTATION_FISCALE', label: 'Attestation fiscale', data: dossier.attestationFiscale },
  ].filter(d => d.data != null), [dossier])

  const defaultRight = docTypes.length > 1 ? (docTypes.find(d => d.key !== docTypes[0]?.key)?.key || '') : ''
  const [left, setLeft] = useState(() => docTypes[0]?.key || '')
  const [right, setRight] = useState(() => defaultRight)

  const leftDoc = docTypes.find(d => d.key === left)
  const rightDoc = docTypes.find(d => d.key === right)

  const renderData = (data: Record<string, unknown>) => (
    <table className="kv-table"><tbody>
      {Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object').map(([k, v]) => (
        <tr key={k}><td>{k}</td><td>{String(v)}</td></tr>
      ))}
    </tbody></table>
  )

  if (docTypes.length < 2) return <div className="card"><p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Pas assez de documents pour comparer</p></div>

  return (
    <div className="card">
      <h2><Columns2 size={14} /> Comparaison de documents</h2>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 12 }}>
        <select className="form-select" value={left} onChange={e => setLeft(e.target.value)}>
          {docTypes.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
        </select>
        <select className="form-select" value={right} onChange={e => setRight(e.target.value)}>
          {docTypes.map(d => <option key={d.key} value={d.key}>{d.label}</option>)}
        </select>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <div>
          <div className="stat-label" style={{ color: 'var(--accent-deep)', marginBottom: 8 }}>{leftDoc?.label || 'Selectionnez'}</div>
          {leftDoc?.data && renderData(leftDoc.data as Record<string, unknown>)}
        </div>
        <div>
          <div className="stat-label" style={{ color: 'var(--info)', marginBottom: 8 }}>{rightDoc?.label || 'Selectionnez'}</div>
          {rightDoc?.data && renderData(rightDoc.data as Record<string, unknown>)}
        </div>
      </div>
    </div>
  )
})
