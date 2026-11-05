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

import org.apache.datasketches.quantiles.ItemsSketch;
import org.apache.datasketches.quantilescommon.QuantileSearchCriteria;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.timeline.partition.PartitionBoundaries;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringTupleSketchTest {
    private static final List<String> DIMENSIONS = Arrays.asList("country", "client");

    @Test
    void serializationKeepsTheTuples() {
        List<StringTuple> tuples = Arrays.asList(
                StringTuple.create("x", null),
                StringTuple.create(null, "y"),
                StringTuple.create("", "éè"),
                StringTuple.create(new String(Character.toChars(0x1F600)), String.valueOf((char) 0xE000)),
                StringTuple.create(null, null),
                StringTuple.create(String.join("", Collections.nCopies(10_000, "z")), "q"));
        StringTupleSketch sketch = StringTupleSketch.create();
        tuples.forEach(sketch::update);

        StringTupleSketch copy = StringTupleSketch.fromByteArray(sketch.toByteArray());

        assertEquals(6, copy.getN());
        // With so few tuples the sketch has all of them: every one of them is a boundary
        RangeBuckets original = sketch.toRangeBuckets(DIMENSIONS, 1);
        RangeBuckets copied = copy.toRangeBuckets(DIMENSIONS, 1);
        assertEquals(original.getBoundariesJson(), copied.getBoundariesJson());
        Set<StringTuple> expected = new HashSet<>(tuples);
        for (int bucket = 0; bucket < copied.getNumBuckets(); bucket++) {
            StringTuple end = copied.getEnd(bucket);
            assertTrue(end == null || expected.contains(end), "unexpected boundary " + end);
        }
        assertTrue(copied.getNumBuckets() > 1);
    }

    @Test
    void numberOfBucketsIsTheNumberOfRowsPerTargetRoundedAtLeastOne() {
        StringTupleSketch sketch = sketchOf(tuples(1000));

        assertEquals(4, sketch.toRangeBuckets(DIMENSIONS, 250).getNumBuckets());
        assertEquals(3, sketch.toRangeBuckets(DIMENSIONS, 300).getNumBuckets());
        // 2.5 is rounded up, as Math.round does in Druid
        assertEquals(3, sketch.toRangeBuckets(DIMENSIONS, 400).getNumBuckets());
        assertEquals(1, sketch.toRangeBuckets(DIMENSIONS, 1000).getNumBuckets());
        assertEquals("[]", sketch.toRangeBuckets(DIMENSIONS, 1000).getBoundariesJson());
        // less than one bucket is rounded to one
        assertEquals(1, sketch.toRangeBuckets(DIMENSIONS, 5000).getNumBuckets());
        assertThrows(IllegalArgumentException.class, () -> sketch.toRangeBuckets(DIMENSIONS, 0));
    }

    @Test
    void bucketsHaveTheSameNumberOfRowsWhenAllTuplesAreDifferent() {
        List<StringTuple> tuples = tuples(1000);
        StringTupleSketch sketch = sketchOf(tuples);

        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 250);

        int[] counts = new int[buckets.getNumBuckets()];
        tuples.forEach(t -> counts[buckets.bucketOf(t)]++);
        for (int count : counts) {
            // a small sketch (less than 2k tuples) is exact
            assertEquals(250, count, Arrays.toString(counts));
        }
    }

    @Test
    void boundariesAreTheOnesOfDruidStringSketch() {
        List<StringTuple> tuples = tuples(1000);
        StringTupleSketch sketch = sketchOf(tuples);

        // The same as StringSketch#getEvenPartitionsByTargetSize of Druid does
        ItemsSketch<StringTuple> druidSketch = ItemsSketch.getInstance(StringTuple.class, 1 << 12, Comparator.naturalOrder());
        tuples.forEach(druidSketch::update);
        int numBuckets = Math.max(1, (int) Math.round(1000d / 300));
        PartitionBoundaries druid = new PartitionBoundaries(
                druidSketch.getPartitionBoundaries(numBuckets, QuantileSearchCriteria.EXCLUSIVE).boundaries);

        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 300);

        // [null, boundaries..., null]
        assertEquals(druid.size() - 1, buckets.getNumBuckets());
        assertEquals(3, buckets.getNumBuckets());
        for (int bucket = 0; bucket < buckets.getNumBuckets(); bucket++) {
            assertEquals(druid.get(bucket), buckets.getStart(bucket));
            assertEquals(druid.get(bucket + 1), buckets.getEnd(bucket));
        }
    }

    @Test
    void commonTuplesAreNotSplitToSeveralBuckets() {
        // 900 times the same tuple, and 100 different ones that are bigger
        List<StringTuple> tuples = new ArrayList<>(Collections.nCopies(900, StringTuple.create("A", "1")));
        for (int i = 0; i < 100; i++) {
            tuples.add(StringTuple.create("B", String.format("%03d", i)));
        }
        StringTupleSketch sketch = sketchOf(tuples);

        // 10 buckets would be expected, but equal boundaries are merged
        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 100);

        assertTrue(buckets.getNumBuckets() < 10, "buckets " + buckets.getNumBuckets());
        // all of the tuples that are equal are in the same bucket
        int hotBucket = buckets.bucketOf(StringTuple.create("A", "1"));
        assertTrue(buckets.contains(hotBucket, StringTuple.create("A", "1")));
    }

    @Test
    void identicalTuplesAreASingleBucket() {
        StringTupleSketch sketch = sketchOf(Collections.nCopies(500, StringTuple.create("A", null)));

        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 100);

        assertEquals(1, buckets.getNumBuckets());
        assertEquals("[]", buckets.getBoundariesJson());
    }

    @Test
    void emptySketchIsASingleBucket() {
        StringTupleSketch sketch = StringTupleSketch.create();

        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 100);

        assertEquals(0, sketch.getN());
        assertEquals(1, buckets.getNumBuckets());
        assertEquals("[]", buckets.getBoundariesJson());
    }

    @Test
    void mergedSketchesAreTheSameAsASketchOfAllTuples() {
        List<StringTuple> tuples = tuples(1000);
        List<StringTupleSketch> parts = Arrays.asList(StringTupleSketch.create(), StringTupleSketch.create(),
                StringTupleSketch.create(), StringTupleSketch.create());
        for (int i = 0; i < tuples.size(); i++) {
            parts.get(i % 3).update(tuples.get(i)); // the fourth is empty
        }

        byte[] merged = StringTupleSketch.merge(
                StringTupleSketch.merge(parts.get(0).toByteArray(), parts.get(1).toByteArray()),
                StringTupleSketch.merge(parts.get(2).toByteArray(), parts.get(3).toByteArray()));

        StringTupleSketch mergedSketch = StringTupleSketch.fromByteArray(merged);
        assertEquals(1000, mergedSketch.getN());
        // exact, as there are not many tuples
        assertEquals(sketchOf(tuples).toRangeBuckets(DIMENSIONS, 250).getBoundariesJson(),
                mergedSketch.toRangeBuckets(DIMENSIONS, 250).getBoundariesJson());
    }

    @Test
    void mergedSketchesOfManyTuplesAreWithinTheErrorOfTheSketch() {
        // More tuples than a sketch can hold exactly, so that the buckets are only approximately even
        int n = 100_000;
        List<StringTuple> tuples = tuples(n);
        Collections.shuffle(tuples, new Random(1));
        List<StringTupleSketch> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parts.add(StringTupleSketch.create());
        }
        for (int i = 0; i < n; i++) {
            parts.get(i % 3).update(tuples.get(i));
        }
        byte[] merged = StringTupleSketch.merge(
                StringTupleSketch.merge(parts.get(0).toByteArray(), parts.get(1).toByteArray()),
                parts.get(2).toByteArray());

        RangeBuckets buckets = StringTupleSketch.fromByteArray(merged).toRangeBuckets(DIMENSIONS, 10_000);

        assertEquals(10, buckets.getNumBuckets());
        int[] counts = new int[buckets.getNumBuckets()];
        tuples.forEach(t -> counts[buckets.bucketOf(t)]++);
        for (int count : counts) {
            // normalized rank error of the sketch is less than 1%
            assertTrue(Math.abs(count - 10_000) < n / 100, Arrays.toString(counts));
        }
    }

    @Test
    void thousandsOfBucketsAreSupported() {
        // More tuples than a sketch holds exactly, and many buckets
        StringTupleSketch sketch = sketchOf(tuples(300_000));

        RangeBuckets buckets = sketch.toRangeBuckets(DIMENSIONS, 300);

        assertEquals(1000, buckets.getNumBuckets());
    }

    /**
     * The writer fails if a bucket has no rows, so buckets of the sketch must have rows when the same rows are written.
     * Boundaries are tuples of the sketch, so the bucket that starts at a boundary has at least that tuple, and the
     * first bucket has the smallest tuple. This holds also with many equal tuples, nulls and merged sketches.
     */
    @Test
    void noBucketIsEmptyForTheRowsOfTheSketch() {
        String[] alphabet = {null, "", "0", "1", "12", "3", "a", "FI", "US"};
        Random random = new Random(7);

        for (int trial = 0; trial < 150; trial++) {
            // from a few rows to more than a sketch holds exactly, from few different tuples to all different
            int n = trial % 25 == 0 ? 120_000 : 1 + random.nextInt(trial % 3 == 0 ? 20_000 : 2_000);
            int distinctValues = 1 + random.nextInt(alphabet.length);
            boolean allDifferent = trial % 4 == 0;
            List<StringTuple> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String first = alphabet[random.nextInt(distinctValues)];
                String second = allDifferent ? String.format("%08d", random.nextInt(1 << 24)) : alphabet[random.nextInt(distinctValues)];
                rows.add(StringTuple.create(first, second));
            }

            // like the sketches of Spark partitions that are merged
            List<StringTupleSketch> parts = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(7); i++) {
                parts.add(StringTupleSketch.create());
            }
            rows.forEach(row -> parts.get(random.nextInt(parts.size())).update(row));
            byte[] merged = StringTupleSketch.create().toByteArray();
            for (StringTupleSketch part : parts) {
                merged = StringTupleSketch.merge(merged, part.toByteArray());
            }

            int target = 1 + random.nextInt(Math.max(1, n / 2));
            RangeBuckets buckets = StringTupleSketch.fromByteArray(merged).toRangeBuckets(DIMENSIONS, target);

            int[] counts = new int[buckets.getNumBuckets()];
            rows.forEach(row -> counts[buckets.bucketOf(row)]++);
            for (int bucket = 0; bucket < counts.length; bucket++) {
                assertTrue(counts[bucket] > 0, "trial " + trial + " (n=" + n + ", target=" + target + ") has no rows in bucket "
                        + bucket + ": " + Arrays.toString(counts));
            }
        }
    }

    /**
     * Tuples that are all different, e.g. (g3, 0003).
     */
    private static List<StringTuple> tuples(int n) {
        List<StringTuple> tuples = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tuples.add(StringTuple.create("g" + (i % 10), String.format("%06d", i)));
        }
        return tuples;
    }

    private static StringTupleSketch sketchOf(List<StringTuple> tuples) {
        StringTupleSketch sketch = StringTupleSketch.create();
        tuples.forEach(sketch::update);
        return sketch;
    }
}
