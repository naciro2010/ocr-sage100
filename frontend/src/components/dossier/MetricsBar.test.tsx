import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import MetricsBar from './MetricsBar'
import type { DossierDetail, ValidationResult, StatutCheck } from '../../api/dossierTypes'

function res(statut: StatutCheck, regle = 'R01'): ValidationResult {
  return {
    regle, libelle: regle, statut, detail: '', valeurAttendue: '', valeurTrouvee: '',
    documentIds: '', evidences: [], dateExecution: new Date().toISOString(),
    statutOriginal: null, commentaire: null, corrigePar: null, dateCorrection: null,
    durationMs: 1,
  } as unknown as ValidationResult
}

function dossier(results: ValidationResult[]): DossierDetail {
  return {
    id: 'd1', reference: 'D-1', type: 'BC', statut: 'BROUILLON',
    fournisseur: 'X', description: '', montantTtc: 1000, montantHt: 800, montantTva: 200,
    montantNetAPayer: null, dateCreation: '2026-04-01',
    dateValidation: null, validePar: null, motifRejet: null,
    documents: [],
    facture: null, factures: [], bonCommande: null, contratAvenant: null,
    ordrePaiement: null, checklistAutocontrole: null, tableauControle: null,
    pvReception: null, attestationFiscale: null,
    resultatsValidation: results,
  } as unknown as DossierDetail
}

const fmt = (n: number | null | undefined) => (n != null ? String(n) : '-')

describe('MetricsBar — couleur shield (regle CLAUDE.md)', () => {
  // jsdom n'evalue pas `var(--danger)` comme une couleur valide CSSOM, donc on
  // verifie l'attribut style brut + le contenu texte plutot que `style.color`.
  it('rouge + libelle "bloquant" si au moins un NON_CONFORME', () => {
    const d = dossier([res('CONFORME'), res('CONFORME', 'R02'), res('NON_CONFORME', 'R03')])
    const { container } = render(<MetricsBar dossier={d} nbConformes={2} fmt={fmt} />)
    // querySelectorAll : il y a 2 spans avec cette classe, le 2nd porte le statut.
    const labels = container.querySelectorAll<HTMLElement>('.metrics-checks-label')
    expect(labels.length).toBe(2)
    const statusLabel = labels[1]
    expect(statusLabel.getAttribute('style') || '').toContain('var(--danger)')
    expect(statusLabel.textContent).toMatch(/bloquant/i)
    expect(statusLabel.textContent).toContain('2/3 OK')
  })

  it('ambre si seulement AVERTISSEMENT (pas de NON_CONFORME)', () => {
    const d = dossier([res('CONFORME'), res('AVERTISSEMENT', 'R02')])
    const { container } = render(<MetricsBar dossier={d} nbConformes={1} fmt={fmt} />)
    const labels = container.querySelectorAll<HTMLElement>('.metrics-checks-label')
    const statusLabel = labels[1]
    expect(statusLabel.getAttribute('style') || '').toContain('var(--warning)')
    expect(statusLabel.textContent).not.toMatch(/bloquant/i)
  })

  it('vert si tous CONFORME', () => {
    const d = dossier([res('CONFORME'), res('CONFORME', 'R02'), res('CONFORME', 'R03')])
    const { container } = render(<MetricsBar dossier={d} nbConformes={3} fmt={fmt} />)
    const labels = container.querySelectorAll<HTMLElement>('.metrics-checks-label')
    const statusLabel = labels[1]
    expect(statusLabel.getAttribute('style') || '').toContain('var(--success)')
    expect(statusLabel.textContent).toContain('3/3 OK')
  })
})
