package com.madaef.recondoc.controller

import com.madaef.recondoc.repository.dossier.FournisseurAliasRepository
import com.madaef.recondoc.repository.dossier.FournisseurCanoniqueRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/fournisseurs")
class FournisseurAdminController(
    private val canoniqueRepo: FournisseurCanoniqueRepository,
    private val aliasRepo: FournisseurAliasRepository
) {

    @GetMapping
    fun list(): List<Map<String, Any?>> {
        return canoniqueRepo.findAllOrderedByDerniereUtilisation().map { c ->
            val aliases = aliasRepo.findByCanoniqueId(c.id!!)
            mapOf(
                "id" to c.id,
                "nomCanonique" to c.nomCanonique,
                "nomNormalise" to c.nomNormalise,
                "sourceTypeDocument" to c.sourceTypeDocument,
                "ice" to c.ice,
                "identifiantFiscal" to c.identifiantFiscal,
                "manuellementConfirme" to c.manuellementConfirme,
                "dateMiseAJour" to c.dateMiseAJour,
                "aliasCount" to aliases.size,
                "aliasRequiresReview" to aliases.count { it.requiresReview },
                "aliases" to aliases.map { a ->
                    mapOf(
                        "id" to a.id,
                        "nomBrut" to a.nomBrut,
                        "sourceTypeDocument" to a.sourceTypeDocument,
                        "similarityScore" to a.similarityScore,
                        "requiresReview" to a.requiresReview,
                        "dateCreation" to a.dateCreation
                    )
                }
            )
        }
    }

    @GetMapping("/pending-review")
    fun pendingReview(): List<Map<String, Any?>> {
        return aliasRepo.findByRequiresReviewTrueOrderByDateCreationDesc().map { a ->
            mapOf(
                "id" to a.id,
                "nomBrut" to a.nomBrut,
                "nomNormalise" to a.nomNormalise,
                "sourceTypeDocument" to a.sourceTypeDocument,
                "similarityScore" to a.similarityScore,
                "dateCreation" to a.dateCreation,
                "canoniqueId" to a.canonique.id,
                "canoniqueNom" to a.canonique.nomCanonique
            )
        }
    }

    @PostMapping("/aliases/{aliasId}/confirm")
    fun confirmAlias(@PathVariable aliasId: UUID): Map<String, Any?> {
        val alias = aliasRepo.findById(aliasId).orElseThrow { IllegalArgumentException("Alias introuvable: $aliasId") }
        alias.requiresReview = false
        aliasRepo.save(alias)
        val c = alias.canonique
        c.manuellementConfirme = true
        canoniqueRepo.save(c)
        return mapOf("aliasId" to alias.id, "canoniqueId" to c.id, "confirmed" to true)
    }
}
