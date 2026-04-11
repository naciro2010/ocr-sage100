import { memo } from 'react'
import { useToast } from '../Toast'

interface Props {
  data: Record<string, unknown> | null | undefined
  docType?: string
}

// Structured field groups for each document type
const INVOICE_SECTIONS: { title: string; fields: string[]; mono?: boolean }[] = [
  { title: 'Identification', fields: ['numeroFacture', 'dateFacture', 'referenceContrat', 'numeroBonCommande'] },
  { title: 'Fournisseur', fields: ['fournisseur', 'ice', 'identifiantFiscal', 'rc', 'cnss', 'patente', 'rib', 'banque'] },
  { title: 'Client', fields: ['client', 'clientIce', 'adresseClient'] },
  { title: 'Montants', fields: ['montantHT', 'tauxTVA', 'montantTVA', 'montantTTC', 'montantNetAPayer'], mono: true },
  { title: 'Details', fields: ['periode', 'objet', 'description', 'modeReglement', 'echeance', 'devise'] },
]

const OP_SECTIONS: { title: string; fields: string[] }[] = [
  { title: 'Identification', fields: ['reference', 'dateOperation', 'nature', 'description'] },
  { title: 'Emetteur', fields: ['emetteur', 'societeGeree'] },
  { title: 'Beneficiaire', fields: ['beneficiaire', 'rib', 'banque', 'ice', 'identifiantFiscal'] },
  { title: 'Montants', fields: ['montantBrut', 'montantOperation', 'montantNetAPayer', 'referenceFacture', 'referenceBcOuContrat'] },
]

const FIELD_LABELS: Record<string, string> = {
  numeroFacture: 'N\u00b0 Facture', dateFacture: 'Date', referenceContrat: 'Ref. Contrat',
  numeroBonCommande: 'N\u00b0 BC', fournisseur: 'Raison sociale', ice: 'ICE',
  identifiantFiscal: 'IF', rc: 'RC', cnss: 'CNSS', patente: 'Patente',
  rib: 'RIB', banque: 'Banque', client: 'Client', clientIce: 'ICE Client',
  adresseClient: 'Adresse', montantHT: 'Montant HT', tauxTVA: 'Taux TVA',
  montantTVA: 'Montant TVA', montantTTC: 'Montant TTC', montantNetAPayer: 'Net a payer',
  periode: 'Periode', objet: 'Objet', description: 'Description',
  modeReglement: 'Mode reglement', echeance: 'Echeance', devise: 'Devise',
  reference: 'Reference', dateOperation: 'Date', nature: 'Nature',
  emetteur: 'Emetteur', societeGeree: 'Societe geree', beneficiaire: 'Beneficiaire',
  montantBrut: 'Montant brut', montantOperation: 'Montant operation',
  referenceFacture: 'Ref. Facture', referenceBcOuContrat: 'Ref. BC/Contrat',
  prestataire: 'Prestataire', referenceFacture2: 'Ref. Facture', nomProjet: 'Projet',
  signataire: 'Signataire', dateEdition: 'Date edition', validite: 'Validite',
  titre: 'Titre', dateReception: 'Date reception',
}

function formatValue(key: string, value: unknown): string {
  if (value == null) return '\u2014'
  const s = String(value)
  if (key.toLowerCase().includes('montant') || key.toLowerCase().includes('ht') || key.toLowerCase().includes('ttc') || key.toLowerCase().includes('tva')) {
    const n = parseFloat(s.replace(/\s/g, '').replace(',', '.'))
    if (!isNaN(n)) return n.toLocaleString('fr-FR', { minimumFractionDigits: 2 }) + ' MAD'
  }
  if (key.toLowerCase().includes('taux')) {
    const n = parseFloat(s.replace(/\s/g, '').replace(',', '.'))
    if (!isNaN(n)) return n + '%'
  }
  return s
}

