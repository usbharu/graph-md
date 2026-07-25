package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.Bm25Scorer
import dev.usbharu.graphmd.query.text.TextAnalyzer

internal data class ScoredTextAssertion(
    val assertion: TextAssertion,
    val score: Double,
)

internal interface QueryDataSource {
    val graph: QueryableGraph

    fun rootNodes(pattern: NodePattern): List<QueryNode>

    fun propertyAssertions(
        owner: AssertionOwner,
        predicate: PropertyPredicate,
    ): List<PropertyAssertion>

    fun relationAssertions(
        nodeId: NodeId,
        pattern: RelationPattern,
    ): List<RelationAssertion>

    fun textAssertions(
        owner: AssertionOwner,
        predicate: TextPredicate,
    ): List<ScoredTextAssertion>
}

internal class ScanQueryDataSource(
    override val graph: QueryableGraph,
) : QueryDataSource {
    private val scorer = Bm25Scorer.from(graph.textAssertions)

    override fun rootNodes(pattern: NodePattern): List<QueryNode> =
        graph.nodes.filter { it.matches(pattern) }

    override fun propertyAssertions(
        owner: AssertionOwner,
        predicate: PropertyPredicate,
    ): List<PropertyAssertion> = graph.propertyAssertions.filter {
        it.owner == owner && it.path == predicate.path && valueMatches(it.value, predicate)
    }

    override fun relationAssertions(
        nodeId: NodeId,
        pattern: RelationPattern,
    ): List<RelationAssertion> = graph.relationAssertions.filter { relation ->
        relation.touches(nodeId, pattern.direction) && relation.matchesType(pattern)
    }

    override fun textAssertions(
        owner: AssertionOwner,
        predicate: TextPredicate,
    ): List<ScoredTextAssertion> = graph.textAssertions.mapNotNull { assertion ->
        if (assertion.owner != owner || !TextAnalyzer.matches(assertion.text, predicate)) {
            null
        } else {
            ScoredTextAssertion(assertion, scorer.score(assertion.id, predicate))
        }
    }
}

