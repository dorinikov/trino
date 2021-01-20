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
package io.trino.plugin.elasticsearch;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.trino.Session;
import io.trino.metadata.QualifiedObjectName;
import io.trino.plugin.tpch.TpchPlugin;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingTrinoClient;
import io.trino.tpch.TpchTable;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;

import java.util.Map;

import static io.airlift.testing.Closeables.closeAllSuppress;
import static io.airlift.units.Duration.nanosSince;
import static io.trino.plugin.tpch.TpchMetadata.TINY_SCHEMA_NAME;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class ElasticsearchQueryRunner
{
    private ElasticsearchQueryRunner() {}

    private static final Logger LOG = Logger.get(ElasticsearchQueryRunner.class);
    private static final String TPCH_SCHEMA = "tpch";

    public static DistributedQueryRunner createElasticsearchQueryRunner(
            HostAndPort address,
            Iterable<TpchTable<?>> tables,
            Map<String, String> extraProperties,
            Map<String, String> extraConnectorProperties)
            throws Exception
    {
        RestHighLevelClient client = null;
        DistributedQueryRunner queryRunner = null;
        try {
            queryRunner = DistributedQueryRunner.builder(createSession())
                    .setExtraProperties(extraProperties)
                    .build();

            queryRunner.installPlugin(new TpchPlugin());
            queryRunner.createCatalog("tpch", "tpch");

            TestingElasticsearchConnectorFactory testFactory = new TestingElasticsearchConnectorFactory();

            installElasticsearchPlugin(address, queryRunner, testFactory, extraConnectorProperties);

            TestingTrinoClient trinoClient = queryRunner.getClient();

            LOG.info("Loading data...");

            client = new RestHighLevelClient(RestClient.builder(HttpHost.create(address.toString())));
            long startTime = System.nanoTime();
            for (TpchTable<?> table : tables) {
                loadTpchTopic(client, trinoClient, table);
            }
            LOG.info("Loading complete in %s", nanosSince(startTime).toString(SECONDS));

            return queryRunner;
        }
        catch (Exception e) {
            closeAllSuppress(e, queryRunner, client);
            throw e;
        }
    }

    private static void installElasticsearchPlugin(HostAndPort address, QueryRunner queryRunner, TestingElasticsearchConnectorFactory factory, Map<String, String> extraConnectorProperties)
    {
        queryRunner.installPlugin(new ElasticsearchPlugin(factory));
        Map<String, String> config = ImmutableMap.<String, String>builder()
                .put("elasticsearch.host", address.getHost())
                .put("elasticsearch.port", Integer.toString(address.getPort()))
                // Node discovery relies on the publish_address exposed via the Elasticseach API
                // This doesn't work well within a docker environment that maps ES's port to a random public port
                .put("elasticsearch.ignore-publish-address", "true")
                .put("elasticsearch.default-schema-name", TPCH_SCHEMA)
                .put("elasticsearch.scroll-size", "1000")
                .put("elasticsearch.scroll-timeout", "1m")
                .put("elasticsearch.request-timeout", "2m")
                .putAll(extraConnectorProperties)
                .build();

        queryRunner.createCatalog("elasticsearch", "elasticsearch", config);
    }

    private static void loadTpchTopic(RestHighLevelClient client, TestingTrinoClient trinoClient, TpchTable<?> table)
    {
        long start = System.nanoTime();
        LOG.info("Running import for %s", table.getTableName());
        ElasticsearchLoader loader = new ElasticsearchLoader(client, table.getTableName().toLowerCase(ENGLISH), trinoClient.getServer(), trinoClient.getDefaultSession());
        loader.execute(format("SELECT * from %s", new QualifiedObjectName(TPCH_SCHEMA, TINY_SCHEMA_NAME, table.getTableName().toLowerCase(ENGLISH))));
        LOG.info("Imported %s in %s", table.getTableName(), nanosSince(start).convertToMostSuccinctTimeUnit());
    }

    public static Session createSession()
    {
        return testSessionBuilder().setCatalog("elasticsearch").setSchema(TPCH_SCHEMA).build();
    }

    public static void main(String[] args)
            throws Exception
    {
        // To start Elasticsearch:
        // docker run -p 9200:9200 -e "discovery.type=single-node" docker.elastic.co/elasticsearch/elasticsearch:7.6.2

        Logging.initialize();

        DistributedQueryRunner queryRunner = createElasticsearchQueryRunner(
                HostAndPort.fromParts("localhost", 9200),
                TpchTable.getTables(),
                ImmutableMap.of("http-server.http.port", "8080"),
                ImmutableMap.of());

        Logger log = Logger.get(ElasticsearchQueryRunner.class);
        log.info("======== SERVER STARTED ========");
        log.info("\n====\n%s\n====", queryRunner.getCoordinator().getBaseUrl());
    }
}