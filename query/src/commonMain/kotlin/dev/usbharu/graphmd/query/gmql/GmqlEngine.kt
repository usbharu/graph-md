package dev.usbharu.graphmd.query.gmql

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.index.*
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.TextAnalyzer

internal class GmqlCompiler(private val graph: QueryableGraph) {
    fun compile(text: String, parameterTypes: Map<String, GmqlType>): GmqlCompileResult {
        val ast = try {
            GmqlParser.parse(text)
        } catch (error: GmqlParseException) {
            return GmqlCompileResult(
                diagnostics = listOf(
                    diagnostic("GMQL1001", error.message ?: "Invalid query", error.sourceRange, GmqlDiagnosticKind.SYNTAX),
                ),
            )
        }
        val diagnostics = mutableListOf<GmqlDiagnostic>()
        val variables = linkedMapOf<String, VariableInfo>()
        ast.patterns.forEach { pattern ->
            pattern.nodes.forEach { node ->
                node.type?.let {
                    if (NodeTypeId(it) !in graph.nodeTypeIds) {
                        diagnostics += diagnostic(
                            "GMQL2001", "Unknown NodeType '$it'.", node.range, GmqlDiagnosticKind.NAME,
                        )
                    }
                }
                node.variable?.let { declare(variables, it, GmqlType.Node, node.type, node.range, diagnostics) }
            }
            pattern.relations.forEach { relation ->
                relation.type?.let {
                    if (RelationTypeId(it) !in graph.relationTypeIds) {
                        diagnostics += diagnostic(
                            "GMQL2002", "Unknown RelType '$it'.", relation.range, GmqlDiagnosticKind.NAME,
                        )
                    }
                }
                relation.variable?.let {
                    declare(variables, it, GmqlType.Relation, relation.type, relation.range, diagnostics)
                }
            }
            validateEndpoints(pattern, diagnostics)
        }
        val checker = ExpressionChecker(graph, variables, parameterTypes, diagnostics)
        ast.where?.let {
            if (checker.typeOf(it).unwrapTemporal() != GmqlType.Boolean) {
                diagnostics += diagnostic(
                    "GMQL3001", "WHERE expression must be Boolean.", it.range, GmqlDiagnosticKind.TYPE,
                )
            }
        }
        ast.valid?.let { valid ->
            if (valid.operator != GmqlValidOperator.ANYTIME && valid.timeline == null) {
                diagnostics += diagnostic(
                    "GMQL4001", "VALID ${valid.operator.name} requires ON Timeline.", valid.range,
                    GmqlDiagnosticKind.TEMPORAL,
                )
            }
            valid.timeline?.let {
                if (TimelineId(it) !in graph.timelineCatalog) {
                    diagnostics += diagnostic(
                        "GMQL4002", "Unknown Timeline '$it'.", valid.range, GmqlDiagnosticKind.TEMPORAL,
                    )
                }
            }
            valid.instant?.let {
                if (!checker.typeOf(it).isNumeric()) diagnostics += diagnostic(
                    "GMQL3002", "VALID AT requires a numeric instant.", it.range, GmqlDiagnosticKind.TYPE,
                )
            }
            valid.interval?.let { interval ->
                listOfNotNull(interval.start, interval.end).forEach {
                    if (!checker.typeOf(it).isNumeric()) diagnostics += diagnostic(
                        "GMQL3002", "Interval bounds must be numeric.", it.range, GmqlDiagnosticKind.TYPE,
                    )
                }
            }
        }
        val columns = ast.returns.mapIndexed { index, item ->
            val type = checker.typeOf(item.expression)
            GmqlColumn(item.alias ?: defaultColumnName(item.expression, index), type)
        }
        val aliases = columns.associate { it.name to it.type }
        ast.orderBy.forEach {
            val type = if (it.expression is GmqlExpression.Variable && it.expression.name in aliases) {
                aliases.getValue(it.expression.name)
            } else checker.typeOf(it.expression)
            if (type is GmqlType.Temporal || !type.isOrderable()) diagnostics += diagnostic(
                "GMQL3003", "ORDER BY expression is not orderable.", it.expression.range, GmqlDiagnosticKind.TYPE,
            )
        }
        listOfNotNull(ast.offset, ast.limit).forEach {
            if (checker.typeOf(it) != GmqlType.Integer) diagnostics += diagnostic(
                "GMQL3004", "OFFSET and LIMIT require Integer values.", it.range, GmqlDiagnosticKind.TYPE,
            )
        }
        return if (diagnostics.isEmpty()) {
            GmqlCompileResult(GmqlCompiledQuery(ast, checker.expressionTypes(), parameterTypes, columns))
        } else {
            GmqlCompileResult(diagnostics = diagnostics)
        }
    }

    private fun validateEndpoints(pattern: GmqlPattern, diagnostics: MutableList<GmqlDiagnostic>) {
        pattern.relations.forEachIndexed { index, relation ->
            val schema = relation.type?.let { graph.relationTypeSchemas[RelationTypeId(it)] } ?: return@forEachIndexed
            val left = pattern.nodes[index].type?.let(::NodeTypeId)
            val right = pattern.nodes[index + 1].type?.let(::NodeTypeId)
            val source = if (relation.direction == RelationDirection.INCOMING) right else left
            val target = if (relation.direction == RelationDirection.INCOMING) left else right
            if (relation.direction != RelationDirection.EITHER) {
                if (source != null && schema.sourceTypeIds != null && !compatible(source, schema.sourceTypeIds)) {
                    diagnostics += diagnostic(
                        "GMQL3005", "RelType '${schema.id.value}' cannot start at '${source.value}'.",
                        relation.range, GmqlDiagnosticKind.TYPE,
                    )
                }
                if (target != null && schema.targetTypeIds != null && !compatible(target, schema.targetTypeIds)) {
                    diagnostics += diagnostic(
                        "GMQL3006", "RelType '${schema.id.value}' cannot end at '${target.value}'.",
                        relation.range, GmqlDiagnosticKind.TYPE,
                    )
                }
            } else if (left != null && right != null) {
                val forward = (schema.sourceTypeIds == null || compatible(left, schema.sourceTypeIds)) &&
                    (schema.targetTypeIds == null || compatible(right, schema.targetTypeIds))
                val reverse = (schema.sourceTypeIds == null || compatible(right, schema.sourceTypeIds)) &&
                    (schema.targetTypeIds == null || compatible(left, schema.targetTypeIds))
                if (!forward && !reverse) diagnostics += diagnostic(
                    "GMQL3006",
                    "RelType '${schema.id.value}' cannot connect '${left.value}' and '${right.value}'.",
                    relation.range,
                    GmqlDiagnosticKind.TYPE,
                )
            }
        }
    }

    private fun compatible(actual: NodeTypeId, accepted: Set<NodeTypeId>): Boolean {
        val schema = graph.nodeTypeSchemas[actual]
        return actual in accepted || schema?.ancestorTypeIds.orEmpty().any { it in accepted }
    }
}

private data class VariableInfo(val type: GmqlType, val schemaType: String?)

