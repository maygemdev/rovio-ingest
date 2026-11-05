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
package com.rovio.ingest.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.StringTuple;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static java.lang.String.format;

/**
 * The buckets of a range partitioning, in the same way as Druid defines them for
 * {@link org.apache.druid.timeline.partition.DimensionRangeShardSpec}: given the K-1 interior boundary tuples
 * {@code b_1 < ... < b_(K-1)}, bucket {@code i} (0 &lt;= i &lt; K) contains the tuples in {@code [b_i, b_(i+1))},
 * where {@code b_0} and {@code b_K} are unbounded (null).
 * <p>
 * A tuple holds the values of the partition dimensions of a row. Tuples are ordered by {@link StringTuple#compareTo},
 * i.e. element by element, {@code null} before any string, and strings by {@link String#compareTo} (UTF-16 code
 * units), as in Druid. This differs from the order of Spark strings (UTF-8 bytes) for some supplementary characters,
 * so the buckets can't be computed with Spark's own ordering.
 * <p>
 * This is shared by the segment writer and by the helper that computes the buckets in Spark, to make sure that both
 * agree on the bucket of a row. Not intended as a public API.
 */
public final class RangeBuckets implements Serializable {
    private static final long serialVersionUID = 1L;

    private final List<String> dimensions;
    // Interior boundaries, K-1 tuples. Not StringTuples, because those are not Serializable.
    private final String[][] boundaries;
    private transient StringTuple[] boundaryTuples;

    private RangeBuckets(List<String> dimensions, String[][] boundaries) {
        this.dimensions = new ArrayList<>(dimensions);
        this.boundaries = boundaries;
    }

