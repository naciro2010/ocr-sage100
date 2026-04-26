package com.madaef.recondoc.service.dossier

import com.madaef.recondoc.entity.dossier.AuditLog
import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.StatutDossier
import com.madaef.recondoc.entity.dossier.StatutExtraction
import com.madaef.recondoc.entity.dossier.TypeDocument
import com.madaef.recondoc.repository.dossier.AuditLogRepository
import com.madaef.recondoc.repository.dossier.DocumentRepository
import com.madaef.recondoc.repository.dossier.DossierRepository
import com.madaef.recondoc.service.ControlPoint
import com.madaef.recondoc.service.DocumentProgress
import com.madaef.recondoc.service.DocumentProgressService
import com.madaef.recondoc.service.FinalizeRequest
import com.madaef.recondoc.service.PdfGeneratorService
import com.madaef.recondoc.service.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * Orchestration de la finalisation et de l'export d'un dossier de paiement.
 *
 * Responsabilites :
 *  - Genere le Tableau de Controle (PDF) et l'Ordre de Paiement (PDF) a partir
 *    des donnees du dossier + saisie operateur ([FinalizeRequest]).
 *  - Persiste les documents generes dans le storage et en base.
 *  - Transitionne le dossier vers [StatutDossier.VALIDE] avec tracabilite.
 *  - Expose les endpoints d'export PDF autonome (sans finalisation).
 *
 * Extrait de [com.madaef.recondoc.service.DossierService] pour isoler le
 * cycle "finalisation" du cycle "upload / extraction / validation".
 */
@Service
class DossierExportService(
    private val dossierRepo: DossierRepository,
    private val documentRepo: DocumentRepository,
    private val auditLogRepo: AuditLogRepository,
    private val pdfGenerator: PdfGeneratorService,
    private val documentStorage: DocumentStorage,
    private val progressService: DocumentProgressService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun finalizeDossier(dossierId: UUID, request: FinalizeRequest): Map<String, Any> {
        val dossier = dossierRepo.findByIdWithAll(dossierId)
            .orElseThrow { NoSuchElementException("Dossier not found: $dossierId") }
        log.info("Finalizing dossier {} with {} points", dossier.reference, request.points.size)

        runCatching {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "tc", statut = "active", detail = "Generation du Tableau de Controle..."
            ))
        }

        val tcPdf = pdfGenerator.generateTC(dossier, request)

        runCatching {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "op", statut = "active", detail = "Generation de l'Ordre de Paiement..."
            ))
        }

        val opPdf = pdfGenerator.generateOP(dossier, request)

        val tcDoc = upsertGeneratedDocument(
            dossier = dossier,
            type = TypeDocument.TABLEAU_CONTROLE,
            fileName = "TC_${dossier.reference}.pdf",
            bytes = tcPdf
        )

        val opDoc = upsertGeneratedDocument(
            dossier = dossier,
            type = TypeDocument.ORDRE_PAIEMENT,
            fileName = "OP_${dossier.reference}.pdf",
            bytes = opPdf
        )

        dossier.statut = StatutDossier.VALIDE
        dossier.dateValidation = LocalDateTime.now()
        dossier.validePar = request.signataire

        auditLogRepo.save(AuditLog(
            dossierId = dossierId, action = "FINALISATION",
            detail = "TC + OP generes, signe par ${request.signataire}"
        ))

        runCatching {
            progressService.emit(dossierId, DocumentProgress(
                documentId = "finalize", nomFichier = "Finalisation",
                step = "done", statut = "done", detail = "Finalisation terminee"
            ))
        }

        return mapOf(
            "tcDocId" to tcDoc.id.toString(),
            "opDocId" to opDoc.id.toString(),
            "reference" to dossier.reference
        )
    }


    private fun upsertGeneratedDocument(
        dossier: DossierPaiement,
        type: TypeDocument,
        fileName: String,
        bytes: ByteArray
    ): Document {
        val dossierId = requireNotNull(dossier.id) { "Dossier id is required for generated exports" }
        val existing = documentRepo.findByDossierIdAndTypeDocument(dossierId, type)
        // Capture l'ancien pointeur AVANT toute mutation : `apply` ci-dessous
        // reecrit `existing.cheminFichier` et perdrait l'info pour le cleanup.
        val oldPointer = existing?.cheminFichier
        val newPointer = documentStorage.store(dossierId, fileName, bytes)

        return try {
            val updated = (existing ?: Document(
                dossier = dossier,
                typeDocument = type,
                nomFichier = fileName,
                cheminFichier = newPointer
            )).apply {
                nomFichier = fileName
                cheminFichier = newPointer
                statutExtraction = StatutExtraction.EXTRAIT
            }
            documentRepo.save(updated).also {
                if (!oldPointer.isNullOrBlank() && oldPointer != newPointer) {
                    documentStorage.delete(oldPointer)
                }
            }
        } catch (ex: Exception) {
            documentStorage.delete(newPointer)
            throw ex
        }
    }

    @Transactional(readOnly = true)
    fun exportTC(dossierId: UUID): ByteArray {
        val dossier = dossierRepo.findByIdWithAll(dossierId)
            .orElseThrow { NoSuchElementException("Dossier not found: $dossierId") }
        val checklist = dossier.checklistAutocontrole
        val points = if (checklist != null && checklist.points.isNotEmpty()) {
            checklist.points.map { pt ->
                ControlPoint(
                    description = pt.description ?: "Point ${pt.numero}",
                    observation = when (pt.estValide) {
                        true -> "Conforme"
                        false -> "Non conforme"
                        null -> "NA"
                    },
                    commentaire = pt.observation
                )
            }
        } else {
            DEFAULT_TC_POINTS.map { ControlPoint(it, "NA", null) }
        }
        return pdfGenerator.generateTC(dossier, FinalizeRequest(
            points = points,
            signataire = dossier.validePar ?: "Non signe"
        ))
    }

    @Transactional(readOnly = true)
    fun exportOP(dossierId: UUID): ByteArray {
        val dossier = dossierRepo.findByIdWithAll(dossierId)
            .orElseThrow { NoSuchElementException("Dossier not found: $dossierId") }
        val defaultRequest = FinalizeRequest(
            points = emptyList(),
            signataire = dossier.validePar ?: "Non signe"
        )
        return pdfGenerator.generateOP(dossier, defaultRequest)
    }

    companion object {
        /** 10 points standard du Tableau de Controle (CCF-EN-04) lorsque la checklist est absente. */
        private val DEFAULT_TC_POINTS = listOf(
            "Concordance facture / modalites contractuelles / livrables",
            "Verification arithmetique des montants",
            "Respect du delai d'execution des prestations",
            "Modifications / avenants (plafonds et variations)",
            "Application des retenues et penalites",
            "Signatures et visas des personnes habilitees",
            "Conformite reglementaire (ICE, IF, RC, CNSS)",
            "Conformite du RIB contractuel vs facture",
            "Conformite BL / PV de reception",
            "Habilitations des signataires des receptions"
        )
    }
}
