package com.madaef.recondoc.service.extraction

import com.madaef.recondoc.entity.dossier.TypeDocument
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Drift monitoring continu par champ (Sprint 3 #10 fiabilite). Expose des
 * metriques Prometheus permettant de detecter une derive silencieuse :
 *
 *   - Anthropic met a jour Sonnet/Haiku sans preavis -> taux de NULL sur
 *     un champ critique passe de 5% a 30% en 24h. La metrique alerte
 *     avant que le metier ne le voit.
 *   - Un nouveau format fournisseur arrive et casse l'extraction d'un
 *     champ specifique -> on voit la decrochage par fournisseur.
 *   - Une regle anti-hallucination devient trop stricte et strip trop
 *     de valeurs valides -> ratio stripped/extracted explose.
 *
 * Compteurs emits :
 *   recondoc.extraction.field.outcome{type,field,outcome}
 *     outcome ∈ {extracted, null, stripped_grounding, stripped_consistency,
 *                stripped_cove, reconciled, reextracted_field}
 *   recondoc.extraction.confidence{type} (DistributionSummary)
 *   recondoc.extraction.quality_score{type} (DistributionSummary)
 *
 * Pas de PII : on ne logge JAMAIS les valeurs extraites, seulement les
 * meta-informations (champ, type, outcome).
 *
 * Throughput : 1 dossier (5 docs * 7 champs) = 35 increments. Negligeable
 * en charge normale, deja batche par Micrometer.
 */
@Service
class ExtractionDriftMonitor(
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class FieldOutcome(val tag: String) {
        EXTRACTED("extracted"),
        NULL_VALUE("null"),
        STRIPPED_GROUNDING("stripped_grounding"),
        STRIPPED_CONSISTENCY("stripped_consistency"),
        STRIPPED_COVE("stripped_cove"),
        RECONCILED("reconciled"),
        REEXTRACTED("reextracted_field")
    }

    /**
     * Cache des compteurs (clef = "type:field:outcome") pour eviter le
     * lookup MeterRegistry a chaque increment. Micrometer fait deja un cache
     * en interne mais il prend un lock fin a chaque get -> on l'evite.
     */
    private val counterCache = ConcurrentHashMap<String, Counter>()
    private val confidenceSummaries = ConcurrentHashMap<TypeDocument, DistributionSummary>()
    private val qualityScoreSummaries = ConcurrentHashMap<TypeDocument, DistributionSummary>()

    /**
     * Enregistre l'issue d'un champ critique apres toutes les couches de
     * validation. Appelee une fois par (document, champ critique).
     */
    fun recordFieldOutcome(type: TypeDocument, field: String, outcome: FieldOutcome) {
        try {
            val key = "${type.name}:$field:${outcome.tag}"
            val counter = counterCache.computeIfAbsent(key) {
                Counter.builder("recondoc.extraction.field.outcome")
                    .description("Issue d'un champ critique apres validation (extracted, null, strip*, reconciled)")
                    .tags(
                        Tags.of(
                            Tag.of("type", type.name),
                            Tag.of("field", field),
                            Tag.of("outcome", outcome.tag)
                        )
                    )
                    .register(meterRegistry)
            }
            counter.increment()
        } catch (e: Exception) {
            log.debug("Drift monitor counter increment failed: {}", e.message)
        }
    }

    /**
     * Enregistre la confidence Claude apres extraction (avant penalisation
     * eventuelle par ExtractionQualityService). Permet de tracker l'evolution
     * de l'auto-evaluation Claude par type.
     */
    fun recordConfidence(type: TypeDocument, confidence: Double) {
        if (confidence < 0.0 || confidence > 1.0) return // ignore les valeurs aberrantes
        try {
            val summary = confidenceSummaries.computeIfAbsent(type) {
                DistributionSummary.builder("recondoc.extraction.confidence")
                    .description("Confidence Claude auto-declaree par type de document (0..1)")
                    .tag("type", type.name)
                    .scale(100.0) // pour avoir des buckets en %
                    .register(meterRegistry)
            }
            summary.record(confidence)
        } catch (e: Exception) {
            log.debug("Drift monitor confidence summary failed: {}", e.message)
        }
    }

    /**
     * Enregistre le score qualite composite final (apres ExtractionQuality
     * Service). C'est le signal de plus haut niveau : derive d'extraction
     * sur un type se traduit par une baisse de la mediane.
     */
    fun recordQualityScore(type: TypeDocument, score: Int) {
        if (score < 0 || score > 100) return
        try {
            val summary = qualityScoreSummaries.computeIfAbsent(type) {
                DistributionSummary.builder("recondoc.extraction.quality_score")
                    .description("Score qualite composite final par type de document (0..100)")
                    .tag("type", type.name)
                    .register(meterRegistry)
            }
            summary.record(score.toDouble())
        } catch (e: Exception) {
            log.debug("Drift monitor quality score summary failed: {}", e.message)
        }
    }

    /**
     * Enregistre toutes les issues d'un document en une fois. Determine
     * l'outcome de chaque champ critique en comparant les snapshots avant /
     * apres chaque couche.
     *
     * Appele depuis DossierService apres l'extraction et toutes les
     * couches de validation. Centralise la logique de classification
     * pour eviter les bugs d'instrumentation eparpilles.
     */
    fun recordDocumentOutcomes(
        type: TypeDocument,
        criticalFields: Collection<String>,
        afterExtraction: Map<String, Any?>,
        afterGrounding: Map<String, Any?>,
        afterConsistency: Map<String, Any?>,
        afterReconciliation: Map<String, Any?>,
        afterCove: Map<String, Any?>
    ) {
        for (field in criticalFields) {
            val v0 = afterExtraction.getFieldCaseInsensitive(field)
            val v1 = afterGrounding.getFieldCaseInsensitive(field)
            val v2 = afterConsistency.getFieldCaseInsensitive(field)
            val v3 = afterReconciliation.getFieldCaseInsensitive(field)
            val v4 = afterCove.getFieldCaseInsensitive(field)

            val outcome = when {
                v0 == null -> FieldOutcome.NULL_VALUE
                v0 != null && v1 == null -> FieldOutcome.STRIPPED_GROUNDING
                v1 != null && v2 == null -> FieldOutcome.STRIPPED_CONSISTENCY
                v3 != null && !v3.toString().equals(v2?.toString().orEmpty()) -> FieldOutcome.RECONCILED
                v3 != null && v4 == null -> FieldOutcome.STRIPPED_COVE
                v4 != null -> FieldOutcome.EXTRACTED
                else -> FieldOutcome.NULL_VALUE
            }
            recordFieldOutcome(type, field, outcome)
        }
    }

    /**
     * Pour les tests : nombre total d'increments observes sur un (type,
     * champ, outcome). Renvoie 0 si la metrique n'a jamais ete enregistree.
     */
    fun countFor(type: TypeDocument, field: String, outcome: FieldOutcome): Long {
        val key = "${type.name}:$field:${outcome.tag}"
        return counterCache[key]?.count()?.toLong() ?: 0L
    }
}
