/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.topic;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.cache.ItemStore;
import io.kroxylicious.testing.kafka.api.KafkaCluster;
import io.kroxylicious.testing.kafka.junit5ext.KafkaClusterExtension;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.topic.KafkaTopicBuilder;
import io.strimzi.operator.common.metrics.MetricsHolder;
import io.strimzi.operator.topic.cruisecontrol.CruiseControlClient;
import io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder;
import io.strimzi.operator.topic.metrics.TopicOperatorMetricsProvider;
import io.strimzi.operator.topic.model.TopicEvent.TopicUpsert;
import io.strimzi.test.mockkube3.MockKube3;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static io.strimzi.api.ResourceAnnotations.ANNO_STRIMZI_IO_PAUSE_RECONCILIATION;
import static io.strimzi.api.kafka.model.topic.KafkaTopic.RESOURCE_KIND;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RECONCILIATIONS;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RECONCILIATIONS_DURATION;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RECONCILIATIONS_FAILED;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RECONCILIATIONS_LOCKED;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RECONCILIATIONS_SUCCESSFUL;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RESOURCES;
import static io.strimzi.operator.common.metrics.MetricsHolder.METRICS_RESOURCES_PAUSED;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_ADD_FINALIZER_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_ALTER_CONFIGS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_CC_TOPIC_CONFIG_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_CC_USER_TASKS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_CREATE_PARTITIONS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_CREATE_TOPICS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_DELETE_TOPICS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_DESCRIBE_CONFIGS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_DESCRIBE_TOPICS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_LIST_REASSIGNMENTS_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_RECONCILIATIONS_MAX_BATCH_SIZE;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_RECONCILIATIONS_MAX_QUEUE_SIZE;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_REMOVE_FINALIZER_DURATION;
import static io.strimzi.operator.topic.metrics.TopicOperatorMetricsHolder.METRICS_UPDATE_TOPICS_DURATION;
import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;

@ExtendWith(KafkaClusterExtension.class)
public class TopicOperatorMetricsTest {
    private static final Logger LOGGER = LogManager.getLogger(TopicOperatorMetricsTest.class);

    private static final String NAMESPACE = TopicOperatorTestUtil.namespaceName(TopicOperatorMetricsTest.class);
    private static final int MAX_QUEUE_SIZE = 200;
    private static final int MAX_BATCH_SIZE = 10;
    private static final long MAX_BATCH_LINGER_MS = 10_000;

    private static MockKube3 mockKube;
    private static KubernetesClient kubernetesClient;

    @BeforeAll
    public static void beforeAll() {
        mockKube = new MockKube3.MockKube3Builder()
            .withKafkaTopicCrd()
            .withDeletionController()
            .withNamespaces(NAMESPACE)
            .build();
        mockKube.start();
        kubernetesClient = mockKube.client();
    }

    @AfterAll
    public static void afterAll() {
        mockKube.stop();
    }

    @AfterEach
    public void afterEach() {
        TopicOperatorTestUtil.cleanupNamespace(kubernetesClient, NAMESPACE);
    }

    @Test
    public void eventHandlerMetrics() throws InterruptedException {
        var config = TopicOperatorConfig.buildFromMap(Map.of(
            TopicOperatorConfig.BOOTSTRAP_SERVERS.key(), "localhost:9092",
            TopicOperatorConfig.NAMESPACE.key(), NAMESPACE)
        );
        var metricsHolder = new TopicOperatorMetricsHolder(RESOURCE_KIND, null, new TopicOperatorMetricsProvider(new SimpleMeterRegistry()));
        var eventHandler = new TopicOperatorEventHandler(config, mock(BatchingLoop.class), metricsHolder);

        var numOfTestResources = 100;
        for (int i = 0; i < numOfTestResources; i++) {
            KafkaTopic kafkaTopic = buildTopicWithVersion("my-topic" + i);
            eventHandler.onAdd(kafkaTopic);
        }
        assertMetricMatches(metricsHolder, METRICS_RESOURCES, "gauge", is(Double.valueOf(numOfTestResources)));

        for (int i = 0; i < numOfTestResources; i++) {
            KafkaTopic kafkaTopic = buildTopicWithVersion("my-topic" + i);
            eventHandler.onDelete(kafkaTopic, false);
        }
        assertMetricMatches(metricsHolder, METRICS_RESOURCES, "gauge", is(0.0));

        var t1 = buildTopicWithVersion("my-topic-1");
        var t2 = buildTopicWithVersion("my-topic-2");
        t2.getMetadata().setAnnotations(Map.of(ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "true"));
        eventHandler.onUpdate(t1, t2);
        assertMetricMatches(metricsHolder, METRICS_RESOURCES_PAUSED, "gauge", is(1.0));

        var t3 = buildTopicWithVersion("t3");
        t3.getMetadata().setAnnotations(Map.of(ANNO_STRIMZI_IO_PAUSE_RECONCILIATION, "false"));
        eventHandler.onUpdate(t2, t3);
        assertMetricMatches(metricsHolder, METRICS_RESOURCES_PAUSED, "gauge", is(0.0));
    }

