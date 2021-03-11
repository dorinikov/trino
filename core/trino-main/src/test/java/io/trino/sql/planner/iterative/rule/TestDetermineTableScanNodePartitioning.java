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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.Session;
import io.trino.connector.CatalogName;
import io.trino.cost.StatsProvider;
import io.trino.metadata.InMemoryNodeManager;
import io.trino.metadata.Metadata;
import io.trino.metadata.TableHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.predicate.TupleDomain;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.TestTableScanNodePartitioning.TestPartitioningProvider;
import io.trino.sql.planner.assertions.MatchResult;
import io.trino.sql.planner.assertions.Matcher;
import io.trino.sql.planner.assertions.SymbolAliases;
import io.trino.sql.planner.iterative.rule.test.RuleTester;
import io.trino.sql.planner.plan.PlanNode;
import io.trino.sql.planner.plan.TableScanNode;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.google.common.base.Predicates.equalTo;
import static io.airlift.testing.Closeables.closeAllRuntimeException;
import static io.trino.sql.planner.TestTableScanNodePartitioning.COLUMN_A;
import static io.trino.sql.planner.TestTableScanNodePartitioning.COLUMN_B;
import static io.trino.sql.planner.TestTableScanNodePartitioning.COLUMN_HANDLE_A;
import static io.trino.sql.planner.TestTableScanNodePartitioning.COLUMN_HANDLE_B;
import static io.trino.sql.planner.TestTableScanNodePartitioning.CONNECTOR_FIXED_PARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.CONNECTOR_PARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.CONNECTOR_UNPARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.DISABLE_PLAN_WITH_TABLE_NODE_PARTITIONING;
import static io.trino.sql.planner.TestTableScanNodePartitioning.ENABLE_PLAN_WITH_TABLE_NODE_PARTITIONING;
import static io.trino.sql.planner.TestTableScanNodePartitioning.FIXED_PARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.MOCK_CATALOG;
import static io.trino.sql.planner.TestTableScanNodePartitioning.PARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.UNPARTITIONED_TABLE_HANDLE;
import static io.trino.sql.planner.TestTableScanNodePartitioning.createMockFactory;
import static io.trino.sql.planner.assertions.MatchResult.NO_MATCH;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableScan;
import static io.trino.sql.planner.iterative.rule.test.RuleTester.defaultRuleTester;

public class TestDetermineTableScanNodePartitioning
{
    private RuleTester tester;

    @BeforeClass
    public void setUp()
    {
        tester = defaultRuleTester();
        tester.getQueryRunner().createCatalog(MOCK_CATALOG, createMockFactory(), ImmutableMap.of());
        tester.getQueryRunner().getNodePartitioningManager().addPartitioningProvider(
                new CatalogName(MOCK_CATALOG),
                new TestPartitioningProvider(new InMemoryNodeManager()));
    }

    @AfterClass(alwaysRun = true)
    public void tearDown()
    {
        closeAllRuntimeException(tester);
        tester = null;
    }

    @Test
    public void testEnablePlanWithTableNodePartitioning()
    {
        testPlanWithTableNodePartitioning(
                ENABLE_PLAN_WITH_TABLE_NODE_PARTITIONING,
                PARTITIONED_TABLE_HANDLE,
                CONNECTOR_PARTITIONED_TABLE_HANDLE,
                true);
    }

    @Test
    public void testDisablePlanWithTableNodePartitioning()
    {
        testPlanWithTableNodePartitioning(
                DISABLE_PLAN_WITH_TABLE_NODE_PARTITIONING,
                PARTITIONED_TABLE_HANDLE,
                CONNECTOR_PARTITIONED_TABLE_HANDLE,
                false);
    }

    @Test
    public void testTableScanWithoutConnectorPartitioning()
    {
        testPlanWithTableNodePartitioning(
                ENABLE_PLAN_WITH_TABLE_NODE_PARTITIONING,
                UNPARTITIONED_TABLE_HANDLE,
                CONNECTOR_UNPARTITIONED_TABLE_HANDLE,
                false);
    }

    @Test
    public void testTableScanWithFixedConnectorPartitioning()
    {
        testPlanWithTableNodePartitioning(
                DISABLE_PLAN_WITH_TABLE_NODE_PARTITIONING,
                FIXED_PARTITIONED_TABLE_HANDLE,
                CONNECTOR_FIXED_PARTITIONED_TABLE_HANDLE,
                true);
    }

    private void testPlanWithTableNodePartitioning(
            Session session,
            TableHandle tableHandle,
            ConnectorTableHandle connectorTableHandle,
            boolean expectedEnabled)
    {
        tester.assertThat(new DetermineTableScanNodePartitioning(tester.getMetadata(), tester.getQueryRunner().getNodePartitioningManager()))
                .on(p -> {
                    Symbol a = p.symbol(COLUMN_A);
                    Symbol b = p.symbol(COLUMN_B);
                    return p.tableScan(tableHandle,
                            ImmutableList.of(a, b),
                            ImmutableMap.of(a, COLUMN_HANDLE_A, b, COLUMN_HANDLE_B));
                })
                .withSession(session)
                .matches(
                        tableScan(
                                equalTo(connectorTableHandle),
                                TupleDomain.all(),
                                ImmutableMap.of(
                                        "A", equalTo(COLUMN_HANDLE_A),
                                        "B", equalTo(COLUMN_HANDLE_B)))
                                .with(planWithTableNodePartitioning(expectedEnabled)));
    }

    private Matcher planWithTableNodePartitioning(boolean enabled)
    {
        return new Matcher()
        {
            @Override
            public boolean shapeMatches(PlanNode node)
            {
                return node instanceof TableScanNode;
            }

            @Override
            public MatchResult detailMatches(PlanNode node, StatsProvider stats, Session session, Metadata metadata, SymbolAliases symbolAliases)
            {
                TableScanNode tableScanNode = (TableScanNode) node;
                if (tableScanNode.getUseConnectorNodePartitioning().isEmpty()) {
                    return NO_MATCH;
                }

                if (tableScanNode.isUseConnectorNodePartitioning() != enabled) {
                    return NO_MATCH;
                }

                return MatchResult.match();
            }
        };
    }
}