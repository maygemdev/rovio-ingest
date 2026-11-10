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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rovio.ingest.model.Field;
import com.rovio.ingest.model.FieldType;
import com.rovio.ingest.model.SegmentSpec;
import com.rovio.ingest.util.MetadataUpdater;
import com.rovio.ingest.util.MetadataUpdater.VersionedShardSpec;
import com.rovio.ingest.util.RangeBuckets;
import com.rovio.ingest.util.ReflectionUtils;
import com.rovio.ingest.util.SegmentStorageUpdater;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.common.config.NullValueHandlingConfig;
import org.apache.druid.data.input.Committer;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.IndexMerger;
import org.apache.druid.segment.IndexMergerV9;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.data.BitmapSerdeFactory;
import org.apache.druid.segment.data.ConciseBitmapSerdeFactory;
import org.apache.druid.segment.data.RoaringBitmapSerdeFactory;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.indexing.RealtimeTuningConfig;
import org.apache.druid.segment.loading.DataSegmentKiller;
import org.apache.druid.segment.loading.DataSegmentPusher;
import org.apache.druid.segment.nested.StructuredData;
import org.apache.druid.segment.realtime.FireDepartmentMetrics;
import org.apache.druid.segment.realtime.appenderator.Appenderator;
import org.apache.druid.segment.realtime.appenderator.DefaultOfflineAppenderatorFactory;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.segment.realtime.appenderator.SegmentNotWritableException;
import org.apache.druid.segment.realtime.appenderator.SegmentsAndCommitMetadata;
import org.apache.druid.segment.realtime.plumber.Committers;
import org.apache.druid.segment.realtime.plumber.CustomVersioningPolicy;
import org.apache.druid.segment.writeout.TmpFileSegmentWriteOutMediumFactory;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.BaseDimensionRangeShardSpec;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.util.ArrayData;
import org.apache.spark.sql.connector.write.DataWriter;
import org.apache.spark.sql.connector.write.WriterCommitMessage;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static java.lang.String.format;

class TaskDataWriter implements DataWriter<InternalRow> {
    private static final IndexIO INDEX_IO = new IndexIO(MAPPER, ColumnConfig.DEFAULT);
    private static final IndexMerger INDEX_MERGER_V_9 = new IndexMergerV9(MAPPER, INDEX_IO, TmpFileSegmentWriteOutMediumFactory.instance());
    private static final Logger LOG = LoggerFactory.getLogger(TaskDataWriter.class);

    private final long taskId;
    private final SegmentSpec segmentSpec;
    private final int maxRows;
    private final DataSchema dataSchema;
    private final RealtimeTuningConfig tuningConfig;
    private final Appenderator appenderator;
    private final DataSegmentKiller segmentKiller;
    private final Supplier<Committer> committerSupplier;
    private final Set<DataSegment> pushedSegments;
    private final File basePersistDirectory;
    private final WriterContext context;
    private final MetadataUpdater metadataUpdater;
    private final Map<Interval, VersionWithPartitionNum> intervalVersionMap;
    // Only in range partitioned mode, see WriterContext#isRangePartitioned
    private final RangeBuckets rangeBuckets;
    private final Map<Interval, SegmentIdWithShardSpec[]> rangeSegmentIds;

    private SegmentIdWithShardSpec current;