    private KafkaTopic buildTopicWithVersion(String name) {
        return new KafkaTopicBuilder()
            .editOrNewMetadata()
                .withNamespace(NAMESPACE)
                .withName(name)
                .withResourceVersion("100100")
            .endMetadata()
            .build();
    }

    @Test
    public void batchingLoopMetrics() throws InterruptedException {
        var controller = mock(BatchingTopicController.class);
        var itemStore = mock(ItemStore.class);
        var stop = mock(Runnable.class);
        var metricsHolder = new TopicOperatorMetricsHolder(RESOURCE_KIND, null,
            new TopicOperatorMetricsProvider(new SimpleMeterRegistry()));
        var batchingLoop = new BatchingLoop(MAX_QUEUE_SIZE, controller, 1, 
            MAX_BATCH_SIZE, MAX_BATCH_LINGER_MS, itemStore, stop, metricsHolder, NAMESPACE);
        batchingLoop.start();
        
        int numOfTestResources = 100;
        for (int i = 0; i < numOfTestResources; i++) {
            if (i < numOfTestResources / 2) {
                batchingLoop.offer(new TopicUpsert(0, NAMESPACE, "t0", "10010" + i));
            } else {
                batchingLoop.offer(new TopicUpsert(0, NAMESPACE, "t" + i, "100100"));
            }
        }

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_MAX_QUEUE_SIZE, "gauge", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_MAX_QUEUE_SIZE, "gauge", lessThanOrEqualTo(Double.valueOf(MAX_QUEUE_SIZE)));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_MAX_BATCH_SIZE,  "gauge", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_MAX_BATCH_SIZE, "gauge", lessThanOrEqualTo(Double.valueOf(MAX_BATCH_SIZE)));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_LOCKED, "counter", greaterThan(0.0));
        batchingLoop.stop();
    }

    @Test
    public void batchingTopicControllerMetrics(KafkaCluster cluster) throws InterruptedException {
        var kafkaAdminClient = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cluster.getBootstrapServers()));
        var config = TopicOperatorConfig.buildFromMap(Map.of(
            TopicOperatorConfig.BOOTSTRAP_SERVERS.key(), "localhost:9092",
            TopicOperatorConfig.NAMESPACE.key(), NAMESPACE,
            TopicOperatorConfig.USE_FINALIZERS.key(), "true",
            TopicOperatorConfig.ENABLE_ADDITIONAL_METRICS.key(), "true",
            TopicOperatorConfig.CRUISE_CONTROL_ENABLED.key(), "true"
        ));

        var cruiseControlClient = Mockito.mock(CruiseControlClient.class);
        var userTaskId = "8911ca89-351f-888-8d0f-9aade00e098h";
        Mockito.doReturn(userTaskId).when(cruiseControlClient).topicConfiguration(anyList());
        var userTaskResponse = new CruiseControlClient.UserTasksResponse(List.of(
            new CruiseControlClient.UserTask("Active", null, null, userTaskId, System.currentTimeMillis())), 1);
        Mockito.doReturn(userTaskResponse).when(cruiseControlClient).userTasks(Set.of(userTaskId));

        var metricsHolder = new TopicOperatorMetricsHolder(RESOURCE_KIND, null, new TopicOperatorMetricsProvider(new SimpleMeterRegistry()));
        var replicasChangeHandler = new ReplicasChangeHandler(config, metricsHolder, cruiseControlClient);
        var controller = new BatchingTopicController(config, Map.of("key", "VALUE"), kafkaAdminClient, kubernetesClient, metricsHolder, replicasChangeHandler);

        // create topics, 3 reconciliations, success
        var t1 = createTopic("my-topic-a");
        var t2 = createTopic("my-topic-b");
        var t3 = createTopic("my-topic-c");
        controller.onUpdate(List.of(
            TopicOperatorTestUtil.reconcilableTopic(t1, NAMESPACE),
            TopicOperatorTestUtil.reconcilableTopic(t2, NAMESPACE),
            TopicOperatorTestUtil.reconcilableTopic(t3, NAMESPACE)
        ));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(3.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(3.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_CREATE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_ADD_FINALIZER_DURATION, "timer", greaterThan(0.0));

        // config change, 1 reconciliation, success
        var t1ConfigChanged = updateTopic(TopicOperatorUtil.topicName(t1), kt -> {
            kt.getSpec().setConfig(Map.of(TopicConfig.RETENTION_MS_CONFIG, "86400000"));
            return kt;
        });
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t1ConfigChanged, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(4.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(4.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_ALTER_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_UPDATE_TOPICS_DURATION, "timer", greaterThan(0.0));

        // increase partitions, 1 reconciliation, success
        var t2PartIncreased = updateTopic(TopicOperatorUtil.topicName(t2), kt -> {
            kt.getSpec().setPartitions(5);
            return kt;
        });
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t2PartIncreased, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(5.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(5.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_CREATE_PARTITIONS_DURATION, "timer", greaterThan(0.0));

        // decrease partitions, 1 reconciliation, fail
        var t2PartDecreased = updateTopic(TopicOperatorUtil.topicName(t2), kt -> {
            kt.getSpec().setPartitions(4);
            return kt;
        });
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t2PartDecreased, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(6.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(5.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_FAILED, "counter", is(1.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));

        // increase replicas, 1 reconciliation, success
        var t3ReplIncreased = updateTopic(TopicOperatorUtil.topicName(t3), kt -> {
            kt.getSpec().setReplicas(2);
            return kt;
        });
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t3ReplIncreased, NAMESPACE)));
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t3ReplIncreased, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(8.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(7.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_FAILED, "counter", is(1.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_LIST_REASSIGNMENTS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_CC_TOPIC_CONFIG_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_CC_USER_TASKS_DURATION, "timer", greaterThan(0.0));

        // unmanage topic, 1 reconciliation, success
        var t1Unmanaged = updateTopic(TopicOperatorUtil.topicName(t1), kt -> {
            kt.getMetadata().setAnnotations(Map.of(TopicOperatorUtil.MANAGED, "false"));
            return kt;
        });
        controller.onUpdate(List.of(TopicOperatorTestUtil.reconcilableTopic(t1Unmanaged, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(9.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(8.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_FAILED, "counter", is(1.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));

        // delete managed topics, 1 reconciliation, success
        controller.onDelete(List.of(TopicOperatorTestUtil.reconcilableTopic(t2, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(10.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(9.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_FAILED, "counter", is(1.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DELETE_TOPICS_DURATION, "timer", greaterThan(0.0));

        // delete unmanaged topic, 1 reconciliation, success
        var t3Unmanaged = updateTopic(TopicOperatorUtil.topicName(t3), kt -> {
            kt.getMetadata().setAnnotations(Map.of(TopicOperatorUtil.MANAGED, "false"));
            return kt;
        });
        controller.onDelete(List.of(TopicOperatorTestUtil.reconcilableTopic(t3Unmanaged, NAMESPACE)));

        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS, "counter", is(11.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_SUCCESSFUL, "counter", is(10.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_FAILED, "counter", is(1.0));
        assertMetricMatches(metricsHolder, METRICS_RECONCILIATIONS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_TOPICS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_DESCRIBE_CONFIGS_DURATION, "timer", greaterThan(0.0));
        assertMetricMatches(metricsHolder, METRICS_REMOVE_FINALIZER_DURATION, "timer", greaterThan(0.0));
    }

    private KafkaTopic createTopic(String name) {
        return Crds.topicOperation(kubernetesClient).inNamespace(NAMESPACE).
            resource(new KafkaTopicBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(NAMESPACE)
                    .addToLabels("key", "VALUE")
                .endMetadata()
                .withNewSpec()
                    .withPartitions(2)
                    .withReplicas(1)
                .endSpec()
                .build()).create();
    }

    private KafkaTopic updateTopic(String name, UnaryOperator<KafkaTopic> changer) {
        var kafkaTopic = Crds.topicOperation(kubernetesClient).inNamespace(NAMESPACE).withName(name).get();
        return TopicOperatorTestUtil.changeTopic(kubernetesClient, kafkaTopic, changer);
    }

    private void assertMetricMatches(MetricsHolder metricsHolder, String name, String type, Matcher<Double> matcher) throws InterruptedException {
        var found = false;
        var timeoutSec = 30;
        while (!found && --timeoutSec > 0) {
            try {
                LOGGER.info("Searching for metric {}", name);
                var requiredSearch = metricsHolder.metricsProvider().meterRegistry().get(name)
                    .tags("kind", RESOURCE_KIND, "namespace", NAMESPACE);
                switch (type) {
                    case "counter":
                        assertThat(requiredSearch.counter().count(), matcher);
                        break;
                    case "gauge":
                        assertThat(requiredSearch.gauge().value(), matcher);
                        break;
                    case "timer":
                        assertThat(requiredSearch.timer().totalTime(TimeUnit.MILLISECONDS), matcher);
                        break;
                    default:
                        throw new RuntimeException(format("Unknown metric type %s", type));
                }
                found = true;
            } catch (MeterNotFoundException mnfe) {
                LOGGER.info("Metric {} not found", name);
                TimeUnit.SECONDS.sleep(1);
            }
        }
        if (!found) {
            throw new RuntimeException(format("Unable to find metric %s", name));
        }
    }
}