private fun declare(
    variables: MutableMap<String, VariableInfo>,
    name: String,
    type: GmqlType,
    schemaType: String?,
    range: GmqlSourceRange,
    diagnostics: MutableList<GmqlDiagnostic>,
) {
    val previous = variables[name]
    if (previous != null && previous.type != type) {
        diagnostics += diagnostic(
            "GMQL2003", "Variable '$name' is used for incompatible entity kinds.", range, GmqlDiagnosticKind.NAME,
        )
    } else if (previous == null || previous.schemaType == null) {
        variables[name] = VariableInfo(type, schemaType)
    }
}

private class ExpressionChecker(
    private val graph: QueryableGraph,
    private val variables: Map<String, VariableInfo>,
    private val parameters: Map<String, GmqlType>,
    private val diagnostics: MutableList<GmqlDiagnostic>,
) {
    private val cache = mutableMapOf<GmqlExpression, GmqlType>()
    fun expressionTypes(): Map<GmqlExpression, GmqlType> = cache.toMap()
    fun typeOf(expression: GmqlExpression): GmqlType = cache.getOrPut(expression) {
        when (expression) {
            is GmqlExpression.Literal -> expression.value.type()
            is GmqlExpression.Parameter -> parameters[expression.name] ?: run {
                diagnostics += diagnostic(
                    "GMQL2004", "Unknown parameter '\$${expression.name}'.", expression.range, GmqlDiagnosticKind.NAME,
                )
                GmqlType.Any
            }
            is GmqlExpression.Variable -> variables[expression.name]?.type ?: run {
                diagnostics += diagnostic(
                    "GMQL2005", "Unknown variable '${expression.name}'.", expression.range, GmqlDiagnosticKind.NAME,
                )
                GmqlType.Any
            }
            is GmqlExpression.Property -> propertyType(expression)
            is GmqlExpression.Call -> callType(expression)
            is GmqlExpression.IsTest -> GmqlType.Boolean
            is GmqlExpression.Unary -> {
                val operand = typeOf(expression.operand)
                if (expression.operator == "NOT") GmqlType.Boolean else operand
            }
            is GmqlExpression.Binary -> binaryType(expression)
        }
    }

    private fun propertyType(expression: GmqlExpression.Property): GmqlType {
        val chain = expression.propertyChain()
        if (chain == null) {
            diagnostics += diagnostic(
                "GMQL3007", "Property receiver must be a Node or Relation.", expression.range, GmqlDiagnosticKind.TYPE,
            )
            return GmqlType.Any
        }
        val variable = variables[chain.first]
        if (variable == null) return GmqlType.Any
        val pseudoType = when {
            variable.type == GmqlType.Node && chain.second == listOf("body") -> GmqlType.Text
            variable.type == GmqlType.Node && chain.second == listOf("title") -> GmqlType.String
            variable.type == GmqlType.Relation && chain.second == listOf("label") -> GmqlType.String
            else -> null
        }
        if (pseudoType != null) return GmqlType.Temporal(pseudoType)
        val schema = when (variable.type) {
            GmqlType.Node -> propertySchemaForNodes(variable.schemaType, chain.second.first())
            GmqlType.Relation -> propertySchemaForRelations(variable.schemaType, chain.second.first())
            else -> null
        }
        if (schema == null) {
            diagnostics += diagnostic(
                "GMQL2006", "Unknown Property '${chain.second.first()}'.", expression.range, GmqlDiagnosticKind.NAME,
            )
            return GmqlType.Any
        }
        var type = schema.toGmqlType()
        chain.second.drop(1).forEach { segment ->
            type = when (type) {
                GmqlType.Text -> GmqlType.String
                is GmqlType.Collection -> if (segment.toIntOrNull() != null) type.elementType else GmqlType.Any
                else -> GmqlType.Any
            }
        }
        return GmqlType.Temporal(type)
    }

    private fun propertySchemaForNodes(type: String?, name: String): ResolvedPropSchema? {
        val schemas = if (type != null) listOfNotNull(graph.nodeTypeSchemas[NodeTypeId(type)])
        else graph.nodeTypeSchemas.values
        return schemas.mapNotNull { it.properties[name] }.distinctBy { it.type to it.items?.type }.singleOrNull()
            ?: schemas.firstNotNullOfOrNull { it.properties[name] }
    }

    private fun propertySchemaForRelations(type: String?, name: String): ResolvedPropSchema? {
        val schemas = if (type != null) listOfNotNull(graph.relationTypeSchemas[RelationTypeId(type)])
        else graph.relationTypeSchemas.values
        return schemas.mapNotNull { it.properties[name] }.distinctBy { it.type to it.items?.type }.singleOrNull()
            ?: schemas.firstNotNullOfOrNull { it.properties[name] }
    }

    private fun callType(call: GmqlExpression.Call): GmqlType {
        val name = call.name.uppercase()
        val allowedCounts = when (name) {
            "SCORE", "MATCHED_VALIDITY" -> setOf(0)
            "VALIDITY" -> setOf(0, 1)
            "FULLTEXT" -> setOf(2)
            "EXISTS", "ID", "TYPE", "KIND", "TITLE", "SOURCE", "START_NODE", "END_NODE" -> setOf(1)
            "TYPE_REF", "REL_TYPE_REF" -> setOf(1)
            else -> {
                diagnostics += diagnostic(
                    "GMQL2007", "Unknown function '${call.name}'.", call.range, GmqlDiagnosticKind.NAME,
                )
                null
            }
        }
        if (allowedCounts != null && call.arguments.size !in allowedCounts) diagnostics += diagnostic(
            "GMQL3008", "Function $name expects ${allowedCounts.sorted().joinToString(" or ")} argument(s).",
            call.range, GmqlDiagnosticKind.TYPE,
        )
        val argumentTypes = call.arguments.map(::typeOf)
        fun requireArgument(index: Int, accepts: (GmqlType) -> Boolean, description: String) {
            val type = argumentTypes.getOrNull(index) ?: return
            if (!accepts(type.unwrapTemporal())) diagnostics += diagnostic(
                "GMQL3008", "Argument ${index + 1} of $name must be $description.",
                call.arguments[index].range, GmqlDiagnosticKind.TYPE,
            )
        }
        when (name) {
            "FULLTEXT" -> {
                requireArgument(0, { it in setOf(GmqlType.Node, GmqlType.Relation, GmqlType.String, GmqlType.Text) },
                    "a Node, Relation, string, or text")
                requireArgument(1, { it == GmqlType.String }, "string")
            }
            "ID", "TYPE", "SOURCE" ->
                requireArgument(0, { it == GmqlType.Node || it == GmqlType.Relation }, "a Node or Relation")
            "KIND", "TITLE" -> requireArgument(0, { it == GmqlType.Node }, "a Node")
            "START_NODE", "END_NODE" -> requireArgument(0, { it == GmqlType.Relation }, "a Relation")
            "TYPE_REF", "REL_TYPE_REF" -> requireArgument(0, { it == GmqlType.String }, "string")
        }
        return when (name) {
            "SCORE" -> GmqlType.Decimal
            "VALIDITY", "MATCHED_VALIDITY" -> GmqlType.TemporalExtent
            "FULLTEXT", "EXISTS" -> GmqlType.Boolean
            "ID", "KIND", "TITLE", "SOURCE" -> GmqlType.String
            "TYPE", "TYPE_REF", "REL_TYPE_REF" -> GmqlType.TypeRef
            "START_NODE", "END_NODE" -> GmqlType.Node
            else -> GmqlType.Any
        }
    }

    private fun binaryType(binary: GmqlExpression.Binary): GmqlType {
        val left = typeOf(binary.left)
        val right = typeOf(binary.right)
        return when (binary.operator.uppercase()) {
            "AND", "OR" -> GmqlType.Boolean
            "=", "!=", "<", "<=", ">", ">=", "IN", "CONTAINS", "STARTS WITH", "ENDS WITH" -> {
                val l = left.unwrapTemporal()
                val r = right.unwrapTemporal()
                val comparable = when (binary.operator.uppercase()) {
                    "STARTS WITH", "ENDS WITH" -> l == GmqlType.String && r == GmqlType.String
                    "IN" -> r is GmqlType.Collection
                    "CONTAINS" -> l == GmqlType.String && r == GmqlType.String || l is GmqlType.Collection
                    else -> l == r || l.isNumeric() && r.isNumeric() || l == GmqlType.Null || r == GmqlType.Null
                }
                if (!comparable || l == GmqlType.Text || r == GmqlType.Text) diagnostics += diagnostic(
                    "GMQL3001", "Operator '${binary.operator}' cannot be applied to $l and $r.",
                    binary.range, GmqlDiagnosticKind.TYPE,
                )
                if (left is GmqlType.Temporal || right is GmqlType.Temporal) {
                    GmqlType.Temporal(GmqlType.Boolean)
                } else {
                    GmqlType.Boolean
                }
            }
            "+", "-", "*", "/", "%" -> {
                if (!left.unwrapTemporal().isNumeric() || !right.unwrapTemporal().isNumeric()) diagnostics += diagnostic(
                    "GMQL3009", "Arithmetic operators require numeric operands.", binary.range, GmqlDiagnosticKind.TYPE,
                )
                val result = if (left.unwrapTemporal() == GmqlType.Decimal || right.unwrapTemporal() == GmqlType.Decimal ||
                    binary.operator == "/"
                ) GmqlType.Decimal else GmqlType.Integer
                if (left is GmqlType.Temporal || right is GmqlType.Temporal) GmqlType.Temporal(result) else result
            }
            else -> GmqlType.Any
        }
    }
}

