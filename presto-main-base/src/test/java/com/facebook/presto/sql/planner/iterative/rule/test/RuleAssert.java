/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule.test;

import com.facebook.presto.Session;
import com.facebook.presto.cost.CachingCostProvider;
import com.facebook.presto.cost.CachingStatsProvider;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.CostProvider;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsAndCosts;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.cost.StatsProvider;
import com.facebook.presto.matching.Match;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.LogicalProperties;
import com.facebook.presto.spi.plan.LogicalPropertiesProvider;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.security.AccessControl;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.assertions.PlanMatchPattern;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.iterative.Memo;
import com.facebook.presto.sql.planner.iterative.PlanNodeMatcher;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.iterative.properties.LogicalPropertiesImpl;
import com.facebook.presto.sql.planner.iterative.properties.LogicalPropertiesProviderImpl;
import com.facebook.presto.sql.relational.FunctionResolution;
import com.facebook.presto.transaction.TransactionManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.facebook.presto.SystemSessionProperties.isVerboseOptimizerInfoEnabled;
import static com.facebook.presto.sql.planner.assertions.PlanAssert.assertPlan;
import static com.facebook.presto.sql.planner.planPrinter.PlanPrinter.textLogicalPlan;
import static com.facebook.presto.transaction.TransactionBuilder.transaction;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.fail;

public class RuleAssert
{
    private final Metadata metadata;
    private final TestingStatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final Rule<?> rule;
    private final PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
    private final TransactionManager transactionManager;
    private final AccessControl accessControl;
    private final List<String> extraCatalogs;

    private Session session;
    private TypeProvider types;
    private PlanNode plan;
    private Optional<LogicalPropertiesProvider> logicalPropertiesProvider;

    public RuleAssert(Metadata metadata, StatsCalculator statsCalculator, CostCalculator costCalculator, Session session, Rule rule, TransactionManager transactionManager, AccessControl accessControl)
    {
        this(metadata, statsCalculator, costCalculator, session, rule, transactionManager, accessControl, Optional.empty(), ImmutableList.of());
    }

    public RuleAssert(Metadata metadata, StatsCalculator statsCalculator, CostCalculator costCalculator, Session session, Rule rule,
                      TransactionManager transactionManager, AccessControl accessControl, Optional<LogicalPropertiesProvider> logicalPropertiesProvider, List<String> extraCatalogs)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.statsCalculator = new TestingStatsCalculator(requireNonNull(statsCalculator, "statsCalculator is null"));
        this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
        this.session = requireNonNull(session, "session is null");
        this.rule = requireNonNull(rule, "rule is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.logicalPropertiesProvider = requireNonNull(logicalPropertiesProvider, "logicalPropertiesProvider is null");
        this.extraCatalogs = requireNonNull(extraCatalogs, "extraCatalogs is null");
    }

    public RuleAssert setSystemProperty(String key, String value)
    {
        return withSession(Session.builder(session)
                .setSystemProperty(key, value)
                .build());
    }

    public RuleAssert withSession(Session session)
    {
        this.session = session;
        return this;
    }

    public RuleAssert overrideStats(String nodeId, PlanNodeStatsEstimate nodeStats)
    {
        statsCalculator.setNodeStats(new PlanNodeId(nodeId), nodeStats);
        return this;
    }

    public RuleAssert on(Function<PlanBuilder, PlanNode> planProvider)
    {
        checkState(plan == null, "plan has already been set");

        PlanBuilder builder = new PlanBuilder(session, idAllocator, metadata);
        plan = planProvider.apply(builder);
        types = builder.getTypes();
        return this;
    }

    public PlanNode get()
    {
        RuleApplication ruleApplication = applyRule();
        TypeProvider types = ruleApplication.types;

        if (!ruleApplication.wasRuleApplied()) {
            fail(String.format(
                    "%s did not fire for:\n%s",
                    rule.getClass().getName(),
                    formatPlan(plan, types)));
        }

        return ruleApplication.getTransformedPlan();
    }