internal class QuerySemantics(
    private val source: QueryDataSource,
    private val query: GraphQuery,
) {
    private val graph = source.graph
    private val nodeById = graph.nodes.associateBy { it.id }
    private val queryWindow by lazy {
        query.temporalWindow
            ?.toIntervalSet(graph.timelineCatalog)
            ?: IntervalSet.universal()
    }

    fun execute(): QueryResult {
        val diagnostics = validate()
        if (diagnostics.isNotEmpty() || queryWindow.isEmpty) return QueryResult(emptyList(), diagnostics)

        val matches = source.rootNodes(query.root).flatMap { root ->
            val rootTime = joinTime(queryWindow, root.validTime)
            if (rootTime.isEmpty) {
                emptyList()
            } else {
                val initial = QueryBinding(
                    variables = mapOf(query.rootVariable to root.id),
                    validTime = rootTime,
                )
                evaluate(query.expression, listOf(initial), root.id)
                    .map { QueryMatch(root.id, it) }
            }
        }
        val merged = mergeMatches(matches, query.rootVariable)
            .sortedWith(
                compareByDescending<QueryMatch> { it.binding.score }
                    .thenBy { it.nodeId.value }
                    .thenBy { bindingSignature(it.binding) },
            )
            .drop(query.offset)
            .take(query.limit)
        return QueryResult(merged)
    }

    private fun validate(): List<QueryDiagnostic> = buildList {
        query.temporalWindow?.let { window ->
            if (window.timelineId !in graph.timelineCatalog) {
                add(
                    QueryDiagnostic(
                        QueryDiagnosticCode.UNKNOWN_TIMELINE,
                        "Unknown Timeline: ${window.timelineId.value}",
                    ),
                )
            }
            val invalid = when (window) {
                is TemporalWindow.At -> false
                is TemporalWindow.Range ->
                    window.start != null && window.endExclusive != null && window.start >= window.endExclusive
                is TemporalWindow.ClosedRange ->
                    window.start != null && window.endInclusive != null && window.start > window.endInclusive
            }
            if (invalid) {
                add(
                    QueryDiagnostic(
                        QueryDiagnosticCode.INVALID_TEMPORAL_WINDOW,
                        "Temporal window start must be before its end",
                    ),
                )
            }
        }
        query.root.typeId?.let { typeId ->
            if (typeId !in graph.nodeTypeIds) {
                add(QueryDiagnostic(QueryDiagnosticCode.UNKNOWN_NODE_TYPE, "Unknown NodeType: ${typeId.value}"))
            }
        }
        validateExpression(query.expression, this)
    }

    private fun validateExpression(
        expression: GraphQueryExpression,
        diagnostics: MutableList<QueryDiagnostic>,
    ) {
        when (expression) {
            GraphQueryExpression.MatchAll, GraphQueryExpression.MatchNone,
            is GraphQueryExpression.Property, is GraphQueryExpression.Text -> Unit
            is GraphQueryExpression.And -> expression.operands.forEach { validateExpression(it, diagnostics) }
            is GraphQueryExpression.Or -> expression.operands.forEach { validateExpression(it, diagnostics) }
            is GraphQueryExpression.Not -> validateExpression(expression.operand, diagnostics)
            is GraphQueryExpression.Relation -> {
                expression.pattern.typeId?.let { typeId ->
                    if (typeId !in graph.relationTypeIds) {
                        diagnostics += QueryDiagnostic(
                            QueryDiagnosticCode.UNKNOWN_RELATION_TYPE,
                            "Unknown RelType: ${typeId.value}",
                        )
                    }
                }
                expression.pattern.target.typeId?.let { typeId ->
                    if (typeId !in graph.nodeTypeIds) {
                        diagnostics += QueryDiagnostic(
                            QueryDiagnosticCode.UNKNOWN_NODE_TYPE,
                            "Unknown NodeType: ${typeId.value}",
                        )
                    }
                }
                validateExpression(expression.pattern.targetExpression, diagnostics)
            }
        }
    }

    private fun evaluate(
        expression: GraphQueryExpression,
        inputs: List<QueryBinding>,
        focusNodeId: NodeId,
    ): List<QueryBinding> = when (expression) {
        GraphQueryExpression.MatchAll -> inputs
        GraphQueryExpression.MatchNone -> emptyList()
        is GraphQueryExpression.And -> expression.operands.fold(inputs) { bindings, operand ->
            mergeBindings(bindings.flatMap { evaluate(operand, listOf(it), focusNodeId) })
        }
        is GraphQueryExpression.Or -> mergeBindings(
            expression.operands.flatMap { evaluate(it, inputs, focusNodeId) },
        )
        is GraphQueryExpression.Not -> inputs.mapNotNull { input ->
            val excluded = evaluate(expression.operand, listOf(input), focusNodeId)
                .map { it.validTime }
                .fold(IntervalSet.empty(), IntervalSet::union)
            when {
                excluded.isEmpty -> input
                input.validTime.isUniversal -> null
                else -> input.validTime.subtract(excluded).takeUnless { it.isEmpty }?.let {
                    input.copy(validTime = it)
                }
            }
        }
        is GraphQueryExpression.Property -> evaluateProperty(expression.predicate, inputs, focusNodeId)
        is GraphQueryExpression.Relation -> evaluateRelation(expression.pattern, inputs, focusNodeId)
        is GraphQueryExpression.Text -> evaluateText(expression.predicate, inputs, AssertionOwner.Node(focusNodeId))
    }

    private fun evaluateProperty(
        predicate: PropertyPredicate,
        inputs: List<QueryBinding>,
        focusNodeId: NodeId,
    ): List<QueryBinding> {
        val assertions = source.propertyAssertions(AssertionOwner.Node(focusNodeId), predicate)
        return mergeBindings(inputs.flatMap { input ->
            assertions.mapNotNull { assertion ->
                join(input, assertion.validTime, assertion.id)
            }
        })
    }

    private fun evaluateRelation(
        pattern: RelationPattern,
        inputs: List<QueryBinding>,
        focusNodeId: NodeId,
    ): List<QueryBinding> {
        val relations = source.relationAssertions(focusNodeId, pattern)
        return mergeBindings(inputs.flatMap { input ->
            relations.flatMap { relation ->
                val targetIds = relation.targetsFrom(focusNodeId, pattern.direction)
                targetIds.flatMap targetLoop@{ targetId ->
                    val target = nodeById[targetId] ?: return@targetLoop emptyList()
                    if (!target.matches(pattern.target)) return@targetLoop emptyList()
                    var current = listOfNotNull(join(input, relation.validTime, relation.id))
                    current = current.mapNotNull { join(it, target.validTime, null) }
                    if (current.isEmpty()) return@targetLoop emptyList()

                    pattern.targetVariable?.let { variable ->
                        current = current.mapNotNull { binding ->
                            val existing = binding.variables[variable]
                            if (existing != null && existing != targetId) {
                                null
                            } else {
                                binding.copy(variables = binding.variables + (variable to targetId))
                            }
                        }
                    }

                    pattern.relationProperties.forEach { predicate ->
                        val candidates = source.propertyAssertions(AssertionOwner.Relation(relation.id), predicate)
                        current = mergeBindings(current.flatMap { binding ->
                            candidates.mapNotNull { assertion ->
                                join(binding, assertion.validTime, assertion.id)
                            }
                        })
                        if (current.isEmpty()) return@targetLoop emptyList()
                    }

                    pattern.label?.let { predicate ->
                        val labels = source.textAssertions(AssertionOwner.Relation(relation.id), predicate)
                        current = mergeBindings(current.flatMap { binding ->
                            labels.mapNotNull { scored ->
                                join(binding, scored.assertion.validTime, scored.assertion.id)
                                    ?.copy(score = binding.score + scored.score)
                            }
                        })
                        if (current.isEmpty()) return@targetLoop emptyList()
                    }

                    evaluate(pattern.targetExpression, current, targetId)
                }
            }
        })
    }

    private fun evaluateText(
        predicate: TextPredicate,
        inputs: List<QueryBinding>,
        owner: AssertionOwner,
    ): List<QueryBinding> {
        val assertions = source.textAssertions(owner, predicate)
        return mergeBindings(inputs.flatMap { input ->
            assertions.mapNotNull { scored ->
                join(input, scored.assertion.validTime, scored.assertion.id)
                    ?.copy(score = input.score + scored.score)
            }
        })
    }

    private fun join(
        binding: QueryBinding,
        assertionTime: IntervalSet,
        assertionId: AssertionId?,
    ): QueryBinding? {
        val validTime = joinTime(binding.validTime, assertionTime)
        if (validTime.isEmpty) return null
        return binding.copy(
            validTime = validTime,
            matchedAssertionIds = assertionId?.let { binding.matchedAssertionIds + it }
                ?: binding.matchedAssertionIds,
        )
    }

    private fun joinTime(
        bindingTime: IntervalSet,
        assertionTime: IntervalSet,
    ): IntervalSet {
        if (assertionTime.isUniversal) return bindingTime
        if (query.temporalWindow == null) return bindingTime intersect assertionTime
        return when (query.temporalOperator) {
            TemporalOperator.AT, TemporalOperator.OVERLAPS -> bindingTime intersect assertionTime
            TemporalOperator.ASSERTION_CONTAINS_QUERY ->
                if (assertionTime.contains(queryWindow)) bindingTime intersect assertionTime else IntervalSet.empty()
            TemporalOperator.QUERY_CONTAINS_ASSERTION ->
                if (queryWindow.contains(assertionTime)) bindingTime intersect assertionTime else IntervalSet.empty()
        }
    }
}

