package io.hasura.ndc.ir

import com.fasterxml.jackson.annotation.JsonInclude

//{
//  "version": "0.1.0",
//  "capabilities": {
//    "query": {
//      "aggregates": {},
//      "variables": {}
//    },
//    "mutation": {},
//    "relationships": {
//      "relation_comparisons": {},
//      "order_by_aggregate": {}
//    }
//  }
//}
data class CapabilitiesResponse(
    val version: String,
    val capabilities: Capabilities
)


// | Name                                              | Description                                                                                                        |
// |---------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
// | `version`                                         | A [semantic version number](https://semver.org) of this specification which the data connector claims to implement |
// | `capabilities.query.aggregates`                   | Whether the data connector supports [aggregate queries](queries/aggregates.md)                                     |
// | `capabilities.query.variables`                    | Whether the data connector supports [queries with variables](queries/variables.md)                                 |
// | `capabilities.query.explain`                      | Whether the data connector is capable of describing query plans                                                    |
// | `capabilities.mutation.transactional`             | Whether the data connector is capable of executing multiple mutations in a transaction                             |
// | `capabilities.mutation.explain`                   | Whether the data connector is capable of describing mutation plans                                                 |
// | `capabilities.relationships`                      | Whether the data connector supports [relationships](queries/relationships.md)                                      |
// | `capabilities.relationships.order_by_aggregate`   | Whether order by clauses can include aggregates                                                                    |
// | `capabilities.relationships.relation_comparisons` | Whether comparisons can include columns reachable via [relationships](queries/relationships.md)                    |
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Capabilities(
    val query: QueryCapabilities,
    val mutation: MutationCapabilities,
    val relationships: RelationshipsCapabilities? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueryCapabilities(
    val aggregates: Map<String, Any>? = null,
    val variables: Map<String, Any>?  = null,
    val explain: Map<String, Any>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MutationCapabilities(
    val transactional: Map<String, Any>? = null,
    val explain: Map<String, Any>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RelationshipsCapabilities(
    val relation_comparisons: Map<String, Any>? = null,
    val order_by_aggregate: Map<String, Any>? = null
)





