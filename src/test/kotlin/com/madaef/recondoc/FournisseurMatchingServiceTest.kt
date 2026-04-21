package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.dossier.FournisseurAliasRepository
import com.madaef.recondoc.repository.dossier.FournisseurCanoniqueRepository
import com.madaef.recondoc.service.fournisseur.FournisseurMatchingService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FournisseurMatchingServiceTest {

    @Autowired lateinit var service: FournisseurMatchingService
    @Autowired lateinit var canoniqueRepo: FournisseurCanoniqueRepository
    @Autowired lateinit var aliasRepo: FournisseurAliasRepository

    @BeforeEach
    fun cleanup() {
        aliasRepo.deleteAll()
        canoniqueRepo.deleteAll()
    }

    @Test
    fun `normalize supprime accents casse et suffixes legaux`() {
        assertEquals("force emploi", service.normalize("Force Emploi S.A.R.L."))
        assertEquals("societe nouvelle papeterie cardone",
            service.normalize("SOCIETE NOUVELLE PAPETERIE CARDONE S.A.R.L."))
        assertEquals("maymana", service.normalize("MAYMANA"))
        assertEquals("maymana patisserie", service.normalize("Maymana Patisserie"))
    }

    @Test
    fun `similarity haute sur variantes proches du meme fournisseur`() {
        val a = service.normalize("Maymana Patisse")
        val b = service.normalize("Maymana Patisserie")
        val score = service.similarity(a, b)
        assertTrue(score >= 0.85, "score attendu >=0.85, obtenu $score")
    }

    @Test
    fun `similarity detecte containment Maroc Force Emploi vs Force Emploi`() {
        val a = service.normalize("MAROC FORCE EMPLOI")
        val b = service.normalize("FORCE EMPLOI")
        val score = service.similarity(a, b)
        assertTrue(score >= 0.80, "containment bonus attendu >=0.80, obtenu $score")
    }

    @Test
    fun `first occurrence cree un canonique`() {
        val r = service.findOrCreateCanonical("Maymana Patisserie", TypeDocument.FACTURE)
        assertTrue(r.isNew)
        assertTrue(r.isExact)
        assertEquals(1, canoniqueRepo.count())
        assertEquals(1, aliasRepo.count())
    }

    @Test
    fun `second occurrence exacte reuse le canonique sans creer alias duplique`() {
        service.findOrCreateCanonical("Maymana Patisserie", TypeDocument.FACTURE)
        val r2 = service.findOrCreateCanonical("maymana patisserie", TypeDocument.FACTURE)
        assertFalse(r2.isNew)
        assertFalse(r2.requiresReview)
        assertEquals(1, canoniqueRepo.count())
        assertEquals(1, aliasRepo.count())
    }

    @Test
    fun `variante proche rattache au canonique avec requires_review`() {
        service.findOrCreateCanonical("Maymana Patisserie", TypeDocument.FACTURE)
        val r2 = service.findOrCreateCanonical("Maymana Patisse", TypeDocument.BON_COMMANDE)
        assertFalse(r2.isNew, "ne doit pas creer un nouveau canonique")
        assertTrue(r2.requiresReview, "similarity entre 0.82 et 0.95 doit demander revue")
        assertEquals(1, canoniqueRepo.count())
        assertEquals(2, aliasRepo.count())
    }

    @Test
    fun `facture impose le nom canonique sur bon_commande moins credible`() {
        service.findOrCreateCanonical("MAYMANA PATISSERIE", TypeDocument.BON_COMMANDE)
        val r2 = service.findOrCreateCanonical("Maymana Patisserie", TypeDocument.FACTURE)
        assertEquals("Maymana Patisserie", r2.canonique.nomCanonique,
            "FACTURE doit imposer son nom vs BON_COMMANDE")
    }

    @Test
    fun `fournisseurs sans lien commun creent des canoniques distincts`() {
        service.findOrCreateCanonical("Maymana Patisserie", TypeDocument.FACTURE)
        val r2 = service.findOrCreateCanonical("SOCIETE NOUVELLE PAPETERIE CARDONE S.A.R.L.", TypeDocument.FACTURE)
        assertTrue(r2.isNew)
        assertEquals(2, canoniqueRepo.count())
    }

    @Test
    fun `match par ICE prime sur la similarity de nom`() {
        service.findOrCreateCanonical("ABC SARL", TypeDocument.FACTURE, ice = "001509176000008")
        val r2 = service.findOrCreateCanonical("XYZ Something Else", TypeDocument.FACTURE, ice = "001509176000008")
        assertFalse(r2.isNew, "meme ICE doit rattacher au meme canonique")
        assertEquals(1, canoniqueRepo.count())
    }
}
