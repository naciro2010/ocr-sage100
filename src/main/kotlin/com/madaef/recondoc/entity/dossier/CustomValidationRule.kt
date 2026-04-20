package com.madaef.recondoc.entity.dossier

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

/**
 * User-defined validation rule evaluated by the LLM.
 *
 * The rule's `code` (e.g. "CUSTOM-01") is stored verbatim in
 * [ResultatValidation.regle], so the existing cascade/override/correction
 * infrastructure keeps working without branching on rule origin.
 */
@Entity
@Table(name = "custom_validation_rule")
class CustomValidationRule(
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,

    @Column(nullable = false, unique = true, length = 20)
    var code: String,

    @Column(nullable = false, length = 200)
    var libelle: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(nullable = false, columnDefinition = "TEXT")
    var prompt: String,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(name = "applies_to_bc", nullable = false)
    var appliesToBC: Boolean = true,

    @Column(name = "applies_to_contractuel", nullable = false)
    var appliesToContractuel: Boolean = true,

    /** CSV of TypeDocument values. Empty/null means "use all dossier documents". */
    @Column(name = "document_types", columnDefinition = "TEXT")
    var documentTypes: String? = null,

    /** Either NON_CONFORME or AVERTISSEMENT — the severity used when the rule fails. */
    @Column(nullable = false, length = 20)
    var severity: String = "NON_CONFORME",

    /** Optional CSV of field names the user flagged as required for evaluation. */
    @Column(name = "required_fields", columnDefinition = "TEXT")
    var requiredFields: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "created_by", length = 100)
    var createdBy: String? = null
)
