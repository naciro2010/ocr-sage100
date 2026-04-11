import { useState } from 'react'
import type { DossierDetail } from '../api/dossierTypes'
import { CHECK_ICONS } from '../api/dossierTypes'
import { updateValidationResult } from '../api/dossierApi'
import { useToast } from './Toast'
import { ShieldCheck, RefreshCw, Loader2, ChevronDown, ChevronUp, Save, ClipboardCheck } from 'lucide-react'

const RULE_PROVENANCE: Record<string, { docs: string[]; fields: string[]; desc: string }> = {
  R01: { docs: ['Facture', 'Bon de commande'], fields: ['montantTtc'], desc: 'Compare le TTC de la facture avec le BC' },
  R02: { docs: ['Facture', 'Bon de commande'], fields: ['montantHt'], desc: 'Compare le HT de la facture avec le BC' },
  R03: { docs: ['Facture', 'Bon de commande'], fields: ['montantTva'], desc: 'Compare la TVA de la facture avec le BC' },
  R03b: { docs: ['Facture', 'Bon de commande'], fields: ['tauxTva'], desc: 'Taux TVA differents (multi-taux possible)' },
  R04: { docs: ['Facture', 'Ordre de paiement'], fields: ['montantTtc', 'montantOperation'], desc: 'OP = TTC facture (sans retenues)' },
  R05: { docs: ['Facture', 'Ordre de paiement'], fields: ['montantTtc', 'retenues'], desc: 'OP = TTC - retenues a la source' },
  R06: { docs: ['Ordre de paiement'], fields: ['retenues.base', 'retenues.taux'], desc: 'Calcul arithmetique des retenues' },
  R07: { docs: ['Facture', 'Ordre de paiement'], fields: ['numeroFacture', 'referenceFacture'], desc: 'N° facture cite dans l\'OP' },
  R08: { docs: ['BC / Contrat', 'Ordre de paiement'], fields: ['reference', 'referenceBcOuContrat'], desc: 'N° BC/contrat cite dans l\'OP' },
  R09: { docs: ['Facture', 'Attestation fiscale'], fields: ['ice'], desc: 'ICE identique entre documents' },
  R10: { docs: ['Facture', 'Attestation fiscale'], fields: ['identifiantFiscal'], desc: 'IF identique entre documents' },
  R11: { docs: ['Facture', 'Ordre de paiement'], fields: ['rib'], desc: 'RIB identique (espaces ignores)' },
  R12: { docs: ['Checklist autocontrole'], fields: ['points[].estValide'], desc: 'Tous les points valides' },
  R13: { docs: ['Tableau de controle'], fields: ['points[].observation'], desc: 'Tous les points Conforme ou NA' },
  R14: { docs: ['Facture', 'BC', 'OP', 'Checklist'], fields: ['fournisseur'], desc: 'Nom fournisseur coherent' },
  R15: { docs: ['Contrat/Avenant', 'Facture', 'PV reception'], fields: ['grillesTarifaires', 'montantHt'], desc: 'Grille x duree = HT facture' },
  R17a: { docs: ['BC / Contrat', 'Facture'], fields: ['date'], desc: 'Date BC/contrat <= date facture' },
  R17b: { docs: ['Facture', 'Ordre de paiement'], fields: ['date'], desc: 'Date facture <= date OP' },
  R18: { docs: ['Attestation fiscale'], fields: ['dateEdition'], desc: 'Attestation valide 6 mois' },
  R20: { docs: ['Tous les documents'], fields: ['typeDocument'], desc: 'Pieces obligatoires presentes' },
}

interface Props {
  dossier: DossierDetail
  onValidate: () => void
  validating: boolean
}

