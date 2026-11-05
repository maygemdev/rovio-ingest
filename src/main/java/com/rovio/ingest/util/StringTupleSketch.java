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

import com.google.common.base.Preconditions;
import org.apache.datasketches.common.ArrayOfItemsSerDe;
import org.apache.datasketches.memory.Memory;
import org.apache.datasketches.quantiles.ItemsSketch;
import org.apache.datasketches.quantiles.ItemsUnion;
import org.apache.datasketches.quantilescommon.QuantileSearchCriteria;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.timeline.partition.PartitionBoundaries;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A quantiles sketch of the tuples of the values of the partition dimensions, which is used to find the boundaries of
 * the buckets of a range partitioning. This does the same as Druid does when it does a range partitioned
 * ingestion, see {@code StringSketch} and {@code StringSketchMerger} of {@code druid-indexing-service} (Druid 29,
 * {@code org.apache.druid.indexing.common.task.batch.parallel.distribution}), which can't be used here as
 * {@code druid-indexing-service} is not a dependency.
 * <p>
 * The sketches are exchanged as byte arrays, because {@link ItemsSketch} is not {@link java.io.Serializable}. Not
 * intended as a public API.
 */
public final class StringTupleSketch {
    // Same as in Druid: the smallest value with normalized rank error < 0.1%, retains up to ~86k elements
    private static final int SKETCH_K = 1 << 12;
    private static final Comparator<StringTuple> STRING_TUPLE_COMPARATOR = Comparator.naturalOrder();
    private static final ArrayOfItemsSerDe<StringTuple> SERDE = new StringTupleSerDe();

    private final ItemsSketch<StringTuple> sketch;

    private StringTupleSketch(ItemsSketch<StringTuple> sketch) {
        this.sketch = sketch;
    }

    public static StringTupleSketch create() {
        return new StringTupleSketch(ItemsSketch.getInstance(StringTuple.class, SKETCH_K, STRING_TUPLE_COMPARATOR));
    }

    public static StringTupleSketch fromByteArray(byte[] bytes) {
        return new StringTupleSketch(
                ItemsSketch.getInstance(StringTuple.class, Memory.wrap(bytes), STRING_TUPLE_COMPARATOR, SERDE));
    }

    /**
     * Merges two serialized sketches into a new serialized sketch. This is the combine function of the aggregation
     * of the sketches of Spark partitions.
     */
    public static byte[] merge(byte[] first, byte[] second) {
        ItemsUnion<StringTuple> union = ItemsUnion.getInstance(StringTuple.class, SKETCH_K, STRING_TUPLE_COMPARATOR);
        union.union(fromByteArray(first).sketch);
        union.union(fromByteArray(second).sketch);
        return new StringTupleSketch(union.getResult()).toByteArray();
    }

    public void update(StringTuple tuple) {
        sketch.update(tuple);
    }

    /**
     * @return the number of the tuples that were added to the sketch
     */
    public long getN() {
        return sketch.getN();
    }

    public byte[] toByteArray() {
        return sketch.toByteArray(SERDE);
    }

