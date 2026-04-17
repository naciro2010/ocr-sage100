import { memo } from 'react'
import { useToast } from '../Toast'

interface Props {
  data: Record<string, unknown> | null | undefined
  docType?: string
}

const INVOICE_SECTIONS: { title: string; fields: string[]; mono?: boolean }[] = [
  { title: 'Identification', fields: ['numeroFacture', 'dateFacture', 'referenceContrat', 'numeroBonCommande'] },
  { title: 'Fournisseur', fields: ['fournisseur', 'ice', 'identifiantFiscal', 'rc', 'cnss', 'patente', 'rib', 'ribs', 'banque'] },
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
  rib: 'RIB', ribs: 'Tous les RIBs', banque: 'Banque', client: 'Client', clientIce: 'ICE Client',
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
  raisonSociale: 'Raison sociale', numero: 'N\u00b0 attestation',
  estEnRegle: 'En regle', codeVerification: 'Code de verification',
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

  if (sections) {
    const allFieldKeys = sections.flatMap(s => s.fields)
    const remainingScalars = scalars.filter(([k]) => !allFieldKeys.includes(k))

    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        {sections.map(section => {
          const sectionFields = section.fields
            .map(f => [f, data[f]] as const)
            .filter(([, v]) => v != null)
          if (sectionFields.length === 0) return null

          return (
            <div key={section.title}>
              <div className="edv-section-title">{section.title}</div>
              <div className={`edv-grid ${sectionFields.length <= 3 ? 'cols-3' : 'cols-auto'}`}>
                {sectionFields.map(([k, v]) => (
                  <div key={k} className="edv-cell">
                    <span className="edv-cell-key">{FIELD_LABELS[k] || k}</span>
                    {Array.isArray(v) ? (
                      <div className="edv-cell-value mono" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                        {(v as string[]).map((item, idx) => (
                          <span key={idx} style={{
                            padding: '2px 6px', borderRadius: 3, fontSize: 12,
                            background: idx === 0 ? 'var(--success-bg)' : 'var(--ink-05)',
                            border: `1px solid ${idx === 0 ? 'rgba(16,185,129,0.2)' : 'var(--ink-10)'}`,
                          }}>
                            {item}{idx === 0 && (v as string[]).length > 1 && <span style={{ fontSize: 8, marginLeft: 4, color: 'var(--success)' }}>principal</span>}
                          </span>
                        ))}
                      </div>
                    ) : (
                      <span
                        className={`edv-cell-value ${(section as { mono?: boolean }).mono ? 'mono' : ''}`}
                        contentEditable suppressContentEditableWarning
                        onBlur={e => {
                          const newVal = e.currentTarget.textContent || ''
                          if (newVal !== String(v)) toast('info', `${FIELD_LABELS[k] || k}: ${String(v)} \u2192 ${newVal}`)
                        }}
                      >
                        {formatValue(k, v)}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )
        })}

        {lignes.length > 0 && (
          <div>
            <div className="edv-section-title">Lignes de facturation ({lignes.length})</div>
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
                    <td className="rule-code" style={{ color: 'var(--ink-30)' }}>{i + 1}</td>
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

        {retenues.length > 0 && (
          <div>
            <div className="edv-section-title">Retenues ({retenues.length})</div>
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

        {remainingScalars.length > 0 && (
          <div>
            <div className="edv-section-other">Autres champs</div>
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

        <SubSections data={data} />
      </div>
    )
  }

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

function SubSections({ data }: { data: Record<string, unknown> }) {
  const points = (data['points'] as Array<Record<string, unknown>> | undefined) || []
  const pieces = (data['pieces'] as Array<Record<string, unknown>> | undefined) || []
  const signataires = (data['signataires'] as Array<Record<string, unknown>> | undefined) || []
  const signataire = data['signataire'] as string | undefined
  const qr = data['_qr'] as Record<string, unknown> | undefined
  const codeVerification = (data['codeVerification'] as string | null | undefined) || null

  return (
    <>
      {qr && <QrVerificationPanel qr={qr} printedCode={codeVerification} />}

      {points.length > 0 && (
        <div>
          <div className="edv-section-title">
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
        <div className="edv-signatures">
          <div className="edv-sig-label">Signatures</div>
          {signataires.length > 0 ? signataires.map((sig, i) => (
            <div key={i} className="edv-sig-row">
              <span className={`check-point-icon ${sig.aSignature ? 'pass' : 'na'}`} style={{ width: 16, height: 16, fontSize: 10 }}>
                {sig.aSignature ? '\u2713' : '\u2014'}
              </span>
              <span className="edv-sig-name">{String(sig.nom || 'Inconnu')}</span>
              {sig.date != null && <span className="edv-sig-date">{String(sig.date)}</span>}
              {sig.aSignature === true && <span className="tag" style={{ fontSize: 8, background: 'var(--success-bg)', color: 'var(--success)' }}>Signe</span>}
            </div>
          )) : <div style={{ fontSize: 12, color: 'var(--ink-50)' }}>{signataire}</div>}
        </div>
      )}

      {pieces.length > 0 && (
        <div>
          <div className="edv-section-title">
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

function QrVerificationPanel({
  qr, printedCode,
}: {
  qr: Record<string, unknown>
  printedCode: string | null
}) {
  const payload = (qr.payload as string | null) || null
  const qrCode = (qr.codeExtrait as string | null) || null
  const host = (qr.host as string | null) || null
  const officialHost = Boolean(qr.officialHost)
  const error = (qr.error as string | null) || null
  const scannedAt = (qr.scannedAt as string | null) || null

  const norm = (s: string | null) => (s ? s.trim().toLowerCase().replace(/[\s\-_|/.]+/g, '') : '')
  const match = qrCode && printedCode ? norm(qrCode) === norm(printedCode) : null

  const status: { label: string; bg: string; color: string } =
    !payload ? { label: 'QR illisible', bg: 'rgba(239,68,68,0.1)', color: '#b91c1c' }
    : match === true && officialHost ? { label: 'Verifie', bg: 'var(--success-bg)', color: 'var(--success)' }
    : match === true ? { label: 'Codes coherents', bg: 'rgba(245,158,11,0.1)', color: '#b45309' }
    : match === false ? { label: 'Mismatch', bg: 'rgba(239,68,68,0.1)', color: '#b91c1c' }
    : { label: 'A verifier', bg: 'rgba(245,158,11,0.1)', color: '#b45309' }

  const isUrl = payload ? /^https?:\/\//i.test(payload) : false

  return (
    <div style={{
      border: '1px solid var(--ink-10)', borderRadius: 6, padding: 12,
      background: 'var(--ink-05)', display: 'flex', flexDirection: 'column', gap: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div className="edv-section-title" style={{ margin: 0 }}>Verification QR code DGI</div>
        <span className="tag" style={{ background: status.bg, color: status.color, fontWeight: 600 }}>
          {status.label}
        </span>
      </div>

      {error && !payload && (
        <div style={{ fontSize: 12, color: '#b91c1c' }}>
          Scan QR : {error}
        </div>
      )}

      <div className="edv-grid cols-auto" style={{ gap: 8 }}>
        <div className="edv-cell">
          <span className="edv-cell-key">Code imprime (OCR)</span>
          <span className="edv-cell-value mono">{printedCode || '\u2014'}</span>
        </div>
        <div className="edv-cell">
          <span className="edv-cell-key">Code extrait du QR</span>
          <span className="edv-cell-value mono" style={{ color: match === false ? '#b91c1c' : undefined }}>
            {qrCode || '\u2014'}
          </span>
        </div>
        <div className="edv-cell">
          <span className="edv-cell-key">Domaine cible</span>
          <span className="edv-cell-value mono" style={{ color: host && !officialHost ? '#b45309' : undefined }}>
            {host || '\u2014'}{officialHost ? ' \u2713' : host ? ' (inattendu)' : ''}
          </span>
        </div>
        {scannedAt && (
          <div className="edv-cell">
            <span className="edv-cell-key">Scanne le</span>
            <span className="edv-cell-value">{new Date(scannedAt).toLocaleString('fr-FR')}</span>
          </div>
        )}
      </div>

      {payload && (
        <div style={{ fontSize: 11, color: 'var(--ink-50)', wordBreak: 'break-all' }}>
          <span style={{ fontWeight: 600 }}>Contenu du QR :</span>{' '}
          {isUrl ? (
            <a href={payload} target="_blank" rel="noreferrer noopener" style={{ color: 'var(--link)' }}>
              {payload}
            </a>
          ) : (
            <span className="mono">{payload}</span>
          )}
        </div>
      )}
    </div>
  )
}
