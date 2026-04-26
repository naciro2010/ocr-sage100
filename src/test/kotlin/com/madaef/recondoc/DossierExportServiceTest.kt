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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

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
        `when`(pdfGenerator.generateTC(eq(dossier), any(FinalizeRequest::class.java))).thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eq(dossier), any(FinalizeRequest::class.java))).thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(dossierId, TypeDocument.TABLEAU_CONTROLE)).thenReturn(existingTc)
        `when`(documentRepo.findByDossierIdAndTypeDocument(dossierId, TypeDocument.ORDRE_PAIEMENT)).thenReturn(existingOp)
        `when`(storage.store(dossierId, "TC_${dossier.reference}.pdf", "tc".toByteArray())).thenReturn("storage/new-tc.pdf")
        `when`(storage.store(dossierId, "OP_${dossier.reference}.pdf", "op".toByteArray())).thenReturn("storage/new-op.pdf")
        `when`(documentRepo.save(existingTc)).thenReturn(existingTc)
        `when`(documentRepo.save(existingOp)).thenReturn(existingOp)

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)
        val out = service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))

        assertEquals("storage/new-tc.pdf", existingTc.cheminFichier)
        assertEquals("storage/new-op.pdf", existingOp.cheminFichier)
        assertEquals(existingTc.id?.toString(), out["tcDocId"])
        assertEquals(existingOp.id?.toString(), out["opDocId"])
        verify(storage).delete("storage/old-tc.pdf")
        verify(storage).delete("storage/old-op.pdf")
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
        `when`(pdfGenerator.generateTC(eq(dossier), any(FinalizeRequest::class.java))).thenReturn("tc".toByteArray())
        `when`(pdfGenerator.generateOP(eq(dossier), any(FinalizeRequest::class.java))).thenReturn("op".toByteArray())
        `when`(documentRepo.findByDossierIdAndTypeDocument(dossierId, TypeDocument.TABLEAU_CONTROLE)).thenReturn(null)
        `when`(storage.store(dossierId, "TC_${dossier.reference}.pdf", "tc".toByteArray())).thenReturn("storage/new-tc.pdf")
        `when`(documentRepo.save(any(Document::class.java))).thenThrow(RuntimeException("db down"))

        val service = DossierExportService(dossierRepo, documentRepo, auditRepo, pdfGenerator, storage, progress)

        runCatching {
            service.finalizeDossier(dossierId, FinalizeRequest(points = emptyList(), signataire = "Controleur"))
        }

        verify(storage).delete("storage/new-tc.pdf")
        verify(storage, never()).delete("storage/old-tc.pdf")
    }
}