internal class GmqlExecutor(
    private val index: SearchIndex,
    private val options: GmqlExecutionOptions,
    private val useIndex: Boolean = true,
) {
    private val graph = index.graph
    private val nodeById = graph.nodes.associateBy { it.id }
    private val propertyById = graph.propertyAssertions.associateBy { it.id }
    private val relationById = graph.relationAssertions.associateBy { it.id }
    private val textById = graph.textAssertions.associateBy { it.id }
    private var operations = 0L

    fun execute(query: GmqlCompiledQuery, parameters: Map<String, GmqlValue>): GmqlQueryResult {
        val parameterError = query.parameterTypes.entries.firstNotNullOfOrNull { (name, type) ->
            val value = parameters[name]
            when {
                value == null -> diagnostic(
                    "GMQL2004", "Missing parameter '\$$name'.", null, GmqlDiagnosticKind.NAME,
                )
                !value.type().assignableTo(type) -> diagnostic(
                    "GMQL3004", "Parameter '\$$name' must be $type.", null, GmqlDiagnosticKind.TYPE,
                )
                else -> null
            }
        }
        if (parameterError != null) return GmqlQueryResult(diagnostics = listOf(parameterError))
        return try {
            executeChecked(query, parameters)
        } catch (limit: GmqlLimitException) {
            GmqlQueryResult(
                diagnostics = listOf(diagnostic("GMQL5001", limit.message ?: "Execution limit exceeded.", null, GmqlDiagnosticKind.LIMIT)),
            )
        } catch (evaluation: GmqlEvaluationException) {
            GmqlQueryResult(diagnostics = listOf(evaluation.diagnostic))
        }
    }

    private fun executeChecked(query: GmqlCompiledQuery, parameters: Map<String, GmqlValue>): GmqlQueryResult {
        var bindings = listOf(RuntimeBinding(emptyMap(), IntervalSet.universal()))
        query.ast.patterns.forEach { pattern ->
            bindings = bindings.flatMap { matchPattern(pattern, it) }
            checkBindings(bindings)
        }
        query.ast.where?.let { expression ->
            bindings = bindings.mapNotNull { binding ->
                val evaluated = evaluate(expression, binding, parameters)
                evaluated.truthTime(binding.validity).takeUnless { it.isEmpty }?.let {
                    binding.copy(validity = it, score = binding.score + evaluated.score)
                }
            }
        }
        bindings = bindings.mapNotNull { applyValid(it, query.ast.valid, parameters) }

        val projected = bindings.map { binding ->
            val values = query.ast.returns.map { evaluate(it.expression, binding, parameters).project(binding.validity) }
            val aliases = query.ast.returns.mapIndexed { index, item ->
                (item.alias ?: defaultColumnName(item.expression, index)) to values[index]
            }.toMap()
            val sortValues = query.ast.orderBy.map { item ->
                if (item.expression is GmqlExpression.Variable && item.expression.name in aliases) {
                    aliases.getValue(item.expression.name)
                } else evaluate(item.expression, binding, parameters).project(binding.validity)
            }
            Projected(GmqlRow(values), sortValues)
        }
        val distinct = if (query.ast.distinct) projected.distinctBy { it.row } else projected
        val ordered = if (query.ast.orderBy.isEmpty()) distinct else distinct.sortedWith { left, right ->
            compareSort(left.sortValues, right.sortValues, query.ast.orderBy)
        }
        val offset = query.ast.offset?.let { integerValue(evaluate(it, RuntimeBinding.EMPTY, parameters).value) } ?: 0
        val requestedLimit = query.ast.limit?.let {
            integerValue(evaluate(it, RuntimeBinding.EMPTY, parameters).value)
        }
        if (offset < 0 || requestedLimit != null && requestedLimit < 0) return GmqlQueryResult(
            diagnostics = listOf(
                diagnostic("GMQL5002", "OFFSET and LIMIT must be non-negative.", null, GmqlDiagnosticKind.LIMIT),
            ),
        )
        if (requestedLimit != null && requestedLimit > options.maxResults ||
            requestedLimit == null && ordered.size > options.maxResults
        ) throw GmqlLimitException("The query exceeded the configured result limit.")
        val limit = requestedLimit ?: ordered.size
        return GmqlQueryResult(query.columns, ordered.drop(offset).take(limit).map { it.row })
    }

    private fun matchPattern(pattern: GmqlPattern, input: RuntimeBinding): List<RuntimeBinding> {
        val first = pattern.nodes.first()
        var states = nodeCandidates(first, input).mapNotNull { node ->
            tick()
            join(input, node.validTime)?.let { bindNode(it, first.variable, node.id) }?.let { PatternState(it, node.id) }
        }
        pattern.relations.forEachIndexed { index, relationPattern ->
            val nextNodePattern = pattern.nodes[index + 1]
            states = states.flatMap { state ->
                relationCandidates(state.nodeId, relationPattern).asSequence()
                    .filter { it.matches(state.nodeId, relationPattern) }
                    .flatMap { relation ->
                        relation.targets(state.nodeId, relationPattern.direction).asSequence().mapNotNull { targetId ->
                            tick()
                            val target = nodeById[targetId] ?: return@mapNotNull null
                            if (!target.matches(nextNodePattern)) return@mapNotNull null
                            var binding = join(state.binding, relation.validTime) ?: return@mapNotNull null
                            binding = join(binding, target.validTime) ?: return@mapNotNull null
                            binding = bindRelation(binding, relationPattern.variable, relation.id) ?: return@mapNotNull null
                            binding = bindNode(binding, nextNodePattern.variable, target.id) ?: return@mapNotNull null
                            PatternState(binding, target.id)
                        }
                    }.toList()
            }
            checkBindings(states.map { it.binding })
        }
        return states.map { it.binding }
    }

    private fun nodeCandidates(pattern: GmqlNodePattern, binding: RuntimeBinding): List<QueryNode> {
        val existing = pattern.variable?.let { binding.entities[it] as? Entity.Node }
        return if (existing != null) listOfNotNull(nodeById[existing.id]).filter { it.matches(pattern) }
        else if (!useIndex) graph.nodes.filter { it.matches(pattern) }
        else pattern.type?.let { type ->
            index.nodeIdsByType[NodeTypeId(type)].orEmpty().mapNotNull(nodeById::get)
        } ?: graph.nodes
    }

    private fun relationCandidates(nodeId: NodeId, pattern: GmqlRelationPattern): List<RelationAssertion> {
        if (!useIndex) return graph.relationAssertions.filter { it.matches(nodeId, pattern) }
        fun outgoing(): List<AssertionId> = pattern.type?.let {
            index.relationIdsByTypeAndSource[RelationEndpointKey(RelationTypeId(it), nodeId)]
        } ?: index.relationIdsBySource[nodeId].orEmpty()
        fun incoming(): List<AssertionId> = pattern.type?.let {
            index.relationIdsByTypeAndTarget[RelationEndpointKey(RelationTypeId(it), nodeId)]
        } ?: index.relationIdsByTarget[nodeId].orEmpty()
        val ids = when (pattern.direction) {
            RelationDirection.OUTGOING -> outgoing()
            RelationDirection.INCOMING -> incoming()
            RelationDirection.EITHER -> outgoing() + incoming()
        }
        return ids.distinct().mapNotNull(relationById::get)
    }

    private fun evaluate(
        expression: GmqlExpression,
        binding: RuntimeBinding,
        parameters: Map<String, GmqlValue>,
    ): Eval {
        tick()
        return when (expression) {
            is GmqlExpression.Literal -> Eval(expression.value, binding.validity)
            is GmqlExpression.Parameter -> Eval(parameters.getValue(expression.name), binding.validity)
            is GmqlExpression.Variable -> {
                when (val entity = binding.entities[expression.name]) {
                    is Entity.Node -> Eval(GmqlValue.NodeValue(entity.id), binding.validity)
                    is Entity.Relation -> Eval(GmqlValue.RelationValue(entity.id), binding.validity)
                    null -> Eval(GmqlValue.NullValue, IntervalSet.empty(), missing = true)
                }
            }
            is GmqlExpression.Property -> evaluateProperty(expression, binding)
            is GmqlExpression.IsTest -> {
                val operand = evaluate(expression.operand, binding, parameters)
                val time = if (expression.missing) {
                    if (operand.missing) binding.validity else binding.validity.subtract(operand.presenceTime())
                } else {
                    operand.entries().filter { it.value == GmqlValue.NullValue }
                        .fold(IntervalSet.empty()) { result, entry -> result union entry.validTime }
                }
                val selected = when {
                    !expression.negated -> time
                    expression.missing -> binding.validity.subtract(time)
                    else -> operand.presenceTime().subtract(time)
                }
                Eval(GmqlValue.BooleanValue(true), selected)
            }
            is GmqlExpression.Unary -> {
                val operand = evaluate(expression.operand, binding, parameters)
                when (expression.operator) {
                    "NOT" -> Eval(GmqlValue.BooleanValue(true), binding.validity.subtract(operand.truthTime(binding.validity)))
                    "-" -> Eval(negate(operand.value), operand.time)
                    else -> operand
                }
            }
            is GmqlExpression.Binary -> evaluateBinary(expression, binding, parameters)
            is GmqlExpression.Call -> evaluateCall(expression, binding, parameters)
        }
    }

    private fun evaluateProperty(expression: GmqlExpression.Property, binding: RuntimeBinding): Eval {
        val chain = expression.propertyChain() ?: return Eval(GmqlValue.NullValue, IntervalSet.empty(), missing = true)
        val owner = when (val entity = binding.entities[chain.first]) {
            is Entity.Node -> AssertionOwner.Node(entity.id)
            is Entity.Relation -> AssertionOwner.Relation(entity.id)
            null -> return Eval(GmqlValue.NullValue, IntervalSet.empty(), missing = true)
        }
        val path = PropertyPath(chain.second)
        val candidates = if (useIndex) {
            index.propertyIdsByOwnerAndPath[PropertyOwnerPathKey(owner, path)]
                .orEmpty()
                .asSequence()
                .mapNotNull(propertyById::get)
        } else {
            graph.propertyAssertions.asSequence()
        }
        val entries = candidates
            .mapNotNull { assertion ->
                tick()
                if (assertion.owner != owner || assertion.path != path) return@mapNotNull null
                val time = binding.validity intersect assertion.validTime
                if (time.isEmpty) null else GmqlValue.TemporalEntry(assertion.value.toGmqlValue(), time)
            }.toList()
        if (entries.isNotEmpty()) return Eval(GmqlValue.TemporalValue(entries), entries.unionTime())
        val pseudoEntries = graph.textAssertions.asSequence()
            .filter { it.owner == owner && it.matchesPseudoField(path) }
            .mapNotNull { assertion ->
                val time = binding.validity intersect assertion.validTime
                if (time.isEmpty) null else GmqlValue.TemporalEntry(GmqlValue.StringValue(assertion.text), time)
            }.toList()
        return if (pseudoEntries.isEmpty()) Eval(GmqlValue.NullValue, IntervalSet.empty(), missing = true)
        else Eval(GmqlValue.TemporalValue(pseudoEntries), pseudoEntries.unionTime())
    }

    private fun evaluateBinary(
        binary: GmqlExpression.Binary,
        binding: RuntimeBinding,
        parameters: Map<String, GmqlValue>,
    ): Eval {
        val left = evaluate(binary.left, binding, parameters)
        val right = evaluate(binary.right, binding, parameters)
        return when (binary.operator.uppercase()) {
            "AND" -> Eval(
                GmqlValue.BooleanValue(true),
                left.truthTime(binding.validity) intersect right.truthTime(binding.validity),
                left.score + right.score,
            )
            "OR" -> Eval(
                GmqlValue.BooleanValue(true),
                left.truthTime(binding.validity) union right.truthTime(binding.validity),
                maxOf(left.score, right.score),
            )
            "+", "-", "*", "/", "%" -> arithmetic(left, right, binary.operator, binary.range)
            else -> predicate(left, right, binary.operator)
        }
    }

    private fun predicate(left: Eval, right: Eval, operator: String): Eval {
        var evaluatedTime = IntervalSet.empty()
        var trueTime = IntervalSet.empty()
        left.entries().forEach { l ->
            right.entries().forEach { r ->
                val overlap = l.validTime intersect r.validTime
                if (!overlap.isEmpty) {
                    evaluatedTime = evaluatedTime union overlap
                    if (compare(l.value, r.value, operator)) trueTime = trueTime union overlap
                }
            }
        }
        if (left.value !is GmqlValue.TemporalValue && right.value !is GmqlValue.TemporalValue) {
            return Eval(
                GmqlValue.BooleanValue(!trueTime.isEmpty),
                evaluatedTime,
                left.score + right.score,
            )
        }
        val entries = buildList {
            if (!trueTime.isEmpty) add(GmqlValue.TemporalEntry(GmqlValue.BooleanValue(true), trueTime))
            val falseTime = evaluatedTime.subtract(trueTime)
            if (!falseTime.isEmpty) add(GmqlValue.TemporalEntry(GmqlValue.BooleanValue(false), falseTime))
        }
        return Eval(GmqlValue.TemporalValue(entries), evaluatedTime, left.score + right.score)
    }

    private fun arithmetic(
        left: Eval,
        right: Eval,
        operator: String,
        range: GmqlSourceRange,
    ): Eval {
        val entries = buildList {
            left.entries().forEach { l ->
                right.entries().forEach { r ->
                    val time = l.validTime intersect r.validTime
                    if (!time.isEmpty) {
                        numeric(l.value, r.value, operator, range)?.let { value ->
                            add(GmqlValue.TemporalEntry(value, time))
                        }
                    }
                }
            }
        }
        return if (entries.size == 1 && left.value !is GmqlValue.TemporalValue && right.value !is GmqlValue.TemporalValue) {
            Eval(entries.single().value, entries.single().validTime)
        } else Eval(GmqlValue.TemporalValue(entries), entries.unionTime())
    }

    private fun evaluateCall(
        call: GmqlExpression.Call,
        binding: RuntimeBinding,
        parameters: Map<String, GmqlValue>,
    ): Eval = when (call.name.uppercase()) {
        "SCORE" -> Eval(GmqlValue.DecimalValue(binding.score), binding.validity)
        "VALIDITY" -> {
            val time = call.arguments.firstOrNull()?.let { evaluate(it, binding, parameters).presenceTime() } ?: binding.validity
            Eval(GmqlValue.TemporalExtentValue(time), binding.validity)
        }
        "MATCHED_VALIDITY" -> Eval(
            GmqlValue.TemporalExtentValue(binding.matchedValidity ?: binding.validity), binding.validity,
        )
        "EXISTS" -> {
            val value = evaluate(call.arguments.first(), binding, parameters)
            Eval(GmqlValue.BooleanValue(true), value.presenceTime())
        }
        "FULLTEXT" -> evaluateFullText(call, binding, parameters)
        "TYPE_REF" -> Eval(
            GmqlValue.TypeRefValue(stringValue(evaluate(call.arguments.first(), binding, parameters).value)), binding.validity,
        )
        "REL_TYPE_REF" -> Eval(
            GmqlValue.TypeRefValue(
                stringValue(evaluate(call.arguments.first(), binding, parameters).value), relation = true,
            ),
            binding.validity,
        )
        else -> entityFunction(call, binding, parameters)
    }

    private fun entityFunction(
        call: GmqlExpression.Call,
        binding: RuntimeBinding,
        parameters: Map<String, GmqlValue>,
    ): Eval {
        val argument = evaluate(call.arguments.first(), binding, parameters).value
        val node = (argument as? GmqlValue.NodeValue)?.id?.let(nodeById::get)
        val relation = (argument as? GmqlValue.RelationValue)?.id?.let(relationById::get)
        val value = when (call.name.uppercase()) {
            "ID" -> GmqlValue.StringValue(node?.id?.value ?: relation?.stableKey?.value.orEmpty())
            "TYPE" -> GmqlValue.TypeRefValue(node?.typeId?.value ?: relation?.relTypeId?.value.orEmpty(), relation != null)
            "KIND" -> GmqlValue.StringValue(node?.kind?.name.orEmpty())
            "SOURCE" -> GmqlValue.StringValue(node?.source?.path ?: relation?.source?.path.orEmpty())
            "TITLE" -> {
                val title = node?.let {
                    graph.textAssertions.firstOrNull { text ->
                        text.owner == AssertionOwner.Node(it.id) && text.kind == TextKind.TITLE
                    }?.text
                } ?: node?.id?.value.orEmpty()
                GmqlValue.StringValue(title)
            }
            "START_NODE" -> relation?.let { GmqlValue.NodeValue(it.sourceNodeId) } ?: GmqlValue.NullValue
            "END_NODE" -> relation?.let { GmqlValue.NodeValue(it.targetNodeId) } ?: GmqlValue.NullValue
            else -> GmqlValue.NullValue
        }
        return Eval(value, binding.validity)
    }

    private fun evaluateFullText(
        call: GmqlExpression.Call,
        binding: RuntimeBinding,
        parameters: Map<String, GmqlValue>,
    ): Eval {
        val query = stringValue(evaluate(call.arguments[1], binding, parameters).value)
        val scope = call.arguments[0]
        val chain = (scope as? GmqlExpression.Property)?.propertyChain()
        val variable = chain?.first ?: (scope as? GmqlExpression.Variable)?.name
        val owner = when (val entity = variable?.let(binding.entities::get)) {
            is Entity.Node -> AssertionOwner.Node(entity.id)
            is Entity.Relation -> AssertionOwner.Relation(entity.id)
            null -> return Eval(GmqlValue.BooleanValue(true), IntervalSet.empty())
        }
        val path = chain?.second?.let(::PropertyPath)
        var time = IntervalSet.empty()
        var score = 0.0
        val candidates = if (useIndex) {
            index.textAssertionIdsByOwner[owner].orEmpty().asSequence().mapNotNull(textById::get)
        } else {
            graph.textAssertions.asSequence().filter { it.owner == owner }
        }
        candidates
            .filter {
                path == null ||
                    it.propertyPath == path ||
                    it.propertyPath?.segments?.take(path.segments.size) == path.segments ||
                    it.matchesPseudoField(path)
            }
            .forEach { assertion ->
                tick()
                val assertionScore = textQueryScore(assertion.text, query)
                if (assertionScore > 0) {
                    val overlap = binding.validity intersect assertion.validTime
                    if (!overlap.isEmpty) {
                        time = time union overlap
                        score = maxOf(score, assertionScore)
                    }
                }
            }
        return Eval(GmqlValue.BooleanValue(true), time, score)
    }

    private fun applyValid(
        binding: RuntimeBinding,
        valid: GmqlValid?,
        parameters: Map<String, GmqlValue>,
    ): RuntimeBinding? {
        if (valid == null) return binding.copy(matchedValidity = binding.validity)
        if (valid.operator == GmqlValidOperator.ANYTIME) {
            val filtered = valid.timeline?.let { timeline ->
                val canonical = graph.timelineCatalog.normalize(TimelineId(timeline), null, null).timelineId
                if (binding.validity.isUniversal) IntervalSet.empty()
                else IntervalSet.of(binding.validity.intervals.filter { it.timelineId == canonical })
            } ?: binding.validity
            return filtered.takeUnless { it.isEmpty }?.let { binding.copy(validity = it, matchedValidity = it) }
        }
        val timeline = TimelineId(checkNotNull(valid.timeline))
        val canonical = graph.timelineCatalog.normalize(timeline, null, null).timelineId
        val scopedValidity = if (binding.validity.isUniversal) {
            IntervalSet.empty()
        } else {
            IntervalSet.of(binding.validity.intervals.filter { it.timelineId == canonical })
        }
        if (scopedValidity.isEmpty) return null
        val window = when (valid.operator) {
            GmqlValidOperator.AT -> {
                val expression = checkNotNull(valid.instant)
                val instant = finiteTemporalBoundary(evaluate(expression, binding, parameters), expression.range)
                IntervalSet.of(graph.timelineCatalog.normalize(
                    timeline, IntervalBoundary(instant, true), IntervalBoundary(instant, true),
                ))
            }
            else -> {
                val interval = checkNotNull(valid.interval)
                val start = interval.start?.let {
                    IntervalBoundary(
                        finiteTemporalBoundary(evaluate(it, binding, parameters), it.range),
                        interval.includeStart,
                    )
                }
                val end = interval.end?.let {
                    IntervalBoundary(
                        finiteTemporalBoundary(evaluate(it, binding, parameters), it.range),
                        interval.includeEnd,
                    )
                }
                if (start != null && end != null && TemporalInterval.isEmpty(start, end)) return null
                IntervalSet.of(graph.timelineCatalog.normalize(timeline, start, end))
            }
        }
        val matched = scopedValidity intersect window
        val accepts = when (valid.operator) {
            GmqlValidOperator.AT, GmqlValidOperator.OVERLAPS -> !matched.isEmpty
            GmqlValidOperator.CONTAINS -> scopedValidity.contains(window)
            GmqlValidOperator.DURING -> !scopedValidity.isEmpty && window.contains(scopedValidity)
            GmqlValidOperator.ANYTIME -> true
        }
        return if (accepts) binding.copy(validity = scopedValidity, matchedValidity = matched) else null
    }

    private fun finiteTemporalBoundary(evaluated: Eval, range: GmqlSourceRange): Double {
        val value = decimalValue(evaluated.value)
        if (!value.isFinite()) {
            throw GmqlEvaluationException(
                diagnostic(
                    "GMQL4003",
                    "Temporal boundary must evaluate to a finite Decimal.",
                    range,
                    GmqlDiagnosticKind.TEMPORAL,
                ),
            )
        }
        return value
    }

    private fun tick() {
        operations++
        if (operations > options.maxOperations) throw GmqlLimitException("The query exceeded the operation limit.")
    }
    private fun checkBindings(bindings: List<RuntimeBinding>) {
        if (bindings.size > options.maxIntermediateBindings) {
            throw GmqlLimitException("The query exceeded the intermediate binding limit.")
        }
    }
}

