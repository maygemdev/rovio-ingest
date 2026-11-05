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

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.druid.segment.QueryableIndex;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static com.rovio.ingest.WriterContext.ConfKeys.DIMENSIONS_SPEC;
import static com.rovio.ingest.WriterContext.ConfKeys.EXCLUDED_DIMENSIONS;
import static com.rovio.ingest.WriterContext.ConfKeys.METRICS_SPEC;
import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_BOUNDARIES;
import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_DIMENSIONS;
import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_NUM_START;
import static org.apache.spark.sql.functions.column;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for writing segments as a range partitioned core set, with options druid.partition_dimensions and
 * druid.partition_boundaries.
 * <p>
 * The test data is 8 rows with dimensions (country, client). In order, as Druid orders them:
 * <pre>
 * (DE,1) (FI,null) (FI,12) (FI,3) (GB,7) (US,12) (US,3) (US,a)
 * </pre>
 * Note that "12" &lt; "3". The bucket (the value of __PARTITION_NUM__) of each row is given in the tests.
 */
public class DruidSourceRangePartitioningTest extends DruidSourceBaseTest {
    private static final Interval DAY = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-17T00:00:00Z"));

    private static final String[][] ROWS = {{"DE", "1"}, {"FI", null}, {"FI", "12"}, {"FI", "3"}, {"GB", "7"}, {"US", "12"}, {"US", "3"}, {"US", "a"}};

    // buckets [null, (FI,12)), [(FI,12), (US,3)), [(US,3), null)
    private static final String THREE_BUCKETS = "[[\"FI\",\"12\"],[\"US\",\"3\"]]";
    private static final int[] BUCKETS_OF_THREE_BUCKETS = {0, 0, 1, 1, 1, 1, 2, 2};

    private static final StructType SCHEMA = new StructType()
            .add("date", DataTypes.TimestampType, false)
            .add("country", DataTypes.StringType, true)
            .add("client", DataTypes.StringType, true)
            .add("shard", DataTypes.LongType, false)
            .add("revenue", DataTypes.DoubleType, false)
            .add("dau", DataTypes.LongType, false)
            .add("__PARTITION_TIME__", DataTypes.TimestampType, false)
            .add("__PARTITION_NUM__", DataTypes.IntegerType, false);

    @BeforeEach
    public void setUpRangeOptions() {
        options.put(PARTITION_DIMENSIONS, "country,client");
        options.put(PARTITION_BOUNDARIES, THREE_BUCKETS);
    }

    @Test
    public void writesRangePartitionedCoreSet() throws IOException {
        write(partitioned(dataset(rows(BUCKETS_OF_THREE_BUCKETS))));

        String version = version();
        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY, version);
        assertEquals(Arrays.asList(0, 1, 2), new ArrayList<>(shardSpecs.keySet()));
        assertEquals(rangeSpec("[\"country\",\"client\"]", "null", "[\"FI\",\"12\"]", 0, 3), shardSpecs.get(0));
        assertEquals(rangeSpec("[\"country\",\"client\"]", "[\"FI\",\"12\"]", "[\"US\",\"3\"]", 1, 3), shardSpecs.get(1));
        assertEquals(rangeSpec("[\"country\",\"client\"]", "[\"US\",\"3\"]", "null", 2, 3), shardSpecs.get(2));

