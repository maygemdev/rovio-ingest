/*
 * Copyright 2021 Rovio Entertainment Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rovio.ingest;

import com.rovio.ingest.model.Field;
import com.rovio.ingest.model.FieldType;
import com.rovio.ingest.model.SegmentSpec;
import com.rovio.ingest.util.MetadataUpdater;
import com.rovio.ingest.util.SegmentStorageUpdater;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.loading.DataSegmentKiller;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.connector.write.BatchWrite;
import org.apache.spark.sql.connector.write.DataWriter;
import org.apache.spark.sql.connector.write.DataWriterFactory;
import org.apache.spark.sql.connector.write.PhysicalWriteInfo;
import org.apache.spark.sql.connector.write.WriterCommitMessage;
import org.apache.spark.sql.types.StructType;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

class DruidDataSourceWriter implements BatchWrite {
    private static final Logger LOG = LoggerFactory.getLogger(DruidDataSourceWriter.class);

    private final WriterContext param;
    private final SegmentSpec segmentSpec;
    private final MetadataUpdater metadataUpdater;

    DruidDataSourceWriter(StructType schema, WriterContext param) {
        if (param.isInitDataSource() && param.isAppend()) {
            // in append mode we don't know all "active" segments, thus can't properly mark other segments as unused
            throw new IllegalStateException("Initializing datasource with append write mode is not supported");
        }
        this.param = param;
        this.segmentSpec = SegmentSpec.from(param.getDataSource(),param.getTimeColumn(), param.getExcludedDimensions(),
                param.getSegmentGranularity(), param.getQueryGranularity(), schema, param.isRollup(),
                param.getDimensionsSpec(), param.getMetricsSpec(), param.getTransformSpec());
        this.metadataUpdater = new MetadataUpdater(param);
        if (param.isRangePartitioned()) {
            validateRangePartitioning(param, segmentSpec);
        }
    }

    private static void validateRangePartitioning(WriterContext param, SegmentSpec segmentSpec) {
        if (param.isAppend()) {
            // Appended segments are never part of a range partitioned core set. Appending to a range partitioned
            // interval is supported, see TaskDataWriter#findAppendTarget.
            throw new IllegalStateException(String.format("\"%s\" is not supported with append write mode",
                    WriterContext.ConfKeys.PARTITION_DIMENSIONS));
        }
        if (segmentSpec.getPartitionTime() == null || segmentSpec.getPartitionNum() == null) {
            throw new IllegalStateException(String.format("\"%s\" requires that the dataset has columns " +
                    "__PARTITION_TIME__ and __PARTITION_NUM__, where __PARTITION_NUM__ is the number of the bucket " +
                    "of the row, and that the dataset is partitioned by them",
                    WriterContext.ConfKeys.PARTITION_DIMENSIONS));
        }

        // The dimensions of the segments, i.e. not metrics and not excluded.
        DimensionsSpec dimensionsSpec = segmentSpec.getDataSchema().getDimensionsSpec();
        Set<String> segmentDimensions = new HashSet<>(dimensionsSpec.getDimensionNames());
        for (String dimension : param.getPartitionDimensions()) {
            if (!segmentDimensions.contains(dimension)) {
                throw new IllegalArgumentException(String.format("Invalid \"%s\": \"%s\" is not a dimension of the " +
                                "segments. Dimensions are %s", WriterContext.ConfKeys.PARTITION_DIMENSIONS, dimension,
                        segmentDimensions));
            }
            FieldType fieldType = segmentSpec.getFields().stream()
                    .filter(f -> f.getName().equals(dimension))
                    .findFirst()
                    .map(Field::getFieldType)
                    .orElseThrow(() -> new IllegalArgumentException(String.format(
                            "Invalid \"%s\": \"%s\" is not a column of the dataset",
                            WriterContext.ConfKeys.PARTITION_DIMENSIONS, dimension)));
            // As in Druid range partitioning, only strings. For other types the tuple of a row would not be what is
            // stored in the segment, e.g. Druid stores a null long dimension as 0 when default values are used for null,
            // so the broker could prune a segment that has matching rows.
            if (fieldType != FieldType.STRING) {
                throw new IllegalArgumentException(String.format("Invalid \"%s\": \"%s\" is of type %s, only string " +
                                "dimensions are supported", WriterContext.ConfKeys.PARTITION_DIMENSIONS,
                        dimension, fieldType));
            }
            // The type that the segments store the dimension as is not always the type of the column:
            // druid.dimensions_spec decides it. A string column stored as e.g. long (so "012" is stored as 12) would
            // not be in the bucket that its string value tells, and the broker could prune a segment that has
            // matching rows.
            ColumnType storedType = dimensionsSpec.getSchema(dimension).getColumnType();
            if (!ColumnType.STRING.equals(storedType)) {
                throw new IllegalArgumentException(String.format("Invalid \"%s\": \"%s\" is stored as %s, as defined " +
                                "by \"%s\", only string dimensions are supported",
                        WriterContext.ConfKeys.PARTITION_DIMENSIONS, dimension, storedType,
                        WriterContext.ConfKeys.DIMENSIONS_SPEC));
            }
        }
    }

    @Override
    public DataWriterFactory createBatchWriterFactory(PhysicalWriteInfo physicalWriteInfo) {
        return new TaskWriterFactory(param, segmentSpec);
    }

    @Override
    public final boolean useCommitCoordinator() {
        return true;
    }

    @Override
    public final void onDataWriterCommit(WriterCommitMessage message) {
        // ignored.
    }

    @Override
    public final void commit(WriterCommitMessage[] messages) {
        List<DataSegment> dataSegments = toDataSegments(messages);
        if (param.isRangePartitioned()) {
            // Throwing here makes Spark abort the write, which removes the pushed segments, see #abort.
            checkRangeCoreSet(dataSegments);
        }
        if (metadataUpdater != null) {
            metadataUpdater.publishSegments(dataSegments);
        }
    }

    @Override
    public final void abort(WriterCommitMessage[] messages) {
        List<DataSegment> dataSegments = toDataSegments(messages);
        DataSegmentKiller segmentKiller = SegmentStorageUpdater.createKiller(param);
        dataSegments.forEach(segmentKiller::killQuietly);
    }

    /**
     * Checks that the pushed segments are a complete range partitioned core set of a single interval: there is exactly
     * one segment for each of the buckets 0..K-1, where K is the number of core partitions that the segments were
     * written with.
     * <p>
     * The segments of a version are visible only when all of its core partitions are loaded, so a missing bucket would
     * make the whole interval invisible. It is not fixed by renumbering the partition nums, as Druid does when it
     * ingests, because the files of the segments stay in the deep storage under the number of their bucket, and
     * segments that are appended to the interval later get numbers from the end of the partition nums, which could
     * then be the number of an existing directory.
     */
    private void checkRangeCoreSet(List<DataSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }

        Set<Interval> intervals = segments.stream().map(DataSegment::getInterval).collect(Collectors.toSet());
        if (intervals.size() > 1) {
            throw new IllegalStateException(String.format("\"%s\" supports only a single interval in a write, but " +
                            "the segments are of %d intervals: %s. Write the intervals separately, each with its own " +
                            "boundaries.", WriterContext.ConfKeys.PARTITION_DIMENSIONS, intervals.size(), intervals));
        }
        Interval interval = intervals.iterator().next();

        // The number of buckets is limited to less than 32768 on the driver, see WriterContext.
        int numCorePartitions = ((DimensionRangeShardSpec) segments.get(0).getShardSpec()).getNumCorePartitions();

        Set<Integer> buckets = new HashSet<>();
        for (DataSegment segment : segments) {
            int bucket = segment.getShardSpec().getPartitionNum();
            if (!buckets.add(bucket)) {
                // Both tasks pushed to the same path in the deep storage, so one of them is already lost.
                throw new IllegalStateException(String.format("Bucket (partition num) %d of interval %s was written " +
                                "by several Spark tasks. Each bucket must be written by a single task: partition " +
                                "the dataset by __PARTITION_NUM__ before writing, e.g. with " +
                                "repartition(col(\"__PARTITION_NUM__\")).", bucket, interval));
            }
        }

        List<Integer> missing = IntStream.range(0, numCorePartitions)
                .filter(bucket -> !buckets.contains(bucket))
                .boxed()
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(String.format("Buckets %s of the %d buckets of interval %s have no rows, " +
                            "so the core set of segments would be incomplete and Druid would not serve the interval. " +
                            "Compute \"%s\" from the data that is written, e.g. with DruidRangePartitioning from the " +
                            "same (cached) dataset, which doesn't produce empty buckets.",
                    missing.size() > 10 ? missing.subList(0, 10) + "... (" + missing.size() + " in all)" : missing,
                    numCorePartitions, interval, WriterContext.ConfKeys.PARTITION_BOUNDARIES));
        }
        LOG.info("Interval {}: range partitioned core set of {} segments", interval, numCorePartitions);
    }

    private List<DataSegment> toDataSegments(WriterCommitMessage[] messages) {
        try {
            List<DataSegment> segments = new ArrayList<>();
            for (WriterCommitMessage message : messages) {
                DataSegmentCommitMessage segmentCommitMessage = (DataSegmentCommitMessage) message;
                if (segmentCommitMessage != null) {
                    segments.addAll(segmentCommitMessage.getSegments());
                }
            }
            return segments;
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize data segments", e);
        }
    }

    private static class TaskWriterFactory implements DataWriterFactory {

        private final WriterContext params;
        private final SegmentSpec segmentSpec;

        TaskWriterFactory(WriterContext params, SegmentSpec segmentSpec) {
            this.params = params;
            this.segmentSpec = segmentSpec;
        }

        @Override
        public DataWriter<InternalRow> createWriter(int partitionId, long taskId) {
            return new TaskDataWriter(taskId, params, segmentSpec);
        }
    }
}
