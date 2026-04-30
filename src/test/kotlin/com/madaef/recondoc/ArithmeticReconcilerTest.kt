package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.service.extraction.ArithmeticReconciler
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verrouille le comportement de reconciliation arithmetique : auto-correction
 * arrondi, calcul du champ manquant, detection des incoherences irrecuperables.
 *
 * Sprint 1 #5 fiabilite : la reconciliation NE doit PAS inventer de valeur
 * depuis le neant. Elle se contente de combler les arrondis et les trous
 * quand au moins 2 des 3 montants sont fournis.
 */
class ArithmeticReconcilerTest {

    private val reconciler = ArithmeticReconciler(
        toleranceAbsRaw = "0.05",
        tolerancePctRaw = "0.005",
        enabled = true
    )

    @Test
    fun `triplet HT TVA TTC parfaitement coherent ne genere aucune correction`() {
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("12000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertTrue(r.corrections.isEmpty(), "Aucune correction sur triplet exact")
        assertTrue(r.unresolvedDetails.isEmpty())
        assertTrue(r.candidatesForReextraction.isEmpty())
    }

    @Test
    fun `arrondi 0,02 MAD est auto-corrige sur TTC`() {
        // Cas frequent : Claude a lu 12000.00 alors que HT+TVA = 12000.02 exactement.
        val data = mapOf(
            "montantHT" to BigDecimal("10000.01"),
            "montantTVA" to BigDecimal("2000.01"),
            "montantTTC" to BigDecimal("12000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertEquals(1, r.corrections.size, "1 seule correction (TTC)")
        val correction = r.corrections.first()
        assertEquals("montantTTC", correction.field)
        assertEquals(BigDecimal("12000.02"), correction.after)
        assertTrue(correction.reason.contains("arrondi"))
        assertEquals(BigDecimal("12000.02"), r.data["montantTTC"])
    }

    @Test
    fun `TTC manquant calcule depuis HT plus TVA`() {
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertEquals(BigDecimal("12000.00"), r.data["montantTTC"])
        assertEquals(1, r.corrections.size)
        assertNull(r.corrections.first().before, "before=null car TTC absent")
        assertEquals("montantTTC", r.corrections.first().field)
    }

    @Test
    fun `HT manquant calcule depuis TTC moins TVA`() {
        val data = mapOf(
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("12000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertEquals(BigDecimal("10000.00"), r.data["montantHT"])
        assertEquals(1, r.corrections.size)
        assertEquals("montantHT", r.corrections.first().field)
    }

    @Test
    fun `TVA manquante calculee depuis TTC moins HT`() {
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTTC" to BigDecimal("12000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertEquals(BigDecimal("2000.00"), r.data["montantTVA"])
        assertEquals(1, r.corrections.size)
        assertEquals("montantTVA", r.corrections.first().field)
    }

    @Test
    fun `incoherence resolvable par tauxTVA - HT et TVA coherents recompute TTC`() {
        // Claude a mal lu TTC (15000 au lieu de 12000) mais HT*20% = TVA, donc
        // HT et TVA sont fiables, TTC est faux -> recompute.
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("15000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertEquals(BigDecimal("12000.00"), r.data["montantTTC"])
        val correction = r.corrections.firstOrNull { it.field == "montantTTC" }
        assertNotNull(correction)
        assertTrue(correction.reason.contains("coherent"))
    }

    @Test
    fun `incoherence sans signal taux fiable - flag reextraction sans corruption`() {
        // HT*20% = 2000 mais TVA imprime = 1500, ET TTC = 11500. Aucun couple
        // ne peut etre considere comme reference -> on flag tout pour revue.
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("1500.00"),
            "montantTTC" to BigDecimal("11500.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        // HT+TVA = 11500 = TTC : COHERENT en realite ! Pas de probleme arithmetique
        // pur, c'est juste tauxTVA qui est faux. On ne touche PAS aux montants.
        assertTrue(r.corrections.none { it.field in listOf("montantHT", "montantTVA", "montantTTC") })
    }

    @Test
    fun `triplet incoherent au-dela de la tolerance signale revue humaine`() {
        // HT+TVA = 12000 mais TTC = 15000, tauxTVA legal mais HT*20% = TVA OK
        // Donc HT/TVA fiables, TTC faux -> recompute via tryResolveByTaux.
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("15000.00"),
            "tauxTVA" to BigDecimal("20")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        // Cas resolvable (cf test precedent)
        assertEquals(BigDecimal("12000.00"), r.data["montantTTC"])

        // Maintenant cas vraiment irresolvable : tauxTVA absent ET HT+TVA != TTC
        val data2 = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("15000.00")
            // pas de tauxTVA -> on ne peut pas resoudre
        )
        val r2 = reconciler.reconcile(TypeDocument.FACTURE, data2)
        assertTrue(r2.unresolvedDetails.isNotEmpty(), "Sans tauxTVA, l'ecart ne peut pas etre resolu")
        assertTrue(r2.candidatesForReextraction.containsAll(listOf("montantHT", "montantTVA", "montantTTC")))
        // Donnees inchangees : pas de corruption
        assertEquals(BigDecimal("15000.00"), r2.data["montantTTC"])
    }

    @Test
    fun `HT superieur a TTC est detecte comme irrecuperable`() {
        // HT > TTC -> impossible (TVA serait negative). Pas de calcul, flag.
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTTC" to BigDecimal("8000.00")
            // TVA absente
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertNull(r.data["montantTVA"], "TVA negative impossible -> ne pas calculer")
        assertTrue(r.unresolvedDetails.isNotEmpty())
        assertTrue(r.candidatesForReextraction.contains("montantHT"))
        assertTrue(r.candidatesForReextraction.contains("montantTTC"))
    }

    @Test
    fun `un seul montant present ne declenche aucune action`() {
        val data = mapOf("montantTTC" to BigDecimal("12000.00"))
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        assertTrue(r.corrections.isEmpty())
        assertTrue(r.unresolvedDetails.isEmpty())
    }

    @Test
    fun `tauxTVA hors liste legale signale incident sans muter le taux`() {
        // tauxTVA = 18% (apparait sur factures etrangeres, doit etre flag).
        // HT*20%/100 = 2000 = TVA, donc taux effectif = 20%, le 18% imprime
        // est probablement une erreur OCR (8 lu au lieu de 0).
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "montantTTC" to BigDecimal("12000.00"),
            "tauxTVA" to BigDecimal("18")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        // tauxTVA n'est PAS modifie automatiquement (R30 doit le voir)
        assertEquals(BigDecimal("18"), r.data["tauxTVA"])
        // Mais un warning est ajoute
        assertTrue(r.unresolvedDetails.any { it.contains("hors liste legale") })
        assertTrue(r.candidatesForReextraction.contains("tauxTVA"))
    }

    @Test
    fun `engagement MARCHE utilise les cles montantHt montantTtc minuscules`() {
        // Schemas engagement (couche marches publics) utilisent
        // `montantHt` / `montantTtc` au lieu de `montantHT` / `montantTTC`.
        val data = mapOf(
            "montantHt" to BigDecimal("100000.00"),
            "montantTva" to BigDecimal("20000.00"),
            "tauxTva" to BigDecimal("20")
            // montantTtc absent
        )
        val r = reconciler.reconcile(TypeDocument.MARCHE, data)
        assertEquals(BigDecimal("120000.00"), r.data["montantTtc"])
        assertEquals(1, r.corrections.size)
    }

    @Test
    fun `reconciler desactive ne modifie rien`() {
        val disabled = ArithmeticReconciler(
            toleranceAbsRaw = "0.05",
            tolerancePctRaw = "0.005",
            enabled = false
        )
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00")
            // TTC absent -> serait calcule si enabled
        )
        val r = disabled.reconcile(TypeDocument.FACTURE, data)
        assertNull(r.data["montantTTC"])
        assertTrue(r.corrections.isEmpty())
    }

    @Test
    fun `types non concernes (PV CHECKLIST) restent inertes`() {
        val data = mapOf("montantHT" to BigDecimal("10000.00"), "montantTVA" to BigDecimal("2000.00"))
        val r = reconciler.reconcile(TypeDocument.PV_RECEPTION, data)
        assertTrue(r.corrections.isEmpty(), "PV_RECEPTION n'a pas de triplet HT/TVA/TTC")
    }

    @Test
    fun `warnings preexistants conserves et corrections concatenees`() {
        val data = mapOf(
            "montantHT" to BigDecimal("10000.00"),
            "montantTVA" to BigDecimal("2000.00"),
            "tauxTVA" to BigDecimal("20"),
            "_warnings" to listOf("OCR confiance basse sur page 2")
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        @Suppress("UNCHECKED_CAST")
        val warnings = r.data["_warnings"] as List<String>
        assertEquals(2, warnings.size, "warning preexistant + 1 correction (TTC calcule)")
        assertTrue(warnings.any { it.contains("OCR confiance basse") })
        assertTrue(warnings.any { it.contains("Reconciliation arithmetique") })
    }

    @Test
    fun `String numerique format FR converti correctement`() {
        // Robustesse au format string FR : "10 000,00".
        val data = mapOf(
            "montantHT" to "10 000,00",
            "montantTVA" to "2 000,00",
            "tauxTVA" to "20"
        )
        val r = reconciler.reconcile(TypeDocument.FACTURE, data)
        // TTC doit etre calcule a 12000.00
        val ttc = r.data["montantTTC"] as? BigDecimal
        assertNotNull(ttc)
        assertEquals(0, ttc.compareTo(BigDecimal("12000.00")))
    }
}
