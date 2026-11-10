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
import com.rovio.ingest.extensions.java.DruidRangePartitioning;
import com.rovio.ingest.util.RangeBuckets;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_BOUNDARIES;
import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_DIMENSIONS;
import static org.apache.spark.sql.functions.col;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DruidRangePartitioning}, which computes the buckets for the range partitioned segments.
 */
public class DruidRangePartitioningTest extends DruidSourceBaseTest {
    private static final Interval DAY = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-17T00:00:00Z"));
    private static final List<String> DIMENSIONS = Arrays.asList("country", "client");

    private static final StructType SCHEMA = new StructType()
            .add("date", DataTypes.TimestampType, false)
            .add("country", DataTypes.StringType, true)
            .add("client", DataTypes.StringType, true)
            .add("shard", DataTypes.LongType, false)
            .add("revenue", DataTypes.DoubleType, false)
            .add("dau", DataTypes.LongType, false);

    @Test
    public void computesEvenBucketsFromAllSparkPartitions() {
        // 1000 rows that are all different, in 3 Spark partitions
        Dataset<Row> dataset = dataset(rows(1000)).repartition(3);

        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(dataset, DIMENSIONS, 250, true);

        assertEquals(1000, partitioning.getRowCount());
        assertEquals(4, partitioning.getNumBuckets());
        assertEquals(DIMENSIONS, partitioning.getDimensions());
        // a small sketch is exact, so that all buckets have the same number of rows
        Map<Integer, Long> counts = bucketCounts(dataset, partitioning);
        assertEquals(4, counts.size());
        counts.values().forEach(count -> assertEquals(250, count));
        assertBucketsAreConsistent(dataset, partitioning, DIMENSIONS, true);
    }

    @Test
    public void boundariesAreTheSameForOneAndForSeveralSparkPartitions() {
        List<Row> rows = rows(1000);

        String one = DruidRangePartitioning.compute(dataset(rows).repartition(1), DIMENSIONS, 250, true).getBoundariesJson();
        String several = DruidRangePartitioning.compute(dataset(rows).repartition(7), DIMENSIONS, 250, true).getBoundariesJson();

        assertEquals(one, several);
    }