internal data class GmqlPhysicalPlan(
    val query: GmqlCompiledQuery,
    val indexed: Boolean,
    val indexes: Set<String>,
)

internal object GmqlPlanner {
    fun plan(query: GmqlCompiledQuery, indexed: Boolean): GmqlPhysicalPlan {
        val indexes = if (!indexed) emptySet() else buildSet {
            if (query.ast.patterns.any { pattern -> pattern.nodes.any { it.type != null } }) add("node-type")
            if (query.ast.patterns.any { it.relations.isNotEmpty() }) add("relation-endpoint")
            if (query.ast.where?.containsPropertyAccess() == true) add("property")
            if (query.ast.where?.containsCall("FULLTEXT") == true) add("fulltext")
            if (query.ast.valid != null) add("interval")
        }
        return GmqlPhysicalPlan(query, indexed, indexes)
    }
}

private sealed interface Entity {
    data class Node(val id: NodeId) : Entity
    data class Relation(val id: AssertionId) : Entity
}
private data class RuntimeBinding(
    val entities: Map<String, Entity>,
    val validity: IntervalSet,
    val matchedValidity: IntervalSet? = null,
    val score: Double = 0.0,
) {
    companion object { val EMPTY = RuntimeBinding(emptyMap(), IntervalSet.universal()) }
}
private data class PatternState(val binding: RuntimeBinding, val nodeId: NodeId)
private data class Projected(val row: GmqlRow, val sortValues: List<GmqlValue>)
private data class Eval(
    val value: GmqlValue,
    val time: IntervalSet,
    val score: Double = 0.0,
    val missing: Boolean = false,
) {
    fun entries(): List<GmqlValue.TemporalEntry> = when (value) {
        is GmqlValue.TemporalValue -> value.entries
        else -> listOf(GmqlValue.TemporalEntry(value, time))
    }
    fun presenceTime(): IntervalSet = if (missing) IntervalSet.empty() else entries().unionTime()
    fun truthTime(bindingTime: IntervalSet): IntervalSet = when (value) {
        is GmqlValue.BooleanValue -> if (value.value) time else IntervalSet.empty()
        is GmqlValue.TemporalValue -> entries().filter { it.value == GmqlValue.BooleanValue(true) }.unionTime()
        else -> IntervalSet.empty()
    } intersect bindingTime
    fun project(bindingTime: IntervalSet): GmqlValue = when (value) {
        is GmqlValue.TemporalValue -> {
            val clipped = value.entries.mapNotNull {
                val overlap = it.validTime intersect bindingTime
                if (overlap.isEmpty) null else it.copy(validTime = overlap)
            }
            GmqlValue.TemporalValue(
                clipped.groupBy { it.value }.map { (entryValue, entries) ->
                    GmqlValue.TemporalEntry(entryValue, entries.unionTime())
                },
            )
        }
        else -> value
    }
}
private class GmqlLimitException(message: String) : IllegalStateException(message)
private class GmqlEvaluationException(val diagnostic: GmqlDiagnostic) : IllegalStateException(diagnostic.message)

