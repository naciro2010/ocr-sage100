import { memo, useState, useMemo } from 'react'
import { Check, X, AlertTriangle, ShieldCheck, Calendar, Loader2 } from 'lucide-react'
import { useToast } from '../Toast'
import { updateAttestationRegularite } from '../../api/dossierApi'
import type { ValidationResult } from '../../api/dossierTypes'

interface Props {
  dossierId: string
  attestation: Record<string, unknown> | null
  ordrePaiement: Record<string, unknown> | null
  validationResults: ValidationResult[]
  onResultsUpdated: (results: ValidationResult[]) => void
  onReload: () => void
}

const FORMULAIRE = {
  enRegle: "N'a pas, a la date de delivrance de cette attestation, de dette fiscale exigible ni de procedure engagee pour un manquement aux obligations de declaration",
  pasEnRegle: "N'est pas en regle quant aux obligations suivantes",
} as const

function parseDate(v: unknown): Date | null {
  if (typeof v !== 'string' || !v) return null
  const d = new Date(v)
  return isNaN(d.getTime()) ? null : d
}

function fmtDate(d: Date | null): string {
  if (!d) return '—'
  return d.toLocaleDateString('fr-FR', { day: '2-digit', month: 'short', year: 'numeric' })
}

function daysDiff(a: Date, b: Date): number {
  return Math.floor((a.getTime() - b.getTime()) / (1000 * 60 * 60 * 24))
}

function CheckboxBox({
  state, label, code,
}: { state: 'checked' | 'unchecked' | 'unknown'; label: string; code: 'en_regle' | 'pas_en_regle' }) {
  const tone = code === 'en_regle' ? 'success' : 'danger'
  const colorOn = tone === 'success' ? 'var(--success)' : 'var(--danger)'
  const colorBg = tone === 'success' ? 'var(--success-bg)' : 'var(--danger-bg)'
  const isChecked = state === 'checked'
  return (
    <div className={`afp-case afp-case-${state}`} style={{
      display: 'flex', gap: 10, alignItems: 'flex-start',
      padding: '10px 12px', borderRadius: 6,
      background: isChecked ? colorBg : 'var(--ink-02)',
      border: `1px solid ${isChecked ? colorOn : 'var(--ink-10)'}`,
      opacity: state === 'unknown' ? 0.6 : 1,
    }}>
      <div style={{
        width: 20, height: 20, flexShrink: 0,
        borderRadius: 3, border: `1.5px solid ${isChecked ? colorOn : 'var(--ink-30)'}`,
        background: isChecked ? colorOn : 'transparent',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        marginTop: 1,
      }}>
        {isChecked && (code === 'en_regle' ? <Check size={14} color="#fff" strokeWidth={3} /> : <X size={14} color="#fff" strokeWidth={3} />)}
      </div>
      <div style={{ fontSize: 12, lineHeight: 1.4, color: isChecked ? colorOn : 'var(--ink-50)', fontWeight: isChecked ? 600 : 400 }}>
        {label}
      </div>
    </div>
  )
}

function StatusPill({ tone, children }: { tone: 'ok' | 'ko' | 'warn' | 'info'; children: React.ReactNode }) {
  const cfg = {
    ok: { bg: 'var(--success-bg)', color: 'var(--success)' },
    ko: { bg: 'var(--danger-bg)', color: 'var(--danger)' },
    warn: { bg: 'var(--warning-bg)', color: 'var(--warning)' },
    info: { bg: 'var(--info-bg)', color: 'var(--info)' },
  }[tone]
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '3px 8px', borderRadius: 999, fontSize: 11, fontWeight: 700,
      background: cfg.bg, color: cfg.color, letterSpacing: 0.2,
    }}>{children}</span>
  )
}

