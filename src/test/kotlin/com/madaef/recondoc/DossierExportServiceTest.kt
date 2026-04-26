package com.madaef.recondoc

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.DossierType
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.StatutExtraction
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.dossier.AuditLogRepository
import com.madaef.recondoc.repository.dossier.DocumentRepository
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.service.DocumentProgressService
import com.madaef.recondoc.service.FinalizeRequest
import com.madaef.recondoc.service.PdfGeneratorService
import com.madaef.recondoc.service.dossier.DossierExportService
import com.madaef.recondoc.service.storage.DocumentStorage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

// Helpers Kotlin-friendly pour Mockito : `any(Class<T>)` renvoie null cote JVM
// et casse les parametres non-null Kotlin. On enregistre le matcher (effet de
// bord) puis on retourne un placeholder non-null que Mockito ignore.
private fun anyByteArray(): ByteArray {
    ArgumentMatchers.any(ByteArray::class.java)
    return ByteArray(0)
}

private fun anyDocument(): Document {
    ArgumentMatchers.any(Document::class.java)
    return Document(
        dossier = DossierPaiement(reference = "x", type = DossierType.BC),
        typeDocument = TypeDocument.TABLEAU_CONTROLE,
        nomFichier = "x", cheminFichier = "x"
    )
}

private fun anyFinalizeRequest(): FinalizeRequest {
    ArgumentMatchers.any(FinalizeRequest::class.java)
    return FinalizeRequest(points = emptyList(), signataire = "x")
}

private fun eqUuid(value: UUID): UUID {
    ArgumentMatchers.eq(value)
    return value
}

private fun eqStr(value: String): String {
    ArgumentMatchers.eq(value)
    return value
}

private fun eqDossier(value: DossierPaiement): DossierPaiement {
    ArgumentMatchers.eq(value)
    return value
}

private fun eqType(value: TypeDocument): TypeDocument {
    ArgumentMatchers.eq(value)
    return value
}

class DossierExportServiceTest {

    private fun buildDossier(id: UUID): DossierPaiement = DossierPaiement(
        id = id,
        reference = "DOS-TEST-001",
        type = DossierType.BC,
        statut = StatutDossier.EN_VERIFICATION,
        fournisseur = "Fournisseur Test"
    )