    @Test
    public void emptyStringIsNullOnlyWithDefaultValuesForNull() {
        // 100 rows of each of null, "" and "z"
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("A", null, i));
            rows.add(row("A", "", i));
            rows.add(row("A", "z", i));
        }
        Dataset<Row> dataset = dataset(rows);

        // "" is null, so that there are only two different tuples, 200 rows of (A,null) and 100 rows of (A,z). As in
        // Druid, the biggest tuple is not a boundary, thus there is a single bucket.
        DruidRangePartitioning legacy = DruidRangePartitioning.compute(dataset, DIMENSIONS, 100, true);
        assertEquals(1, legacy.getNumBuckets());
        assertEquals("[]", legacy.getBoundariesJson());

        // otherwise null is before "", which is before "z"
        DruidRangePartitioning sqlCompatible = DruidRangePartitioning.compute(dataset, DIMENSIONS, 100, false);
        assertEquals(2, sqlCompatible.getNumBuckets());
        assertEquals("[[\"A\",\"\"]]", sqlCompatible.getBoundariesJson());
        for (Row row : dataset.withColumn("bucket", sqlCompatible.bucketColumn()).collectAsList()) {
            assertEquals(row.getAs("client") == null ? 0 : 1, (int) row.<Integer>getAs("bucket"));
        }
        assertBucketsAreConsistent(dataset, sqlCompatible, DIMENSIONS, false);
    }

    @Test
    public void ordersStringsAsDruidDoes() {
        // By UTF-16 code units, which is how Druid orders strings, a < supplementary < U+E000 < U+F000. By UTF-8 bytes,
        // which is how Spark orders them, U+E000 and U+F000 would be before the supplementary character.
        String supplementary = new String(Character.toChars(0x1F600));
        String privateUse1 = String.valueOf((char) 0xE000);
        String privateUse2 = String.valueOf((char) 0xF000);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            for (String country : Arrays.asList("a", supplementary, privateUse1, privateUse2)) {
                rows.add(row(country, "x", i));
            }
        }
        Dataset<Row> dataset = dataset(rows);

        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(dataset, Collections.singletonList("country"), 100, true);

        // The biggest value, privateUse2, is not a boundary, so privateUse1 and privateUse2 are in the same bucket.
        assertEquals(3, partitioning.getNumBuckets());
        Map<Object, Integer> bucketOfCountry = new HashMap<>();
        for (Row row : dataset.withColumn("bucket", partitioning.bucketColumn()).collectAsList()) {
            bucketOfCountry.put(row.getAs("country"), row.<Integer>getAs("bucket"));
        }
        assertEquals(0, (int) bucketOfCountry.get("a"));
        assertEquals(1, (int) bucketOfCountry.get(supplementary));
        assertEquals(2, (int) bucketOfCountry.get(privateUse1));
        assertEquals(2, (int) bucketOfCountry.get(privateUse2));
    }


    @Test
    public void commonTuplesAreNotSplit() {
        // 900 times the same, and 100 that are different
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 900; i++) {
            rows.add(row("A", "1", i));
        }
        for (int i = 0; i < 100; i++) {
            rows.add(row("B", String.format("%03d", i), i));
        }
        Dataset<Row> dataset = dataset(rows).repartition(3);

        // 10 buckets of 100 rows would be expected
        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(dataset, DIMENSIONS, 100, true);

        assertTrue(partitioning.getNumBuckets() < 10, "buckets " + partitioning.getNumBuckets());
        Set<Integer> bucketsOfTheCommonTuple = dataset.where("country = 'A'")
                .withColumn("bucket", partitioning.bucketColumn()).select("bucket").collectAsList().stream()
                .map(row -> row.getInt(0))
                .collect(Collectors.toSet());
        assertEquals(1, bucketsOfTheCommonTuple.size());
        assertBucketsAreConsistent(dataset, partitioning, DIMENSIONS, true);
    }

    @Test
    public void emptyDatasetIsASingleBucket() {
        Dataset<Row> empty = dataset(Collections.emptyList());

        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(empty, DIMENSIONS, 100, true);

        assertEquals(0, partitioning.getRowCount());
        assertEquals(1, partitioning.getNumBuckets());
        assertEquals("[]", partitioning.getBoundariesJson());
        // a dataset with rows gets the only bucket
        Dataset<Row> dataset = dataset(rows(10));
        dataset.withColumn("bucket", partitioning.bucketColumn()).collectAsList()
                .forEach(row -> assertEquals(0, (int) row.<Integer>getAs("bucket")));
    }

    @Test
    public void supportsColumnNamesWithDotsAndBackticks() {
        // The name of a column is not an expression: "geo.country" is not the field country of a column geo
        StructType schema = new StructType()
                .add("geo.country", DataTypes.StringType, true)
                .add("back`tick", DataTypes.StringType, true)
                .add("dau", DataTypes.LongType, false);
        List<Row> rows = IntStream.range(0, 400)
                .mapToObj(i -> RowFactory.create("c" + (i % 10), String.format("%04d", i), 1L))
                .collect(Collectors.toList());
        Dataset<Row> dataset = spark.createDataFrame(rows, schema).repartition(3);
        List<String> dimensions = Arrays.asList("geo.country", "back`tick");

        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(dataset, dimensions, 100, true);

        assertEquals(4, partitioning.getNumBuckets());
        assertBucketsAreConsistent(dataset, partitioning, dimensions, true);
    }

    @Test
    public void failForInvalidArguments() {
        Dataset<Row> dataset = dataset(rows(10));

        assertInvalid(() -> DruidRangePartitioning.compute(dataset, DIMENSIONS, 0, true), "targetRowsPerSegment must be positive");
        assertInvalid(() -> DruidRangePartitioning.compute(dataset, Collections.emptyList(), 100, true), "At least one partition dimension");
        assertInvalid(() -> DruidRangePartitioning.compute(dataset, Arrays.asList("country", "country"), 100, true), "Duplicate partition dimension \"country\"");
        assertInvalid(() -> DruidRangePartitioning.compute(dataset, Arrays.asList("country", "nope"), 100, true), "\"nope\" is not a column of the dataset");
        assertInvalid(() -> DruidRangePartitioning.compute(dataset, Arrays.asList("country", "revenue"), 100, true),
                "\"revenue\" is of type DOUBLE, only string dimensions are supported");
        // Druid stores a null long dimension as 0 (with default values for null), so the tuple of a row would not be
        // what is in the segment: only strings
        assertInvalid(() -> DruidRangePartitioning.compute(dataset, Arrays.asList("country", "shard"), 100, true),
                "\"shard\" is of type LONG, only string dimensions are supported");
    }

    /**
     * The buckets are used for writing: segments have about the number of rows that was asked, and the writer, which
     * checks that every row is in its bucket, accepts the rows.
     */
    @Test
    public void bucketsAreWrittenAsRangePartitionedSegments() throws IOException {
        Dataset<Row> dataset = dataset(rows(2000));
        DruidRangePartitioning partitioning = DruidRangePartitioning.compute(dataset, DIMENSIONS, 500, true);
        assertEquals(4, partitioning.getNumBuckets());

        options.put(PARTITION_DIMENSIONS, String.join(",", DIMENSIONS));
        options.put(PARTITION_BOUNDARIES, partitioning.getBoundariesJson());
        dataset
                .withColumn("__PARTITION_TIME__", col("date"))
                .withColumn("__PARTITION_NUM__", partitioning.bucketColumn())
                .repartitionByRange(partitioning.getNumBuckets(), col("__PARTITION_NUM__"))
                .write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY, version);
        assertEquals(Arrays.asList(0, 1, 2, 3), new ArrayList<>(shardSpecs.keySet()));
        verifySegmentTable(DAY, version, true, 4);
        for (int bucket = 0; bucket < 4; bucket++) {
            JsonNode shardSpec = shardSpecs.get(bucket);
            assertEquals("range", shardSpec.get("type").asText());
            assertEquals(4, shardSpec.get("numCorePartitions").asInt());
            if (bucket > 0) {
                assertEquals(shardSpecs.get(bucket - 1).get("end"), shardSpec.get("start"));
            }
            try (QueryableIndex index = loadSegmentIndex(Paths.get(testFolder.toString(), DATA_SOURCE), DAY.getStart(), version, bucket)) {
                assertEquals(500, index.getNumRows());
            }
        }
        assertTrue(shardSpecs.get(0).get("start").isNull());
        assertTrue(shardSpecs.get(3).get("end").isNull());
    }

    /**
     * Checks that the bucket of every row is the same as what the boundaries of the buckets tell, by parsing the
     * boundaries, as the writer does.
     */
    private static void assertBucketsAreConsistent(Dataset<Row> dataset, DruidRangePartitioning partitioning,
                                                   List<String> dimensions, boolean useDefaultValueForNull) {
        RangeBuckets buckets = RangeBuckets.of(dimensions, partitioning.getBoundariesJson());
        assertEquals(partitioning.getNumBuckets(), buckets.getNumBuckets());

        List<Row> rows = dataset.withColumn("bucket", partitioning.bucketColumn()).collectAsList();
        assertEquals(partitioning.getRowCount(), rows.size());
        Set<Integer> usedBuckets = new HashSet<>();
        for (Row row : rows) {
            String[] values = new String[dimensions.size()];
            for (int i = 0; i < values.length; i++) {
                Object value = row.getAs(dimensions.get(i));
                values[i] = value == null ? null : String.valueOf(value);
            }
            int bucket = row.getAs("bucket");
            usedBuckets.add(bucket);
            assertTrue(buckets.contains(bucket, RangeBuckets.tupleOf(values, useDefaultValueForNull)),
                    "row " + Arrays.toString(values) + " is not in bucket " + bucket);
        }
        // The writer requires that every bucket has rows
        assertEquals(partitioning.getNumBuckets(), usedBuckets.size(), "buckets without rows");
    }

    private static Map<Integer, Long> bucketCounts(Dataset<Row> dataset, DruidRangePartitioning partitioning) {
        Map<Integer, Long> counts = new HashMap<>();
        dataset.withColumn("bucket", partitioning.bucketColumn()).groupBy("bucket").count().collectAsList()
                .forEach(row -> counts.put(row.getInt(0), row.getLong(1)));
        return counts;
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable, String expectedMessage) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, executable);
        assertThat(thrown.getMessage(), containsString(expectedMessage));
    }

    /**
     * Rows that are all different: (c3, 0123), ...
     */
    private static List<Row> rows(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> row("c" + (i % 10), String.format("%05d", i), i))
                .collect(Collectors.toList());
    }

    private static Row row(String country, String client, long shard) {
        return RowFactory.create(Timestamp.from(Instant.parse("2019-10-16T00:00:00Z")), country, client, shard, 1.0, 1L);
    }

    private Dataset<Row> dataset(List<Row> rows) {
        return spark.createDataFrame(rows, SCHEMA);
    }
}