export default function ValidationPanel({ dossier, onValidate, validating }: Props) {
  const { toast } = useToast()
  const [expandedRule, setExpandedRule] = useState<string | null>(null)
  const [editComment, setEditComment] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState<string | null>(null)
  const results = dossier.resultatsValidation

  const nbConformes = results.filter(r => r.statut === 'CONFORME').length
  const nbNonConformes = results.filter(r => r.statut === 'NON_CONFORME').length
  const nbWarn = results.filter(r => r.statut === 'AVERTISSEMENT').length

  // Sort + dedup
  const seen = new Set<string>()
  const sorted = [...results]
    .sort((a, b) => {
      const na = parseInt(a.regle.replace(/\D/g, '')) || 0
      const nb = parseInt(b.regle.replace(/\D/g, '')) || 0
      return na !== nb ? na - nb : a.regle.localeCompare(b.regle)
    })
    .filter(r => { const k = `${r.regle}:${r.libelle}`; if (seen.has(k)) return false; seen.add(k); return true })

  const getSourceValue = (rule: string): { docA?: string; valA?: string; docB?: string; valB?: string } => {
    const r = results.find(x => x.regle === rule)
    if (!r) return {}
    const prov = RULE_PROVENANCE[rule]
    if (!prov) return {}
    return {
      docA: prov.docs[0],
      valA: r.valeurAttendue || undefined,
      docB: prov.docs[1],
      valB: r.valeurTrouvee || undefined,
    }
  }

  // Group results by rule group for organized display
  // Autocontrole points from checklist
  const checklistData = dossier.checklistAutocontrole
  const checklistPoints = (checklistData?.points as Array<Record<string, unknown>> | undefined) || []
  const hasChecklist = checklistPoints.length > 0
  const checklistConformes = checklistPoints.filter(p => p.estValide === true).length
  const checklistNonConformes = checklistPoints.filter(p => p.estValide === false).length

  return (
    <>
    {/* Autocontrole verification block */}
    {hasChecklist && (
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}>
            <ClipboardCheck size={14} /> Verification autocontrole
            <span style={{ fontWeight: 500, fontSize: 11, color: 'var(--ink-40)', marginLeft: 8, textTransform: 'none', letterSpacing: 0 }}>
              {checklistConformes} OK, {checklistNonConformes} KO, {checklistPoints.length - checklistConformes - checklistNonConformes} N/A
            </span>
          </h2>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {checklistPoints.map((pt, i) => {
            const isOk = pt.estValide === true
            const isFail = pt.estValide === false
            const icon = isOk ? '\u2713' : isFail ? '\u2717' : '\u2014'
            const color = isOk ? '#10b981' : isFail ? '#ef4444' : '#6b7280'
            const cls = isFail ? 'fail' : ''
            return (
              <div key={i} className={`validation-item ${cls}`}>
                <span style={{ color, fontWeight: 800, fontSize: 14, width: 20, flexShrink: 0, textAlign: 'center' }}>{icon}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 12 }}>
                    <span style={{ color: 'var(--ink-30)', marginRight: 6, fontSize: 10, fontFamily: 'var(--font-mono)' }}>CK{String(pt.numero || i + 1).padStart(2, '0')}</span>
                    {String(pt.description || `Point ${i + 1}`)}
                  </div>
                  {pt.observation != null && String(pt.observation) !== '\\u2014' && (
                    <div style={{ fontSize: 11, color: 'var(--ink-40)', marginTop: 1 }}>{String(pt.observation)}</div>
                  )}
                </div>
                <span className="prov-badge" style={{ background: 'var(--ink-05)', color: 'var(--ink-40)', marginRight: 4 }}>Autocontrole</span>
              </div>
            )
          })}
        </div>
      </div>
    )}

    <div className="card">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ marginBottom: 0 }}>
          <ShieldCheck size={14} /> Verification croisee
          <span style={{ fontWeight: 500, fontSize: 11, color: 'var(--ink-40)', marginLeft: 8, textTransform: 'none', letterSpacing: 0 }}>
            {nbConformes} conformes, {nbNonConformes} non conformes, {nbWarn} avertissements
          </span>
        </h2>
        <button className="btn btn-secondary btn-sm" onClick={onValidate} disabled={validating}>
          {validating ? <Loader2 size={12} className="spin" /> : <RefreshCw size={12} />}
          Relancer
        </button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {sorted.map((r, i) => {
          const chk = CHECK_ICONS[r.statut]
          const cls = r.statut === 'NON_CONFORME' ? 'fail' : r.statut === 'AVERTISSEMENT' ? 'warn' : ''
          const prov = RULE_PROVENANCE[r.regle]
          const isExpanded = expandedRule === `${r.regle}-${i}`
          const sv = getSourceValue(r.regle)

          return (
            <div key={`${r.regle}-${i}`}>
              <div
                className={`validation-item ${cls}`}
                style={{ cursor: 'pointer' }}
                onClick={() => setExpandedRule(isExpanded ? null : `${r.regle}-${i}`)}
              >
                <span style={{ color: chk.color, fontWeight: 800, fontSize: 14, width: 20, flexShrink: 0, textAlign: 'center' }}>{chk.icon}</span>
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 600, fontSize: 12 }}>
                    <span style={{ color: 'var(--ink-30)', marginRight: 6, fontSize: 10, fontFamily: 'var(--font-mono)' }}>{r.regle}</span>
                    {r.libelle}
                  </div>
                  {r.detail && !isExpanded && (
                    <div style={{ fontSize: 11, color: 'var(--ink-40)', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 500 }}>{r.detail}</div>
                  )}
                </div>
                <span className={`prov-badge ${r.source.toLowerCase()}`} style={{ marginRight: 4 }}>{r.source === 'DETERMINISTE' ? 'Systeme' : r.source === 'LLM' ? 'IA' : r.source}</span>
                {isExpanded ? <ChevronUp size={14} style={{ color: 'var(--ink-30)' }} /> : <ChevronDown size={14} style={{ color: 'var(--ink-30)' }} />}
              </div>

              {/* Expanded compare view */}
              {isExpanded && (
                <div style={{
                  margin: '0 0 4px 0', padding: '12px 14px',
                  background: 'var(--ink-02)', borderRadius: '0 0 6px 6px',
                  borderTop: '1px solid var(--ink-05)',
                  animation: 'fadeUp 0.15s ease'
                }}>
                  {/* Rule description */}
                  {prov && (
                    <div style={{ fontSize: 11, color: 'var(--ink-40)', marginBottom: 10 }}>
                      {prov.desc}
                    </div>
                  )}

                  {/* Compare side by side */}
                  {sv.valA != null && sv.valB != null && (
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 10 }}>
                      <div style={{ padding: '8px 10px', background: '#fff', borderRadius: 5, border: '1px solid var(--ink-05)' }}>
                        <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 4, fontFamily: 'var(--font-mono)' }}>
                          {sv.docA || 'Attendu'}
                        </div>
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)', fontFamily: 'var(--font-mono)', wordBreak: 'break-all' }}>
                          {sv.valA}
                        </div>
                      </div>
                      <div style={{
                        padding: '8px 10px', borderRadius: 5,
                        background: r.statut === 'CONFORME' ? 'var(--success-bg)' : r.statut === 'NON_CONFORME' ? 'var(--danger-bg)' : 'var(--warning-bg)',
                        border: `1px solid ${r.statut === 'CONFORME' ? 'rgba(16,185,129,0.1)' : r.statut === 'NON_CONFORME' ? 'rgba(239,68,68,0.1)' : 'rgba(245,158,11,0.1)'}`
                      }}>
                        <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', letterSpacing: 1, marginBottom: 4, fontFamily: 'var(--font-mono)' }}>
                          {sv.docB || 'Trouve'}
                        </div>
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ink)', fontFamily: 'var(--font-mono)', wordBreak: 'break-all' }}>
                          {sv.valB}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Full detail */}
                  {r.detail && (
                    <div style={{ fontSize: 12, color: 'var(--ink-50)', lineHeight: 1.5, marginBottom: 8 }}>
                      {r.detail}
                    </div>
                  )}

                  {/* Source documents & fields */}
                  {prov && (
                    <div style={{ display: 'flex', gap: 16, fontSize: 10, color: 'var(--ink-30)', marginBottom: 10 }}>
                      <div>
                        <span style={{ fontWeight: 700, fontFamily: 'var(--font-mono)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Documents : </span>
                        {prov.docs.join(' \u2194 ')}
                      </div>
                      <div>
                        <span style={{ fontWeight: 700, fontFamily: 'var(--font-mono)', textTransform: 'uppercase', letterSpacing: 0.5 }}>Champs : </span>
                        {prov.fields.join(', ')}
                      </div>
                    </div>
                  )}

                  {/* Correction controls */}
                  {r.id && (
                    <div style={{ borderTop: '1px solid var(--ink-05)', paddingTop: 10, marginTop: 6 }}>
                      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-end' }}>
                        <div style={{ flex: 0 }}>
                          <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', marginBottom: 3 }}>Statut</div>
                          <select className="form-select" value={r.statut}
                            style={{ fontSize: 11, padding: '4px 6px', width: 'auto' }}
                            onChange={async (e) => {
                              setSaving(r.id)
                              try {
                                await updateValidationResult(dossier.id, r.id!, { statut: e.target.value })
                                toast('success', `${r.regle} corrige`)
                                onValidate()
                              } catch { toast('error', 'Erreur') }
                              finally { setSaving(null) }
                            }}>
                            <option value="CONFORME">Conforme</option>
                            <option value="NON_CONFORME">Non conforme</option>
                            <option value="AVERTISSEMENT">Avertissement</option>
                            <option value="NON_APPLICABLE">Non applicable</option>
                          </select>
                        </div>
                        <div style={{ flex: 1 }}>
                          <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--ink-30)', textTransform: 'uppercase', marginBottom: 3 }}>Commentaire</div>
                          <input className="form-input"
                            placeholder="Commentaire de correction..."
                            value={editComment[r.id!] ?? r.commentaire ?? ''}
                            onChange={e => setEditComment(prev => ({ ...prev, [r.id!]: e.target.value }))}
                            style={{ fontSize: 11, padding: '4px 8px' }}
                          />
                        </div>
                        <button className="btn btn-primary btn-sm"
                          disabled={saving === r.id}
                          onClick={async () => {
                            setSaving(r.id)
                            try {
                              await updateValidationResult(dossier.id, r.id!, { commentaire: editComment[r.id!] || r.commentaire || '' })
                              toast('success', 'Commentaire sauvegarde')
                              onValidate()
                            } catch { toast('error', 'Erreur') }
                            finally { setSaving(null) }
                          }}>
                          {saving === r.id ? <Loader2 size={11} className="spin" /> : <Save size={11} />}
                        </button>
                      </div>
                      {r.statutOriginal && r.statutOriginal !== r.statut && (
                        <div style={{ fontSize: 10, color: 'var(--warning)', marginTop: 4, fontFamily: 'var(--font-mono)' }}>
                          Corrige : {r.statutOriginal} \u2192 {r.statut} {r.corrigePar && `par ${r.corrigePar}`}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
    </>
  )
}