    public void doesNotFire()
    {
        RuleApplication ruleApplication = applyRule();

        if (ruleApplication.wasRuleApplied()) {
            fail(String.format(
                    "Expected %s to not fire for:\n%s",
                    rule.getClass().getName(),
                    inTransaction(session -> textLogicalPlan(plan, ruleApplication.types, StatsAndCosts.empty(), metadata.getFunctionAndTypeManager(), session, 2))));
        }
    }

    public void matches(PlanMatchPattern pattern)
    {
        RuleApplication ruleApplication = applyRule();
        TypeProvider types = ruleApplication.types;

        if (!ruleApplication.wasRuleApplied()) {
            fail(String.format(
                    "%s did not fire for:\n%s",
                    rule.getClass().getName(),
                    formatPlan(plan, types)));
        }

        PlanNode actual = ruleApplication.getTransformedPlan();

        if (actual == plan) { // plans are not comparable, so we can only ensure they are not the same instance
            fail(String.format(
                    "%s: rule fired but return the original plan:\n%s",
                    rule.getClass().getName(),
                    formatPlan(plan, types)));
        }

        if (!ImmutableSet.copyOf(plan.getOutputVariables()).equals(ImmutableSet.copyOf(actual.getOutputVariables()))) {
            fail(String.format(
                    "%s: output schema of transformed and original plans are not equivalent\n" +
                            "\texpected: %s\n" +
                            "\tactual:   %s",
                    rule.getClass().getName(),
                    plan.getOutputVariables(),
                    actual.getOutputVariables()));
        }

        inTransaction(session -> {
            assertPlan(session, metadata, ruleApplication.statsProvider, new Plan(actual, types, StatsAndCosts.empty()), ruleApplication.lookup, pattern, planNode -> planNode);
            return null;
        });
    }

    public void matches(LogicalProperties expectedLogicalProperties)
    {
        RuleApplication ruleApplication = applyRule();
        TypeProvider types = ruleApplication.types;

        if (!ruleApplication.wasRuleApplied()) {
            fail(String.format(
                    "%s did not fire for:\n%s",
                    rule.getClass().getName(),
                    formatPlan(plan, types)));
        }

        // ensure that the logical properties of the root group are equivalent to the expected logical properties
        LogicalProperties rootNodeLogicalProperties = ruleApplication.getMemo().getLogicalProperties(ruleApplication.getMemo().getRootGroup()).get();
        if (!((LogicalPropertiesImpl) rootNodeLogicalProperties).equals((LogicalPropertiesImpl) expectedLogicalProperties)) {
            fail(String.format(
                    "Logical properties of root node doesn't match expected logical properties\n" +
                            "\texpected: %s\n" +
                            "\tactual:   %s",
                    expectedLogicalProperties,
                    rootNodeLogicalProperties));
        }
    }

    private RuleApplication applyRule()
    {
        VariableAllocator variableAllocator = new VariableAllocator(types.allVariables());
        Memo memo = new Memo(idAllocator, plan, logicalPropertiesProvider);
        Lookup lookup = Lookup.from(planNode -> Stream.of(memo.resolve(planNode)));

        PlanNode memoRoot = memo.getNode(memo.getRootGroup());

        return inTransaction(session -> applyRule(rule, memoRoot, ruleContext(statsCalculator, costCalculator, variableAllocator, memo, lookup, session), memo));
    }

    private static <T> RuleApplication applyRule(Rule<T> rule, PlanNode planNode, Rule.Context context, Memo memo)
    {
        PlanNodeMatcher matcher = new PlanNodeMatcher(context.getLookup());
        Match<T> match = matcher.match(rule.getPattern(), planNode);

        Rule.Result result;
        if (!rule.isEnabled(context.getSession()) || match.isEmpty()) {
            result = Rule.Result.empty();
        }
        else {
            result = rule.apply(match.value(), match.captures(), context);
        }

        return new RuleApplication(context.getLookup(), context.getStatsProvider(), TypeProvider.viewOf(context.getVariableAllocator().getVariables()), memo, result);
    }