    TaskDataWriter(long taskId, WriterContext context, SegmentSpec segmentSpec) {
        this.taskId = taskId;
        this.segmentSpec = segmentSpec;
        this.maxRows = context.getSegmentMaxRows();
        this.dataSchema = segmentSpec.getDataSchema();
        this.context = context;
        this.metadataUpdater = context.isAppend() ? new MetadataUpdater(context) : null; // don't make a connection if it's overwrite (we will not need it)

        this.tuningConfig = getTuningConfig(context);
        DataSegmentPusher segmentPusher = SegmentStorageUpdater.createPusher(context);
        // Similar code for creating a basePersistDirectory was removed in https://github.com/apache/druid/pull/13040
        // with a mention that basePersistDirectory "is always overridden in production using withBasePersistDirectory given some subdirectory of the task work directory".
        // Creating a temp folder manually here, as appenderator.startJob() requires it, but there doesn't seem to be an easy way to get it provided at this point(?).
        // To clean up, this dir is deleted in #close
        this.basePersistDirectory = FileUtils.createTempDir("rovio-ingest-persist-");
        this.segmentKiller = SegmentStorageUpdater.createKiller(context);
        this.appenderator = new DefaultOfflineAppenderatorFactory(segmentPusher, MAPPER, INDEX_IO, INDEX_MERGER_V_9)
                .build(dataSchema, tuningConfig.withBasePersistDirectory(basePersistDirectory), new FireDepartmentMetrics());
        this.committerSupplier = Committers::nil;
        this.pushedSegments = new HashSet<>();
        this.intervalVersionMap = new HashMap<>();
        this.rangeBuckets = context.isRangePartitioned()
                ? RangeBuckets.of(context.getPartitionDimensions(), context.getPartitionBoundaries())
                : null;
        this.rangeSegmentIds = new HashMap<>();
        this.appenderator.startJob();

        try {
            ReflectionUtils.setStaticFieldValue(NullHandling.class, "INSTANCE", new NullValueHandlingConfig(context.isUseDefaultValueForNull(), context.isUseThreeValueLogicForNativeFilters(), null));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("Unable to set null handling!!", e);
        }
    }

    @Override
    public void write(InternalRow record) throws IOException {
        try {
            Map<String, Object> map = parse(record);
            long timestamp = (long) map.get(SegmentSpec.TIME_DIMENSION);
            InputRow inputRow = new MapBasedInputRow(timestamp,
                    dataSchema.getDimensionsSpec().getDimensionNames(), map);

            long bucketStartEpoch = segmentSpec.getPartitionTime() != null
                    // Adjust as spark returns long value with milliseconds.
                    ? record.getLong(segmentSpec.getPartitionTime().getOrdinal()) / 1000
                    : timestamp;
            DateTime bucketStart = dataSchema.getGranularitySpec().getSegmentGranularity().bucketStart(DateTimes.utc(bucketStartEpoch));
            final Interval interval = new Interval(bucketStart, dataSchema.getGranularitySpec().getSegmentGranularity().increment(bucketStart));

            if (segmentSpec.getPartitionTime() != null && segmentSpec.getPartitionNum() != null) {
                int partitionNum = record.getInt(segmentSpec.getPartitionNum().getOrdinal());
                current = rangeBuckets != null
                        // __PARTITION_NUM__ is the number of the bucket
                        ? rangeSegmentId(interval, partitionNum, inputRow)
                        : newSegmentId(interval, partitionNum);
            } else {
                if (current != null && !current.getInterval().equals(interval)) {
                    // Check if any open segment has the same interval.
                    LOG.debug("TaskId={}, currentId={}, new segment interval = {}", taskId, current, interval);
                    current = appenderator.getSegments()
                            .stream()
                            .filter(s -> s.getInterval().equals(interval))
                            .findAny()
                            .orElse(null);
                }

                if (current == null) {
                    // New interval.
                    current = newSegmentId(interval, 0);
                }

                if (appenderator.getSegments().contains(current) && appenderator.getRowCount(current) >= maxRows) {
                    LOG.debug("TaskId={}, New segment interval = {} has {} rows", taskId, interval, appenderator.getRowCount(current));
                    pushSegments(Collections.singletonList(current));
                    int partition = current.getShardSpec().getPartitionNum();
                    current = newSegmentId(interval, partition + 1);
                }
            }

            appenderator.add(current, inputRow, committerSupplier::get, true);
        } catch (SegmentNotWritableException e) {
            throw new IOException("Failed to write segment", e);
        }
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
        try {
            List<SegmentIdWithShardSpec> toPush = appenderator.getSegments();
            pushSegments(toPush);
            LOG.info("Commit taskId = {}, pushedSegments={}", taskId, pushedSegments.size());
            return DataSegmentCommitMessage.getInstance(pushedSegments);
        } finally {
            appenderator.close();
        }
    }