    /**
     * @param dimensions       the partition dimensions
     * @param boundariesJson   the K-1 interior boundary tuples as a json array of arrays, e.g.
     *                         {@code [["FI","12"],["US",null]]}. {@code []} is a single bucket.
     * @throws IllegalArgumentException if the json is invalid or the boundaries are not valid for the dimensions
     */
    public static RangeBuckets of(List<String> dimensions, String boundariesJson) {
        JsonNode root;
        try {
            root = MAPPER.readTree(boundariesJson);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid boundaries, expected a json array of arrays of strings: " + e.getMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Invalid boundaries, expected a json array of arrays of strings");
        }
        // Read the values by hand: Jackson would silently convert numbers and booleans to strings, e.g. [[12]] to [["12"]].
        String[][] boundaries = new String[root.size()][];
        for (int i = 0; i < boundaries.length; i++) {
            JsonNode tuple = root.get(i);
            if (!tuple.isArray()) {
                throw new IllegalArgumentException(format(
                        "Invalid boundary %d (%s), expected a json array of strings or nulls", i, tuple));
            }
            boundaries[i] = new String[tuple.size()];
            for (int j = 0; j < boundaries[i].length; j++) {
                JsonNode value = tuple.get(j);
                if (value.isTextual()) {
                    boundaries[i][j] = value.textValue();
                } else if (!value.isNull()) {
                    throw new IllegalArgumentException(format(
                            "Invalid boundary %d (%s), value %d (%s) is neither a string nor null", i, tuple, j, value));
                }
            }
        }
        return of(dimensions, boundaries);
    }

    /**
     * The boundaries are copied.
     *
     * @throws IllegalArgumentException if the boundaries are not valid for the dimensions
     */
    public static RangeBuckets of(List<String> dimensions, String[][] givenBoundaries) {
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("At least one partition dimension is required");
        }
        // Copy first, so that the checks below are done on the values that are kept even if the caller changes its array.
        String[][] boundaries = new String[givenBoundaries.length][];
        for (int i = 0; i < boundaries.length; i++) {
            boundaries[i] = givenBoundaries[i] == null ? null : givenBoundaries[i].clone();
        }
        StringTuple[] tuples = new StringTuple[boundaries.length];
        for (int i = 0; i < boundaries.length; i++) {
            if (boundaries[i] == null || boundaries[i].length != dimensions.size()) {
                throw new IllegalArgumentException(format(
                        "Boundary %d (%s) should have %d values, one for each of the partition dimensions %s",
                        i, boundaries[i] == null ? null : Arrays.toString(boundaries[i]), dimensions.size(), dimensions));
            }
            tuples[i] = new StringTuple(boundaries[i]);
            if (i > 0 && tuples[i - 1].compareTo(tuples[i]) >= 0) {
                throw new IllegalArgumentException(format(
                        "Boundaries should be strictly increasing, but boundary %d (%s) is not greater than boundary %d (%s)",
                        i, tuples[i], i - 1, tuples[i - 1]));
            }
        }
        RangeBuckets buckets = new RangeBuckets(dimensions, boundaries);
        buckets.boundaryTuples = tuples;
        return buckets;
    }

    /**
     * Builds the tuple of a row in the same way as Druid does when it looks for the range shard spec of a row: the
     * value of a dimension that has exactly one value, otherwise (missing or multi-value) null. With
     * {@code useDefaultValueForNull} an empty string is the same as null.
     */
    public static StringTuple tupleOf(List<String> dimensions, InputRow row, boolean useDefaultValueForNull) {
        String[] values = new String[dimensions.size()];
        for (int i = 0; i < values.length; i++) {
            List<String> dimensionValues = row.getDimension(dimensions.get(i));
            values[i] = dimensionValues != null && dimensionValues.size() == 1 ? dimensionValues.get(0) : null;
        }
        return tupleOf(values, useDefaultValueForNull);
    }

    /**
     * Builds the tuple of the given values of the partition dimensions, which are null if the row doesn't have a
     * (single) value. With {@code useDefaultValueForNull} an empty string is the same as null.
     */
    public static StringTuple tupleOf(String[] values, boolean useDefaultValueForNull) {
        String[] normalized = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            normalized[i] = useDefaultValueForNull && "".equals(values[i]) ? null : values[i];
        }
        return new StringTuple(normalized);
    }

    /**
     * @return the interior boundaries as a json array, for the option druid.partition_boundaries
     */
    public String getBoundariesJson() {
        try {
            return MAPPER.writeValueAsString(boundaries);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write the boundaries as json", e);
        }
    }

    public List<String> getDimensions() {
        return dimensions;
    }

    /**
     * @return K, the number of buckets
     */
    public int getNumBuckets() {
        return boundaries.length + 1;
    }

    /**
     * @return the inclusive start of the bucket, null if unbounded
     */
    public StringTuple getStart(int bucket) {
        checkBucket(bucket);
        return bucket == 0 ? null : tuples()[bucket - 1];
    }

    /**
     * @return the exclusive end of the bucket, null if unbounded
     */
    public StringTuple getEnd(int bucket) {
        checkBucket(bucket);
        return bucket == boundaries.length ? null : tuples()[bucket];
    }

    /**
     * @return true if the tuple is in the range {@code [start, end)} of the bucket
     */
    public boolean contains(int bucket, StringTuple tuple) {
        StringTuple start = getStart(bucket);
        StringTuple end = getEnd(bucket);
        return (start == null || start.compareTo(tuple) <= 0) && (end == null || tuple.compareTo(end) < 0);
    }

    /**
     * @return the bucket that the tuple belongs to
     */
    public int bucketOf(StringTuple tuple) {
        StringTuple[] tuples = tuples();
        int index = Arrays.binarySearch(tuples, tuple);
        // A tuple that is equal to a boundary is the start of the bucket that follows the boundary. Otherwise the
        // bucket is the number of the boundaries that are smaller than the tuple.
        return index >= 0 ? index + 1 : -index - 1;
    }

    private void checkBucket(int bucket) {
        if (bucket < 0 || bucket >= getNumBuckets()) {
            throw new IllegalArgumentException(format("Bucket %d is out of range [0, %d)", bucket, getNumBuckets()));
        }
    }

    private StringTuple[] tuples() {
        StringTuple[] tuples = boundaryTuples;
        if (tuples == null) {
            // After deserialization
            tuples = Arrays.stream(boundaries).map(StringTuple::new).toArray(StringTuple[]::new);
            boundaryTuples = tuples;
        }
        return tuples;
    }
}