        verifySegmentTable(DAY, version, true, 3);
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), DAY, version, 3, false);
        // each segment has the rows of its bucket
        int[] expectedNumRows = {2, 4, 2};
        for (int bucket = 0; bucket < 3; bucket++) {
            try (QueryableIndex index = loadSegmentIndex(Paths.get(testFolder.toString(), DATA_SOURCE), DAY.getStart(), version, bucket)) {
                assertEquals(expectedNumRows[bucket], index.getNumRows());
            }
        }
    }

    @Test
    public void writesASingleSegmentForOneBucket() throws IOException {
        options.put(PARTITION_BOUNDARIES, "[]");

        write(partitioned(dataset(rows(new int[ROWS.length]))));

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY, version());
        assertEquals(1, shardSpecs.size());
        assertEquals(rangeSpec("[\"country\",\"client\"]", "null", "null", 0, 1), shardSpecs.get(0));
    }

    @Test
    public void failWhenABucketHasNoRows() throws IOException {
        // The middle bucket [(FI,4), (FI,5)) has no rows. The core set would be incomplete, and renumbering the
        // segments would make the files of segments not match their partition nums: segments appended later could
        // overwrite them. The segments of the other buckets have been pushed when this is noticed at commit, and Spark
        // must abort the write to remove them.
        options.put(PARTITION_BOUNDARIES, "[[\"FI\",\"4\"],[\"FI\",\"5\"]]");

        assertWriteFails(partitioned(dataset(rows(0, 0, 0, 0, 2, 2, 2, 2))), "Buckets [1] of the 3 buckets of interval");

        assertNothingWritten();
    }

    @Test
    public void appendAfterRangeWriteCreatesNumberedTailsAfterTheCoreSegments() throws IOException {
        write(partitioned(dataset(rows(BUCKETS_OF_THREE_BUCKETS))));

        // without druid.partition_num_start, so that the partition num is the first one after the core set
        options.remove(PARTITION_DIMENSIONS);
        options.remove(PARTITION_BOUNDARIES);
        dataset(rows(BUCKETS_OF_THREE_BUCKETS)).drop("__PARTITION_TIME__", "__PARTITION_NUM__")
                .repartition(column("date"))
                .write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        String version = version();
        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY, version);
        assertEquals(Arrays.asList(0, 1, 2, 3), new ArrayList<>(shardSpecs.keySet()));
        assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":3,\"partitions\":3}"), shardSpecs.get(3));
        // The files of the core segments are not overwritten by the appended segment: each has its rows
        int[] expectedNumRows = {2, 4, 2, 8};
        for (int partitionNum = 0; partitionNum < 4; partitionNum++) {
            try (QueryableIndex index = loadSegmentIndex(Paths.get(testFolder.toString(), DATA_SOURCE), DAY.getStart(), version, partitionNum)) {
                assertEquals(expectedNumRows[partitionNum], index.getNumRows(), "segment " + partitionNum);
            }
        }
    }

    @Test
    public void emptyStringIsNullWhenDefaultValuesAreUsedForNull() throws IOException {
        // In the default null handling mode an empty string is null: (FI,"") is the tuple (FI,null), which is before
        // the boundary (FI,""). (The boundary has "" only to show this, boundaries are used as they are.)
        options.put(PARTITION_BOUNDARIES, "[[\"FI\",\"\"]]");
        List<Row> rows = Arrays.asList(
                row("2019-10-16", "FI", "", 1, 0),
                row("2019-10-16", "FI", "a", 2, 1));

        write(partitioned(spark.createDataFrame(rows, SCHEMA)));

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY, version());
        assertEquals(2, shardSpecs.size());
        assertEquals(rangeSpec("[\"country\",\"client\"]", "null", "[\"FI\",\"\"]", 0, 2), shardSpecs.get(0));
        assertEquals(rangeSpec("[\"country\",\"client\"]", "[\"FI\",\"\"]", "null", 1, 2), shardSpecs.get(1));

        // (FI,"") is not in the second bucket, which starts at (FI,"")
        List<Row> inSecondBucket = Arrays.asList(
                row("2019-10-16", "FI", "", 1, 1),
                row("2019-10-16", "FI", "a", 2, 1));
        assertWriteFails(partitioned(spark.createDataFrame(inSecondBucket, SCHEMA)), "Row doesn't belong to its bucket");
    }

    @Test
    public void failWhenRowDoesNotBelongToItsBucket() throws IOException {
        // (US,a) belongs to the last bucket
        int[] buckets = {0, 0, 1, 1, 1, 1, 2, 1};

        assertWriteFails(partitioned(dataset(rows(buckets))), "Row doesn't belong to its bucket (partition num) in interval");

        assertNothingWritten();
    }

    @Test
    public void failWhenBucketIsOutOfRange() throws IOException {
        int[] buckets = {0, 0, 1, 1, 1, 1, 2, 3};

        assertWriteFails(partitioned(dataset(rows(buckets))), "Invalid partition num 3 in interval");

        assertNothingWritten();
    }

    @Test
    public void failWhenABucketIsWrittenBySeveralTasks() throws IOException {
        // Each row is in a task of its own, so buckets with several rows are written by several tasks. Segments of
        // the tasks have been pushed when this is noticed at commit. Spark must abort the write, to remove them.
        Dataset<Row> dataset = dataset(rows(BUCKETS_OF_THREE_BUCKETS)).repartition(ROWS.length);

        assertWriteFails(dataset, "was written by several Spark tasks");

        assertNothingWritten();
    }

    @Test
    public void failWhenWritingSeveralIntervals() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < ROWS.length; i++) {
            rows.add(row(i < 4 ? "2019-10-16" : "2019-10-17", ROWS[i][0], ROWS[i][1], i + 1, BUCKETS_OF_THREE_BUCKETS[i]));
        }

        assertWriteFails(partitioned(spark.createDataFrame(rows, SCHEMA)), "supports only a single interval in a write");

        assertNothingWritten();
    }

    @Test
    public void failWithAppendWriteMode() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> partitioned(dataset(rows(BUCKETS_OF_THREE_BUCKETS))).write()
                        .format("com.rovio.ingest.DruidSource")
                        .mode(SaveMode.Append)
                        .options(options)
                        .save());

        assertThat(thrown.getMessage(), containsString("\"druid.partition_dimensions\" is not supported with append write mode"));
    }

    @Test
    public void emptyWritePublishesNothingAndKeepsExistingSegments() throws IOException {
        // as a write without range options: no segments, no error, nothing changed
        write(partitioned(dataset(rows(BUCKETS_OF_THREE_BUCKETS))));
        assertEquals(3, countSegments());

        write(partitioned(dataset(Collections.emptyList())));

        assertEquals(3, countSegments());
        assertEquals(Arrays.asList(0, 1, 2), new ArrayList<>(findShardSpecs(DAY, version()).keySet()));
    }

    @Test
    public void failWithoutPartitionColumns() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> write(dataset(rows(BUCKETS_OF_THREE_BUCKETS)).drop("__PARTITION_NUM__")));

        assertThat(thrown.getMessage(), containsString("requires that the dataset has columns __PARTITION_TIME__ and __PARTITION_NUM__"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("invalidOptions")
    public void failForInvalidOptions(Consumer<Map<String, String>> changeOptions, String expectedMessage) {
        changeOptions.accept(options);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> write(partitioned(dataset(rows(BUCKETS_OF_THREE_BUCKETS)))));

        assertThat(thrown.getMessage(), containsString(expectedMessage));
        assertEquals(0, countSegments());
    }

    static Stream<Arguments> invalidOptions() {
        return Stream.of(
                invalid("should be given together", o -> o.remove(PARTITION_BOUNDARIES)),
                invalid("should be given together", o -> o.remove(PARTITION_DIMENSIONS)),
                invalid("empty dimension name", o -> o.put(PARTITION_DIMENSIONS, "country,,client")),
                invalid("duplicate dimension \"country\"", o -> o.put(PARTITION_DIMENSIONS, "country,country")),
                invalid("Invalid \"druid.partition_boundaries\"", o -> o.put(PARTITION_BOUNDARIES, "not json")),
                invalid("should have 2 values", o -> o.put(PARTITION_BOUNDARIES, "[[\"FI\"]]")),
                invalid("is neither a string nor null", o -> o.put(PARTITION_BOUNDARIES, "[[\"FI\",4]]")),
                invalid("strictly increasing", o -> o.put(PARTITION_BOUNDARIES, "[[\"US\",\"3\"],[\"FI\",\"12\"]]")),
                invalid("strictly increasing", o -> o.put(PARTITION_BOUNDARIES, "[[\"FI\",\"12\"],[\"FI\",\"12\"]]")),
                invalid("\"druid.partition_num_start\" should be 0", o -> o.put(PARTITION_NUM_START, "1000")),
                invalid("\"nope\" is not a dimension of the segments", o -> {
                    o.put(PARTITION_DIMENSIONS, "country,nope");
                }),
                // dau is a metric
                invalid("\"dau\" is not a dimension of the segments", o -> {
                    o.put(PARTITION_DIMENSIONS, "dau");
                    o.put(PARTITION_BOUNDARIES, "[]");
                }),
                invalid("\"client\" is not a dimension of the segments", o -> o.put(EXCLUDED_DIMENSIONS, "client")),
                // with only dau as a metric revenue is a double dimension
                invalid("\"revenue\" is of type DOUBLE, only string dimensions are supported", o -> {
                    o.put(METRICS_SPEC, "[{\"type\":\"longSum\",\"name\":\"dau\",\"fieldName\":\"dau\"}]");
                    o.put(PARTITION_DIMENSIONS, "revenue");
                    o.put(PARTITION_BOUNDARIES, "[]");
                }),
                // with only dau as a metric shard is a long dimension. Druid stores a null long dimension as 0, so the
                // tuple of a row would not be what is in the segment.
                invalid("\"shard\" is of type LONG, only string dimensions are supported", o -> {
                    o.put(METRICS_SPEC, "[{\"type\":\"longSum\",\"name\":\"dau\",\"fieldName\":\"dau\"}]");
                    o.put(PARTITION_DIMENSIONS, "country,shard");
                    o.put(PARTITION_BOUNDARIES, "[]");
                }),
                // client is a string column, but druid.dimensions_spec decides what the segments store: "012" would
                // be stored as 12, which is not in the bucket that the string tells
                invalid("\"client\" is stored as LONG, as defined by \"druid.dimensions_spec\"", o ->
                        o.put(DIMENSIONS_SPEC, "{\"dimensions\":[{\"type\":\"string\",\"name\":\"country\"},{\"type\":\"long\",\"name\":\"client\"}]}")),
                invalid("\"client\" is stored as", o ->
                        o.put(DIMENSIONS_SPEC, "{\"dimensions\":[{\"type\":\"string\",\"name\":\"country\"},{\"type\":\"json\",\"name\":\"client\"}]}")),
                // 32769 buckets are too many, as partition nums of a core set must be less than 32768. Fails on the
                // driver, before any task writes.
                invalid("32769 buckets, but there must be less than 32768", o ->
                        o.put(PARTITION_BOUNDARIES, boundariesOfBuckets(32769)))
        );
    }

    private static String boundariesOfBuckets(int numBuckets) {
        return IntStream.range(0, numBuckets - 1)
                .mapToObj(i -> String.format("[\"FI\",\"%05d\"]", i))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static Arguments invalid(String expectedMessage, Consumer<Map<String, String>> changeOptions) {
        return Arguments.of(changeOptions, expectedMessage);
    }

    private void write(Dataset<Row> dataset) {
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();
    }

    /**
     * A failure in a task is thrown by Spark as a SparkException, a failure when committing as it is.
     */
    private void assertWriteFails(Dataset<Row> dataset, String expectedMessage) {
        Exception thrown = assertThrows(Exception.class, () -> write(dataset));
        assertThat(ExceptionUtils.getStackTrace(thrown), containsString(expectedMessage));
    }

    private void assertNothingWritten() throws IOException {
        assertEquals(0, countSegments());
        assertNoSegmentFiles();
    }

    private static String version() {
        return DateTime.now(ISOChronology.getInstanceUTC()).toString();
    }

    /**
     * The test rows of 2019-10-16 with the given buckets.
     */
    private static List<Row> rows(int... buckets) {
        return IntStream.range(0, ROWS.length)
                .mapToObj(i -> row("2019-10-16", ROWS[i][0], ROWS[i][1], i + 1, buckets[i]))
                .collect(Collectors.toList());
    }

    private static Row row(String day, String country, String client, long shard, int bucket) {
        Timestamp time = Timestamp.from(Instant.parse(day + "T00:00:00Z"));
        return RowFactory.create(time, country, client, shard, 1.0, 1L, time, bucket);
    }

    private Dataset<Row> dataset(List<Row> rows) {
        return spark.createDataFrame(rows, SCHEMA);
    }

    /**
     * Partition the dataset by __PARTITION_TIME__ and __PARTITION_NUM__ (the bucket), so that all rows of a bucket are
     * written by one task.
     */
    private static Dataset<Row> partitioned(Dataset<Row> dataset) {
        return dataset.repartition(column("__PARTITION_TIME__"), column("__PARTITION_NUM__"));
    }

    private static JsonNode rangeSpec(String dimensions, String start, String end, int partitionNum, int numCorePartitions) throws IOException {
        return MAPPER.readTree(String.format(
                "{\"type\":\"range\",\"dimensions\":%s,\"start\":%s,\"end\":%s,\"partitionNum\":%d,\"numCorePartitions\":%d}",
                dimensions, start, end, partitionNum, numCorePartitions));
    }
}
