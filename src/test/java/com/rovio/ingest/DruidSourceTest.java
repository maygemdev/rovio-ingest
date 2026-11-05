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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.rovio.ingest.extensions.java.DruidDatasetExtensions;
import com.rovio.ingest.util.MetadataUpdater;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.druid.collections.bitmap.ConciseBitmapFactory;
import org.apache.druid.collections.bitmap.RoaringBitmapFactory;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.column.StringUtf8DictionaryEncodedColumn;
import org.apache.druid.segment.data.FrontCodedIndexed;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.NumberedOverwriteShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.PartitionIds;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.TombstoneShardSpec;
import org.apache.spark.SparkException;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.Interval;
import org.joda.time.chrono.ISOChronology;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import scala.collection.JavaConverters;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static com.rovio.ingest.WriterContext.ConfKeys.BITMAP_FACTORY;
import static com.rovio.ingest.WriterContext.ConfKeys.DATASOURCE_INIT;
import static com.rovio.ingest.WriterContext.ConfKeys.EXCLUDED_DIMENSIONS;
import static com.rovio.ingest.WriterContext.ConfKeys.INDEX_SPEC;
import static com.rovio.ingest.WriterContext.ConfKeys.PARTITION_NUM_START;
import static com.rovio.ingest.WriterContext.ConfKeys.SEGMENT_GRANULARITY;
import static com.rovio.ingest.WriterContext.ConfKeys.SEGMENT_MAX_ROWS;
import static com.rovio.ingest.WriterContext.ConfKeys.SEGMENT_VERSION;
import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.column;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.unix_timestamp;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DruidSourceTest extends DruidSourceBaseTest {
    private static final Interval DAY_1 =
            new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-17T00:00:00Z"));
    private static final Interval DAY_2 =
            new Interval(DateTime.parse("2019-10-17T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
    private static final List<String> RANGE_DIMENSIONS = Collections.singletonList("country");

    @Test
    public void failWithoutDatasourceOption() {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> dataset.write().format("com.rovio.ingest.DruidSource").mode(SaveMode.Append).save());
        assertThat(thrown.getMessage(), containsString("Missing mandatory \"druid.datasource\" option"));
    }

    @Test
    public void failForInitDatasourceWithAppendWriteMode() {
        options.put(DATASOURCE_INIT, "true");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loadCsv(spark, "/data.csv")
                        .write()
                        .format("com.rovio.ingest.DruidSource")
                        .mode(SaveMode.Append)
                        .options(options)
                        .save());

        assertThat(thrown.getMessage(), containsString("Initializing datasource with append write mode is not supported"));
    }

    @Test
    public void failForInvalidVersionOption() {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        options.put(SEGMENT_VERSION, "invalid-version-format");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> dataset.write().format("com.rovio.ingest.DruidSource").mode(SaveMode.Overwrite).options(options).save());
        assertThat(thrown.getMessage(),
                containsString("\"druid.segment_version\" should be timestamp in ISO8601 format (yyyy-MM-ddTHH:mm:ss.SSSZZ)"));
    }

    @Test
    public void failForInvalidPartitionNumStartOption() {
        Executable runJob = () -> loadCsv(spark, "/data.csv")
                .write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        options.put(PARTITION_NUM_START, "-1");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, runJob);
        assertThat(thrown.getMessage(), containsString("\"druid.partition_num_start\" should be >= 0"));
    }

    @Test
    public void failForInvalidIndexSpecOption() {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        Executable runJob = () -> dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        options.put(INDEX_SPEC, "not a json");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, runJob);
        assertThat(thrown.getMessage(), containsString("Invalid \"druid.index_spec\""));

        options.put(INDEX_SPEC, "{\"stringDictionaryEncoding\":{\"type\":\"unknown\"}}");
        thrown = assertThrows(IllegalArgumentException.class, runJob);
        assertThat(thrown.getMessage(), containsString("Invalid \"druid.index_spec\""));

        options.put(INDEX_SPEC, "");
        thrown = assertThrows(IllegalArgumentException.class, runJob);
        assertThat(thrown.getMessage(), containsString("Invalid \"druid.index_spec\""));
    }

    @Test
    public void failForNullTimestamp() {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv")
                .drop("date")
                .withColumn("date", lit(null).cast(DataTypes.TimestampType))
                .repartition(column("country"));
        dataset.show(false);
        SparkException thrown = assertThrows(SparkException.class,
                () -> dataset.write()
                        .format("com.rovio.ingest.DruidSource")
                        .mode(SaveMode.Overwrite)
                        .options(options)
                        .save());
        assertThat(thrown.getCause().getMessage(), containsString(
                "Null value for column 'date'."));
    }

    @Test
    public void passWhenPartitionedByTime() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        dataset = dataset.repartition(column("date"));
        dataset.show(false);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        List<SegmentId> segmentIds = verifySegmentTable(interval, version, true, 2);
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(0).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(0).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(1).getPartitionNum());

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        segmentIds = verifySegmentTable(interval, version, true, 2);
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(0).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(0).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(1).getPartitionNum());
    }

    @Test
    public void passWhenPartitionedByTimeWithAppendWriteModeAndExplicitSegmentVersionParam() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        dataset = dataset.repartition(column("date"));
        dataset.show(false);

        String version = DateTime.now(ISOChronology.getInstanceUTC()).plusMinutes(10).toString();
        options.put(SEGMENT_VERSION, version);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        List<SegmentId> segmentIds = verifySegmentTable(interval, version, true, 2);
        Assertions.assertEquals(version, segmentIds.get(0).getVersion());
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(0).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(0).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals(version, segmentIds.get(1).getVersion());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(0, segmentIds.get(1).getPartitionNum());

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 2, false);
        segmentIds = verifySegmentTable(interval, version, true, 4);
        Assertions.assertEquals(0, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals(version, segmentIds.get(1).getVersion());
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(1, segmentIds.get(1).getPartitionNum());
        Assertions.assertEquals(0, segmentIds.get(2).getPartitionNum());
        Assertions.assertEquals(version, segmentIds.get(3).getVersion());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(3).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(3).getIntervalEnd().toString());
        Assertions.assertEquals(1, segmentIds.get(3).getPartitionNum());
    }

    @Test
    public void passWhenPartitionedByTimeWithAppendWriteModeAndPartitionNumStart() throws IOException {
        options.put(PARTITION_NUM_START, "1000");

        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        dataset = dataset.repartition(column("date"));
        dataset.show(false);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        List<SegmentId> segmentIds = verifySegmentTable(interval, version, true, 2);
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(0).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(0).getIntervalEnd().toString());
        Assertions.assertEquals(1000, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(1000, segmentIds.get(1).getPartitionNum());

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();

        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 2, false);
        segmentIds = verifySegmentTable(interval, version, true, 4);
        Assertions.assertEquals(1000, segmentIds.get(0).getPartitionNum());
        Assertions.assertEquals("2019-10-16T00:00:00.000Z", segmentIds.get(1).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(1).getIntervalEnd().toString());
        Assertions.assertEquals(1001, segmentIds.get(1).getPartitionNum());
        Assertions.assertEquals(1000, segmentIds.get(2).getPartitionNum());
        Assertions.assertEquals("2019-10-17T00:00:00.000Z", segmentIds.get(3).getIntervalStart().toString());
        Assertions.assertEquals("2019-10-18T00:00:00.000Z", segmentIds.get(3).getIntervalEnd().toString());
        Assertions.assertEquals(1001, segmentIds.get(3).getPartitionNum());
    }

    @Test
    public void passWhenPartitionedByDateWithOverwriteWriteModeAndExplicitSegmentVersionParam() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv")
                // Convert TimestampType -> DateType
                .withColumn("date", column("date").cast(DataTypes.DateType));
        dataset = dataset.repartition(column("date"));
        dataset.show(false);

        String version = DateTime.now(ISOChronology.getInstanceUTC()).plusMinutes(15).toString();
        options.put(SEGMENT_VERSION, version);

        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        verifySegmentTable(interval, version, true, 2);
    }

    @Test
    public void passWhenPartitionedByTransitiveTimeDimension() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, false);
        verifySegmentTable(interval, version, true, 2);
    }

    @Test
    public void passForMonthGranularity() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("MONTH"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        options.put(SEGMENT_GRANULARITY, "MONTH");
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-01T00:00:00Z"), DateTime.parse("2019-11-01T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 1, true);
        verifySegmentTable(interval, version, true, 1);
    }

    @Test
    public void passForDayGranularityWithMaxRows() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        options.put(SEGMENT_MAX_ROWS, String.valueOf(1));
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 4, false);
        verifySegmentTable(interval, version, true, 8);
    }

    @Test
    public void passForDayGranularityWithPartitionTimeAndNum() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__PARTITION_TIME__",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq())
                        .divide(1000)
                        .cast(DataTypes.TimestampType));

        dataset = dataset.withColumn("__PARTITION_NUM__", functions.row_number()
                .over(Window.partitionBy("__PARTITION_TIME__").orderBy("__PARTITION_TIME__")).mod(2));
        dataset = dataset.repartition(column("__PARTITION_TIME__"), column("__PARTITION_NUM__"));
        dataset.show(false);

        options.put(SEGMENT_MAX_ROWS, String.valueOf(1));
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, version, 2, false);
        verifySegmentTable(interval, version, true, 4);
    }

    @Test
    public void passForPartiallyOverWrittenSegments() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String firstVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, firstVersion, 1, false);
        verifySegmentTable(interval, firstVersion, true, 2);

        Dataset<Row> dataset2 = loadCsv(spark, "/data2.csv");
        dataset2.show(false);
        columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset2 = dataset2.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset2 = dataset2.repartition(dataset2.col("__partition"));
        dataset2.show(false);

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);
        dataset2.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, firstVersion, 1, false);
        verifySegmentTable(interval, firstVersion, true, 2);

        interval = new Interval(DateTime.parse("2019-10-17T00:00:00Z"), DateTime.parse("2019-10-19T00:00:00Z"));
        String secondVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, secondVersion, 1, false);
        verifySegmentTable(interval, secondVersion, true, 2);
    }

    @Test
    public void shouldSaveWhenIncrementalAndGranularityChanges() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String firstVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, firstVersion, 1, false);
        verifySegmentTable(interval, firstVersion, true, 2);

        dataset = loadCsv(spark, "/data2.csv");
        dataset.show(false);
        columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("MONTH"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        Dataset<Row> dataset2 = dataset.repartition(dataset.col("__partition"));
        dataset2.show(false);

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);
        options.put(SEGMENT_GRANULARITY, "MONTH");
        dataset2.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();
        interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        verifySegmentTable(interval, firstVersion, true, 2);

        String secondVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        interval = new Interval(DateTime.parse("2019-10-01T00:00:00Z"), DateTime.parse("2019-11-01T00:00:00Z"));
        verifySegmentTable(interval, secondVersion, true, 1);
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, secondVersion, 1, true);
    }

    @Test
    public void shouldOverwriteWhenGranularityChangesWithInit() throws IOException {
        Dataset<Row> dataset = loadCsv(spark, "/data.csv");
        List<Column> columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("DAY"));
        dataset = dataset.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset = dataset.repartition(column("__partition"));
        dataset.show(false);

        options.put(EXCLUDED_DIMENSIONS, "__partition");
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        Interval interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        String firstVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, firstVersion, 1, false);
        verifySegmentTable(interval, firstVersion, true, 2);

        Dataset<Row> dataset2 = loadCsv(spark, "/data2.csv");
        dataset2.show(false);
        columns = Lists.newArrayList(unix_timestamp(column("date")).multiply(1000), lit("MONTH"));
        dataset2 = dataset2.withColumn("__partition",
                callUDF("normalizeTimeColumn",
                        JavaConverters.asScalaIteratorConverter(columns.iterator()).asScala().toSeq()));

        dataset2 = dataset2.repartition(dataset2.col("__partition"));
        dataset2.show(false);

        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS + 60_000);
        options.put(SEGMENT_GRANULARITY, "MONTH");
        options.put(DATASOURCE_INIT, "true");

        dataset2.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        interval = new Interval(DateTime.parse("2019-10-16T00:00:00Z"), DateTime.parse("2019-10-18T00:00:00Z"));
        verifySegmentTable(interval, firstVersion, false, 2);

        String secondVersion = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        interval = new Interval(DateTime.parse("2019-10-01T00:00:00Z"), DateTime.parse("2019-11-01T00:00:00Z"));
        verifySegmentTable(interval, secondVersion, true, 1);
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), interval, secondVersion, 1, true);
    }

    @Test
    public void appendCreatesNumberedTailsOnRangeCoreSet() throws IOException {
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);
        publishRangeCoreSet(DAY_2, version);
        options.put(PARTITION_NUM_START, "1000");

        append(loadCsv(spark, "/data.csv").repartition(column("date")));

        for (Interval day : Arrays.asList(DAY_1, DAY_2)) {
            Map<Integer, JsonNode> shardSpecs = findShardSpecs(day, version);
            Assertions.assertEquals(Arrays.asList(0, 1, 2, 1000), new ArrayList<>(shardSpecs.keySet()));
            // the core set is untouched
            Assertions.assertEquals("range", shardSpecs.get(2).get("type").asText());
            Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":1000,\"partitions\":3}"),
                    shardSpecs.get(1000));
        }
        // one new segment per day in the same version as the core set
        verifySegmentPath(Paths.get(testFolder.toString(), DATA_SOURCE), new Interval(DAY_1.getStart(), DAY_2.getEnd()),
                version, 1, false);
        verifySegmentTable(new Interval(DAY_1.getStart(), DAY_2.getEnd()), version, true, 8);
    }

    @Test
    public void appendContinuesAfterTheMaxPartitionNumOfNumberedTails() throws IOException {
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);
        options.put(PARTITION_NUM_START, "1000");

        append(day1Dataset());
        append(day1Dataset());

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, version);
        Assertions.assertEquals(Arrays.asList(0, 1, 2, 1000, 1001), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":1000,\"partitions\":3}"),
                shardSpecs.get(1000));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":1001,\"partitions\":3}"),
                shardSpecs.get(1001));
    }

    @Test
    public void appendCreatesNumberedTailsOnNumberedSegments() throws IOException {
        String version = coreVersion();
        publishSegments(DAY_1, version, new NumberedShardSpec(0, 2), new NumberedShardSpec(1, 2));

        // no druid.partition_num_start: tail starts right after the core set
        append(day1Dataset());

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, version);
        Assertions.assertEquals(Arrays.asList(0, 1, 2), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":2,\"partitions\":2}"),
                shardSpecs.get(2));
    }

    @Test
    public void appendKeepsLinearTailsOnLinearSegments() throws IOException {
        append(day1Dataset());
        append(day1Dataset());

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, DateTime.now(ISOChronology.getInstanceUTC()).toString());
        Assertions.assertEquals(Arrays.asList(0, 1), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"linear\",\"partitionNum\":0}"), shardSpecs.get(0));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"linear\",\"partitionNum\":1}"), shardSpecs.get(1));
    }

    @Test
    public void failToAppendToIntervalWithBothLinearAndRangeSegments() throws IOException {
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);
        publishSegments(DAY_1, version, new LinearShardSpec(3));

        assertAppendFails("both linear and non-linear");

        Assertions.assertEquals(4, countSegments());
        assertNoSegmentFiles();
    }

    @Test
    public void failToAppendWithTooLargePartitionNumToRangeCoreSet() throws IOException {
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);
        options.put(PARTITION_NUM_START, "40000");

        assertAppendFails("Partition num 40000 of a new segment");

        Assertions.assertEquals(3, countSegments());
        assertNoSegmentFiles();
    }

    @Test
    public void failToAppendWhenNumberOfCorePartitionsIsUnknown() throws IOException {
        String version = coreVersion();
        publishSegments(DAY_1, version, new DimensionRangeShardSpec(RANGE_DIMENSIONS, null, null, 0, null));

        // DimensionRangeShardSpec.UNKNOWN_NUM_CORE_PARTITIONS
        assertAppendFails("agree on a known number of core partitions, but found [-1]");

        Assertions.assertEquals(1, countSegments());
        assertNoSegmentFiles();
    }

    @Test
    public void appendCreatesNumberedTailsOnNumberedSegmentsWithoutCorePartitions() throws IOException {
        // As written by streaming ingestion: a complete set that has no core partitions
        String version = coreVersion();
        publishSegments(DAY_1, version, new NumberedShardSpec(0, 0), new NumberedShardSpec(1, 0));

        append(day1Dataset());

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, version);
        Assertions.assertEquals(Arrays.asList(0, 1, 2), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":2,\"partitions\":0}"),
                shardSpecs.get(2));
    }

    @Test
    public void appendedSegmentsNeverTakeThePartitionNumOfACoreSegment() throws IOException {
        // The core set has 3 segments, but the one with partition num 2 is not among the used segments, e.g. because it
        // was killed. The next partition num after the existing ones would be 2, the one of a core segment. Then the
        // set would look complete, with a segment that is not the one of the core set.
        String version = coreVersion();
        publishSegments(DAY_1, version,
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, null, StringTuple.create("FI"), 0, 3),
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, StringTuple.create("FI"), StringTuple.create("US"), 1, 3));

        append(day1Dataset());

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, version);
        Assertions.assertEquals(Arrays.asList(0, 1, 3), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":3,\"partitions\":3}"),
                shardSpecs.get(3));
    }

    @Test
    public void appendedPartitionNumsOfTheInputAreAddedToTheFirstPartitionNumAfterTheCoreSet() throws IOException {
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);

        append(withPartitionNum(day1Dataset(), 1));

        Map<Integer, JsonNode> shardSpecs = findShardSpecs(DAY_1, version);
        // the first partition num after the core set is 3, and the one of the input is added to it
        Assertions.assertEquals(Arrays.asList(0, 1, 2, 4), new ArrayList<>(shardSpecs.keySet()));
        Assertions.assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":4,\"partitions\":3}"),
                shardSpecs.get(4));
    }

    @Test
    public void failToAppendWithNegativePartitionNumThatWouldTakeTheNumOfACoreSegment() throws IOException {
        // The first partition num after the core set of 3 segments is 3, so -1 of the input would make it 2, the
        // partition num of a core segment: the storage path of that segment would be overwritten.
        String version = coreVersion();
        publishRangeCoreSet(DAY_1, version);
        Dataset<Row> negative = withPartitionNum(day1Dataset(), -1);

        SparkException thrown = assertThrows(SparkException.class, () -> append(negative));

        assertThat(ExceptionUtils.getStackTrace(thrown),
                containsString("is the partition num of a segment of the core set of 3 segments"));
        Assertions.assertEquals(3, countSegments());
        assertNoSegmentFiles();
    }

    /**
     * The dataset with columns __PARTITION_TIME__ and __PARTITION_NUM__, as the Dataset extension makes them, but with
     * the given partition num.
     */
    private static Dataset<Row> withPartitionNum(Dataset<Row> dataset, int partitionNum) {
        return DruidDatasetExtensions.repartitionByDruidSegmentSize(dataset, "date", "DAY", 5_000_000, false)
                .withColumn("__PARTITION_NUM__", lit(partitionNum))
                .repartition(column("__PARTITION_TIME__"), column("__PARTITION_NUM__"));
    }

    @Test
    public void failToAppendToTombstone() throws IOException {
        // A tombstone has no core partitions, but it is not a core set that can be followed by segments
        String version = coreVersion();
        publishSegments(DAY_1, version, new TombstoneShardSpec());

        assertAppendFails("appending to segments of shard spec types [tombstone] is not supported");

        Assertions.assertEquals(1, countSegments());
        assertNoSegmentFiles();
    }

    @Test
    public void failToAppendToSegmentsThatOverwriteOthers() throws IOException {
        String version = coreVersion();
        publishSegments(DAY_1, version, new NumberedOverwriteShardSpec(PartitionIds.NON_ROOT_GEN_START_PARTITION_ID, 0, 1, (short) 1, (short) 1));

        assertAppendFails("appending to segments of shard spec types [numbered_overwrite] is not supported");

        Assertions.assertEquals(1, countSegments());
        assertNoSegmentFiles();
    }

    @Test
    public void failToAppendWhenCorePartitionsDisagree() throws Exception {
        String version = coreVersion();
        publishSegments(DAY_1, version,
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, null, StringTuple.create("FI"), 0, 3),
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, StringTuple.create("FI"), null, 1, 2));

        assertAppendFails("agree on a known number of core partitions");

        Assertions.assertEquals(2, countSegments());
        assertNoSegmentFiles();
    }

    /**
     * A version older than the one that a write would generate.
     */
    private static String coreVersion() {
        return DateTime.now(ISOChronology.getInstanceUTC()).minusDays(1).toString();
    }

    /**
     * Only the rows of {@link #DAY_1} of data.csv.
     */
    private Dataset<Row> day1Dataset() {
        return loadCsv(spark, "/data.csv").where("date < '2019-10-17'").repartition(column("date"));
    }

    private void append(Dataset<Row> dataset) {
        dataset.write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Append)
                .options(options)
                .save();
    }

    private void assertAppendFails(String expectedMessage) {
        SparkException thrown = assertThrows(SparkException.class, () -> append(day1Dataset()));
        assertThat(ExceptionUtils.getStackTrace(thrown), containsString(expectedMessage));
    }

    private void assertNoSegmentFiles() throws IOException {
        Path root = Paths.get(testFolder.toString(), DATA_SOURCE);
        if (Files.exists(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                Assertions.assertTrue(files.noneMatch(Files::isRegularFile), "Segment files left in deep storage");
            }
        }
    }

    /**
     * A range partitioned core set of 3 segments, as written by Druid compaction.
     */
    private void publishRangeCoreSet(Interval interval, String version) {
        publishSegments(interval, version,
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, null, StringTuple.create("FI"), 0, 3),
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, StringTuple.create("FI"), StringTuple.create("US"), 1, 3),
                new DimensionRangeShardSpec(RANGE_DIMENSIONS, StringTuple.create("US"), null, 2, 3));
    }

    /**
     * Publishes segments, without any data, to the metadata as if they were written earlier.
     */
    private void publishSegments(Interval interval, String version, ShardSpec... shardSpecs) {
        List<DataSegment> segments = Arrays.stream(shardSpecs)
                .map(shardSpec -> DataSegment.builder()
                        .dataSource(DATA_SOURCE)
                        .loadSpec(ImmutableMap.of("type", "local", "path", "tmp/unused/index.zip"))
                        .shardSpec(shardSpec)
                        .size(1)
                        .binaryVersion(9)
                        .interval(interval)
                        .version(version)
                        .build())
                .collect(Collectors.toList());
        new MetadataUpdater(WriterContext.from(new CaseInsensitiveStringMap(options), version)).publishSegments(segments);
    }

    @Test
    public void passWithoutIndexSpec() throws Exception {
        try (QueryableIndex index = writeAndLoadSegment()) {
            // Default encodings of the library: utf8 dictionary, concise bitmaps.
            assertThat(stringDictionary(index, "country"), not(instanceOf(FrontCodedIndexed.class)));
            assertThat(index.getBitmapFactoryForDimensions(), instanceOf(ConciseBitmapFactory.class));
        }
    }

    @Test
    public void passWithFrontCodedIndexSpec() throws Exception {
        options.put(INDEX_SPEC, "{\"bitmap\":{\"type\":\"roaring\"},\"dimensionCompression\":\"lz4\","
                + "\"stringDictionaryEncoding\":{\"type\":\"frontCoded\",\"bucketSize\":4,\"formatVersion\":1},"
                + "\"metricCompression\":\"lz4\",\"longEncoding\":\"longs\"}");

        try (QueryableIndex index = writeAndLoadSegment()) {
            assertThat(stringDictionary(index, "country"), instanceOf(FrontCodedIndexed.class));
            assertThat(index.getBitmapFactoryForDimensions(), instanceOf(RoaringBitmapFactory.class));
        }
    }

    @Test
    public void passWithIndexSpecWithoutBitmapUsesDruidDefault() throws Exception {
        options.put(INDEX_SPEC, "{\"stringDictionaryEncoding\":{\"type\":\"frontCoded\"}}");

        try (QueryableIndex index = writeAndLoadSegment()) {
            assertThat(stringDictionary(index, "country"), instanceOf(FrontCodedIndexed.class));
            // druid.bitmap_factory is not set, so the default of Druid is used instead of the default of rovio-ingest.
            assertThat(index.getBitmapFactoryForDimensions(), instanceOf(RoaringBitmapFactory.class));
        }
    }

    @Test
    public void passWithBitmapFactoryOverridingIndexSpec() throws Exception {
        options.put(INDEX_SPEC, "{\"bitmap\":{\"type\":\"concise\"},"
                + "\"stringDictionaryEncoding\":{\"type\":\"frontCoded\"}}");
        options.put(BITMAP_FACTORY, "roaring");

        try (QueryableIndex index = writeAndLoadSegment()) {
            assertThat(stringDictionary(index, "country"), instanceOf(FrontCodedIndexed.class));
            assertThat(index.getBitmapFactoryForDimensions(), instanceOf(RoaringBitmapFactory.class));
        }
    }

    @Test
    public void passWithBitmapFactoryAddedToIndexSpec() throws Exception {
        options.put(INDEX_SPEC, "{\"stringDictionaryEncoding\":{\"type\":\"frontCoded\"}}");
        options.put(BITMAP_FACTORY, "concise");

        try (QueryableIndex index = writeAndLoadSegment()) {
            assertThat(stringDictionary(index, "country"), instanceOf(FrontCodedIndexed.class));
            assertThat(index.getBitmapFactoryForDimensions(), instanceOf(ConciseBitmapFactory.class));
        }
    }

    /**
     * Writes data.csv with the current options and loads the first segment of the first day.
     */
    private QueryableIndex writeAndLoadSegment() throws IOException {
        loadCsv(spark, "/data.csv")
                .repartition(column("date"))
                .write()
                .format("com.rovio.ingest.DruidSource")
                .mode(SaveMode.Overwrite)
                .options(options)
                .save();

        String version = DateTime.now(ISOChronology.getInstanceUTC()).toString();
        return loadSegmentIndex(Paths.get(testFolder.toString(), DATA_SOURCE),
                DateTime.parse("2019-10-16T00:00:00Z"), version, 0);
    }

    /**
     * The dictionary class of a string column: {@link FrontCodedIndexed} when front coded, otherwise something else.
     * Druid doesn't expose the dictionary encoding of a loaded column, thus reading the private field.
     */
    private static Object stringDictionary(QueryableIndex index, String columnName) throws Exception {
        Object column = index.getColumnHolder(columnName).getColumn();
        assertThat(column, instanceOf(StringUtf8DictionaryEncodedColumn.class));
        Field dictionary = StringUtf8DictionaryEncodedColumn.class.getDeclaredField("utf8Dictionary");
        dictionary.setAccessible(true);
        return dictionary.get(column);
    }
}