private fun QueryNode.matches(pattern: GmqlNodePattern): Boolean =
    pattern.type == null || typeId == NodeTypeId(pattern.type) || NodeTypeId(pattern.type) in ancestorTypeIds

private fun RelationAssertion.matches(nodeId: NodeId, pattern: GmqlRelationPattern): Boolean =
    (pattern.type == null || relTypeId == RelationTypeId(pattern.type) || RelationTypeId(pattern.type) in ancestorRelTypeIds) &&
        when (pattern.direction) {
            RelationDirection.OUTGOING -> sourceNodeId == nodeId
            RelationDirection.INCOMING -> targetNodeId == nodeId
            RelationDirection.EITHER -> sourceNodeId == nodeId || targetNodeId == nodeId
        }

private fun RelationAssertion.targets(nodeId: NodeId, direction: RelationDirection): Set<NodeId> = when (direction) {
    RelationDirection.OUTGOING -> setOf(targetNodeId)
    RelationDirection.INCOMING -> setOf(sourceNodeId)
    RelationDirection.EITHER -> buildSet {
        if (sourceNodeId == nodeId) add(targetNodeId)
        if (targetNodeId == nodeId) add(sourceNodeId)
    }
}

private fun TextAssertion.matchesPseudoField(path: PropertyPath): Boolean = when (path.segments) {
    listOf("title") -> kind == TextKind.TITLE
    listOf("body") -> kind in setOf(TextKind.HEADING, TextKind.PARAGRAPH, TextKind.CODE)
    listOf("label") -> kind == TextKind.RELATION_LABEL
    else -> false
}

