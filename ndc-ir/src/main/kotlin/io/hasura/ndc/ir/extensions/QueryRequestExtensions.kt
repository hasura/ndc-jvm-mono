package io.hasura.ndc.ir.extensions

import io.hasura.ndc.ir.QueryRequest

fun QueryRequest.isVariablesRequest() = !this.variables.isNullOrEmpty()

fun QueryRequest.isRootRequest() = this.collection == this.root_collection
