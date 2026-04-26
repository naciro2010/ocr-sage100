import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { getDashboardStats, getDossierSummary, invalidateUrl } from './dossierApi'

// La logique testee ici est critique : SWR + dedup d'inflight cote client.
// Si elle se casse (ex: cache jamais hit), le frontend bombarde le backend
// a chaque navigation et peut afficher des donnees stale silencieusement.

describe('dossierApi cache + SWR', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // Vide le cache module en invalidant les URLs connues. (Pas d'API publique
    // pour reset complet — on triche en re-important ou en invalidant chacune.)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('met en cache et dedoublonne les requetes en parallele', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ total: 1, brouillons: 0, enVerification: 0, valides: 1, rejetes: 0, montantTotal: 0 }), { status: 200 })
    )

    const [a, b, c] = await Promise.all([getDashboardStats(), getDashboardStats(), getDashboardStats()])
    // 3 appels concurrents -> 1 seul roundtrip (dedup d'inflight).
    expect(fetchSpy).toHaveBeenCalledTimes(1)
    expect(a.total).toBe(1)
    expect(b.total).toBe(1)
    expect(c.total).toBe(1)

    // Appel suivant immediat -> cache hit, pas de nouveau roundtrip.
    await getDashboardStats()
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('invalidateUrl force un re-fetch', async () => {
    const url = '/api/dossiers/abc/summary'
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(new Response(JSON.stringify({
        id: 'abc', reference: 'D', type: 'BC', statut: 'BROUILLON',
        fournisseur: null, description: null,
        montantTtc: null, montantHt: null, montantTva: null, montantNetAPayer: null,
        dateCreation: '2026-01-01', dateValidation: null, validePar: null, motifRejet: null,
        nbDocuments: 0, nbExtractEnAttente: 0, nbExtractEnCours: 0, nbExtractTermine: 0,
        nbExtractErreur: 0, nbResultatsValidation: 0, nbConformes: 0, nbNonConformes: 0
      }), { status: 200 }))
    )

    await getDossierSummary('abc')
    await getDossierSummary('abc') // hit cache
    expect(fetchSpy).toHaveBeenCalledTimes(1)

    invalidateUrl(url)
    await getDossierSummary('abc')
    expect(fetchSpy).toHaveBeenCalledTimes(2)
  })
})
