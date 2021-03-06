/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.streams.StreamingConfig;
import org.apache.kafka.streams.StreamingMetrics;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A StreamTask is associated with a {@link PartitionGroup}, and is assigned to a StreamThread for processing.
 */
public class StreamTask implements Punctuator {

    private static final Logger log = LoggerFactory.getLogger(StreamTask.class);

    private final int id;
    private final int maxBufferedSize;

    private final Consumer consumer;
    private final PartitionGroup partitionGroup;
    private final PartitionGroup.RecordInfo recordInfo = new PartitionGroup.RecordInfo();
    private final PunctuationQueue punctuationQueue;
    private final ProcessorContextImpl processorContext;
    private final ProcessorTopology topology;

    private final Map<TopicPartition, Long> consumedOffsets;
    private final RecordCollector recordCollector;
    private final ProcessorStateManager stateMgr;

    private boolean commitRequested = false;
    private boolean commitOffsetNeeded = false;
    private StampedRecord currRecord = null;
    private ProcessorNode currNode = null;

    /**
     * Create {@link StreamTask} with its assigned partitions
     *
     * @param id                    the ID of this task
     * @param consumer              the instance of {@link Consumer}
     * @param producer              the instance of {@link Producer}
     * @param restoreConsumer       the instance of {@link Consumer} used when restoring state
     * @param partitions            the collection of assigned {@link TopicPartition}
     * @param topology              the instance of {@link ProcessorTopology}
     * @param config                the {@link StreamingConfig} specified by the user
     * @param metrics               the {@link StreamingMetrics} created by the thread
     */
    public StreamTask(int id,
                      Consumer<byte[], byte[]> consumer,
                      Producer<byte[], byte[]> producer,
                      Consumer<byte[], byte[]> restoreConsumer,
                      Collection<TopicPartition> partitions,
                      ProcessorTopology topology,
                      StreamingConfig config,
                      StreamingMetrics metrics) {

        this.id = id;
        this.consumer = consumer;
        this.punctuationQueue = new PunctuationQueue();
        this.maxBufferedSize = config.getInt(StreamingConfig.BUFFERED_RECORDS_PER_PARTITION_CONFIG);
        this.topology = topology;

        // create queues for each assigned partition and associate them
        // to corresponding source nodes in the processor topology
        Map<TopicPartition, RecordQueue> partitionQueues = new HashMap<>();

        for (TopicPartition partition : partitions) {
            SourceNode source = topology.source(partition.topic());
            RecordQueue queue = createRecordQueue(partition, source);
            partitionQueues.put(partition, queue);
        }

        TimestampExtractor timestampExtractor = config.getConfiguredInstance(StreamingConfig.TIMESTAMP_EXTRACTOR_CLASS_CONFIG, TimestampExtractor.class);
        this.partitionGroup = new PartitionGroup(partitionQueues, timestampExtractor);

        // initialize the consumed and produced offset cache
        this.consumedOffsets = new HashMap<>();

        // create the record recordCollector that maintains the produced offsets
        this.recordCollector = new RecordCollector(producer);

        log.info("Creating restoration consumer client for stream task [" + id + "]");

        // create the processor state manager
        try {
            File stateFile = new File(config.getString(StreamingConfig.STATE_DIR_CONFIG), Integer.toString(id));
            this.stateMgr = new ProcessorStateManager(id, stateFile, restoreConsumer);
        } catch (IOException e) {
            throw new KafkaException("Error while creating the state manager", e);
        }

        // initialize the topology with its own context
        this.processorContext = new ProcessorContextImpl(id, this, config, recordCollector, stateMgr, metrics);

        // initialize the task by initializing all its processor nodes in the topology
        for (ProcessorNode node : this.topology.processors()) {
            this.currNode = node;
            try {
                node.init(this.processorContext);
            } finally {
                this.currNode = null;
            }
        }

        this.processorContext.initialized();
    }

    public int id() {
        return id;
    }

    public Set<TopicPartition> partitions() {
        return this.partitionGroup.partitions();
    }

    /**
     * Adds records to queues
     *
     * @param partition the partition
     * @param records  the records
     */
    @SuppressWarnings("unchecked")
    public void addRecords(TopicPartition partition, Iterable<ConsumerRecord<byte[], byte[]>> records) {
        int queueSize = partitionGroup.addRawRecords(partition, records);

        // if after adding these records, its partition queue's buffered size has been
        // increased beyond the threshold, we can then pause the consumption for this partition
        if (queueSize > this.maxBufferedSize) {
            consumer.pause(partition);
        }
    }

