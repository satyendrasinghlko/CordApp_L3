package com.example.schema

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for IOUState.
 */
object IOUSchema

/**
 * An IOUState schema.
 */
object IOUSchemaV1 : MappedSchema(
        schemaFamily = IOUSchema.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentIOU::class.java)) {
    @Entity
    @Table(name = "iou_states")
    class PersistentIOU(
            @Column(name = "lender")
            var lenderName: String,

            @Column(name = "borrower")
            var borrowerName: String,

            @Column(name = "linear_id")
            var linearId: UUID,

            @Column(name = "amount")
            var amount: Double,

            @Column(name = "tradeDate")
            var tradeDate: Date,

            @Column(name = "status")
            var status: String

    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this("", "", UUID.randomUUID(),0.0, Date(),"")

    }
}