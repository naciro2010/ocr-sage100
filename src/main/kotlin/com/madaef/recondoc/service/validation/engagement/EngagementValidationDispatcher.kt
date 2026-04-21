package com.madaef.recondoc.service.validation.engagement

import com.madaef.recondoc.entity.dossier.DossierPaiement
import com.madaef.recondoc.entity.dossier.ResultatValidation
import com.madaef.recondoc.entity.engagement.Engagement
import com.madaef.recondoc.repository.engagement.EngagementRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Orchestrateur : selectionne les validators applicables au type runtime
 * de l'engagement (Marche / BC / Contrat) et concatene leurs resultats.
 *
 * Le validator commun est toujours execute ; le validator specifique
 * au type est trouve via la liste injectee par Spring.
 *
 * Principe zero-regression : si le dossier n'a pas d'engagement rattache,
 * aucune regle R-E/R-M/R-B/R-C n'est produite.
 */
@Service
class EngagementValidationDispatcher(
    private val engagementRepo: EngagementRepository,
    private val commonValidator: CommonEngagementValidator,
    private val typedValidators: List<EngagementValidator<*>>,
    @Value("\${app.tolerance-montant:0.05}") private val toleranceMontant: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validate(dossier: DossierPaiement): List<ResultatValidation> {
        val engagement = dossier.engagement ?: return emptyList()
        val engagementId = engagement.id ?: return emptyList()

        val t0 = System.nanoTime()
        val montantConsomme = engagementRepo.sumDossiersMontantTtc(engagementId)
        val dossiersRattaches = engagementRepo.findDossiersByEngagement(engagementId)

        val context = EngagementValidationContext(
            montantConsomme = montantConsomme,
            dossiersRattaches = dossiersRattaches,
            toleranceMontant = BigDecimal(toleranceMontant)
        )

        val results = mutableListOf<ResultatValidation>()

        // 1. Regles communes (R-E01..R-E05)
        results += commonValidator.validate(engagement, dossier, context)

        // 2. Regles specifiques au type (R-M / R-B / R-C)
        val typeValidator = typedValidators.firstOrNull { v ->
            v.supports() != Engagement::class.java && v.supports().isInstance(engagement)
        }
        if (typeValidator != null) {
            @Suppress("UNCHECKED_CAST")
            val validator = typeValidator as EngagementValidator<Engagement>
            results += validator.validate(engagement, dossier, context)
        } else {
            log.warn("Aucun validator specifique pour le type {} (dossier {})",
                engagement.typeEngagement(), dossier.reference)
        }

        val durationMs = (System.nanoTime() - t0) / 1_000_000
        results.forEach { it.durationMs = durationMs / results.size.coerceAtLeast(1) }

        log.info("Engagement {} ({}) : {} regles executees pour dossier {} ({}ms)",
            engagement.reference, engagement.typeEngagement(), results.size, dossier.reference, durationMs)
        return results
    }

    /** Retourne tous les codes de regles couverts par le dispatcher, groupes par validator. */
    fun allRules(): Map<String, List<String>> = buildMap {
        put("common", commonValidator.rules())
        typedValidators.filter { it.supports() != Engagement::class.java }.forEach { v ->
            put(v.supports().simpleName, v.rules())
        }
    }
}