    @Override
    public void abort() throws IOException {
        try {
            for (DataSegment segment : pushedSegments) {
                segmentKiller.killQuietly(segment);
            }
            // Note: this does not clear segments from deep storage
            appenderator.clear();
            LOG.info("Abort taskId = {}, pushedSegments={}", taskId, pushedSegments.size());
        } catch (InterruptedException e) {
            throw new IOException("Interrupted during abort", e);
        } finally {
            appenderator.close();
        }
    }

    private Map<String, Object> parse(InternalRow record) {
        Map<String, Object> map = new HashMap<>();
        for (Field field : segmentSpec.getFields()) {
            String columnName = field.getName();
            if (segmentSpec.getTimeColumn().equals(columnName)) {
                Long tsVal = (Long) record.get(field.getOrdinal(), DataTypes.TimestampType);
                if (tsVal != null && field.getSqlType() == DataTypes.DateType) {
                    // date is stored as days since epoch
                    tsVal = LocalDate.ofEpochDay(tsVal).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                } else if (tsVal != null) {
                    // Adjust to millis as spark returns long value with microseconds.
                    tsVal = tsVal / 1000;
                }
                // the configured time columnName is mapped to __time
                map.put(SegmentSpec.TIME_DIMENSION, tsVal);
            } else {
                DataType sqlType = field.getSqlType();
                Object value = record.get(field.getOrdinal(), sqlType);
                if (value != null && segmentSpec.getComplexMetricColumns().contains(columnName) && sqlType == DataTypes.StringType) {
                    // Convert to Java String as Spark return UTF8String which is not compatible with Druid sketches.
                    value = value.toString();
                }
                if (value != null && segmentSpec.getComplexDimensionColumns().contains(columnName) && sqlType == DataTypes.StringType) {
                    try {
                        value = MAPPER.readValue(value.toString(), StructuredData.class);
                    } catch (JsonProcessingException e) {
                        value = null;
                    }
                }
                if (value != null && field.getFieldType() == FieldType.ARRAY_OF_STRING) {
                    ArrayData arrayData = record.getArray(field.getOrdinal());
                    int arraySize = arrayData.numElements();
                    List<String> valueArrayOfString = new ArrayList<>(arraySize);
                    for (int i = 0; i < arraySize; i++) {
                        valueArrayOfString.add(arrayData.get(i, DataTypes.StringType).toString());
                    }
                    value = valueArrayOfString;
                }
                if (value != null && field.getFieldType()== FieldType.ARRAY_OF_DOUBLE) {
                    ArrayData arrayData = record.getArray(field.getOrdinal());
                    int arraySize = arrayData.numElements();
                    Double[] valueArrayOfFloat = new Double[arraySize];
                    for (int i = 0; i < arraySize; i++) {
                        valueArrayOfFloat[i] = (Double) arrayData.get(i, DataTypes.DoubleType);
                    }
                    value = valueArrayOfFloat;
                }
                if (value != null && field.getFieldType()== FieldType.ARRAY_OF_LONG) {
                    ArrayData arrayData = record.getArray(field.getOrdinal());
                    int arraySize = arrayData.numElements();
                    Long[] valueArrayOfLong = new Long[arraySize];
                    for (int i = 0; i < arraySize; i++) {
                        valueArrayOfLong[i] = (Long) arrayData.get(i, DataTypes.LongType);
                    }
                    value = valueArrayOfLong;
                }
                map.put(columnName, value);
            }
        }
        // build the full map first and validate then, so that map can be used in error message for convenience.
        // (toString of InternalRow doesn't produce human-readable output)
        if (map.get(SegmentSpec.TIME_DIMENSION) == null) {
            throw new IllegalStateException(
                    String.format("Null value for column '%s'. Row: %s", segmentSpec.getTimeColumn(), map));
        }
        return map;
    }