private fun join(binding: RuntimeBinding, time: IntervalSet): RuntimeBinding? {
    val joined = binding.validity intersect time
    return joined.takeUnless { it.isEmpty }?.let { binding.copy(validity = it) }
}
private fun bindNode(binding: RuntimeBinding, name: String?, id: NodeId): RuntimeBinding? {
    if (name == null) return binding
    val existing = binding.entities[name]
    return if (existing != null && existing != Entity.Node(id)) null
    else binding.copy(entities = binding.entities + (name to Entity.Node(id)))
}
private fun bindRelation(binding: RuntimeBinding, name: String?, id: AssertionId): RuntimeBinding? {
    if (name == null) return binding
    val existing = binding.entities[name]
    return if (existing != null && existing != Entity.Relation(id)) null
    else binding.copy(entities = binding.entities + (name to Entity.Relation(id)))
}

private fun GmqlExpression.Property.propertyChain(): Pair<String, List<String>>? {
    val segments = mutableListOf(name)
    var current = receiver
    while (current is GmqlExpression.Property) {
        segments += current.name
        current = current.receiver
    }
    val variable = current as? GmqlExpression.Variable ?: return null
    return variable.name to segments.asReversed()
}

private fun GmqlExpression.containsPropertyAccess(): Boolean = when (this) {
    is GmqlExpression.Property -> true
    is GmqlExpression.Binary -> left.containsPropertyAccess() || right.containsPropertyAccess()
    is GmqlExpression.Unary -> operand.containsPropertyAccess()
    is GmqlExpression.IsTest -> operand.containsPropertyAccess()
    is GmqlExpression.Call -> arguments.any(GmqlExpression::containsPropertyAccess)
    else -> false
}