internal fun QueryNode.matches(pattern: NodePattern): Boolean =
    (pattern.id == null || id == pattern.id) &&
        (
            pattern.typeId == null ||
                typeId == pattern.typeId ||
                pattern.includeDerivedTypes && pattern.typeId in ancestorTypeIds
            )

internal fun RelationAssertion.matchesType(pattern: RelationPattern): Boolean =
    pattern.typeId == null ||
        relTypeId == pattern.typeId ||
        pattern.includeDerivedTypes && pattern.typeId in ancestorRelTypeIds

internal fun RelationAssertion.touches(nodeId: NodeId, direction: RelationDirection): Boolean = when (direction) {
    RelationDirection.OUTGOING -> sourceNodeId == nodeId
    RelationDirection.INCOMING -> targetNodeId == nodeId
    RelationDirection.EITHER -> sourceNodeId == nodeId || targetNodeId == nodeId
}

internal fun RelationAssertion.targetsFrom(
    nodeId: NodeId,
    direction: RelationDirection,
): Set<NodeId> = when (direction) {
    RelationDirection.OUTGOING -> setOfNotNull(targetNodeId.takeIf { sourceNodeId == nodeId })
    RelationDirection.INCOMING -> setOfNotNull(sourceNodeId.takeIf { targetNodeId == nodeId })
    RelationDirection.EITHER -> buildSet {
        if (sourceNodeId == nodeId) add(targetNodeId)
        if (targetNodeId == nodeId) add(sourceNodeId)
    }
}