    private SegmentIdWithShardSpec newSegmentId(Interval interval, int partitionNum) {
        VersionWithPartitionNum versionWithPartitionNum;
        if (context.isAppend()) {
            versionWithPartitionNum = intervalVersionMap.computeIfAbsent(interval, (k) -> findAppendTarget(interval));
        } else {
            versionWithPartitionNum = new VersionWithPartitionNum(
                    tuningConfig.getVersioningPolicy().getVersion(interval), context.getPartitionNumStart(), null);
        }

        int shardPartitionNum = versionWithPartitionNum.maxPartitionNum() + partitionNum;
        ShardSpec shardSpec;
        if (versionWithPartitionNum.numCorePartitions() == null) {
            shardSpec = new LinearShardSpec(shardPartitionNum);
        } else {
            if (partitionNum < 0) {
                // The first partition num is after the existing segments and not below the number of core partitions.
                // A negative partition num of the input would take the partition num of an existing segment, a core
                // segment or an earlier tail, and overwrite the files of that segment in the deep storage.
                throw new IllegalStateException(format(
                        "Partition num %d of the input for interval %s (version %s) is negative: partition nums of " +
                                "segments appended to a non-linear (e.g. range) segment set are added to the first " +
                                "partition num after the existing segments (%d), so they must not be negative.",
                        partitionNum, interval, versionWithPartitionNum.version(),
                        versionWithPartitionNum.maxPartitionNum()));
            }
            if (shardPartitionNum >= PartitionIds.ROOT_GEN_END_PARTITION_ID) {
                throw new IllegalStateException(format(
                        "Partition num %d of a new segment in interval %s (version %s) is too large: partition nums of " +
                                "segments appended to a non-linear (e.g. range) segment set must be less than %d. " +
                                "Check \"%s\" and the partition nums of the input.",
                        shardPartitionNum, interval, versionWithPartitionNum.version(),
                        PartitionIds.ROOT_GEN_END_PARTITION_ID, WriterContext.ConfKeys.PARTITION_NUM_START));
            }
            shardSpec = new NumberedShardSpec(shardPartitionNum, versionWithPartitionNum.numCorePartitions());
        }

        return new SegmentIdWithShardSpec(
                dataSchema.getDataSource(),
                interval,
                versionWithPartitionNum.version(),
                shardSpec
        );
    }

    /**
     * The segment of a range partitioned core set, for the bucket of the row. There is a single segment for each
     * interval and bucket of the task.
     *
     * @throws IOException if the row doesn't belong to the bucket
     */
    private SegmentIdWithShardSpec rangeSegmentId(Interval interval, int bucket, InputRow row) throws IOException {
        int numBuckets = rangeBuckets.getNumBuckets();
        if (bucket < 0 || bucket >= numBuckets) {
            throw new IOException(format("Invalid partition num %d in interval %s: it is the number of the bucket " +
                    "of \"%s\", which should be in [0, %d)", bucket, interval, WriterContext.ConfKeys.PARTITION_BOUNDARIES, numBuckets));
        }

        // Rows must be in the bucket that the shard spec of the segment tells, or the broker would prune the segment
        // when it has data that matches a query.
        StringTuple tuple = RangeBuckets.tupleOf(rangeBuckets.getDimensions(), row, context.isUseDefaultValueForNull());
        if (!rangeBuckets.contains(bucket, tuple)) {
            throw new IOException(format("Row doesn't belong to its bucket (partition num) in interval %s: " +
                            "values of %s are %s, but bucket %d is [%s, %s)",
                    interval, rangeBuckets.getDimensions(), tuple, bucket,
                    rangeBuckets.getStart(bucket), rangeBuckets.getEnd(bucket)));
        }

        SegmentIdWithShardSpec[] segmentIds = rangeSegmentIds.computeIfAbsent(interval, k -> new SegmentIdWithShardSpec[numBuckets]);
        if (segmentIds[bucket] == null) {
            segmentIds[bucket] = new SegmentIdWithShardSpec(
                    dataSchema.getDataSource(),
                    interval,
                    tuningConfig.getVersioningPolicy().getVersion(interval),
                    // The number of core partitions is the number of buckets. The commit fails if some bucket got no
                    // rows, see DruidDataSourceWriter#checkRangeCoreSet.
                    new DimensionRangeShardSpec(rangeBuckets.getDimensions(), rangeBuckets.getStart(bucket),
                            rangeBuckets.getEnd(bucket), bucket, numBuckets));
        }
        return segmentIds[bucket];
    }