private fun GmqlExpression.containsCall(target: String): Boolean = when (this) {
    is GmqlExpression.Call -> name.equals(target, true) || arguments.any { it.containsCall(target) }
    is GmqlExpression.Binary -> left.containsCall(target) || right.containsCall(target)
    is GmqlExpression.Unary -> operand.containsCall(target)
    is GmqlExpression.IsTest -> operand.containsCall(target)
    is GmqlExpression.Property -> receiver.containsCall(target)
    else -> false
}

private fun ResolvedPropSchema.toGmqlType(): GmqlType = when (type) {
    PropType.string -> GmqlType.String
    PropType.text -> GmqlType.Text
    PropType.number -> GmqlType.Decimal
    PropType.instant, PropType.duration -> GmqlType.Any
    PropType.array -> GmqlType.Collection(items?.toGmqlType() ?: GmqlType.Any)
}

internal fun GmqlValue.type(): GmqlType = when (this) {
    is GmqlValue.StringValue -> GmqlType.String
    is GmqlValue.IntegerValue -> GmqlType.Integer
    is GmqlValue.DecimalValue -> GmqlType.Decimal
    is GmqlValue.BooleanValue -> GmqlType.Boolean
    GmqlValue.NullValue -> GmqlType.Null
    is GmqlValue.NodeValue -> GmqlType.Node
    is GmqlValue.RelationValue -> GmqlType.Relation
    is GmqlValue.TypeRefValue -> GmqlType.TypeRef
    is GmqlValue.CollectionValue -> GmqlType.Collection(values.firstOrNull()?.type() ?: GmqlType.Any)
    is GmqlValue.TemporalValue -> GmqlType.Temporal(entries.firstOrNull()?.value?.type() ?: GmqlType.Any)
    is GmqlValue.TemporalExtentValue -> GmqlType.TemporalExtent
}

private fun NormalizedValue.toGmqlValue(): GmqlValue = when (this) {
    is StringValue -> GmqlValue.StringValue(value)
    is IntegerValue -> GmqlValue.IntegerValue(value)
    is NumberValue -> GmqlValue.DecimalValue(value)
    is BooleanValue -> GmqlValue.BooleanValue(value)
    NullValue -> GmqlValue.NullValue
    is ArrayValue -> GmqlValue.CollectionValue(values.map { it.toGmqlValue() })
    is TextValue -> GmqlValue.CollectionValue(values.entries.map {
        GmqlValue.CollectionValue(listOf(GmqlValue.StringValue(it.key), GmqlValue.StringValue(it.value)))
    })
    is ObjectValue -> GmqlValue.CollectionValue(values.entries.map {
        GmqlValue.CollectionValue(listOf(GmqlValue.StringValue(it.key), it.value.toGmqlValue()))
    })
    is InstantValue -> GmqlValue.DecimalValue((timecode as NumberTimecode).value)
    is DurationValue -> GmqlValue.CollectionValue(
        listOfNotNull(from?.let { GmqlValue.DecimalValue(it.timecode) }, to?.let { GmqlValue.DecimalValue(it.timecode) }),
    )
}

private fun List<GmqlValue.TemporalEntry>.unionTime(): IntervalSet =
    fold(IntervalSet.empty()) { result, entry -> result union entry.validTime }

private fun GmqlType.unwrapTemporal(): GmqlType = if (this is GmqlType.Temporal) valueType else this
private fun GmqlType.isNumeric() = unwrapTemporal() == GmqlType.Integer || unwrapTemporal() == GmqlType.Decimal
private fun GmqlType.isOrderable() =
    this == GmqlType.String || this == GmqlType.Integer || this == GmqlType.Decimal || this == GmqlType.Boolean
private fun GmqlType.assignableTo(target: GmqlType) =
    target == GmqlType.Any || this == target || this == GmqlType.Integer && target == GmqlType.Decimal

private fun defaultColumnName(expression: GmqlExpression, index: Int): String = when (expression) {
    is GmqlExpression.Variable -> expression.name
    is GmqlExpression.Property -> expression.name
    is GmqlExpression.Call -> expression.name.lowercase()
    else -> "column${index + 1}"
}

private fun diagnostic(
    code: String,
    message: String,
    range: GmqlSourceRange?,
    kind: GmqlDiagnosticKind,
) = GmqlDiagnostic(code, message, range, kind)

