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
package com.rovio.ingest.extensions.java;

import com.rovio.ingest.model.FieldType;
import com.rovio.ingest.util.RangeBuckets;
import com.rovio.ingest.util.StringTupleSketch;
import org.apache.druid.data.input.StringTuple;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.apache.spark.sql.api.java.UDF1;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.UserDefinedFunction;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;

/**
 * Computes the buckets of a range partitioning for the options {@code druid.partition_dimensions} and
 * {@code druid.partition_boundaries} of {@link com.rovio.ingest.DruidSource}, in Spark and in the same way as Druid
 * does when it does a range partitioned ingestion: the boundaries are quantiles of the tuples of the values of the
 * partition dimensions, such that the buckets have about the same number of rows.
 * <p>
 * Usage:
 * <pre>{@code
 * DruidRangePartitioning partitioning = DruidRangePartitioning.compute(
 *         dataset, Arrays.asList("country", "client"), 5_000_000, true);
 *
 * options.put(ConfKeys.PARTITION_DIMENSIONS, "country,client");
 * options.put(ConfKeys.PARTITION_BOUNDARIES, partitioning.getBoundariesJson());
 *
 * dataset
 *     .withColumn("__PARTITION_TIME__", ...)
 *     .withColumn("__PARTITION_NUM__", partitioning.bucketColumn())
 *     .repartitionByRange(partitioning.getNumBuckets(), functions.col("__PARTITION_NUM__"))
 *     .write()...
 * }</pre>
 * The dataset is scanned once to compute the boundaries. It is assumed to be already rolled up (to have a single row
 * for each row of Druid), as the number of the rows is the number of the rows of the dataset. Use the same
 * {@code useDefaultValueForNull} as for {@code druid.use_default_values_for_null}.
 * <p>
 * Note that Spark must not be asked to do the range partitioning itself (e.g. with {@code repartitionByRange} on the
 * dimension columns): Spark orders strings by their UTF-8 bytes, but Druid by their UTF-16 code units, which differ
 * for some supplementary characters.
 */
public final class DruidRangePartitioning {
    private static final Logger LOG = LoggerFactory.getLogger(DruidRangePartitioning.class);
    // How many levels of merging sketches of Spark partitions, so that the driver gets only a few partial sketches
    // (about the square root of the number of Spark partitions) instead of one for each partition
    private static final int TREE_AGGREGATE_DEPTH = 2;

    private final RangeBuckets buckets;
    private final long rowCount;
    private final boolean useDefaultValueForNull;

    private DruidRangePartitioning(RangeBuckets buckets, long rowCount, boolean useDefaultValueForNull) {
        this.buckets = buckets;
        this.rowCount = rowCount;
        this.useDefaultValueForNull = useDefaultValueForNull;
    }

    /**
     * Computes the buckets for the dataset.
     *
     * @param dataset                the dataset to be written, already rolled up, with all of the partition dimensions
     *                               as columns. The columns must be of type string. The dataset must give the same rows
     *                               when it is written as when this is computed (cache it if it is not deterministic), as
     *                               otherwise a bucket may end up without rows and the write fails.
     * @param dimensions             the partition dimensions, in order of significance
     * @param targetRowsPerSegment   the number of rows that the buckets should have, approximately. The number of
     *                               the buckets is the number of the rows divided by this, rounded, at least 1.
     * @param useDefaultValueForNull whether {@code ""} is {@code null}, the same as the option
     *                               {@code druid.use_default_values_for_null} of the write
     * @throws IllegalArgumentException if the dimensions are not valid for the dataset
     */
    public static DruidRangePartitioning compute(Dataset<Row> dataset, List<String> dimensions,
                                                 int targetRowsPerSegment, boolean useDefaultValueForNull) {
        if (targetRowsPerSegment <= 0) {
            throw new IllegalArgumentException("targetRowsPerSegment must be positive but is " + targetRowsPerSegment);
        }
        validate(dataset.schema(), dimensions);

        Dataset<byte[]> sketches = dataset.select(columns(dimensions))
                // A sketch of each Spark partition. This must not use the fields of the class, to be serializable.
                .mapPartitions((MapPartitionsFunction<Row, byte[]>) rows -> {
                    StringTupleSketch sketch = StringTupleSketch.create();
                    while (rows.hasNext()) {
                        sketch.update(tupleOf(rows.next(), useDefaultValueForNull));
                    }
                    return Collections.singletonList(sketch.toByteArray()).iterator();
                }, Encoders.BINARY());
        // Merge the sketches in several steps, so that the driver doesn't get all of them. They can be big.
        byte[] merged = sketches.toJavaRDD().treeAggregate(
                StringTupleSketch.create().toByteArray(),
                StringTupleSketch::merge,
                StringTupleSketch::merge,
                TREE_AGGREGATE_DEPTH);

        StringTupleSketch sketch = StringTupleSketch.fromByteArray(merged);
        RangeBuckets buckets = sketch.toRangeBuckets(new ArrayList<>(dimensions), targetRowsPerSegment);
        LOG.info("Range partitioning by {}: {} rows, {} buckets for about {} rows each",
                dimensions, sketch.getN(), buckets.getNumBuckets(), targetRowsPerSegment);
        return new DruidRangePartitioning(buckets, sketch.getN(), useDefaultValueForNull);
    }

