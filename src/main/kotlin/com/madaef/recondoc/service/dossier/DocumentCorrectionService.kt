package com.madaef.recondoc.service.dossier

import com.madaef.recondoc.entity.dossier.Document
import com.madaef.recondoc.entity.dossier.DocumentCorrection
import com.madaef.recondoc.repository.dossier.DocumentCorrectionRepository
import com.madaef.recondoc.repository.dossier.DocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * CRUD des corrections humaines de champs extraits.
 * Chaque correction est unique par (document, champ). Les ecritures repetees
 * sur le meme champ se font en upsert : on conserve la valeur originale telle
 * qu'elle etait au moment de la PREMIERE correction (audit), et on met a jour
 * la valeur corrigee, l'auteur, la date, le motif.
 */
@Service
class DocumentCorrectionService(
    private val correctionRepository: DocumentCorrectionRepository,
    private val documentRepository: DocumentRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class CorrectionInput(
        val documentId: UUID,
        val champ: String,
        val valeurCorrigee: String?,
        val motif: String? = null,
        val regle: String? = null,
        val corrigePar: String? = null
    )

    @Transactional
    fun upsert(input: CorrectionInput): DocumentCorrection {
        val doc = documentRepository.findById(input.documentId)
            .orElseThrow { NoSuchElementException("Document ${input.documentId} introuvable") }
        val champ = normalizeChamp(input.champ)
        val existing = correctionRepository.findByDocumentIdAndChamp(input.documentId, champ)
        val nowValue = input.valeurCorrigee?.takeIf { it.isNotBlank() }
        val original = readOriginal(doc, champ)

        val saved = if (existing != null) {
            existing.valeurCorrigee = nowValue
            existing.motif = input.motif ?: existing.motif
            existing.regle = input.regle ?: existing.regle
            existing.corrigePar = input.corrigePar ?: existing.corrigePar
            existing.dateCorrection = LocalDateTime.now()
            existing
        } else {
            correctionRepository.save(
                DocumentCorrection(
                    document = doc,
                    champ = champ,
                    valeurOriginale = original,
                    valeurCorrigee = nowValue,
                    regle = input.regle,
                    motif = input.motif,
                    corrigePar = input.corrigePar
                )
            )
        }
        log.info("Document correction upserted: doc={} champ={} value='{}' rule={} by={}",
            input.documentId, champ, nowValue, input.regle, input.corrigePar)
        return saved
    }

    @Transactional
    fun upsertAll(inputs: List<CorrectionInput>): List<DocumentCorrection> = inputs.map { upsert(it) }

    @Transactional(readOnly = true)
    fun listForDocument(documentId: UUID): List<DocumentCorrection> =
        correctionRepository.findByDocumentId(documentId)

    @Transactional
    fun delete(documentId: UUID, champ: String) {
        correctionRepository.deleteByDocumentIdAndChamp(documentId, normalizeChamp(champ))
    }

    /**
     * Le champ peut arriver casse-mixte (montantTTC / montantTtc). On garde la
     * casse Kotlin canonique pour faciliter le mapping cote applier.
     */
    private fun normalizeChamp(champ: String): String = champ.trim()

    private fun readOriginal(doc: Document, champ: String): String? {
        val data = doc.donneesExtraites ?: return null
        // Tolere la collision de casse (montantTTC vs montantTtc).
        val direct = data[champ]
        if (direct != null) return direct.toString()
        return data.entries.firstOrNull { it.key.equals(champ, ignoreCase = true) }?.value?.toString()
    }
}