    /**
     * Decides the version, the first partition num and the type of the shard spec for segments appended to an interval,
     * based on the used segments of the max used version of the interval.
     * <p>
     * Druid can't serve a version that mixes linear segments with non-linear (e.g. range or numbered) ones. Thus
     * appended segments must be of the same kind as the existing ones:
     * <ul>
     * <li>no existing segments: {@link LinearShardSpec}, with version from the versioning policy</li>
     * <li>only {@link LinearShardSpec}: {@link LinearShardSpec}</li>
     * <li>only numbered (incl. hash partitioned) or range shard specs: {@link NumberedShardSpec} with the number of core
     * partitions of the existing segments. Segments with partition num &gt;= number of core partitions are not part of
     * the core set, so they don't affect when the version becomes visible.</li>
     * <li>other kinds of shard specs, or a mix: the write fails</li>
     * </ul>
     */
    private VersionWithPartitionNum findAppendTarget(Interval interval) {
        List<MetadataUpdater.VersionedShardSpec> used =
                metadataUpdater.findUsedSegmentShardSpecs(dataSchema.getDataSource(), interval);
        if (used.isEmpty()) {
            return new VersionWithPartitionNum(
                    tuningConfig.getVersioningPolicy().getVersion(interval), context.getPartitionNumStart(), null);
        }

        // All used segments are of the same version, see MetadataUpdater#findUsedSegmentShardSpecs.
        String version = used.get(0).version();
        int nextPartitionNum = Math.max(context.getPartitionNumStart(),
                used.stream().mapToInt(s -> s.shardSpec().getPartitionNum()).max().getAsInt() + 1);

        long linearCount = used.stream().filter(s -> s.shardSpec() instanceof LinearShardSpec).count();
        if (linearCount == used.size()) {
            return new VersionWithPartitionNum(version, nextPartitionNum, null);
        }
        if (linearCount > 0) {
            throw new IllegalStateException(format(
                    "Can't append to interval %s (version %s) of datasource %s: it has both linear and non-linear " +
                            "segments, which Druid can't serve together.",
                    interval, version, dataSchema.getDataSource()));
        }

        // Numbered segments (incl. hash partitioned) and range segments have a core set that appended segments can
        // follow. Other kinds don't, e.g. tombstones, which have no core partitions either, and segments that overwrite
        // other segments, which have their own partition num space.
        Set<String> unsupportedTypes = used.stream()
                .map(VersionedShardSpec::shardSpec)
                .filter(s -> !(s instanceof NumberedShardSpec || s instanceof BaseDimensionRangeShardSpec))
                .map(ShardSpec::getType)
                .collect(Collectors.toSet());
        if (!unsupportedTypes.isEmpty()) {
            throw new IllegalStateException(format(
                    "Can't append to interval %s (version %s) of datasource %s: appending to segments of shard " +
                            "spec types %s is not supported.",
                    interval, version, dataSchema.getDataSource(), unsupportedTypes));
        }

        // 0 is a valid number of core partitions, as in the numbered segments of streaming ingestion. The unknown
        // number of core partitions (a negative number) of old range segments is not.
        Set<Integer> numCorePartitions = used.stream()
                .map(s -> s.shardSpec().getNumCorePartitions())
                .collect(Collectors.toSet());
        if (numCorePartitions.size() != 1 || numCorePartitions.iterator().next() < 0) {
            throw new IllegalStateException(format(
                    "Can't append to interval %s (version %s) of datasource %s: expected the existing segments to " +
                            "agree on a known number of core partitions, but found %s (shard spec types: %s).",
                    interval, version, dataSchema.getDataSource(), numCorePartitions,
                    used.stream().map(s -> s.shardSpec().getType()).collect(Collectors.toSet())));
        }
        int numCore = numCorePartitions.iterator().next();
        // Appended segments never take the partition num of a core partition, also if some core segment is not among
        // the used segments: it would complete the core set with segments that are not the ones of the core set.
        return new VersionWithPartitionNum(version, Math.max(nextPartitionNum, numCore), numCore);
    }