export default memo(function ExtractedDataView({ data, docType }: Props) {
  const { toast } = useToast()
  if (!data) return <p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Aucune donnee extraite</p>

  const isInvoice = docType === 'FACTURE'
  const isOP = docType === 'ORDRE_PAIEMENT'
  const sections = isInvoice ? INVOICE_SECTIONS : isOP ? OP_SECTIONS : null

  const scalars = Object.entries(data).filter(([, v]) => v !== null && !Array.isArray(v) && typeof v !== 'object')
  const lignes = (data['lignes'] as Array<Record<string, unknown>> | undefined) || []
  const retenues = (data['retenues'] as Array<Record<string, unknown>> | undefined) || []
  const isLlmExtracted = scalars.length > 3

  // Structured view for invoices and OPs
  if (sections) {
    const allFieldKeys = sections.flatMap(s => s.fields)
    const remainingScalars = scalars.filter(([k]) => !allFieldKeys.includes(k))

    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {/* Structured sections */}
        {sections.map(section => {
          const sectionFields = section.fields
            .map(f => [f, data[f]] as const)
            .filter(([, v]) => v != null)
          if (sectionFields.length === 0) return null

          return (
            <div key={section.title}>
              <div style={{
                fontSize: 9, fontWeight: 700, color: 'var(--accent-deep)',
                textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6,
                fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: 6
              }}>
                <span style={{ width: 12, height: 1, background: 'var(--accent)', display: 'inline-block' }} />
                {section.title}
              </div>
              <div style={{
                display: 'grid',
                gridTemplateColumns: section.fields.length <= 3 ? '1fr 1fr 1fr' : 'repeat(auto-fill, minmax(180px, 1fr))',
                gap: '1px',
                background: 'var(--ink-05)',
                borderRadius: 6,
                overflow: 'hidden',
                border: '1px solid var(--ink-05)',
              }}>
                {sectionFields.map(([k, v]) => (
                  <div key={k} style={{
                    padding: '8px 10px', background: '#fff',
                    display: 'flex', flexDirection: 'column', gap: 2,
                  }}>
                    <span style={{
                      fontSize: 9, fontWeight: 600, color: 'var(--ink-30)',
                      textTransform: 'uppercase', letterSpacing: 0.5,
                      fontFamily: 'var(--font-mono)',
                    }}>
                      {FIELD_LABELS[k] || k}
                    </span>
                    <span
                      style={{
                        fontSize: 13, fontWeight: 600, color: 'var(--ink)',
                        fontFamily: (section as { mono?: boolean }).mono ? 'var(--font-mono)' : 'inherit',
                        wordBreak: 'break-all',
                      }}
                      contentEditable suppressContentEditableWarning
                      onBlur={e => {
                        const newVal = e.currentTarget.textContent || ''
                        if (newVal !== String(v)) toast('info', `${FIELD_LABELS[k] || k}: ${String(v)} \u2192 ${newVal}`)
                      }}
                    >
                      {formatValue(k, v)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )
        })}

        {/* Invoice line items */}
        {lignes.length > 0 && (
          <div>
            <div style={{
              fontSize: 9, fontWeight: 700, color: 'var(--accent-deep)',
              textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6,
              fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: 6
            }}>
              <span style={{ width: 12, height: 1, background: 'var(--accent)', display: 'inline-block' }} />
              Lignes de facturation ({lignes.length})
            </div>
            <table className="data-table">
              <thead><tr>
                <th style={{ width: 30 }}>#</th>
                <th>Designation</th>
                <th style={{ width: 60 }}>Qte</th>
                <th style={{ width: 90 }}>PU HT</th>
                <th style={{ width: 100 }}>Total HT</th>
              </tr></thead>
              <tbody>
                {lignes.map((ln, i) => (
                  <tr key={i}>
                    <td style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-30)' }}>{i + 1}</td>
                    <td>{String(ln.designation || ln.codeArticle || '\u2014')}</td>
                    <td className="cell-mono">{ln.quantite != null ? String(ln.quantite) : '\u2014'}</td>
                    <td className="cell-mono">{formatValue('montant', ln.prixUnitaireHT)}</td>
                    <td className="cell-mono" style={{ fontWeight: 600 }}>{formatValue('montant', ln.montantTotalHT)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* OP retenues */}
        {retenues.length > 0 && (
          <div>
            <div style={{
              fontSize: 9, fontWeight: 700, color: 'var(--accent-deep)',
              textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6,
              fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: 6
            }}>
              <span style={{ width: 12, height: 1, background: 'var(--accent)', display: 'inline-block' }} />
              Retenues ({retenues.length})
            </div>
            <table className="data-table">
              <thead><tr><th>Designation</th><th>Base</th><th>Taux</th><th>Montant</th></tr></thead>
              <tbody>
                {retenues.map((r, i) => (
                  <tr key={i}>
                    <td>{String(r.designation || r.type || '\u2014')}</td>
                    <td className="cell-mono">{formatValue('montant', r.base)}</td>
                    <td className="cell-mono">{r.taux != null ? String(r.taux) + '%' : '\u2014'}</td>
                    <td className="cell-mono" style={{ fontWeight: 600 }}>{formatValue('montant', r.montant)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Remaining fields not in structured sections */}
        {remainingScalars.length > 0 && (
          <div>
            <div style={{
              fontSize: 9, fontWeight: 700, color: 'var(--ink-30)',
              textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6,
              fontFamily: 'var(--font-mono)',
            }}>
              Autres champs
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
              {remainingScalars.map(([k, v]) => (
                <div key={k} className="data-field">
                  <span className="data-field-key">{FIELD_LABELS[k] || k}</span>
                  <span className="data-field-value" contentEditable suppressContentEditableWarning
                    onBlur={e => { const nv = e.currentTarget.textContent || ''; if (nv !== String(v)) toast('info', `${k}: ${String(v)} \u2192 ${nv}`) }}>
                    {formatValue(k, v)}
                  </span>
                  <span className="data-field-source ai">IA</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Render shared sub-sections (points, pieces, signataires) */}
        <SubSections data={data} />
      </div>
    )
  }

  // Generic view for other doc types
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
        {scalars.map(([k, v]) => (
          <div key={k} className="data-field">
            <span className="data-field-key">{FIELD_LABELS[k] || k}</span>
            <span className="data-field-value" contentEditable suppressContentEditableWarning
              onBlur={e => { const nv = e.currentTarget.textContent || ''; if (nv !== String(v)) toast('info', `${k}: ${String(v)} \u2192 ${nv}`) }}>
              {formatValue(k, v)}
            </span>
            <span className={`data-field-source ${isLlmExtracted ? 'ai' : 'ocr'}`}>{isLlmExtracted ? 'IA' : 'OCR'}</span>
          </div>
        ))}
      </div>
      <SubSections data={data} />
    </div>
  )
})

// Shared sub-components for points, pieces, signataires
function SubSections({ data }: { data: Record<string, unknown> }) {
  const points = (data['points'] as Array<Record<string, unknown>> | undefined) || []
  const pieces = (data['pieces'] as Array<Record<string, unknown>> | undefined) || []
  const signataires = (data['signataires'] as Array<Record<string, unknown>> | undefined) || []
  const signataire = data['signataire'] as string | undefined

  return (
    <>
      {points.length > 0 && (
        <div>
          <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--accent-deep)', textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6, fontFamily: 'var(--font-mono)', display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 12, height: 1, background: 'var(--accent)', display: 'inline-block' }} />
            Points de controle ({points.filter(p => p.estValide === true).length}/{points.length} valides)
          </div>
          {points.map((pt, i) => {
            const ok = pt.estValide === true, fail = pt.estValide === false
            return (
              <div key={i} className="check-point">
                <span className="check-point-num">{pt.numero != null ? String(pt.numero) : String(i + 1)}</span>
                <span className={`check-point-icon ${ok ? 'pass' : fail ? 'fail' : 'na'}`}>
                  {ok ? '\u2713' : fail ? '\u2717' : '\u2014'}
                </span>
                <div className="check-point-body">
                  <div className="check-point-label">{String(pt.description || pt.designation || `Point ${i + 1}`)}</div>
                  {pt.observation != null && <div className="check-point-obs">{String(pt.observation)}</div>}
                </div>
              </div>
            )
          })}
        </div>
      )}

      {(signataires.length > 0 || signataire) && (
        <div style={{ padding: '10px 12px', background: 'var(--ink-02)', borderRadius: 6 }}>
          <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 6, fontFamily: 'var(--font-mono)' }}>Signatures</div>
          {signataires.length > 0 ? signataires.map((sig, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '4px 0' }}>
              <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 16, height: 16, fontSize: 10 }}>
                {sig.aSignature ? '\u2713' : '\u2014'}
              </span>
              <span style={{ fontWeight: 600, fontSize: 12 }}>{String(sig.nom || 'Inconnu')}</span>
              {sig.date != null && <span style={{ fontSize: 11, color: 'var(--ink-40)', fontFamily: 'var(--font-mono)' }}>{String(sig.date)}</span>}
              {sig.aSignature === true && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Signe</span>}
            </div>
          )) : <div style={{ fontSize: 12, color: 'var(--ink-50)' }}>{signataire}</div>}
        </div>
      )}

      {pieces.length > 0 && (
        <div>
          <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--accent-deep)', textTransform: 'uppercase', letterSpacing: 1.5, marginBottom: 6, fontFamily: 'var(--font-mono)' }}>
            Pieces justificatives ({pieces.length})
          </div>
          {pieces.map((pc, i) => (
            <div key={i} className="check-point">
              <span className={`check-point-icon ${pc.estPresent ? 'pass' : pc.estPresent === false ? 'fail' : 'na'}`}>
                {pc.estPresent ? '\u2713' : pc.estPresent === false ? '\u2717' : '?'}
              </span>
              <div className="check-point-body">
                <div className="check-point-label">{String(pc.designation || `Piece ${i + 1}`)}</div>
                {pc.observation != null && <div className="check-point-obs">{String(pc.observation)}</div>}
              </div>
              <span className="tag" style={{ fontSize: 9 }}>{pc.original ? 'Original' : 'Copie'}</span>
            </div>
          ))}
        </div>
      )}
    </>
  )
}