    /**
     * Process one record
     *
     * @return number of records left in the buffer of this task's partition group after the processing is done
     */
    @SuppressWarnings("unchecked")
    public int process() {
        synchronized (this) {
            // get the next record to process
            StampedRecord record = partitionGroup.nextRecord(recordInfo);

            // if there is no record to process, return immediately
            if (record == null)
                return 0;

            try {
                // process the record by passing to the source node of the topology
                this.currRecord = record;
                this.currNode = recordInfo.node();
                TopicPartition partition = recordInfo.partition();

                log.debug("Start processing one record [" + currRecord + "]");

                this.currNode.process(currRecord.key(), currRecord.value());

                log.debug("Completed processing one record [" + currRecord + "]");

                // update the consumed offset map after processing is done
                consumedOffsets.put(partition, currRecord.offset());
                commitOffsetNeeded = true;

                // after processing this record, if its partition queue's buffered size has been
                // decreased to the threshold, we can then resume the consumption on this partition
                if (partitionGroup.numBuffered(partition) == this.maxBufferedSize) {
                    consumer.resume(partition);
                }
            } finally {
                this.currRecord = null;
                this.currNode = null;
            }

            return partitionGroup.numBuffered();
        }
    }

    /**
     * Possibly trigger registered punctuation functions if
     * current time has reached the defined stamp
     *
     * @param timestamp
     */
    public boolean maybePunctuate(long timestamp) {
        return punctuationQueue.mayPunctuate(timestamp, this);
    }

    @Override
    public void punctuate(ProcessorNode node, long timestamp) {
        if (currNode != null)
            throw new IllegalStateException("Current node is not null");

        currNode = node;
        try {
            node.processor().punctuate(timestamp);
        } finally {
            currNode = null;
        }
    }

    public StampedRecord record() {
        return this.currRecord;
    }

    public ProcessorNode node() {
        return this.currNode;
    }

    public ProcessorTopology topology() {
        return this.topology;
    }

    /**
     * Commit the current task state
     */
    public void commit() {
        // 1) flush produced records in the downstream and change logs of local states
        recordCollector.flush();

        // 2) flush local state
        stateMgr.flush();

        // 3) commit consumed offsets if it is dirty already
        if (commitOffsetNeeded) {
            Map<TopicPartition, OffsetAndMetadata> consumedOffsetsAndMetadata = new HashMap<>(consumedOffsets.size());
            for (Map.Entry<TopicPartition, Long> entry : consumedOffsets.entrySet()) {
                consumedOffsetsAndMetadata.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
            }
            consumer.commitSync(consumedOffsetsAndMetadata);
            commitOffsetNeeded = false;
        }

        commitRequested = false;
    }

    /**
     * Whether or not a request has been made to commit the current state
     */
    public boolean commitNeeded() {
        return this.commitRequested;
    }

    /**
     * Request committing the current task's state
     */
    public void needCommit() {
        this.commitRequested = true;
    }

    /**
     * Schedules a punctuation for the processor
     *
     * @param interval  the interval in milliseconds
     */
    public void schedule(long interval) {
        if (currNode == null)
            throw new IllegalStateException("Current node is null");

        punctuationQueue.schedule(new PunctuationSchedule(currNode, interval));
    }

    public void close() {
        this.partitionGroup.close();
        this.consumedOffsets.clear();

        // close the processors
        // make sure close() is called for each node even when there is a RuntimeException
        RuntimeException exception = null;
        for (ProcessorNode node : this.topology.processors()) {
            currNode = node;
            try {
                node.close();
            } catch (RuntimeException e) {
                exception = e;
            } finally {
                currNode = null;
            }
        }

        if (exception != null)
            throw exception;

        try {
            stateMgr.close(recordCollector.offsets());
        } catch (IOException e) {
            throw new KafkaException("Error while closing the state manager in processor context", e);
        }
    }

    private RecordQueue createRecordQueue(TopicPartition partition, SourceNode source) {
        return new RecordQueue(partition, source);
    }

    @SuppressWarnings("unchecked")
    public <K, V> void forward(K key, V value) {
        ProcessorNode thisNode = currNode;
        for (ProcessorNode childNode : (List<ProcessorNode<K, V>>) thisNode.children()) {
            currNode = childNode;
            try {
                childNode.process(key, value);
            } finally {
                currNode = thisNode;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <K, V> void forward(K key, V value, int childIndex) {
        ProcessorNode thisNode = currNode;
        ProcessorNode childNode = (ProcessorNode<K, V>) thisNode.children().get(childIndex);
        currNode = childNode;
        try {
            childNode.process(key, value);
        } finally {
            currNode = thisNode;
        }
    }

    public ProcessorContext context() {
        return processorContext;
    }

}
