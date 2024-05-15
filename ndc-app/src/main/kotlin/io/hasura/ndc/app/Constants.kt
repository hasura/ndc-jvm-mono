package io.hasura.ndc.app

import io.hasura.ndc.ir.Capabilities
import io.hasura.ndc.ir.CapabilitiesResponse
import io.hasura.ndc.ir.MutationCapabilities
import io.hasura.ndc.ir.QueryCapabilities

// Structure of this page (IE, @JvmField annotations) taken from advice in below article
// https://www.egorand.dev/where-should-i-keep-my-constants-in-kotlin/

@JvmField
val JDBC_DATASOURCE_COMMON_CAPABILITY_RESPONSE = CapabilitiesResponse(
    version = "0.0.1",
    capabilities = Capabilities(
        query = QueryCapabilities(),
        mutation = MutationCapabilities()
    )
)