    /**
     * @return the number of rows of the dataset
     */
    public long getRowCount() {
        return rowCount;
    }

    /**
     * @return the number of buckets, K. This is the number of values of {@link #bucketColumn()}, from 0 to K-1, and
     * at most the number of rows divided by the target rows per segment. It can be less if some tuples of values are
     * very common.
     */
    public int getNumBuckets() {
        return buckets.getNumBuckets();
    }

    public List<String> getDimensions() {
        return buckets.getDimensions();
    }

    /**
     * @return the value for the option {@code druid.partition_boundaries}: the interior boundaries of the buckets, K-1
     * tuples as a json array of arrays
     */
    public String getBoundariesJson() {
        return buckets.getBoundariesJson();
    }

    /**
     * @return a column that is the bucket of the row, from 0 to K-1. Use it as the column {@code __PARTITION_NUM__}.
     * The dataset must have the partition dimensions as columns.
     */
    public Column bucketColumn() {
        // Not to use the fields of the class, to be serializable
        RangeBuckets buckets = this.buckets;
        boolean useDefaultValueForNull = this.useDefaultValueForNull;
        UserDefinedFunction bucketOf = functions.udf(
                (UDF1<Row, Integer>) values -> buckets.bucketOf(tupleOf(values, useDefaultValueForNull)),
                DataTypes.IntegerType);
        return bucketOf.apply(functions.struct(columns(buckets.getDimensions())));
    }

    private static Column[] columns(List<String> dimensions) {
        // Quoted, as the name of a column is not an expression: a column "a.b" is not the field b of a column a.
        return dimensions.stream()
                .map(dimension -> functions.col("`" + dimension.replace("`", "``") + "`"))
                .toArray(Column[]::new);
    }

    /**
     * The tuple of a row that has only the values of the partition dimensions. Values are converted to strings in the
     * same way as the writer does.
     */
    private static StringTuple tupleOf(Row row, boolean useDefaultValueForNull) {
        String[] values = new String[row.length()];
        for (int i = 0; i < values.length; i++) {
            Object value = row.get(i);
            values[i] = value == null ? null : String.valueOf(value);
        }
        return RangeBuckets.tupleOf(values, useDefaultValueForNull);
    }

    private static void validate(StructType schema, List<String> dimensions) {
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("At least one partition dimension is required");
        }
        Set<String> seen = new HashSet<>();
        Set<String> columns = new HashSet<>(Arrays.asList(schema.fieldNames()));
        for (String dimension : dimensions) {
            if (!seen.add(dimension)) {
                throw new IllegalArgumentException(format("Duplicate partition dimension \"%s\"", dimension));
            }
            if (!columns.contains(dimension)) {
                throw new IllegalArgumentException(format("Partition dimension \"%s\" is not a column of the dataset, " +
                        "columns are %s", dimension, columns));
            }
            // The same as what is supported by the writer
            FieldType fieldType = FieldType.from(schema.apply(dimension).dataType());
            if (fieldType != FieldType.STRING) {
                throw new IllegalArgumentException(format("Partition dimension \"%s\" is of type %s, only string " +
                        "dimensions are supported", dimension, fieldType));
            }
        }
    }
}