    private String formatPlan(PlanNode plan, TypeProvider types)
    {
        StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, session, types);
        CostProvider costProvider = new CachingCostProvider(costCalculator, statsProvider, session);
        return inTransaction(session -> textLogicalPlan(plan, types, StatsAndCosts.create(plan, statsProvider, costProvider, session), metadata.getFunctionAndTypeManager(), session, 2, false, isVerboseOptimizerInfoEnabled(session)));
    }

    private <T> T inTransaction(Function<Session, T> transactionSessionConsumer)
    {
        return transaction(transactionManager, accessControl)
                .singleStatement()
                .execute(session, session -> {
                    // metadata.getCatalogHandle() registers the catalog for the transaction
                    session.getCatalog().ifPresent(catalog -> metadata.getCatalogHandle(session, catalog));
                    extraCatalogs.forEach(catalog -> metadata.getCatalogHandle(session, catalog));
                    return transactionSessionConsumer.apply(session);
                });
    }

    private Rule.Context ruleContext(StatsCalculator statsCalculator, CostCalculator costCalculator, VariableAllocator variableAllocator, Memo memo, Lookup lookup, Session session)
    {
        StatsProvider statsProvider = new CachingStatsProvider(statsCalculator, Optional.of(memo), lookup, session, TypeProvider.viewOf(variableAllocator.getVariables()));
        CostProvider costProvider = new CachingCostProvider(costCalculator, statsProvider, Optional.of(memo), session);
        LogicalPropertiesProvider logicalPropertiesProvider = new LogicalPropertiesProviderImpl(new FunctionResolution(metadata.getFunctionAndTypeManager().getFunctionAndTypeResolver()));

        return new Rule.Context()
        {
            @Override
            public Lookup getLookup()
            {
                return lookup;
            }

            @Override
            public PlanNodeIdAllocator getIdAllocator()
            {
                return idAllocator;
            }

            @Override
            public VariableAllocator getVariableAllocator()
            {
                return variableAllocator;
            }

            @Override
            public Session getSession()
            {
                return session;
            }

            @Override
            public StatsProvider getStatsProvider()
            {
                return statsProvider;
            }

            @Override
            public CostProvider getCostProvider()
            {
                return costProvider;
            }

            @Override
            public void checkTimeoutNotExhausted() {}

            @Override
            public WarningCollector getWarningCollector()
            {
                return WarningCollector.NOOP;
            }

            @Override
            public Optional<LogicalPropertiesProvider> getLogicalPropertiesProvider()
            {
                return Optional.of(logicalPropertiesProvider);
            }
        };
    }

    private static class RuleApplication
    {
        private final Lookup lookup;
        private final StatsProvider statsProvider;
        private final TypeProvider types;
        private final Rule.Result result;
        private final Memo memo;

        public RuleApplication(Lookup lookup, StatsProvider statsProvider, TypeProvider types, Memo memo, Rule.Result result)
        {
            this.lookup = requireNonNull(lookup, "lookup is null");
            this.statsProvider = requireNonNull(statsProvider, "statsProvider is null");
            this.types = requireNonNull(types, "types is null");
            this.result = requireNonNull(result, "result is null");
            this.memo = requireNonNull(memo, "memo is null");
        }

        private boolean wasRuleApplied()
        {
            return !result.isEmpty();
        }

        public PlanNode getTransformedPlan()
        {
            return result.getTransformedPlan().orElseThrow(() -> new IllegalStateException("Rule did not produce transformed plan"));
        }

        private Memo getMemo()
        {
            return memo;
        }
    }

    public static class TestingStatsCalculator
            implements StatsCalculator
    {
        private final StatsCalculator delegate;
        private final Map<PlanNodeId, PlanNodeStatsEstimate> stats = new HashMap<>();

        public TestingStatsCalculator(StatsCalculator delegate)
        {
            this.delegate = requireNonNull(delegate, "delegate is null");
        }

        @Override
        public PlanNodeStatsEstimate calculateStats(PlanNode node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types)
        {
            if (stats.containsKey(node.getId())) {
                return stats.get(node.getId());
            }
            return delegate.calculateStats(node, sourceStats, lookup, session, types);
        }

        public void setNodeStats(PlanNodeId nodeId, PlanNodeStatsEstimate nodeStats)
        {
            stats.put(nodeId, nodeStats);
        }
    }
}
