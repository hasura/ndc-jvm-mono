package io.hasura.ndc.sqlgen

import io.hasura.ndc.ir.*
import org.jooq.DSLContext
import org.jooq.Select

abstract class BaseMutationTranslator  {

    fun translate(
        mutationRequest: MutationRequest,
        ctx: DSLContext,
        returnHandler: (QueryRequest) -> Select<*>
    ): List<MutationOperationResult> {
        return emptyList()
    }
}

object MutationTranslator : BaseMutationTranslator()
