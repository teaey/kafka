/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.runtime.distributed;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.ConfigValue;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.connect.connector.policy.ConnectorClientConfigOverridePolicy;
import org.apache.kafka.connect.connector.policy.NoneConnectorClientConfigOverridePolicy;
import org.apache.kafka.connect.errors.AlreadyExistsException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.NotFoundException;
import org.apache.kafka.connect.runtime.ConnectMetrics.MetricGroup;
import org.apache.kafka.connect.runtime.ConnectorConfig;
import org.apache.kafka.connect.runtime.Herder;
import org.apache.kafka.connect.runtime.MockConnectMetrics;
import org.apache.kafka.connect.runtime.RestartPlan;
import org.apache.kafka.connect.runtime.RestartRequest;
import org.apache.kafka.connect.runtime.SessionKey;
import org.apache.kafka.connect.runtime.SinkConnectorConfig;
import org.apache.kafka.connect.runtime.SourceConnectorConfig;
import org.apache.kafka.connect.runtime.TargetState;
import org.apache.kafka.connect.runtime.TaskConfig;
import org.apache.kafka.connect.runtime.TopicStatus;
import org.apache.kafka.connect.runtime.Worker;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.runtime.WorkerConfigTransformer;
import org.apache.kafka.connect.runtime.distributed.DistributedHerder.HerderMetrics;
import org.apache.kafka.connect.runtime.isolation.DelegatingClassLoader;
import org.apache.kafka.connect.runtime.isolation.PluginClassLoader;
import org.apache.kafka.connect.runtime.isolation.Plugins;
import org.apache.kafka.connect.runtime.rest.InternalRequestSignature;
import org.apache.kafka.connect.runtime.rest.entities.ConfigInfos;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorStateInfo;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorType;
import org.apache.kafka.connect.runtime.rest.entities.TaskInfo;
import org.apache.kafka.connect.runtime.rest.errors.BadRequestException;
import org.apache.kafka.connect.runtime.rest.errors.ConnectRestException;
import org.apache.kafka.connect.sink.SinkConnector;
import org.apache.kafka.connect.source.ConnectorTransactionBoundaries;
import org.apache.kafka.connect.source.ExactlyOnceSupport;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.storage.ConfigBackingStore;
import org.apache.kafka.connect.storage.StatusBackingStore;
import org.apache.kafka.connect.util.Callback;
import org.apache.kafka.connect.util.ConnectorTaskId;
import org.apache.kafka.connect.util.FutureCallback;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.easymock.PowerMock;
import org.powermock.api.easymock.annotation.Mock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.Collections.singletonList;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static org.apache.kafka.connect.runtime.SourceConnectorConfig.ExactlyOnceSupportLevel.REQUIRED;
import static org.apache.kafka.connect.runtime.distributed.ConnectProtocol.CONNECT_PROTOCOL_V0;
import static org.apache.kafka.connect.runtime.distributed.DistributedConfig.EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG;
import static org.apache.kafka.connect.runtime.distributed.DistributedConfig.INTER_WORKER_KEY_GENERATION_ALGORITHM_DEFAULT;
import static org.apache.kafka.connect.runtime.distributed.IncrementalCooperativeConnectProtocol.CONNECT_PROTOCOL_V1;
import static org.apache.kafka.connect.runtime.distributed.IncrementalCooperativeConnectProtocol.CONNECT_PROTOCOL_V2;
import static org.apache.kafka.connect.source.SourceTask.TransactionBoundary.CONNECTOR;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(PowerMockRunner.class)
@PrepareForTest({DistributedHerder.class, Plugins.class})
@PowerMockIgnore({"javax.management.*", "javax.crypto.*"})
public class DistributedHerderTest {
    private static final Map<String, String> HERDER_CONFIG = new HashMap<>();
    static {
        HERDER_CONFIG.put(DistributedConfig.STATUS_STORAGE_TOPIC_CONFIG, "status-topic");
        HERDER_CONFIG.put(DistributedConfig.CONFIG_TOPIC_CONFIG, "config-topic");
        HERDER_CONFIG.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        HERDER_CONFIG.put(DistributedConfig.GROUP_ID_CONFIG, "connect-test-group");
        // The WorkerConfig base class has some required settings without defaults
        HERDER_CONFIG.put(WorkerConfig.KEY_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter");
        HERDER_CONFIG.put(WorkerConfig.VALUE_CONVERTER_CLASS_CONFIG, "org.apache.kafka.connect.json.JsonConverter");
        HERDER_CONFIG.put(DistributedConfig.OFFSET_STORAGE_TOPIC_CONFIG, "connect-offsets");
    }
    private static final String MEMBER_URL = "memberUrl";

    private static final String CONN1 = "sourceA";
    private static final String CONN2 = "sourceB";
    private static final ConnectorTaskId TASK0 = new ConnectorTaskId(CONN1, 0);
    private static final ConnectorTaskId TASK1 = new ConnectorTaskId(CONN1, 1);
    private static final ConnectorTaskId TASK2 = new ConnectorTaskId(CONN1, 2);
    private static final Integer MAX_TASKS = 3;
    private static final Map<String, String> CONN1_CONFIG = new HashMap<>();
    private static final String FOO_TOPIC = "foo";
    private static final String BAR_TOPIC = "bar";
    private static final String BAZ_TOPIC = "baz";
    static {
        CONN1_CONFIG.put(ConnectorConfig.NAME_CONFIG, CONN1);
        CONN1_CONFIG.put(ConnectorConfig.TASKS_MAX_CONFIG, MAX_TASKS.toString());
        CONN1_CONFIG.put(SinkConnectorConfig.TOPICS_CONFIG, String.join(",", FOO_TOPIC, BAR_TOPIC));
        CONN1_CONFIG.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, BogusSourceConnector.class.getName());
    }
    private static final Map<String, String> CONN1_CONFIG_UPDATED = new HashMap<>(CONN1_CONFIG);
    static {
        CONN1_CONFIG_UPDATED.put(SinkConnectorConfig.TOPICS_CONFIG, String.join(",", FOO_TOPIC, BAR_TOPIC, BAZ_TOPIC));
    }
    private static final ConfigInfos CONN1_CONFIG_INFOS =
        new ConfigInfos(CONN1, 0, Collections.emptyList(), Collections.emptyList());
    private static final Map<String, String> CONN2_CONFIG = new HashMap<>();
    static {
        CONN2_CONFIG.put(ConnectorConfig.NAME_CONFIG, CONN2);
        CONN2_CONFIG.put(ConnectorConfig.TASKS_MAX_CONFIG, MAX_TASKS.toString());
        CONN2_CONFIG.put(SinkConnectorConfig.TOPICS_CONFIG, String.join(",", FOO_TOPIC, BAR_TOPIC));
        CONN2_CONFIG.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, BogusSourceConnector.class.getName());
    }
    private static final ConfigInfos CONN2_CONFIG_INFOS =
        new ConfigInfos(CONN2, 0, Collections.emptyList(), Collections.emptyList());
    private static final ConfigInfos CONN2_INVALID_CONFIG_INFOS =
        new ConfigInfos(CONN2, 1, Collections.emptyList(), Collections.emptyList());
    private static final Map<String, String> TASK_CONFIG = new HashMap<>();
    static {
        TASK_CONFIG.put(TaskConfig.TASK_CLASS_CONFIG, BogusSourceTask.class.getName());
    }
    private static final List<Map<String, String>> TASK_CONFIGS = new ArrayList<>();
    static {
        TASK_CONFIGS.add(TASK_CONFIG);
        TASK_CONFIGS.add(TASK_CONFIG);
        TASK_CONFIGS.add(TASK_CONFIG);
    }
    private static final HashMap<ConnectorTaskId, Map<String, String>> TASK_CONFIGS_MAP = new HashMap<>();
    static {
        TASK_CONFIGS_MAP.put(TASK0, TASK_CONFIG);
        TASK_CONFIGS_MAP.put(TASK1, TASK_CONFIG);
        TASK_CONFIGS_MAP.put(TASK2, TASK_CONFIG);
    }
    private static final ClusterConfigState SNAPSHOT = new ClusterConfigState(1, null, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet());
    private static final ClusterConfigState SNAPSHOT_PAUSED_CONN1 = new ClusterConfigState(1, null, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.PAUSED),
            TASK_CONFIGS_MAP, Collections.emptySet());
    private static final ClusterConfigState SNAPSHOT_UPDATED_CONN1_CONFIG = new ClusterConfigState(1, null, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG_UPDATED), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet());

    private static final String WORKER_ID = "localhost:8083";
    private static final String KAFKA_CLUSTER_ID = "I4ZmrWqfT2e-upky_4fdPA";
    private static final Runnable EMPTY_RUNNABLE = () -> {
    };

    @Mock private ConfigBackingStore configBackingStore;
    @Mock private StatusBackingStore statusBackingStore;
    @Mock private WorkerGroupMember member;
    private MockTime time;
    private DistributedHerder herder;
    private MockConnectMetrics metrics;
    @Mock private Worker worker;
    @Mock private WorkerConfigTransformer transformer;
    @Mock private Callback<Herder.Created<ConnectorInfo>> putConnectorCallback;
    @Mock private Plugins plugins;
    @Mock private PluginClassLoader pluginLoader;
    @Mock private DelegatingClassLoader delegatingLoader;
    private CountDownLatch shutdownCalled = new CountDownLatch(1);

    private ConfigBackingStore.UpdateListener configUpdateListener;
    private WorkerRebalanceListener rebalanceListener;

    private SinkConnectorConfig conn1SinkConfig;
    private SinkConnectorConfig conn1SinkConfigUpdated;
    private short connectProtocolVersion;
    private final ConnectorClientConfigOverridePolicy
        noneConnectorClientConfigOverridePolicy = new NoneConnectorClientConfigOverridePolicy();


    @Before
    public void setUp() throws Exception {
        time = new MockTime();
        metrics = new MockConnectMetrics(time);
        worker = PowerMock.createMock(Worker.class);
        EasyMock.expect(worker.isSinkConnector(CONN1)).andStubReturn(Boolean.TRUE);
        AutoCloseable uponShutdown = () -> shutdownCalled.countDown();

        // Default to the old protocol unless specified otherwise
        connectProtocolVersion = CONNECT_PROTOCOL_V0;

        herder = PowerMock.createPartialMock(DistributedHerder.class,
                new String[]{"connectorTypeForClass", "updateDeletedConnectorStatus", "updateDeletedTaskStatus", "validateConnectorConfig", "buildRestartPlan", "recordRestarting"},
                new DistributedConfig(HERDER_CONFIG), worker, WORKER_ID, KAFKA_CLUSTER_ID,
                statusBackingStore, configBackingStore, member, MEMBER_URL, metrics, time, noneConnectorClientConfigOverridePolicy,
                new AutoCloseable[]{uponShutdown});

        configUpdateListener = herder.new ConfigUpdateListener();
        rebalanceListener = herder.new RebalanceListener(time);
        plugins = PowerMock.createMock(Plugins.class);
        conn1SinkConfig = new SinkConnectorConfig(plugins, CONN1_CONFIG);
        conn1SinkConfigUpdated = new SinkConnectorConfig(plugins, CONN1_CONFIG_UPDATED);
        EasyMock.expect(herder.connectorTypeForClass(BogusSourceConnector.class.getName())).andReturn(ConnectorType.SOURCE).anyTimes();
        pluginLoader = PowerMock.createMock(PluginClassLoader.class);
        delegatingLoader = PowerMock.createMock(DelegatingClassLoader.class);
        PowerMock.mockStatic(Plugins.class);
        PowerMock.expectPrivate(herder, "updateDeletedConnectorStatus").andVoid().anyTimes();
        PowerMock.expectPrivate(herder, "updateDeletedTaskStatus").andVoid().anyTimes();
    }

    @After
    public void tearDown() {
        if (metrics != null) metrics.stop();
    }

    @Test
    public void testJoinAssignment() throws Exception {
        // Join group and get assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        PowerMock.expectLastCall();
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        PowerMock.verifyAll();
    }

    @Test
    public void testRebalance() throws Exception {
        // Join group and get assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(Arrays.asList(CONN1), Arrays.asList(TASK1), ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList());

        // and the new assignment started
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        time.sleep(1000L);
        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 2000);
        herder.tick();

        time.sleep(3000L);
        assertStatistics(3, 2, 100, 3000);

        PowerMock.verifyAll();
    }

    @Test
    public void testIncrementalCooperativeRebalanceForNewMember() throws Exception {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;
        // Join group. First rebalance contains revocations from other members. For the new
        // member the assignment should be empty
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V1);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // The new member got its assignment
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);

        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        // and the new assignment started
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        time.sleep(1000L);
        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 2000);
        herder.tick();

        time.sleep(3000L);
        assertStatistics(3, 2, 100, 3000);

        PowerMock.verifyAll();
    }

    @Test
    public void testIncrementalCooperativeRebalanceForExistingMember() {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;
        // Join group. First rebalance contains revocations because a new member joined.
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V1);
        expectRebalance(Arrays.asList(CONN1), Arrays.asList(TASK1),
                ConnectProtocol.Assignment.NO_ERROR, 1,
                Collections.emptyList(), Collections.emptyList(), 0);
        member.requestRejoin();
        PowerMock.expectLastCall();

        // In the second rebalance the new member gets its assignment and this member has no
        // assignments or revocations
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.configState = SNAPSHOT;
        time.sleep(1000L);
        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 2000);
        herder.tick();

        time.sleep(3000L);
        assertStatistics(3, 2, 100, 3000);

        PowerMock.verifyAll();
    }

    @Test
    public void testIncrementalCooperativeRebalanceWithDelay() throws Exception {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;
        // Join group. First rebalance contains some assignments but also a delay, because a
        // member was detected missing
        int rebalanceDelay = 10_000;

        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V1);
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR, 1,
                Collections.emptyList(), Arrays.asList(TASK2),
                rebalanceDelay);
        expectPostRebalanceCatchup(SNAPSHOT);

        worker.startTask(EasyMock.eq(TASK2), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall().andAnswer(() -> {
            time.sleep(9900L);
            return null;
        });

        // Request to re-join because the scheduled rebalance delay has been reached
        member.requestRejoin();
        PowerMock.expectLastCall();

        // The member got its assignment and revocation
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);

        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        // and the new assignment started
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        time.sleep(1000L);
        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 2, 100, 2000);

        PowerMock.verifyAll();
    }

    @Test
    public void testRebalanceFailedConnector() throws Exception {
        // Join group and get assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        expectRebalance(Arrays.asList(CONN1), Arrays.asList(TASK1), ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList());

        // and the new assignment started
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(false);

        // worker is not running, so we should see no call to connectorTaskConfigs()

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        herder.tick();
        time.sleep(2000L);
        assertStatistics(3, 2, 100, 2000L);

        PowerMock.verifyAll();
    }

    @Test
    public void testRevoke() throws TimeoutException {
        revokeAndReassign(false);
    }

    @Test
    public void testIncompleteRebalanceBeforeRevoke() throws TimeoutException {
        revokeAndReassign(true);
    }

    public void revokeAndReassign(boolean incompleteRebalance) throws TimeoutException {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;
        int configOffset = 1;

        // Join group and get initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(connectProtocolVersion);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        // The lists need to be mutable because assignments might be removed
        expectRebalance(configOffset, new ArrayList<>(singletonList(CONN1)), new ArrayList<>(singletonList(TASK1)));
        expectPostRebalanceCatchup(SNAPSHOT);
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
            EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
            EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // worker is stable with an existing set of tasks

        if (incompleteRebalance) {
            // Perform a partial re-balance just prior to the revocation
            // bump the configOffset to trigger reading the config topic to the end
            configOffset++;
            expectRebalance(configOffset, Arrays.asList(), Arrays.asList());
            // give it the wrong snapshot, as if we're out of sync/can't reach the broker
            expectPostRebalanceCatchup(SNAPSHOT);
            member.requestRejoin();
            PowerMock.expectLastCall();
            // tick exits early because we failed, and doesn't do the poll at the end of the method
            // the worker did not startWork or reset the rebalanceResolved flag
        }

        // Revoke the connector in the next rebalance
        expectRebalance(Arrays.asList(CONN1), Arrays.asList(),
            ConnectProtocol.Assignment.NO_ERROR, configOffset, Arrays.asList(),
            Arrays.asList());

        if (incompleteRebalance) {
            // Same as SNAPSHOT, except with an updated offset
            // Allow the task to read to the end of the topic and complete the rebalance
            ClusterConfigState secondSnapshot = new ClusterConfigState(
                configOffset, null, Collections.singletonMap(CONN1, 3),
                Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
                TASK_CONFIGS_MAP, Collections.emptySet());
            expectPostRebalanceCatchup(secondSnapshot);
        }
        member.requestRejoin();
        PowerMock.expectLastCall();

        // re-assign the connector back to the same worker to ensure state was cleaned up
        expectRebalance(configOffset, Arrays.asList(CONN1), Arrays.asList());
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(),
            EasyMock.anyObject(),
            EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig))
            .andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        if (incompleteRebalance) {
            herder.tick();
        }
        herder.tick();
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testHaltCleansUpWorker() {
        EasyMock.expect(worker.connectorNames()).andReturn(Collections.singleton(CONN1));
        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();
        EasyMock.expect(worker.taskIds()).andReturn(Collections.singleton(TASK1));
        worker.stopAndAwaitTask(TASK1);
        PowerMock.expectLastCall();
        member.stop();
        PowerMock.expectLastCall();
        configBackingStore.stop();
        PowerMock.expectLastCall();
        statusBackingStore.stop();
        PowerMock.expectLastCall();
        worker.stop();
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.halt();

        PowerMock.verifyAll();
    }

    @Test
    public void testCreateConnector() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);

        member.wakeup();
        PowerMock.expectLastCall();

        // mock the actual validation since its asynchronous nature is difficult to test and should
        // be covered sufficiently by the unit tests for the AbstractHerder class
        Capture<Callback<ConfigInfos>> validateCallback = newCapture();
        herder.validateConnectorConfig(EasyMock.eq(CONN2_CONFIG), capture(validateCallback));
        PowerMock.expectLastCall().andAnswer(() -> {
            validateCallback.getValue().onCompletion(null, CONN2_CONFIG_INFOS);
            return null;
        });

        // CONN2 is new, should succeed
        configBackingStore.putConnectorConfig(CONN2, CONN2_CONFIG);
        PowerMock.expectLastCall();
        ConnectorInfo info = new ConnectorInfo(CONN2, CONN2_CONFIG, Collections.emptyList(),
            ConnectorType.SOURCE);
        putConnectorCallback.onCompletion(null, new Herder.Created<>(true, info));
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();
        // These will occur just before/during the second tick
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // No immediate action besides this -- change will be picked up via the config log

        PowerMock.replayAll();

        herder.putConnectorConfig(CONN2, CONN2_CONFIG, false, putConnectorCallback);
        // First tick runs the initial herder request, which issues an asynchronous request for
        // connector validation
        herder.tick();

        // Once that validation is complete, another request is added to the herder request queue
        // for actually performing the config write; this tick is for that request
        herder.tick();

        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        PowerMock.verifyAll();
    }

    @Test
    public void testCreateConnectorFailedValidation() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);

        HashMap<String, String> config = new HashMap<>(CONN2_CONFIG);
        config.remove(ConnectorConfig.NAME_CONFIG);

        member.wakeup();
        PowerMock.expectLastCall();

        // mock the actual validation since its asynchronous nature is difficult to test and should
        // be covered sufficiently by the unit tests for the AbstractHerder class
        Capture<Callback<ConfigInfos>> validateCallback = newCapture();
        herder.validateConnectorConfig(EasyMock.eq(config), capture(validateCallback));
        PowerMock.expectLastCall().andAnswer(() -> {
            // CONN2 creation should fail
            validateCallback.getValue().onCompletion(null, CONN2_INVALID_CONFIG_INFOS);
            return null;
        });

        Capture<Throwable> error = newCapture();
        putConnectorCallback.onCompletion(capture(error), EasyMock.isNull());
        PowerMock.expectLastCall();

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the second tick
        member.wakeup();
        PowerMock.expectLastCall();

        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // No immediate action besides this -- change will be picked up via the config log

        PowerMock.replayAll();

        herder.putConnectorConfig(CONN2, config, false, putConnectorCallback);
        herder.tick();
        herder.tick();

        assertTrue(error.hasCaptured());
        assertTrue(error.getValue() instanceof BadRequestException);

        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorNameConflictsWithWorkerGroupId() {
        Map<String, String> config = new HashMap<>(CONN2_CONFIG);
        config.put(ConnectorConfig.NAME_CONFIG, "test-group");

        SinkConnector connectorMock = PowerMock.createMock(SinkConnector.class);

        PowerMock.replayAll(connectorMock);

        // CONN2 creation should fail because the worker group id (connect-test-group) conflicts with
        // the consumer group id we would use for this sink
        Map<String, ConfigValue> validatedConfigs = herder.validateSinkConnectorConfig(
                connectorMock, SinkConnectorConfig.configDef(), config);

        ConfigValue nameConfig = validatedConfigs.get(ConnectorConfig.NAME_CONFIG);
        assertEquals(
                Collections.singletonList("Consumer group for sink connector named test-group conflicts with Connect worker group connect-test-group"),
                nameConfig.errorMessages());

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidation() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, REQUIRED.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.exactlyOnceSupport(EasyMock.eq(config)))
                .andReturn(ExactlyOnceSupport.SUPPORTED);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertEquals(Collections.emptyList(), errors);

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidationOnUnsupportedConnector() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, REQUIRED.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.exactlyOnceSupport(EasyMock.eq(config)))
                .andReturn(ExactlyOnceSupport.UNSUPPORTED);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertEquals(
                Collections.singletonList("The connector does not support exactly-once delivery guarantees with the provided configuration."),
                errors);

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidationOnUnknownConnector() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, REQUIRED.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.exactlyOnceSupport(EasyMock.eq(config)))
                .andReturn(null);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains("The connector does not implement the API required for preflight validation of exactly-once source support."));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidationHandlesConnectorErrorsGracefully() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, REQUIRED.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        String errorMessage = "time to add a new unit test :)";
        EasyMock.expect(connectorMock.exactlyOnceSupport(EasyMock.eq(config)))
                .andThrow(new NullPointerException(errorMessage));

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains(errorMessage));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidationWhenExactlyOnceNotEnabledOnWorker() {
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, REQUIRED.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.exactlyOnceSupport(EasyMock.eq(config)))
                .andReturn(ExactlyOnceSupport.SUPPORTED);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertEquals(
                Collections.singletonList("This worker does not have exactly-once source support enabled."),
                errors);

        PowerMock.verifyAll();
    }

    @Test
    public void testExactlyOnceSourceSupportValidationHandlesInvalidValuesGracefully() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG, "invalid");

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.EXACTLY_ONCE_SUPPORT_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains("String must be one of (case insensitive): "));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorTransactionBoundaryValidation() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG, CONNECTOR.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.canDefineTransactionBoundaries(EasyMock.eq(config)))
                .andReturn(ConnectorTransactionBoundaries.SUPPORTED);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG).errorMessages();
        assertEquals(Collections.emptyList(), errors);

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorTransactionBoundaryValidationOnUnsupportedConnector() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG, CONNECTOR.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        EasyMock.expect(connectorMock.canDefineTransactionBoundaries(EasyMock.eq(config)))
                .andReturn(ConnectorTransactionBoundaries.UNSUPPORTED);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains("The connector does not support connector-defined transaction boundaries with the given configuration."));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorTransactionBoundaryValidationHandlesConnectorErrorsGracefully() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG, CONNECTOR.toString());

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);
        String errorMessage = "Wait I thought we tested for this?";
        EasyMock.expect(connectorMock.canDefineTransactionBoundaries(EasyMock.eq(config)))
                .andThrow(new ConnectException(errorMessage));

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains(errorMessage));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorTransactionBoundaryValidationHandlesInvalidValuesGracefully() {
        herder = exactlyOnceHerder();
        Map<String, String> config = new HashMap<>();
        config.put(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG, "CONNECTOR.toString()");

        SourceConnector connectorMock = PowerMock.createMock(SourceConnector.class);

        PowerMock.replayAll(connectorMock);

        Map<String, ConfigValue> validatedConfigs = herder.validateSourceConnectorConfig(
                connectorMock, SourceConnectorConfig.configDef(), config);

        List<String> errors = validatedConfigs.get(SourceConnectorConfig.TRANSACTION_BOUNDARY_CONFIG).errorMessages();
        assertFalse(errors.isEmpty());
        assertTrue(
                "Error message did not contain expected text: " + errors.get(0),
                errors.get(0).contains("String must be one of (case insensitive): "));
        assertEquals(1, errors.size());

        PowerMock.verifyAll();
    }

    @Test
    public void testCreateConnectorAlreadyExists() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);

        // mock the actual validation since its asynchronous nature is difficult to test and should
        // be covered sufficiently by the unit tests for the AbstractHerder class
        Capture<Callback<ConfigInfos>> validateCallback = newCapture();
        herder.validateConnectorConfig(EasyMock.eq(CONN1_CONFIG), capture(validateCallback));
        PowerMock.expectLastCall().andAnswer(() -> {
            validateCallback.getValue().onCompletion(null, CONN1_CONFIG_INFOS);
            return null;
        });

        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);

        member.wakeup();
        PowerMock.expectLastCall();
        // CONN1 already exists
        putConnectorCallback.onCompletion(EasyMock.<AlreadyExistsException>anyObject(), EasyMock.isNull());
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the second tick
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // No immediate action besides this -- change will be picked up via the config log

        PowerMock.replayAll();

        herder.putConnectorConfig(CONN1, CONN1_CONFIG, false, putConnectorCallback);
        herder.tick();
        herder.tick();

        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        PowerMock.verifyAll();
    }

    @Test
    public void testDestroyConnector() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        // Start with one connector
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(1, Arrays.asList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        // And delete the connector
        member.wakeup();
        PowerMock.expectLastCall();
        configBackingStore.removeConnectorConfig(CONN1);
        PowerMock.expectLastCall();
        putConnectorCallback.onCompletion(null, new Herder.Created<>(false, null));
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // The change eventually is reflected to the config topic and the deleted connector and
        // tasks are revoked
        member.wakeup();
        PowerMock.expectLastCall();
        TopicStatus fooStatus = new TopicStatus(FOO_TOPIC, CONN1, 0, time.milliseconds());
        TopicStatus barStatus = new TopicStatus(BAR_TOPIC, CONN1, 0, time.milliseconds());
        EasyMock.expect(statusBackingStore.getAllTopics(EasyMock.eq(CONN1))).andReturn(new HashSet<>(Arrays.asList(fooStatus, barStatus))).times(2);
        statusBackingStore.deleteTopic(EasyMock.eq(CONN1), EasyMock.eq(FOO_TOPIC));
        PowerMock.expectLastCall().times(2);
        statusBackingStore.deleteTopic(EasyMock.eq(CONN1), EasyMock.eq(BAR_TOPIC));
        PowerMock.expectLastCall().times(2);
        expectRebalance(Arrays.asList(CONN1), Arrays.asList(TASK1),
                ConnectProtocol.Assignment.NO_ERROR, 2,
                Collections.emptyList(), Collections.emptyList(), 0);
        expectPostRebalanceCatchup(ClusterConfigState.EMPTY);
        member.requestRejoin();
        PowerMock.expectLastCall();
        PowerMock.replayAll();

        herder.deleteConnectorConfig(CONN1, putConnectorCallback);
        herder.tick();

        time.sleep(1000L);
        assertStatistics("leaderUrl", false, 3, 1, 100, 1000L);

        configUpdateListener.onConnectorConfigRemove(CONN1); // read updated config that removes the connector
        herder.configState = ClusterConfigState.EMPTY;
        herder.tick();
        time.sleep(1000L);
        assertStatistics("leaderUrl", true, 3, 1, 100, 2100L);

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnector() throws Exception {
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andStubReturn(TASK_CONFIGS);

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        expectRebalance(1, singletonList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartConnector(CONN1, callback);
        herder.tick();
        callback.get(1000L, TimeUnit.MILLISECONDS);

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartUnknownConnector() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartConnector(CONN2, callback);
        herder.tick();
        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotFoundException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotFoundException);
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorRedirectToLeader() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartConnector(CONN1, callback);
        herder.tick();

        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotLeaderException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotLeaderException);
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorRedirectToOwner() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        String ownerUrl = "ownerUrl";
        EasyMock.expect(member.ownerUrl(CONN1)).andReturn(ownerUrl);

        PowerMock.replayAll();

        herder.tick();
        time.sleep(1000L);
        assertStatistics(3, 1, 100, 1000L);

        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartConnector(CONN1, callback);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 3000L);

        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotLeaderException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotAssignedException);
            NotAssignedException notAssignedException = (NotAssignedException) e.getCause();
            assertEquals(ownerUrl, notAssignedException.forwardUrl());
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorAndTasksUnknownConnector() throws Exception {
        String connectorName = "UnknownConnector";
        RestartRequest restartRequest = new RestartRequest(connectorName, false, true);

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<ConnectorStateInfo> callback = new FutureCallback<>();
        herder.restartConnectorAndTasks(restartRequest, callback);
        herder.tick();
        ExecutionException ee = assertThrows(ExecutionException.class, () -> callback.get(1000L, TimeUnit.MILLISECONDS));
        assertTrue(ee.getCause() instanceof NotFoundException);
        assertTrue(ee.getMessage().contains("Unknown connector:"));

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorAndTasksNotLeader() throws Exception {
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<ConnectorStateInfo> callback = new FutureCallback<>();
        herder.restartConnectorAndTasks(restartRequest, callback);
        herder.tick();
        ExecutionException ee = assertThrows(ExecutionException.class, () -> callback.get(1000L, TimeUnit.MILLISECONDS));
        assertTrue(ee.getCause() instanceof NotLeaderException);

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorAndTasksUnknownStatus() throws Exception {
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.empty()).anyTimes();

        configBackingStore.putRestartRequest(restartRequest);
        PowerMock.expectLastCall();

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<ConnectorStateInfo> callback = new FutureCallback<>();
        herder.restartConnectorAndTasks(restartRequest, callback);
        herder.tick();
        ExecutionException ee = assertThrows(ExecutionException.class, () -> callback.get(1000L, TimeUnit.MILLISECONDS));
        assertTrue(ee.getCause() instanceof NotFoundException);
        assertTrue(ee.getMessage().contains("Status for connector"));
        PowerMock.verifyAll();
    }

    @Test
    public void testRestartConnectorAndTasksSuccess() throws Exception {
        RestartPlan restartPlan = PowerMock.createMock(RestartPlan.class);
        ConnectorStateInfo connectorStateInfo = PowerMock.createMock(ConnectorStateInfo.class);
        EasyMock.expect(restartPlan.restartConnectorStateInfo()).andReturn(connectorStateInfo).anyTimes();

        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.of(restartPlan)).anyTimes();

        configBackingStore.putRestartRequest(restartRequest);
        PowerMock.expectLastCall();

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the connector restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<ConnectorStateInfo> callback = new FutureCallback<>();
        herder.restartConnectorAndTasks(restartRequest, callback);
        herder.tick();
        assertEquals(connectorStateInfo,  callback.get(1000L, TimeUnit.MILLISECONDS));
        PowerMock.verifyAll();
    }

    @Test
    public void testDoRestartConnectorAndTasksEmptyPlan() {
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.empty()).anyTimes();

        PowerMock.replayAll();

        herder.doRestartConnectorAndTasks(restartRequest);
        PowerMock.verifyAll();
    }

    @Test
    public void testDoRestartConnectorAndTasksNoAssignments() {
        ConnectorTaskId taskId = new ConnectorTaskId(CONN1, 0);
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        RestartPlan restartPlan = PowerMock.createMock(RestartPlan.class);
        EasyMock.expect(restartPlan.shouldRestartConnector()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.shouldRestartTasks()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.taskIdsToRestart()).andReturn(Collections.singletonList(taskId)).anyTimes();

        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.of(restartPlan)).anyTimes();

        PowerMock.replayAll();
        herder.assignment = ExtendedAssignment.empty();
        herder.doRestartConnectorAndTasks(restartRequest);
        PowerMock.verifyAll();
    }

    @Test
    public void testDoRestartConnectorAndTasksOnlyConnector() {
        ConnectorTaskId taskId = new ConnectorTaskId(CONN1, 0);
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        RestartPlan restartPlan = PowerMock.createMock(RestartPlan.class);
        EasyMock.expect(restartPlan.shouldRestartConnector()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.shouldRestartTasks()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.taskIdsToRestart()).andReturn(Collections.singletonList(taskId)).anyTimes();

        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.of(restartPlan)).anyTimes();

        herder.assignment = PowerMock.createMock(ExtendedAssignment.class);
        EasyMock.expect(herder.assignment.connectors()).andReturn(Collections.singletonList(CONN1)).anyTimes();
        EasyMock.expect(herder.assignment.tasks()).andReturn(Collections.emptyList()).anyTimes();

        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();

        Capture<Callback<TargetState>>  stateCallback = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.anyObject(TargetState.class), capture(stateCallback));


        herder.onRestart(CONN1);
        EasyMock.expectLastCall();

        PowerMock.replayAll();
        herder.doRestartConnectorAndTasks(restartRequest);
        PowerMock.verifyAll();
    }

    @Test
    public void testDoRestartConnectorAndTasksOnlyTasks() {
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        RestartPlan restartPlan = PowerMock.createMock(RestartPlan.class);
        EasyMock.expect(restartPlan.shouldRestartConnector()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.shouldRestartTasks()).andReturn(true).anyTimes();
        // The connector has three tasks
        EasyMock.expect(restartPlan.taskIdsToRestart()).andReturn(Arrays.asList(TASK0, TASK1, TASK2)).anyTimes();
        EasyMock.expect(restartPlan.restartTaskCount()).andReturn(3).anyTimes();
        EasyMock.expect(restartPlan.totalTaskCount()).andReturn(3).anyTimes();
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.of(restartPlan)).anyTimes();

        herder.assignment = PowerMock.createMock(ExtendedAssignment.class);
        EasyMock.expect(herder.assignment.connectors()).andReturn(Collections.emptyList()).anyTimes();
        // But only one task is assigned to this worker
        EasyMock.expect(herder.assignment.tasks()).andReturn(Collections.singletonList(TASK0)).anyTimes();

        worker.stopAndAwaitTasks(Collections.singletonList(TASK0));
        PowerMock.expectLastCall();

        herder.onRestart(TASK0);
        EasyMock.expectLastCall();

        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.anyObject(TargetState.class));
        PowerMock.expectLastCall().andReturn(true);

        PowerMock.replayAll();
        herder.doRestartConnectorAndTasks(restartRequest);
        PowerMock.verifyAll();
    }

    @Test
    public void testDoRestartConnectorAndTasksBoth() {
        ConnectorTaskId taskId = new ConnectorTaskId(CONN1, 0);
        RestartRequest restartRequest = new RestartRequest(CONN1, false, true);
        RestartPlan restartPlan = PowerMock.createMock(RestartPlan.class);
        EasyMock.expect(restartPlan.shouldRestartConnector()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.shouldRestartTasks()).andReturn(true).anyTimes();
        EasyMock.expect(restartPlan.taskIdsToRestart()).andReturn(Collections.singletonList(taskId)).anyTimes();
        EasyMock.expect(restartPlan.restartTaskCount()).andReturn(1).anyTimes();
        EasyMock.expect(restartPlan.totalTaskCount()).andReturn(1).anyTimes();
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andReturn(Optional.of(restartPlan)).anyTimes();

        herder.assignment = PowerMock.createMock(ExtendedAssignment.class);
        EasyMock.expect(herder.assignment.connectors()).andReturn(Collections.singletonList(CONN1)).anyTimes();
        EasyMock.expect(herder.assignment.tasks()).andReturn(Collections.singletonList(taskId)).anyTimes();

        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();

        Capture<Callback<TargetState>>  stateCallback = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.anyObject(TargetState.class), capture(stateCallback));


        herder.onRestart(CONN1);
        EasyMock.expectLastCall();

        worker.stopAndAwaitTasks(Collections.singletonList(taskId));
        PowerMock.expectLastCall();

        herder.onRestart(taskId);
        EasyMock.expectLastCall();

        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.anyObject(TargetState.class));
        PowerMock.expectLastCall().andReturn(true);

        PowerMock.replayAll();
        herder.doRestartConnectorAndTasks(restartRequest);
        PowerMock.verifyAll();
    }

    @Test
    public void testRestartTask() throws Exception {
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andStubReturn(TASK_CONFIGS);

        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), singletonList(TASK0));
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);

        // now handle the task restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        worker.stopAndAwaitTask(TASK0);
        PowerMock.expectLastCall();
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartTask(TASK0, callback);
        herder.tick();
        callback.get(1000L, TimeUnit.MILLISECONDS);

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartUnknownTask() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        FutureCallback<Void> callback = new FutureCallback<>();
        herder.tick();
        herder.restartTask(new ConnectorTaskId("blah", 0), callback);
        herder.tick();

        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotLeaderException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotFoundException);
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testRequestProcessingOrder() {
        final DistributedHerder.DistributedHerderRequest req1 = herder.addRequest(100, null, null);
        final DistributedHerder.DistributedHerderRequest req2 = herder.addRequest(10, null, null);
        final DistributedHerder.DistributedHerderRequest req3 = herder.addRequest(200, null, null);
        final DistributedHerder.DistributedHerderRequest req4 = herder.addRequest(200, null, null);

        assertEquals(req2, herder.requests.pollFirst()); // lowest delay
        assertEquals(req1, herder.requests.pollFirst()); // next lowest delay
        assertEquals(req3, herder.requests.pollFirst()); // same delay as req4, but added first
        assertEquals(req4, herder.requests.pollFirst());
    }

    @Test
    public void testRestartTaskRedirectToLeader() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the task restart
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartTask(TASK0, callback);
        herder.tick();

        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotLeaderException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotLeaderException);
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testRestartTaskRedirectToOwner() throws Exception {
        // get the initial assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // now handle the task restart
        String ownerUrl = "ownerUrl";
        EasyMock.expect(member.ownerUrl(TASK0)).andReturn(ownerUrl);
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        FutureCallback<Void> callback = new FutureCallback<>();
        herder.restartTask(TASK0, callback);
        herder.tick();

        try {
            callback.get(1000L, TimeUnit.MILLISECONDS);
            fail("Expected NotLeaderException to be raised");
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof NotAssignedException);
            NotAssignedException notAssignedException = (NotAssignedException) e.getCause();
            assertEquals(ownerUrl, notAssignedException.forwardUrl());
        }

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorConfigAdded() {
        // If a connector was added, we need to rebalance
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);

        // join, no configs so no need to catch up on config topic
        expectRebalance(-1, Collections.emptyList(), Collections.emptyList());
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // apply config
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        // Checks for config updates and starts rebalance
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);
        member.requestRejoin();
        PowerMock.expectLastCall();
        // Performs rebalance and gets new assignment
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR, 1, Arrays.asList(CONN1), Collections.emptyList());
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorConfigUpdate(CONN1); // read updated config
        herder.tick(); // apply config
        herder.tick(); // do rebalance

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorConfigUpdate() throws Exception {
        // Connector config can be applied without any rebalance

        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.singleton(CONN1));

        // join
        expectRebalance(1, Arrays.asList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // apply config
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT); // for this test, it doesn't matter if we use the same config snapshot
        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the third tick
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorConfigUpdate(CONN1); // read updated config
        herder.tick(); // apply config
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorPaused() throws Exception {
        // ensure that target state changes are propagated to the worker

        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.singleton(CONN1));

        // join
        expectRebalance(1, Arrays.asList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // handle the state change
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT_PAUSED_CONN1);
        PowerMock.expectLastCall();

        Capture<Callback<TargetState>> onPause = newCapture();
        worker.setTargetState(EasyMock.eq(CONN1), EasyMock.eq(TargetState.PAUSED), capture(onPause));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.PAUSED);
            return null;
        });

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the third tick
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorTargetStateChange(CONN1); // state changes to paused
        herder.tick(); // worker should apply the state change
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorResumed() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.singleton(CONN1));

        // start with the connector paused
        expectRebalance(1, Arrays.asList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT_PAUSED_CONN1);
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.PAUSED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.PAUSED);
            return true;
        });
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // handle the state change
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);
        PowerMock.expectLastCall();

        // we expect reconfiguration after resuming
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        Capture<Callback<TargetState>> onResume = newCapture();
        worker.setTargetState(EasyMock.eq(CONN1), EasyMock.eq(TargetState.STARTED), capture(onResume));
        PowerMock.expectLastCall().andAnswer(() -> {
            onResume.getValue().onCompletion(null, TargetState.STARTED);
            return null;
        });
        member.wakeup();
        PowerMock.expectLastCall();

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the third tick
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorTargetStateChange(CONN1); // state changes to started
        herder.tick(); // apply state change
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testUnknownConnectorPaused() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.singleton(CONN1));

        // join
        expectRebalance(1, Collections.emptyList(), singletonList(TASK0));
        expectPostRebalanceCatchup(SNAPSHOT);
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // state change is ignored since we have no target state
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);
        PowerMock.expectLastCall();

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorTargetStateChange("unknown-connector");
        herder.tick(); // continue

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorPausedRunningTaskOnly() throws Exception {
        // even if we don't own the connector, we should still propagate target state
        // changes to the worker so that tasks will transition correctly

        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.emptySet());

        // join
        expectRebalance(1, Collections.emptyList(), singletonList(TASK0));
        expectPostRebalanceCatchup(SNAPSHOT);
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // handle the state change
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT_PAUSED_CONN1);
        PowerMock.expectLastCall();

        Capture<Callback<TargetState>> onPause = newCapture();
        worker.setTargetState(EasyMock.eq(CONN1), EasyMock.eq(TargetState.PAUSED), capture(onPause));
        PowerMock.expectLastCall().andAnswer(() -> {
            onPause.getValue().onCompletion(null, TargetState.STARTED);
            return null;
        });
        member.wakeup();
        PowerMock.expectLastCall();

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorTargetStateChange(CONN1); // state changes to paused
        herder.tick(); // apply state change

        PowerMock.verifyAll();
    }

    @Test
    public void testConnectorResumedRunningTaskOnly() throws Exception {
        // even if we don't own the connector, we should still propagate target state
        // changes to the worker so that tasks will transition correctly

        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.connectorNames()).andStubReturn(Collections.emptySet());

        // join
        expectRebalance(1, Collections.emptyList(), singletonList(TASK0));
        expectPostRebalanceCatchup(SNAPSHOT_PAUSED_CONN1);
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.PAUSED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // handle the state change
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);
        PowerMock.expectLastCall();

        Capture<Callback<TargetState>> onStart = newCapture();
        worker.setTargetState(EasyMock.eq(CONN1), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return null;
        });
        member.wakeup();
        PowerMock.expectLastCall();

        EasyMock.expect(worker.isRunning(CONN1)).andReturn(false);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // These will occur just before/during the third tick
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onConnectorTargetStateChange(CONN1); // state changes to paused
        herder.tick(); // apply state change
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testTaskConfigAdded() {
        // Task config always requires rebalance
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);

        // join
        expectRebalance(-1, Collections.emptyList(), Collections.emptyList());
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // apply config
        member.wakeup();
        PowerMock.expectLastCall();
        member.ensureActive();
        PowerMock.expectLastCall();
        // Checks for config updates and starts rebalance
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);
        member.requestRejoin();
        PowerMock.expectLastCall();
        // Performs rebalance and gets new assignment
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR, 1, Collections.emptyList(),
                Arrays.asList(TASK0));
        worker.startTask(EasyMock.eq(TASK0), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick(); // join
        configUpdateListener.onTaskConfigUpdate(Arrays.asList(TASK0, TASK1, TASK2)); // read updated config
        herder.tick(); // apply config
        herder.tick(); // do rebalance

        PowerMock.verifyAll();
    }

    @Test
    public void testJoinLeaderCatchUpFails() throws Exception {
        // Join group and as leader fail to do assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.CONFIG_MISMATCH, 1, Collections.emptyList(),
                Collections.emptyList());
        // Reading to end of log times out
        configBackingStore.refresh(EasyMock.anyLong(), EasyMock.anyObject(TimeUnit.class));
        EasyMock.expectLastCall().andThrow(new TimeoutException());
        member.maybeLeaveGroup(EasyMock.eq("taking too long to read the log"));
        EasyMock.expectLastCall();
        member.requestRejoin();

        // After backoff, restart the process and this time succeed
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);

        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);
        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // one more tick, to make sure we don't keep trying to read to the config topic unnecessarily
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        long before = time.milliseconds();
        int workerUnsyncBackoffMs = DistributedConfig.WORKER_UNSYNC_BACKOFF_MS_DEFAULT;
        int coordinatorDiscoveryTimeoutMs = 100;
        herder.tick();
        assertEquals(before + coordinatorDiscoveryTimeoutMs + workerUnsyncBackoffMs, time.milliseconds());

        time.sleep(1000L);
        assertStatistics("leaderUrl", true, 3, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        before = time.milliseconds();
        herder.tick();
        assertEquals(before + coordinatorDiscoveryTimeoutMs, time.milliseconds());
        time.sleep(2000L);
        assertStatistics("leaderUrl", false, 3, 1, 100, 2000L);

        // tick once more to ensure that the successful read to the end of the config topic was 
        // tracked and no further unnecessary attempts were made
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testJoinLeaderCatchUpRetriesForIncrementalCooperative() throws Exception {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;

        // Join group and as leader fail to do assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V1);
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // The leader got its assignment
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);

        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // Another rebalance is triggered but this time it fails to read to the max offset and
        // triggers a re-sync
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.CONFIG_MISMATCH, 1, Collections.emptyList(),
                Collections.emptyList());

        // The leader will retry a few times to read to the end of the config log
        int retries = 2;
        member.requestRejoin();
        for (int i = retries; i >= 0; --i) {
            // Reading to end of log times out
            configBackingStore.refresh(EasyMock.anyLong(), EasyMock.anyObject(TimeUnit.class));
            EasyMock.expectLastCall().andThrow(new TimeoutException());
            member.maybeLeaveGroup(EasyMock.eq("taking too long to read the log"));
            EasyMock.expectLastCall();
        }

        // After a few retries succeed to read the log to the end
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 2000);
        herder.tick();

        long before;
        int coordinatorDiscoveryTimeoutMs = 100;
        int maxRetries = 5;
        for (int i = maxRetries; i >= maxRetries - retries; --i) {
            before = time.milliseconds();
            int workerUnsyncBackoffMs =
                    DistributedConfig.SCHEDULED_REBALANCE_MAX_DELAY_MS_DEFAULT / 10 / i;
            herder.tick();
            assertEquals(before + coordinatorDiscoveryTimeoutMs + workerUnsyncBackoffMs, time.milliseconds());
            coordinatorDiscoveryTimeoutMs = 0;
        }

        before = time.milliseconds();
        coordinatorDiscoveryTimeoutMs = 100;
        herder.tick();
        assertEquals(before + coordinatorDiscoveryTimeoutMs, time.milliseconds());

        PowerMock.verifyAll();
    }

    @Test
    public void testJoinLeaderCatchUpFailsForIncrementalCooperative() throws Exception {
        connectProtocolVersion = CONNECT_PROTOCOL_V1;

        // Join group and as leader fail to do assignment
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V1);
        expectRebalance(1, Arrays.asList(CONN1), Arrays.asList(TASK1));
        expectPostRebalanceCatchup(SNAPSHOT);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // The leader got its assignment
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);

        EasyMock.expect(worker.getPlugins()).andReturn(plugins);
        // and the new assignment started
        Capture<Callback<TargetState>> onStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        member.wakeup();
        PowerMock.expectLastCall();
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        worker.startTask(EasyMock.eq(TASK1), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED));
        PowerMock.expectLastCall().andReturn(true);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // Another rebalance is triggered but this time it fails to read to the max offset and
        // triggers a re-sync
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.CONFIG_MISMATCH, 1, Collections.emptyList(),
                Collections.emptyList());

        // The leader will exhaust the retries while trying to read to the end of the config log
        int maxRetries = 5;
        member.requestRejoin();
        for (int i = maxRetries; i >= 0; --i) {
            // Reading to end of log times out
            configBackingStore.refresh(EasyMock.anyLong(), EasyMock.anyObject(TimeUnit.class));
            EasyMock.expectLastCall().andThrow(new TimeoutException());
            member.maybeLeaveGroup(EasyMock.eq("taking too long to read the log"));
            EasyMock.expectLastCall();
        }

        Capture<ExtendedAssignment> assignmentCapture = newCapture();
        member.revokeAssignment(capture(assignmentCapture));
        PowerMock.expectLastCall();

        // After a complete backoff and a revocation of running tasks rejoin and this time succeed
        // The worker gets back the assignment that had given up
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR,
                1, Arrays.asList(CONN1), Arrays.asList(TASK1), 0);
        expectPostRebalanceCatchup(SNAPSHOT);
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        assertStatistics(0, 0, 0, Double.POSITIVE_INFINITY);
        herder.tick();

        time.sleep(2000L);
        assertStatistics(3, 1, 100, 2000);
        herder.tick();

        long before;
        int coordinatorDiscoveryTimeoutMs = 100;
        for (int i = maxRetries; i > 0; --i) {
            before = time.milliseconds();
            int workerUnsyncBackoffMs =
                    DistributedConfig.SCHEDULED_REBALANCE_MAX_DELAY_MS_DEFAULT / 10 / i;
            herder.tick();
            assertEquals(before + coordinatorDiscoveryTimeoutMs + workerUnsyncBackoffMs, time.milliseconds());
            coordinatorDiscoveryTimeoutMs = 0;
        }

        before = time.milliseconds();
        herder.tick();
        assertEquals(before, time.milliseconds());
        assertEquals(Collections.singleton(CONN1), assignmentCapture.getValue().connectors());
        assertEquals(Collections.singleton(TASK1), assignmentCapture.getValue().tasks());
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testAccessors() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        EasyMock.expect(worker.getPlugins()).andReturn(plugins).anyTimes();
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT).times(2);

        WorkerConfigTransformer configTransformer = EasyMock.mock(WorkerConfigTransformer.class);
        EasyMock.expect(configTransformer.transform(EasyMock.eq(CONN1), EasyMock.anyObject()))
            .andThrow(new AssertionError("Config transformation should not occur when requesting connector or task info"));
        EasyMock.replay(configTransformer);
        ClusterConfigState snapshotWithTransform = new ClusterConfigState(1, null, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet(), configTransformer);

        expectPostRebalanceCatchup(snapshotWithTransform);


        member.wakeup();
        PowerMock.expectLastCall().anyTimes();
        // list connectors, get connector info, get connector config, get task configs
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();


        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        PowerMock.replayAll();

        FutureCallback<Collection<String>> listConnectorsCb = new FutureCallback<>();
        herder.connectors(listConnectorsCb);
        FutureCallback<ConnectorInfo> connectorInfoCb = new FutureCallback<>();
        herder.connectorInfo(CONN1, connectorInfoCb);
        FutureCallback<Map<String, String>> connectorConfigCb = new FutureCallback<>();
        herder.connectorConfig(CONN1, connectorConfigCb);
        FutureCallback<List<TaskInfo>> taskConfigsCb = new FutureCallback<>();
        herder.taskConfigs(CONN1, taskConfigsCb);

        herder.tick();
        assertTrue(listConnectorsCb.isDone());
        assertEquals(Collections.singleton(CONN1), listConnectorsCb.get());
        assertTrue(connectorInfoCb.isDone());
        ConnectorInfo info = new ConnectorInfo(CONN1, CONN1_CONFIG, Arrays.asList(TASK0, TASK1, TASK2),
            ConnectorType.SOURCE);
        assertEquals(info, connectorInfoCb.get());
        assertTrue(connectorConfigCb.isDone());
        assertEquals(CONN1_CONFIG, connectorConfigCb.get());
        assertTrue(taskConfigsCb.isDone());
        assertEquals(Arrays.asList(
                        new TaskInfo(TASK0, TASK_CONFIG),
                        new TaskInfo(TASK1, TASK_CONFIG),
                        new TaskInfo(TASK2, TASK_CONFIG)),
                taskConfigsCb.get());

        PowerMock.verifyAll();
    }

    @Test
    public void testPutConnectorConfig() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        expectRebalance(1, Arrays.asList(CONN1), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onFirstStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onFirstStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onFirstStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfig)).andReturn(TASK_CONFIGS);

        // list connectors, get connector info, get connector config, get task configs
        member.wakeup();
        PowerMock.expectLastCall().anyTimes();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // Poll loop for second round of calls
        member.ensureActive();
        PowerMock.expectLastCall();

        EasyMock.expect(worker.getPlugins()).andReturn(plugins).anyTimes();
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT);

        Capture<Callback<ConfigInfos>> validateCallback = newCapture();
        herder.validateConnectorConfig(EasyMock.eq(CONN1_CONFIG_UPDATED), capture(validateCallback));
        PowerMock.expectLastCall().andAnswer(() -> {
            validateCallback.getValue().onCompletion(null, CONN1_CONFIG_INFOS);
            return null;
        });
        configBackingStore.putConnectorConfig(CONN1, CONN1_CONFIG_UPDATED);
        PowerMock.expectLastCall().andAnswer(() -> {
            // Simulate response to writing config + waiting until end of log to be read
            configUpdateListener.onConnectorConfigUpdate(CONN1);
            return null;
        });
        // As a result of reconfig, should need to update snapshot. With only connector updates, we'll just restart
        // connector without rebalance
        EasyMock.expect(configBackingStore.snapshot()).andReturn(SNAPSHOT_UPDATED_CONN1_CONFIG).times(2);
        worker.stopAndAwaitConnector(CONN1);
        PowerMock.expectLastCall();
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V0);
        Capture<Callback<TargetState>> onSecondStart = newCapture();
        worker.startConnector(EasyMock.eq(CONN1), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.eq(herder), EasyMock.eq(TargetState.STARTED), capture(onSecondStart));
        PowerMock.expectLastCall().andAnswer(() -> {
            onSecondStart.getValue().onCompletion(null, TargetState.STARTED);
            return true;
        });
        EasyMock.expect(worker.isRunning(CONN1)).andReturn(true);
        EasyMock.expect(worker.connectorTaskConfigs(CONN1, conn1SinkConfigUpdated)).andReturn(TASK_CONFIGS);

        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        // Third tick just to read the config
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        // Should pick up original config
        FutureCallback<Map<String, String>> connectorConfigCb = new FutureCallback<>();
        herder.connectorConfig(CONN1, connectorConfigCb);
        herder.tick();
        assertTrue(connectorConfigCb.isDone());
        assertEquals(CONN1_CONFIG, connectorConfigCb.get());

        // Apply new config.
        FutureCallback<Herder.Created<ConnectorInfo>> putConfigCb = new FutureCallback<>();
        herder.putConnectorConfig(CONN1, CONN1_CONFIG_UPDATED, true, putConfigCb);
        herder.tick();
        assertTrue(putConfigCb.isDone());
        ConnectorInfo updatedInfo = new ConnectorInfo(CONN1, CONN1_CONFIG_UPDATED, Arrays.asList(TASK0, TASK1, TASK2),
            ConnectorType.SOURCE);
        assertEquals(new Herder.Created<>(false, updatedInfo), putConfigCb.get());

        // Check config again to validate change
        connectorConfigCb = new FutureCallback<>();
        herder.connectorConfig(CONN1, connectorConfigCb);
        herder.tick();
        assertTrue(connectorConfigCb.isDone());
        assertEquals(CONN1_CONFIG_UPDATED, connectorConfigCb.get());

        PowerMock.verifyAll();
    }

    @Test
    public void testKeyRotationWhenWorkerBecomesLeader() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V2);

        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        // First rebalance: poll indefinitely as no key has been read yet, so expiration doesn't come into play
        member.poll(Long.MAX_VALUE);
        EasyMock.expectLastCall();

        expectRebalance(2, Collections.emptyList(), Collections.emptyList());
        SessionKey initialKey = new SessionKey(EasyMock.mock(SecretKey.class), 0);
        ClusterConfigState snapshotWithKey =  new ClusterConfigState(2, initialKey, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet());
        expectPostRebalanceCatchup(snapshotWithKey);
        // Second rebalance: poll indefinitely as worker is follower, so expiration still doesn't come into play
        member.poll(Long.MAX_VALUE);
        EasyMock.expectLastCall();

        expectRebalance(2, Collections.emptyList(), Collections.emptyList(), "member", MEMBER_URL);
        Capture<SessionKey> updatedKey = EasyMock.newCapture();
        configBackingStore.putSessionKey(EasyMock.capture(updatedKey));
        EasyMock.expectLastCall().andAnswer(() -> {
            configUpdateListener.onSessionKeyUpdate(updatedKey.getValue());
            return null;
        });
        // Third rebalance: poll for a limited time as worker has become leader and must wake up for key expiration
        Capture<Long> pollTimeout = EasyMock.newCapture();
        member.poll(EasyMock.captureLong(pollTimeout));
        EasyMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        configUpdateListener.onSessionKeyUpdate(initialKey);
        herder.tick();
        herder.tick();

        assertTrue(pollTimeout.getValue() <= DistributedConfig.INTER_WORKER_KEY_TTL_MS_MS_DEFAULT);

        PowerMock.verifyAll();
    }

    @Test
    public void testKeyRotationDisabledWhenWorkerBecomesFollower() throws Exception {
        EasyMock.expect(member.memberId()).andStubReturn("member");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V2);

        expectRebalance(1, Collections.emptyList(), Collections.emptyList(), "member", MEMBER_URL);
        SecretKey initialSecretKey = EasyMock.mock(SecretKey.class);
        EasyMock.expect(initialSecretKey.getAlgorithm()).andReturn(DistributedConfig.INTER_WORKER_KEY_GENERATION_ALGORITHM_DEFAULT).anyTimes();
        EasyMock.expect(initialSecretKey.getEncoded()).andReturn(new byte[32]).anyTimes();
        SessionKey initialKey = new SessionKey(initialSecretKey, time.milliseconds());
        ClusterConfigState snapshotWithKey =  new ClusterConfigState(1, initialKey, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet());
        expectPostRebalanceCatchup(snapshotWithKey);
        // First rebalance: poll for a limited time as worker is leader and must wake up for key expiration
        Capture<Long> firstPollTimeout = EasyMock.newCapture();
        member.poll(EasyMock.captureLong(firstPollTimeout));
        EasyMock.expectLastCall();

        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        // Second rebalance: poll indefinitely as worker is no longer leader, so key expiration doesn't come into play
        member.poll(Long.MAX_VALUE);
        EasyMock.expectLastCall();

        PowerMock.replayAll(initialSecretKey);

        configUpdateListener.onSessionKeyUpdate(initialKey);
        herder.tick();
        assertTrue(firstPollTimeout.getValue() <= DistributedConfig.INTER_WORKER_KEY_TTL_MS_MS_DEFAULT);
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testPutTaskConfigsSignatureNotRequiredV0() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);

        member.wakeup();
        EasyMock.expectLastCall().once();
        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V0).anyTimes();
        PowerMock.replayAll(taskConfigCb);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, null);

        PowerMock.verifyAll();
    }
    @Test
    public void testPutTaskConfigsSignatureNotRequiredV1() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);

        member.wakeup();
        EasyMock.expectLastCall().once();
        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V1).anyTimes();
        PowerMock.replayAll(taskConfigCb);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, null);

        PowerMock.verifyAll();
    }

    @Test
    public void testPutTaskConfigsMissingRequiredSignature() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);
        Capture<Throwable> errorCapture = Capture.newInstance();
        taskConfigCb.onCompletion(capture(errorCapture), EasyMock.eq(null));
        EasyMock.expectLastCall().once();

        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V2).anyTimes();
        PowerMock.replayAll(taskConfigCb);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, null);

        PowerMock.verifyAll();
        assertTrue(errorCapture.getValue() instanceof BadRequestException);
    }

    @Test
    public void testPutTaskConfigsDisallowedSignatureAlgorithm() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);
        Capture<Throwable> errorCapture = Capture.newInstance();
        taskConfigCb.onCompletion(capture(errorCapture), EasyMock.eq(null));
        EasyMock.expectLastCall().once();

        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V2).anyTimes();

        InternalRequestSignature signature = EasyMock.mock(InternalRequestSignature.class);
        EasyMock.expect(signature.keyAlgorithm()).andReturn("HmacSHA489").anyTimes();

        PowerMock.replayAll(taskConfigCb, signature);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, signature);

        PowerMock.verifyAll();
        assertTrue(errorCapture.getValue() instanceof BadRequestException);
    }

    @Test
    public void testPutTaskConfigsInvalidSignature() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);
        Capture<Throwable> errorCapture = Capture.newInstance();
        taskConfigCb.onCompletion(capture(errorCapture), EasyMock.eq(null));
        EasyMock.expectLastCall().once();

        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V2).anyTimes();

        InternalRequestSignature signature = EasyMock.mock(InternalRequestSignature.class);
        EasyMock.expect(signature.keyAlgorithm()).andReturn("HmacSHA256").anyTimes();
        EasyMock.expect(signature.isValid(EasyMock.anyObject())).andReturn(false).anyTimes();

        PowerMock.replayAll(taskConfigCb, signature);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, signature);

        PowerMock.verifyAll();
        assertTrue(errorCapture.getValue() instanceof ConnectRestException);
        assertEquals(FORBIDDEN.getStatusCode(), ((ConnectRestException) errorCapture.getValue()).statusCode());
    }

    @Test
    public void testPutTaskConfigsValidRequiredSignature() {
        Callback<Void> taskConfigCb = EasyMock.mock(Callback.class);

        member.wakeup();
        EasyMock.expectLastCall().once();
        EasyMock.expect(member.currentProtocolVersion()).andReturn(CONNECT_PROTOCOL_V2).anyTimes();

        InternalRequestSignature signature = EasyMock.mock(InternalRequestSignature.class);
        EasyMock.expect(signature.keyAlgorithm()).andReturn("HmacSHA256").anyTimes();
        EasyMock.expect(signature.isValid(EasyMock.anyObject())).andReturn(true).anyTimes();

        PowerMock.replayAll(taskConfigCb, signature);

        herder.putTaskConfigs(CONN1, TASK_CONFIGS, taskConfigCb, signature);

        PowerMock.verifyAll();
    }

    @Test
    public void testFailedToWriteSessionKey() throws Exception {
        // First tick -- after joining the group, we try to write a new
        // session key to the config topic, and fail
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V2);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        configBackingStore.putSessionKey(anyObject(SessionKey.class));
        EasyMock.expectLastCall().andThrow(new ConnectException("Oh no!"));

        // Second tick -- we read to the end of the config topic first,
        // then ensure we're still active in the group
        // then try a second time to write a new session key,
        // then finally begin polling for group activity
        expectPostRebalanceCatchup(SNAPSHOT);
        member.ensureActive();
        PowerMock.expectLastCall();
        configBackingStore.putSessionKey(anyObject(SessionKey.class));
        EasyMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll();

        herder.tick();
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testFailedToReadBackNewlyWrittenSessionKey() throws Exception {
        SecretKey secretKey = EasyMock.niceMock(SecretKey.class);
        EasyMock.expect(secretKey.getAlgorithm()).andReturn(INTER_WORKER_KEY_GENERATION_ALGORITHM_DEFAULT);
        EasyMock.expect(secretKey.getEncoded()).andReturn(new byte[32]);
        SessionKey sessionKey = new SessionKey(secretKey, time.milliseconds());
        ClusterConfigState snapshotWithSessionKey = new ClusterConfigState(1, sessionKey, Collections.singletonMap(CONN1, 3),
            Collections.singletonMap(CONN1, CONN1_CONFIG), Collections.singletonMap(CONN1, TargetState.STARTED),
            TASK_CONFIGS_MAP, Collections.emptySet());

        // First tick -- after joining the group, we try to write a new session key to
        // the config topic, and fail (in this case, we're trying to simulate that we've
        // actually written the key successfully, but haven't been able to read it back
        // from the config topic, so to the herder it looks the same as if it'd just failed
        // to write the key)
        EasyMock.expect(member.memberId()).andStubReturn("leader");
        EasyMock.expect(member.currentProtocolVersion()).andStubReturn(CONNECT_PROTOCOL_V2);
        expectRebalance(1, Collections.emptyList(), Collections.emptyList());
        expectPostRebalanceCatchup(SNAPSHOT);
        configBackingStore.putSessionKey(anyObject(SessionKey.class));
        EasyMock.expectLastCall().andThrow(new ConnectException("Oh no!"));

        // Second tick -- we read to the end of the config topic first, and pick up
        // the session key that we were able to write the last time,
        // then ensure we're still active in the group
        // then finally begin polling for group activity
        // Importantly, we do not try to write a new session key this time around
        configBackingStore.refresh(EasyMock.anyLong(), EasyMock.anyObject(TimeUnit.class));
        EasyMock.expectLastCall().andAnswer(() -> {
            configUpdateListener.onSessionKeyUpdate(sessionKey);
            return null;
        });
        EasyMock.expect(configBackingStore.snapshot()).andReturn(snapshotWithSessionKey);
        member.ensureActive();
        PowerMock.expectLastCall();
        member.poll(EasyMock.anyInt());
        PowerMock.expectLastCall();

        PowerMock.replayAll(secretKey);

        herder.tick();
        herder.tick();

        PowerMock.verifyAll();
    }

    @Test
    public void testKeyExceptionDetection() {
        assertFalse(herder.isPossibleExpiredKeyException(
            time.milliseconds(),
            new RuntimeException()
        ));
        assertFalse(herder.isPossibleExpiredKeyException(
            time.milliseconds(),
            new BadRequestException("")
        ));
        assertFalse(herder.isPossibleExpiredKeyException(
            time.milliseconds() - TimeUnit.MINUTES.toMillis(2),
            new ConnectRestException(FORBIDDEN.getStatusCode(), "")
        ));
        assertTrue(herder.isPossibleExpiredKeyException(
            time.milliseconds(),
            new ConnectRestException(FORBIDDEN.getStatusCode(), "")
        ));
    }

    @Test
    public void testInconsistentConfigs() {
        // FIXME: if we have inconsistent configs, we need to request forced reconfig + write of the connector's task configs
        // This requires inter-worker communication, so needs the REST API
    }


    @Test
    public void testThreadNames() {
        assertTrue(Whitebox.<ThreadPoolExecutor>getInternalState(herder, "herderExecutor").
                getThreadFactory().newThread(EMPTY_RUNNABLE).getName().startsWith(DistributedHerder.class.getSimpleName()));

        assertTrue(Whitebox.<ThreadPoolExecutor>getInternalState(herder, "forwardRequestExecutor").
                getThreadFactory().newThread(EMPTY_RUNNABLE).getName().startsWith("ForwardRequestExecutor"));

        assertTrue(Whitebox.<ThreadPoolExecutor>getInternalState(herder, "startAndStopExecutor").
                getThreadFactory().newThread(EMPTY_RUNNABLE).getName().startsWith("StartAndStopExecutor"));
    }

    @Test
    public void testHerderStopServicesClosesUponShutdown() {
        assertEquals(1, shutdownCalled.getCount());
        herder.stopServices();
        assertEquals(0, shutdownCalled.getCount());
    }

    private void expectRebalance(final long offset,
                                 final List<String> assignedConnectors,
                                 final List<ConnectorTaskId> assignedTasks) {
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR, offset, assignedConnectors, assignedTasks, 0);
    }

    private void expectRebalance(final long offset,
                                 final List<String> assignedConnectors,
                                 final List<ConnectorTaskId> assignedTasks,
                                 String leader, String leaderUrl) {
        expectRebalance(Collections.emptyList(), Collections.emptyList(),
                ConnectProtocol.Assignment.NO_ERROR, offset, leader, leaderUrl, assignedConnectors, assignedTasks, 0);
    }

    // Handles common initial part of rebalance callback. Does not handle instantiation of connectors and tasks.
    private void expectRebalance(final Collection<String> revokedConnectors,
                                 final List<ConnectorTaskId> revokedTasks,
                                 final short error,
                                 final long offset,
                                 final List<String> assignedConnectors,
                                 final List<ConnectorTaskId> assignedTasks) {
        expectRebalance(revokedConnectors, revokedTasks, error, offset, assignedConnectors, assignedTasks, 0);
    }

    // Handles common initial part of rebalance callback. Does not handle instantiation of connectors and tasks.
    private void expectRebalance(final Collection<String> revokedConnectors,
                                 final List<ConnectorTaskId> revokedTasks,
                                 final short error,
                                 final long offset,
                                 final List<String> assignedConnectors,
                                 final List<ConnectorTaskId> assignedTasks,
                                 int delay) {
        expectRebalance(revokedConnectors, revokedTasks, error, offset, "leader", "leaderUrl", assignedConnectors, assignedTasks, delay);
    }

    // Handles common initial part of rebalance callback. Does not handle instantiation of connectors and tasks.
    private void expectRebalance(final Collection<String> revokedConnectors,
                                 final List<ConnectorTaskId> revokedTasks,
                                 final short error,
                                 final long offset,
                                 String leader,
                                 String leaderUrl,
                                 final List<String> assignedConnectors,
                                 final List<ConnectorTaskId> assignedTasks,
                                 int delay) {
        member.ensureActive();
        PowerMock.expectLastCall().andAnswer(() -> {
            ExtendedAssignment assignment;
            if (!revokedConnectors.isEmpty() || !revokedTasks.isEmpty()) {
                rebalanceListener.onRevoked(leader, revokedConnectors, revokedTasks);
            }

            if (connectProtocolVersion == CONNECT_PROTOCOL_V0) {
                assignment = new ExtendedAssignment(
                        connectProtocolVersion, error, leader, leaderUrl, offset,
                        assignedConnectors, assignedTasks,
                        Collections.emptyList(), Collections.emptyList(), 0);
            } else {
                assignment = new ExtendedAssignment(
                        connectProtocolVersion, error, leader, leaderUrl, offset,
                        assignedConnectors, assignedTasks,
                        new ArrayList<>(revokedConnectors), new ArrayList<>(revokedTasks), delay);
            }
            rebalanceListener.onAssigned(assignment, 3);
            time.sleep(100L);
            return null;
        });

        if (!revokedConnectors.isEmpty()) {
            for (String connector : revokedConnectors) {
                worker.stopAndAwaitConnector(connector);
                PowerMock.expectLastCall();
            }
        }

        if (!revokedTasks.isEmpty()) {
            worker.stopAndAwaitTask(EasyMock.anyObject(ConnectorTaskId.class));
            PowerMock.expectLastCall();
        }

        if (!revokedConnectors.isEmpty()) {
            statusBackingStore.flush();
            PowerMock.expectLastCall();
        }

        member.wakeup();
        PowerMock.expectLastCall();
    }

    private void expectPostRebalanceCatchup(final ClusterConfigState readToEndSnapshot) throws TimeoutException {
        configBackingStore.refresh(EasyMock.anyLong(), EasyMock.anyObject(TimeUnit.class));
        EasyMock.expectLastCall();
        EasyMock.expect(configBackingStore.snapshot()).andReturn(readToEndSnapshot);
    }

    private void assertStatistics(int expectedEpoch, int completedRebalances, double rebalanceTime, double millisSinceLastRebalance) {
        String expectedLeader = completedRebalances <= 0 ? null : "leaderUrl";
        assertStatistics(expectedLeader, false, expectedEpoch, completedRebalances, rebalanceTime, millisSinceLastRebalance);
    }

    private void assertStatistics(String expectedLeader, boolean isRebalancing, int expectedEpoch, int completedRebalances, double rebalanceTime, double millisSinceLastRebalance) {
        HerderMetrics herderMetrics = herder.herderMetrics();
        MetricGroup group = herderMetrics.metricGroup();
        double epoch = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "epoch");
        String leader = MockConnectMetrics.currentMetricValueAsString(metrics, group, "leader-name");
        double rebalanceCompletedTotal = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "completed-rebalances-total");
        double rebalancing = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "rebalancing");
        double rebalanceTimeMax = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "rebalance-max-time-ms");
        double rebalanceTimeAvg = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "rebalance-avg-time-ms");
        double rebalanceTimeSinceLast = MockConnectMetrics.currentMetricValueAsDouble(metrics, group, "time-since-last-rebalance-ms");

        assertEquals(expectedEpoch, epoch, 0.0001d);
        assertEquals(expectedLeader, leader);
        assertEquals(completedRebalances, rebalanceCompletedTotal, 0.0001d);
        assertEquals(isRebalancing ? 1.0d : 0.0d, rebalancing, 0.0001d);
        assertEquals(millisSinceLastRebalance, rebalanceTimeSinceLast, 0.0001d);
        if (rebalanceTime <= 0L) {
            assertEquals(Double.NaN, rebalanceTimeMax, 0.0001d);
            assertEquals(Double.NaN, rebalanceTimeAvg, 0.0001d);
        } else {
            assertEquals(rebalanceTime, rebalanceTimeMax, 0.0001d);
            assertEquals(rebalanceTime, rebalanceTimeAvg, 0.0001d);
        }
    }

    @Test
    public void processRestartRequestsFailureSuppression() {
        member.wakeup();
        PowerMock.expectLastCall().anyTimes();

        final String connectorName = "foo";
        RestartRequest restartRequest = new RestartRequest(connectorName, false, false);
        EasyMock.expect(herder.buildRestartPlan(restartRequest)).andThrow(new RuntimeException()).anyTimes();

        PowerMock.replayAll();

        configUpdateListener.onRestartRequest(restartRequest);
        assertEquals(1, herder.pendingRestartRequests.size());
        herder.processRestartRequests();
        assertTrue(herder.pendingRestartRequests.isEmpty());
    }

    @Test
    public void processRestartRequestsDequeue() {
        member.wakeup();
        PowerMock.expectLastCall().anyTimes();

        EasyMock.expect(herder.buildRestartPlan(EasyMock.anyObject(RestartRequest.class))).andReturn(Optional.empty()).anyTimes();

        PowerMock.replayAll();

        RestartRequest restartRequest = new RestartRequest("foo", false, false);
        configUpdateListener.onRestartRequest(restartRequest);
        restartRequest = new RestartRequest("bar", false, false);
        configUpdateListener.onRestartRequest(restartRequest);
        assertEquals(2, herder.pendingRestartRequests.size());
        herder.processRestartRequests();
        assertTrue(herder.pendingRestartRequests.isEmpty());
    }

    @Test
    public void preserveHighestImpactRestartRequest() {
        member.wakeup();
        PowerMock.expectLastCall().anyTimes();
        PowerMock.replayAll();

        final String connectorName = "foo";
        RestartRequest restartRequest = new RestartRequest(connectorName, false, false);
        configUpdateListener.onRestartRequest(restartRequest);

        //will overwrite as this is higher impact
        restartRequest = new RestartRequest(connectorName, false, true);
        configUpdateListener.onRestartRequest(restartRequest);
        assertEquals(1, herder.pendingRestartRequests.size());
        assertFalse(herder.pendingRestartRequests.get(connectorName).onlyFailed());
        assertTrue(herder.pendingRestartRequests.get(connectorName).includeTasks());

        //will be ignored as the existing request has higher impact
        restartRequest = new RestartRequest(connectorName, true, false);
        configUpdateListener.onRestartRequest(restartRequest);
        assertEquals(1, herder.pendingRestartRequests.size());
        //compare against existing request
        assertFalse(herder.pendingRestartRequests.get(connectorName).onlyFailed());
        assertTrue(herder.pendingRestartRequests.get(connectorName).includeTasks());
    }

    // We need to use a real class here due to some issue with mocking java.lang.Class
    private abstract class BogusSourceConnector extends SourceConnector {
    }

    private abstract class BogusSourceTask extends SourceTask {
    }

    private DistributedHerder exactlyOnceHerder() {
        Map<String, String> config = new HashMap<>(HERDER_CONFIG);
        config.put(EXACTLY_ONCE_SOURCE_SUPPORT_CONFIG, "enabled");
        return PowerMock.createPartialMock(DistributedHerder.class,
                new String[]{"connectorTypeForClass", "updateDeletedConnectorStatus", "updateDeletedTaskStatus", "validateConnectorConfig"},
                new DistributedConfig(config), worker, WORKER_ID, KAFKA_CLUSTER_ID,
                statusBackingStore, configBackingStore, member, MEMBER_URL, metrics, time, noneConnectorClientConfigOverridePolicy,
                new AutoCloseable[0]);
    }

}