internal fun valueMatches(value: NormalizedValue, predicate: PropertyPredicate): Boolean {
    val comparison = compareNormalizedValues(value, predicate.value)
    return when (predicate.operator) {
        ValueOperator.EQUALS -> normalizedValueEquals(value, predicate.value)
        ValueOperator.NOT_EQUALS -> !normalizedValueEquals(value, predicate.value)
        ValueOperator.LESS_THAN -> comparison != null && comparison < 0
        ValueOperator.LESS_THAN_OR_EQUALS -> comparison != null && comparison <= 0
        ValueOperator.GREATER_THAN -> comparison != null && comparison > 0
        ValueOperator.GREATER_THAN_OR_EQUALS -> comparison != null && comparison >= 0
        ValueOperator.CONTAINS -> normalizedContains(value, predicate.value)
    }
}

internal fun normalizedValueEquals(left: NormalizedValue, right: NormalizedValue): Boolean {
    val numericLeft = left.numericValue()
    val numericRight = right.numericValue()
    return if (numericLeft != null && numericRight != null) numericLeft == numericRight else left == right
}

internal fun compareNormalizedValues(left: NormalizedValue, right: NormalizedValue): Int? {
    val numericLeft = left.numericValue()
    val numericRight = right.numericValue()
    if (numericLeft != null && numericRight != null) return numericLeft.compareTo(numericRight)
    return when {
        left is StringValue && right is StringValue -> left.value.compareTo(right.value)
        left is BooleanValue && right is BooleanValue -> left.value.compareTo(right.value)
        else -> null
    }
}

private fun NormalizedValue.numericValue(): Double? = when (this) {
    is IntegerValue -> value.toDouble()
    is NumberValue -> value
    else -> null
}

private fun normalizedContains(container: NormalizedValue, item: NormalizedValue): Boolean = when {
    container is StringValue && item is StringValue -> item.value in container.value
    container is TextValue && item is StringValue -> container.values.values.any { item.value in it }
    container is ArrayValue -> container.values.any { normalizedValueEquals(it, item) }
    container is ObjectValue && item is StringValue -> item.value in container.values
    else -> false
}

private fun mergeBindings(bindings: List<QueryBinding>): List<QueryBinding> {
    val merged = linkedMapOf<Map<VariableId, NodeId>, QueryBinding>()
    bindings.forEach { binding ->
        val previous = merged[binding.variables]
        merged[binding.variables] = if (previous == null) {
            binding
        } else {
            previous.copy(
                validTime = previous.validTime union binding.validTime,
                score = maxOf(previous.score, binding.score),
                matchedAssertionIds = previous.matchedAssertionIds + binding.matchedAssertionIds,
            )
        }
    }
    return merged.values.toList()
}

private fun mergeMatches(matches: List<QueryMatch>, rootVariable: VariableId): List<QueryMatch> =
    mergeBindings(matches.map { it.binding }).map { binding ->
        QueryMatch(binding.variables.getValue(rootVariable), binding)
    }

private fun bindingSignature(binding: QueryBinding): String =
    binding.variables.entries.sortedBy { it.key.value }
        .joinToString("|") { "${it.key.value}=${it.value.value}" }