export default memo(function AttestationFiscalePanel({
  dossierId, attestation, ordrePaiement, validationResults, onResultsUpdated, onReload,
}: Props) {
  const { toast } = useToast()
  const [saving, setSaving] = useState<'true' | 'false' | 'null' | null>(null)

  const estEnRegle = attestation?.estEnRegle
  const enRegleState: 'checked' | 'unchecked' | 'unknown' =
    estEnRegle === true ? 'checked' : estEnRegle === false ? 'unchecked' : 'unknown'
  const pasEnRegleState: 'checked' | 'unchecked' | 'unknown' =
    estEnRegle === false ? 'checked' : estEnRegle === true ? 'unchecked' : 'unknown'

  const dateEdition = useMemo(() => parseDate(attestation?.dateEdition), [attestation])
  const dateValidite = useMemo(() => {
    const stored = parseDate(attestation?.dateValidite)
    if (stored) return stored
    if (!dateEdition) return null
    const d = new Date(dateEdition)
    d.setMonth(d.getMonth() + 6)
    return d
  }, [attestation, dateEdition])
  const dateOp = useMemo(() => parseDate(ordrePaiement?.dateEmission), [ordrePaiement])
  const today = useMemo(() => new Date(), [])

  const r18 = validationResults.find(r => r.regle === 'R18')
  const r18b = validationResults.find(r => r.regle === 'R18b')
  const r23 = validationResults.find(r => r.regle === 'R23')
  const r19 = validationResults.find(r => r.regle === 'R19')

  const opAfterValidite = dateOp && dateValidite ? dateOp.getTime() > dateValidite.getTime() : false
  const expiredToday = dateValidite ? today.getTime() > dateValidite.getTime() : false
  const daysToExpire = dateValidite ? daysDiff(dateValidite, today) : null

  const handleCorrect = async (next: boolean | null) => {
    const tag = next == null ? 'null' : next ? 'true' : 'false'
    setSaving(tag)
    try {
      const results = await updateAttestationRegularite(dossierId, next, 'operateur')
      onResultsUpdated(results)
      toast('success', next == null ? 'Statut remis a indetermine' : next ? 'Marquee comme en regle' : 'Marquee comme NON en regle')
      onReload()
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur lors de la correction')
    } finally {
      setSaving(null)
    }
  }

  const aggregateStatus: { tone: 'ok' | 'ko' | 'warn' | 'info'; label: string; detail: string } = (() => {
    const checks = [r18, r18b, r19, r23]
    const ko = checks.find(c => c?.statut === 'NON_CONFORME')
    if (ko) return { tone: 'ko', label: 'Bloquant', detail: `${ko.regle} : ${ko.detail || ko.libelle}` }
    const warn = checks.find(c => c?.statut === 'AVERTISSEMENT')
    if (warn) return { tone: 'warn', label: 'A verifier', detail: `${warn.regle} : ${warn.detail || warn.libelle}` }
    const ok = checks.filter(c => c?.statut === 'CONFORME').length
    if (ok > 0) return { tone: 'ok', label: 'Conforme', detail: `${ok}/${checks.filter(Boolean).length} controles fiscaux conformes` }
    return { tone: 'info', label: 'Non evalue', detail: 'Lancer la verification du dossier' }
  })()

  return (
    <div className="card" style={{ padding: 0, overflow: 'hidden', borderTop: '3px solid var(--info)' }}>
      <div style={{ padding: '12px 18px', borderBottom: '1px solid var(--ink-05)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <ShieldCheck size={16} style={{ color: 'var(--info)' }} />
          <h2 style={{ marginBottom: 0 }}>Regularite fiscale du fournisseur</h2>
          {attestation?.numero != null && (
            <span className="tag" style={{ fontSize: 10, marginLeft: 4 }}>
              N&deg; {String(attestation.numero)}
            </span>
          )}
        </div>
        <StatusPill tone={aggregateStatus.tone}>{aggregateStatus.label}</StatusPill>
      </div>

      {/* Resume + alerte */}
      <div style={{ padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ fontSize: 12, color: 'var(--ink-50)' }}>{aggregateStatus.detail}</div>

        {/* Bandeau dates */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 10 }}>
          <Stat label="Editee le" value={fmtDate(dateEdition)} />
          <Stat
            label="Expire le"
            value={fmtDate(dateValidite)}
            tone={expiredToday ? 'danger' : daysToExpire != null && daysToExpire <= 30 ? 'warning' : undefined}
            extra={daysToExpire != null
              ? (expiredToday ? `expiree depuis ${-daysToExpire} j` : `dans ${daysToExpire} j`)
              : null}
          />
          {dateOp && (
            <Stat label="Date OP" value={fmtDate(dateOp)}
              tone={opAfterValidite ? 'danger' : undefined} />
          )}
        </div>

        {opAfterValidite && (
          <div className="alert alert-error" style={{ display: 'flex', gap: 8, alignItems: 'flex-start', fontSize: 12 }}>
            <AlertTriangle size={14} style={{ flexShrink: 0, marginTop: 1 }} />
            <div>
              <div style={{ fontWeight: 700 }}>OP postérieur à l'expiration de l'attestation (R18b)</div>
              <div style={{ marginTop: 2 }}>
                Paiement émis le {fmtDate(dateOp)}, attestation expirée depuis le {fmtDate(dateValidite)}.
                Le paiement n'est pas couvert par une attestation fiscale en cours de validité.
              </div>
            </div>
          </div>
        )}

        {/* Cases formulaire DGI */}
        <div>
          <div style={{ fontSize: 11, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 0.4, fontWeight: 700, marginBottom: 6 }}>
            Cases du formulaire DGI
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <CheckboxBox state={enRegleState} label={FORMULAIRE.enRegle} code="en_regle" />
            <CheckboxBox state={pasEnRegleState} label={FORMULAIRE.pasEnRegle} code="pas_en_regle" />
          </div>
        </div>

        {/* Toggle de correction (uniquement si extraction ambigue ou case "pas en regle") */}
        {(estEnRegle == null || estEnRegle === false) && (
          <div style={{
            padding: '10px 12px', borderRadius: 6, background: 'var(--ink-02)',
            border: '1px dashed var(--ink-20)',
          }}>
            <div style={{ fontSize: 12, fontWeight: 700, marginBottom: 4 }}>
              {estEnRegle == null ? 'Case ambigue : confirmez le statut' : 'Corriger le statut extrait'}
            </div>
            <div style={{ fontSize: 11, color: 'var(--ink-50)', marginBottom: 8 }}>
              {estEnRegle == null
                ? "L'IA n'a pas pu identifier la case cochee. Vérifiez sur le PDF puis confirmez ci-dessous (relance R18, R18b, R23 en cascade)."
                : "Si l'IA a mal lu la case, corrigez ici. Cette correction est tracée dans l'historique du dossier."}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <button
                type="button"
                className="btn btn-sm"
                disabled={saving != null}
                onClick={() => handleCorrect(true)}
                style={{
                  background: estEnRegle === true ? 'var(--success)' : 'transparent',
                  color: estEnRegle === true ? '#fff' : 'var(--success)',
                  border: '1px solid var(--success)',
                }}
                aria-label="Marquer comme en regle"
              >
                {saving === 'true' ? <Loader2 size={12} className="spin" /> : <Check size={12} />}
                En regle
              </button>
              <button
                type="button"
                className="btn btn-sm"
                disabled={saving != null}
                onClick={() => handleCorrect(false)}
                style={{
                  background: estEnRegle === false ? 'var(--danger)' : 'transparent',
                  color: estEnRegle === false ? '#fff' : 'var(--danger)',
                  border: '1px solid var(--danger)',
                }}
                aria-label="Marquer comme pas en regle"
              >
                {saving === 'false' ? <Loader2 size={12} className="spin" /> : <X size={12} />}
                Pas en regle
              </button>
              {estEnRegle != null && (
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  disabled={saving != null}
                  onClick={() => handleCorrect(null)}
                  aria-label="Remettre a indetermine"
                >
                  {saving === 'null' ? <Loader2 size={12} className="spin" /> : null}
                  Indetermine
                </button>
              )}
            </div>
          </div>
        )}

        {/* Recap des controles fiscaux */}
        <div>
          <div style={{ fontSize: 11, color: 'var(--ink-40)', textTransform: 'uppercase', letterSpacing: 0.4, fontWeight: 700, marginBottom: 6 }}>
            Controles lies (4)
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <RuleLine code="R18" label="Validite (6 mois)" result={r18} />
            <RuleLine code="R18b" label="OP couvert par l'attestation" result={r18b} />
            <RuleLine code="R19" label="QR code DGI" result={r19} />
            <RuleLine code="R23" label="Regularite (case cochee)" result={r23} />
          </div>
        </div>
      </div>
    </div>
  )
})

function Stat({
  label, value, tone, extra,
}: { label: string; value: string; tone?: 'danger' | 'warning'; extra?: string | null }) {
  const color = tone === 'danger' ? 'var(--danger)' : tone === 'warning' ? 'var(--warning)' : undefined
  return (
    <div style={{
      padding: '8px 10px', borderRadius: 6, background: 'var(--ink-02)',
      border: '1px solid var(--ink-05)',
    }}>
      <div style={{
        fontSize: 10, color: 'var(--ink-40)', textTransform: 'uppercase',
        letterSpacing: 0.4, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 4, marginBottom: 4,
      }}>
        <Calendar size={11} /> {label}
      </div>
      <div style={{ fontSize: 13, fontWeight: 600, color }}>
        {value}
        {extra && <span style={{ marginLeft: 6, fontSize: 10, fontWeight: 600 }}>({extra})</span>}
      </div>
    </div>
  )
}

function RuleLine({ code, label, result }: { code: string; label: string; result?: ValidationResult }) {
  const tone: 'ok' | 'ko' | 'warn' | 'na' =
    result?.statut === 'CONFORME' ? 'ok'
    : result?.statut === 'NON_CONFORME' ? 'ko'
    : result?.statut === 'AVERTISSEMENT' ? 'warn'
    : 'na'
  const icon = tone === 'ok' ? '✓' : tone === 'ko' ? '✗' : tone === 'warn' ? '⚠' : '—'
  const color = tone === 'ok' ? 'var(--success)' : tone === 'ko' ? 'var(--danger)' : tone === 'warn' ? 'var(--warning)' : 'var(--ink-30)'
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
      <span style={{
        width: 18, height: 18, borderRadius: 4, display: 'inline-flex',
        alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 12,
        color, border: `1px solid ${color}`, flexShrink: 0,
      }}>{icon}</span>
      <span style={{ fontWeight: 600, color: 'var(--ink-70)', minWidth: 42 }}>{code}</span>
      <span style={{ color: 'var(--ink-50)', flex: 1 }}>{label}</span>
      {result?.detail && (
        <span style={{ fontSize: 10, color: 'var(--ink-40)', maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          title={result.detail}>
          {result.detail}
        </span>
      )}
    </div>
  )
}