    private void pushSegments(List<SegmentIdWithShardSpec> toPush) throws IOException {
        try {
            SegmentsAndCommitMetadata segmentsAndMetadata = appenderator.push(toPush, committerSupplier.get(), false).get();
            List<DataSegment> updated = segmentsAndMetadata.getSegments();
            if (updated.size() != toPush.size()) {
                LOG.error("Failed to push all segments, taskId={}, toPush={}, updated={}", taskId, toPush.size(), updated.size());
                throw new IOException("Failed to push all segments");
            }

            for (SegmentIdWithShardSpec identifier : toPush) {
                LOG.debug("TaskId={} pushed segment segmentId={}", taskId, identifier);
                appenderator.drop(identifier).get();
            }
            pushedSegments.addAll(updated);
            LOG.info("TaskId={}, pushed segments {}", taskId, pushedSegments.size());
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Failed to push segment", e);
        }
    }

    private RealtimeTuningConfig getTuningConfig(WriterContext context) {
        return new RealtimeTuningConfig(
                null,
                context.getMaxRowsInMemory(),
                -1L,
                null,
                null,
                null,
                null,
                new CustomVersioningPolicy(context.getVersion()),
                null,
                null,
                null,
                getIndexSpec(context),
                null,
                0,
                0,
                true,
                0L,
                0L,
                null,
                null
        );
    }

    // Not private to be tested
    static IndexSpec getIndexSpec(WriterContext context) {
        if (context.getIndexSpec() == null) {
            return new IndexSpec(getBitmapSerdeFactory(context), null, null, null, null, null, null);
        }

        IndexSpec indexSpec = WriterContext.parseIndexSpec(context.getIndexSpec());
        if (!context.isBitmapFactoryExplicit()) {
            // Anything not given in druid.index_spec (incl. the bitmap) is left to Druid's defaults.
            return indexSpec;
        }
        // An explicitly given druid.bitmap_factory takes precedence over the bitmap of druid.index_spec. The bitmap is
        // replaced in the JSON, which is then parsed once more, so that no other field of the spec is dropped, also not
        // one that a later version of Druid adds.
        try {
            JsonNode tree = MAPPER.readTree(context.getIndexSpec());
            if (!(tree instanceof ObjectNode)) {
                throw new IllegalStateException(format("\"%s\" is not a JSON object", WriterContext.ConfKeys.INDEX_SPEC));
            }
            ((ObjectNode) tree).set("bitmap", MAPPER.valueToTree(getBitmapSerdeFactory(context)));
            return MAPPER.treeToValue(tree, IndexSpec.class);
        } catch (IOException e) {
            throw new IllegalStateException(format("Failed to apply \"%s\" to \"%s\": %s",
                    WriterContext.ConfKeys.BITMAP_FACTORY, WriterContext.ConfKeys.INDEX_SPEC, e.getMessage()), e);
        }
    }

    private static BitmapSerdeFactory getBitmapSerdeFactory(WriterContext context) {
        switch (context.getBitmapFactory()) {
            case "concise":
                return new ConciseBitmapSerdeFactory();
            case "roaring":
                return RoaringBitmapSerdeFactory.getInstance();
        }
        throw new IllegalArgumentException(
                "Unknown bitmap factory: '" + context.getBitmapFactory() + "'");
    }

    @Override
    public void close() throws IOException {
        if (this.basePersistDirectory != null) {
            FileUtils.deleteDirectory(basePersistDirectory);
        }
    }

    /**
     * @param numCorePartitions number of core partitions of the existing segments for {@link NumberedShardSpec},
     *                          null for {@link LinearShardSpec}
     */
    private record VersionWithPartitionNum(String version, int maxPartitionNum, Integer numCorePartitions) {
    }
}