    @Test
    fun `finalize remplace les pointeurs TC OP existants et nettoie les anciens objets`() {
        val dossierId = UUID.randomUUID()
        val dossier = buildDossier(dossierId)

        val dossierRepo = mock(DossierRepository::class.java)
        val documentRepo = mock(DocumentRepository::class.java)
        val auditRepo = mock(AuditLogRepository::class.java)
        val pdfGenerator = mock(PdfGeneratorService::class.java)
        val storage = mock(DocumentStorage::class.java)
        val progress = mock(DocumentProgressService::class.java)

        val existingTc = Document(
            id = UUID.randomUUID(),
            dossier = dossier,
            typeDocument = TypeDocument.TABLEAU_CONTROLE,
            nomFichier = "old-tc.pdf",
            cheminFichier = "storage/old-tc.pdf",
            statutExtraction = StatutExtraction.EXTRAIT
        )
        val existingOp = Document(
            id = UUID.randomUUID(),
            dossier = dossier,
            typeDocument = TypeDocument.ORDRE_PAIEMENT,
            nomFichier = "old-op.pdf",
            cheminFichier = "storage/old-op.pdf",
            statutExtraction = StatutExtraction.EXTRAIT
        )

        `when`(dossierRepo.findByIdWithAll(dossierId)).thenReturn(Optional.of(dossier))
        `when`(pdfGenerator.generateTC(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(dossierId, TypeDocument.TABLEAU_CONTROLE))
            .thenReturn(existingTc)
        `when`(documentRepo.findByDossierIdAndTypeDocument(dossierId, TypeDocument.ORDRE_PAIEMENT))
            .thenReturn(existingOp)
        `when`(storage.store(eqUuid(dossierId), eqStr("TC_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-tc.pdf")
        `when`(storage.store(eqUuid(dossierId), eqStr("OP_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-op.pdf")
        `when`(documentRepo.save(anyDocument())).thenAnswer { it.arguments[0] as Document }

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)
        val out = service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))

        assertEquals("storage/new-tc.pdf", existingTc.cheminFichier)
        assertEquals("storage/new-op.pdf", existingOp.cheminFichier)
        assertEquals("TC_${dossier.reference}.pdf", existingTc.nomFichier)
        assertEquals("OP_${dossier.reference}.pdf", existingOp.nomFichier)
        assertEquals(StatutExtraction.EXTRAIT, existingTc.statutExtraction)
        assertEquals(StatutExtraction.EXTRAIT, existingOp.statutExtraction)
        assertEquals(existingTc.id?.toString(), out["tcDocId"])
        assertEquals(existingOp.id?.toString(), out["opDocId"])
        assertEquals(StatutDossier.VALIDE, dossier.statut)
        assertEquals("Controleur", dossier.validePar)
        verify(storage).delete("storage/old-tc.pdf")
        verify(storage).delete("storage/old-op.pdf")
    }

    @Test
    fun `finalize cree les documents quand aucun TC OP n existe`() {
        val dossierId = UUID.randomUUID()
        val dossier = buildDossier(dossierId)

        val dossierRepo = mock(DossierRepository::class.java)
        val documentRepo = mock(DocumentRepository::class.java)
        val auditRepo = mock(AuditLogRepository::class.java)
        val pdfGenerator = mock(PdfGeneratorService::class.java)
        val storage = mock(DocumentStorage::class.java)
        val progress = mock(DocumentProgressService::class.java)

        `when`(dossierRepo.findByIdWithAll(dossierId)).thenReturn(Optional.of(dossier))
        `when`(pdfGenerator.generateTC(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(eqUuid(dossierId), eqType(TypeDocument.TABLEAU_CONTROLE)))
            .thenReturn(null)
        `when`(documentRepo.findByDossierIdAndTypeDocument(eqUuid(dossierId), eqType(TypeDocument.ORDRE_PAIEMENT)))
            .thenReturn(null)
        `when`(storage.store(eqUuid(dossierId), eqStr("TC_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-tc.pdf")
        `when`(storage.store(eqUuid(dossierId), eqStr("OP_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-op.pdf")
        `when`(documentRepo.save(anyDocument())).thenAnswer { it.arguments[0] as Document }

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)
        service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))

        verify(storage, never()).delete(ArgumentMatchers.anyString())
    }

    @Test
    fun `finalize supprime le nouveau PDF si la sauvegarde en base echoue`() {
        val dossierId = UUID.randomUUID()
        val dossier = buildDossier(dossierId)

        val dossierRepo = mock(DossierRepository::class.java)
        val documentRepo = mock(DocumentRepository::class.java)
        val auditRepo = mock(AuditLogRepository::class.java)
        val pdfGenerator = mock(PdfGeneratorService::class.java)
        val storage = mock(DocumentStorage::class.java)
        val progress = mock(DocumentProgressService::class.java)

        `when`(dossierRepo.findByIdWithAll(dossierId)).thenReturn(Optional.of(dossier))
        `when`(pdfGenerator.generateTC(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(eqUuid(dossierId), eqType(TypeDocument.TABLEAU_CONTROLE)))
            .thenReturn(null)
        `when`(storage.store(eqUuid(dossierId), eqStr("TC_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-tc.pdf")
        `when`(documentRepo.save(anyDocument())).thenThrow(RuntimeException("db down"))

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)

        assertThrows<RuntimeException> {
            service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))
        }

        verify(storage).delete("storage/new-tc.pdf")
        verify(storage, never()).delete("storage/old-tc.pdf")
    }

    @Test
    fun `finalize ne supprime pas le pointeur quand le nouveau pointeur est identique a l ancien`() {
        val dossierId = UUID.randomUUID()
        val dossier = buildDossier(dossierId)
        val samePointer = "storage/same-tc.pdf"

        val dossierRepo = mock(DossierRepository::class.java)
        val documentRepo = mock(DocumentRepository::class.java)
        val auditRepo = mock(AuditLogRepository::class.java)
        val pdfGenerator = mock(PdfGeneratorService::class.java)
        val storage = mock(DocumentStorage::class.java)
        val progress = mock(DocumentProgressService::class.java)

        val existingTc = Document(
            id = UUID.randomUUID(),
            dossier = dossier,
            typeDocument = TypeDocument.TABLEAU_CONTROLE,
            nomFichier = "TC_${dossier.reference}.pdf",
            cheminFichier = samePointer,
            statutExtraction = StatutExtraction.EXTRAIT
        )

        `when`(dossierRepo.findByIdWithAll(dossierId)).thenReturn(Optional.of(dossier))
        `when`(pdfGenerator.generateTC(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eqDossier(dossier), anyFinalizeRequest()))
            .thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(eqUuid(dossierId), eqType(TypeDocument.TABLEAU_CONTROLE)))
            .thenReturn(existingTc)
        `when`(documentRepo.findByDossierIdAndTypeDocument(eqUuid(dossierId), eqType(TypeDocument.ORDRE_PAIEMENT)))
            .thenReturn(null)
        `when`(storage.store(eqUuid(dossierId), eqStr("TC_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn(samePointer)
        `when`(storage.store(eqUuid(dossierId), eqStr("OP_${dossier.reference}.pdf"), anyByteArray()))
            .thenReturn("storage/new-op.pdf")
        `when`(documentRepo.save(anyDocument())).thenAnswer { it.arguments[0] as Document }

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)
        service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))

        verify(storage, never()).delete(samePointer)
    }
}