    /**
     * Finds the buckets for the given number of rows per segment, in the same way as Druid does, see
     * {@code StringSketch#getEvenPartitionsByTargetSize}: the number of buckets K is {@code n / target} rounded, at
     * least 1, and the boundaries are the K+1 quantiles of the tuples at the evenly spaced ranks, from which equal
     * tuples are removed. The first and the last are not boundaries, they are the unbounded start and end.
     * So the number of the buckets can be less than K if some tuples are very common.
     *
     * @param dimensions the partition dimensions, i.e. what the tuples are of
     */
    public RangeBuckets toRangeBuckets(List<String> dimensions, int targetRowsPerSegment) {
        Preconditions.checkArgument(targetRowsPerSegment > 0, "targetRowsPerSegment must be positive but is %s", targetRowsPerSegment);
        // The same as getEvenPartitionsByTargetSize of Druid 29:
        // https://github.com/apache/druid/blob/druid-29.0.0/indexing-service/src/main/java/org/apache/druid/indexing/common/task/batch/parallel/distribution/StringSketch.java
        // (getEvenPartitionsByMaxSize, which also takes the error of the sketch into account, is not what is used for
        // a target size)
        int numBuckets = Math.max(1, (int) Math.round((double) sketch.getN() / targetRowsPerSegment));

        PartitionBoundaries partitionBoundaries = sketch.isEmpty()
                ? new PartitionBoundaries(new StringTuple[0])
                : new PartitionBoundaries(sketch.getPartitionBoundaries(numBuckets, QuantileSearchCriteria.EXCLUSIVE).boundaries);

        // [null, boundary 1, ..., boundary K-1, null], or nothing if there were no tuples
        List<String[]> interiorBoundaries = new ArrayList<>();
        for (int i = 1; i < partitionBoundaries.size() - 1; i++) {
            interiorBoundaries.add(partitionBoundaries.get(i).toArray());
        }
        return RangeBuckets.of(dimensions, interiorBoundaries.toArray(new String[0][]));
    }

    /**
     * Serialization of the tuples: for each tuple the number of the values, and for each value its length in bytes
     * (-1 for null) and its bytes in UTF-8. Little endian, as the sketches.
     */
    private static final class StringTupleSerDe extends ArrayOfItemsSerDe<StringTuple> {
        private static final int NULL_LENGTH = -1;

        @Override
        public byte[] serializeToByteArray(StringTuple item) {
            return serializeToByteArray(new StringTuple[]{item});
        }

        @Override
        public byte[] serializeToByteArray(StringTuple[] items) {
            byte[][][] encoded = new byte[items.length][][];
            int size = 0;
            for (int i = 0; i < items.length; i++) {
                encoded[i] = new byte[items[i].size()][];
                size += Integer.BYTES;
                for (int j = 0; j < encoded[i].length; j++) {
                    String value = items[i].get(j);
                    encoded[i][j] = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
                    size += Integer.BYTES + (value == null ? 0 : encoded[i][j].length);
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            for (byte[][] tuple : encoded) {
                buffer.putInt(tuple.length);
                for (byte[] value : tuple) {
                    buffer.putInt(value == null ? NULL_LENGTH : value.length);
                    if (value != null) {
                        buffer.put(value);
                    }
                }
            }
            return buffer.array();
        }

        @Override
        public StringTuple[] deserializeFromMemory(Memory memory, int numItems) {
            return deserializeFromMemory(memory, 0, numItems);
        }

        @Override
        public StringTuple[] deserializeFromMemory(Memory memory, long offset, int numItems) {
            StringTuple[] items = new StringTuple[numItems];
            long position = offset;
            for (int i = 0; i < numItems; i++) {
                String[] values = new String[memory.getInt(position)];
                position += Integer.BYTES;
                for (int j = 0; j < values.length; j++) {
                    int length = memory.getInt(position);
                    position += Integer.BYTES;
                    if (length != NULL_LENGTH) {
                        byte[] bytes = new byte[length];
                        memory.getByteArray(position, bytes, 0, length);
                        position += length;
                        values[j] = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
                items[i] = new StringTuple(values);
            }
            return items;
        }

        @Override
        public int sizeOf(StringTuple item) {
            int size = Integer.BYTES;
            for (int i = 0; i < item.size(); i++) {
                String value = item.get(i);
                size += Integer.BYTES + (value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length);
            }
            return size;
        }

        @Override
        public int sizeOf(Memory memory, long offset, int numItems) {
            long position = offset;
            for (int i = 0; i < numItems; i++) {
                int numValues = memory.getInt(position);
                position += Integer.BYTES;
                for (int j = 0; j < numValues; j++) {
                    int length = memory.getInt(position);
                    position += Integer.BYTES + Math.max(length, 0);
                }
            }
            return (int) (position - offset);
        }

        @Override
        public String toString(StringTuple item) {
            return String.valueOf(item);
        }

        @Override
        public Class<?> getClassOfT() {
            return StringTuple.class;
        }
    }
}