private fun stringValue(value: GmqlValue): String = (value as? GmqlValue.StringValue)?.value.orEmpty()
private fun integerValue(value: GmqlValue): Int = when (value) {
    is GmqlValue.IntegerValue -> value.value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    else -> 0
}
private fun decimalValue(value: GmqlValue): Double = when (value) {
    is GmqlValue.IntegerValue -> value.value.toDouble()
    is GmqlValue.DecimalValue -> value.value
    else -> Double.NaN
}
private fun negate(value: GmqlValue): GmqlValue = when (value) {
    is GmqlValue.IntegerValue -> GmqlValue.IntegerValue(-value.value)
    is GmqlValue.DecimalValue -> GmqlValue.DecimalValue(-value.value)
    else -> GmqlValue.NullValue
}

private fun numeric(
    left: GmqlValue,
    right: GmqlValue,
    operator: String,
    range: GmqlSourceRange,
): GmqlValue? {
    if (left is GmqlValue.IntegerValue && right is GmqlValue.IntegerValue && operator != "/") {
        return when (operator) {
            "+" -> GmqlValue.IntegerValue(left.value + right.value)
            "-" -> GmqlValue.IntegerValue(left.value - right.value)
            "*" -> GmqlValue.IntegerValue(left.value * right.value)
            "%" -> if (right.value == 0L) null else GmqlValue.IntegerValue(left.value % right.value)
            else -> null
        }
    }
    val l = decimalValue(left)
    val r = decimalValue(right)
    if (!l.isFinite() || !r.isFinite()) return null
    val result = when (operator) {
        "+" -> l + r
        "-" -> l - r
        "*" -> l * r
        "/" -> if (r == 0.0) return null else l / r
        "%" -> if (r == 0.0) return null else l % r
        else -> return null
    }
    if (!result.isFinite()) {
        throw GmqlEvaluationException(
            diagnostic(
                "GMQL5003",
                "Numeric expression must evaluate to a finite Decimal.",
                range,
                GmqlDiagnosticKind.TYPE,
            ),
        )
    }
    return GmqlValue.DecimalValue(result)
}

private fun compare(left: GmqlValue, right: GmqlValue, operator: String): Boolean {
    if (left == GmqlValue.NullValue || right == GmqlValue.NullValue) return false
    val comparison = scalarCompare(left, right)
    return when (operator.uppercase()) {
        "=" -> left == right || comparison == 0
        "!=" -> left != right && comparison != 0
        "<" -> comparison?.let { it < 0 } == true
        "<=" -> comparison?.let { it <= 0 } == true
        ">" -> comparison?.let { it > 0 } == true
        ">=" -> comparison?.let { it >= 0 } == true
        "STARTS WITH" -> stringValue(left).startsWith(stringValue(right))
        "ENDS WITH" -> stringValue(left).endsWith(stringValue(right))
        "CONTAINS" -> when (left) {
            is GmqlValue.StringValue -> stringValue(right) in left.value
            is GmqlValue.CollectionValue -> right in left.values
            else -> false
        }
        "IN" -> left in ((right as? GmqlValue.CollectionValue)?.values.orEmpty())
        else -> false
    }
}

private fun scalarCompare(left: GmqlValue, right: GmqlValue): Int? = when {
    left is GmqlValue.StringValue && right is GmqlValue.StringValue -> left.value.compareTo(right.value)
    left is GmqlValue.IntegerValue && right is GmqlValue.IntegerValue -> left.value.compareTo(right.value)
    left is GmqlValue.IntegerValue && right is GmqlValue.DecimalValue ->
        compareLongToDouble(left.value, right.value)
    left is GmqlValue.DecimalValue && right is GmqlValue.IntegerValue ->
        -compareLongToDouble(right.value, left.value)
    left is GmqlValue.DecimalValue && right is GmqlValue.DecimalValue -> left.value.compareTo(right.value)
    left is GmqlValue.BooleanValue && right is GmqlValue.BooleanValue -> left.value.compareTo(right.value)
    left is GmqlValue.TypeRefValue && right is GmqlValue.TypeRefValue ->
        if (left.relation == right.relation) left.name.compareTo(right.name) else null
    else -> null
}

private fun compareSort(
    left: List<GmqlValue>,
    right: List<GmqlValue>,
    order: List<GmqlOrderItem>,
): Int {
    order.indices.forEach { index ->
        val l = left[index]
        val r = right[index]
        if (l == GmqlValue.NullValue && r != GmqlValue.NullValue) return 1
        if (r == GmqlValue.NullValue && l != GmqlValue.NullValue) return -1
        val comparison = scalarCompare(l, r) ?: 0
        if (comparison != 0) return if (order[index].ascending) comparison else -comparison
    }
    return 0
}

private fun textQueryScore(text: String, query: String): Double {
    val clauses = splitTextOr(query)
    return clauses.maxOfOrNull { clause ->
        val terms = parseTextAnd(clause)
        if (terms.isEmpty()) 0.0 else {
            val analyzed = TextAnalyzer.analyze(text)
            if (terms.all { term ->
                    when (term.kind) {
                        TextTermKind.PHRASE -> term.value.lowercase() in analyzed.normalized
                        TextTermKind.PREFIX -> analyzed.terms.any { it.startsWith(term.value.lowercase()) }
                        TextTermKind.TERM -> TextAnalyzer.analyze(term.value).terms.all { it in analyzed.terms }
                    }
                }
            ) terms.sumOf { term ->
                when (term.kind) {
                    TextTermKind.PHRASE -> 1.0
                    TextTermKind.PREFIX -> analyzed.terms.count { it.startsWith(term.value.lowercase()) }.toDouble()
                    TextTermKind.TERM -> TextAnalyzer.scanScore(text, TextPredicate(term.value, TextMatchMode.ALL_TERMS))
                }
            } else 0.0
        }
    } ?: 0.0
}
private enum class TextTermKind { TERM, PHRASE, PREFIX }
private data class TextTerm(val value: String, val kind: TextTermKind)
private fun splitTextOr(query: String): List<String> {
    val clauses = mutableListOf<String>()
    var quoted = false
    var start = 0
    var index = 0
    while (index < query.length) {
        if (query[index] == '"') quoted = !quoted
        if (!quoted && query.regionMatches(index, "OR", 0, 2, ignoreCase = true) &&
            query.getOrNull(index - 1)?.isWhitespace() == true &&
            query.getOrNull(index + 2)?.isWhitespace() == true
        ) {
            query.substring(start, index).trim().takeIf(String::isNotEmpty)?.let(clauses::add)
            index += 2
            start = index
        } else {
            index++
        }
    }
    query.substring(start).trim().takeIf(String::isNotEmpty)?.let(clauses::add)
    return clauses
}
private fun parseTextAnd(query: String): List<TextTerm> {
    val result = mutableListOf<TextTerm>()
    val regex = Regex(""""([^"]+)"|(\S+)""")
    regex.findAll(query).forEach { match ->
        val phrase = match.groups[1]?.value
        val raw = phrase ?: match.groups[2]!!.value
        result += TextTerm(
            raw.removeSuffix("*"),
            when {
                phrase != null -> TextTermKind.PHRASE
                raw.endsWith("*") -> TextTermKind.PREFIX
                else -> TextTermKind.TERM
            },
        )
    }
    return result
}
