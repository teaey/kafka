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

package org.apache.kafka.controller;


public interface ControllerMetrics extends AutoCloseable {
    void setActive(boolean active);

    boolean active();

    void updateEventQueueTime(long durationMs);

    void updateEventQueueProcessingTime(long durationMs);

    void setFencedBrokerCount(int brokerCount);

    int fencedBrokerCount();

    void setActiveBrokerCount(int brokerCount);

    int activeBrokerCount();

    void setGlobalTopicsCount(int topicCount);

    int globalTopicsCount();

    void setGlobalPartitionCount(int partitionCount);

    int globalPartitionCount();

    void setOfflinePartitionCount(int offlinePartitions);

    int offlinePartitionCount();

    void setPreferredReplicaImbalanceCount(int replicaImbalances);

    int preferredReplicaImbalanceCount();

    void setLastAppliedRecordOffset(long offset);

    long lastAppliedRecordOffset();

    void setLastCommittedRecordOffset(long offset);

    long lastCommittedRecordOffset();

    void setLastAppliedRecordTimestamp(long timestamp);

    long lastAppliedRecordTimestamp();

    void close();
}
