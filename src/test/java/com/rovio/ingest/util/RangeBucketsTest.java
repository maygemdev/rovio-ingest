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

import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.ShardSpecLookup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RangeBucketsTest {
    private static final List<String> DIMENSIONS = Arrays.asList("country", "client");
    // [null, (FI,12)), [(FI,12), (US,3)), [(US,3), null)
    private static final String BOUNDARIES = "[[\"FI\",\"12\"],[\"US\",\"3\"]]";

    @BeforeAll
    static void initNullHandling() {
        NullHandling.initializeForTests();
    }

    @Test
    void bucketsAreDefinedByTheInteriorBoundaries() {
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, BOUNDARIES);

        assertEquals(3, buckets.getNumBuckets());
        assertNull(buckets.getStart(0));
        assertEquals(StringTuple.create("FI", "12"), buckets.getEnd(0));
        assertEquals(StringTuple.create("FI", "12"), buckets.getStart(1));
        assertEquals(StringTuple.create("US", "3"), buckets.getEnd(1));
        assertEquals(StringTuple.create("US", "3"), buckets.getStart(2));
        assertNull(buckets.getEnd(2));
        assertThrows(IllegalArgumentException.class, () -> buckets.getStart(3));
        assertThrows(IllegalArgumentException.class, () -> buckets.getEnd(-1));
    }

    @Test
    void noBoundariesIsASingleUnboundedBucket() {
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, "[]");

        assertEquals(1, buckets.getNumBuckets());
        assertNull(buckets.getStart(0));
        assertNull(buckets.getEnd(0));
        assertEquals(0, buckets.bucketOf(StringTuple.create("anything", null)));
        assertTrue(buckets.contains(0, StringTuple.create(null, null)));
    }

    @Test
    void tupleOnTheBoundaryIsInTheBucketThatStartsThere() {
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, BOUNDARIES);

        assertEquals(0, buckets.bucketOf(StringTuple.create("FI", "11")));
        assertEquals(1, buckets.bucketOf(StringTuple.create("FI", "12")));
        assertEquals(1, buckets.bucketOf(StringTuple.create("US", "29")));
        assertEquals(2, buckets.bucketOf(StringTuple.create("US", "3")));
        assertFalse(buckets.contains(0, StringTuple.create("FI", "12")));
        assertTrue(buckets.contains(1, StringTuple.create("FI", "12")));
        assertFalse(buckets.contains(1, StringTuple.create("US", "3")));
    }

    @Test
    void orderIsTheOneOfDruid() {
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, BOUNDARIES);

        // Strings are compared as strings: "12" < "3"
        assertEquals(1, buckets.bucketOf(StringTuple.create("FI", "3")));
        // null is before any string, also in the middle of a tuple
        assertEquals(0, buckets.bucketOf(StringTuple.create("FI", null)));
        assertEquals(0, buckets.bucketOf(StringTuple.create(null, "zzz")));
        assertEquals(0, buckets.bucketOf(StringTuple.create("FI", "")));

        // UTF-16 code units: a supplementary character (surrogates 0xD800..0xDFFF) is before U+E000, but its UTF-8
        // bytes are after those of U+E000, which is how Spark would order them.
        String supplementary = new String(Character.toChars(0x1F600));
        String privateUse = String.valueOf((char) 0xE000);
        RangeBuckets utf16Buckets = RangeBuckets.of(Collections.singletonList("d"), new String[][]{{privateUse}});
        assertEquals(0, utf16Buckets.bucketOf(StringTuple.create(supplementary)));
        assertEquals(1, utf16Buckets.bucketOf(StringTuple.create(privateUse)));
    }

    @Test
    void bucketsAgreeWithDruidShardSpecLookup() {
        List<String> dimensions = Arrays.asList("d1", "d2");
        List<String> alphabet = Arrays.asList(null, "", "1", "12", "3", "a", "FI", "US", new String(Character.toChars(0x1F600)), String.valueOf((char) 0xE000));
        Random random = new Random(42);

        for (int round = 0; round < 200; round++) {
            // random distinct boundaries in increasing order
            TreeSet<StringTuple> sorted = new TreeSet<>();
            int numBoundaries = random.nextInt(6);
            for (int i = 0; i < numBoundaries; i++) {
                sorted.add(randomTuple(random, alphabet));
            }
            String[][] boundaries = sorted.stream().map(StringTuple::toArray).toArray(String[][]::new);
            RangeBuckets buckets = RangeBuckets.of(dimensions, boundaries);

            // Druid's own shard specs, of the buckets as defined by Druid
            int numBuckets = boundaries.length + 1;
            List<ShardSpec> shardSpecs = new ArrayList<>();
            for (int i = 0; i < numBuckets; i++) {
                StringTuple start = i == 0 ? null : new StringTuple(boundaries[i - 1]);
                StringTuple end = i == numBuckets - 1 ? null : new StringTuple(boundaries[i]);
                shardSpecs.add(new DimensionRangeShardSpec(dimensions, start, end, i, numBuckets));
            }
            ShardSpecLookup lookup = shardSpecs.get(0).getLookup(shardSpecs);

            for (int i = 0; i < 100; i++) {
                String[] values = randomTuple(random, alphabet).toArray();
                Map<String, Object> event = new HashMap<>();
                event.put("d1", values[0]);
                event.put("d2", values[1]);
                InputRow row = new MapBasedInputRow(0L, dimensions, event);

                // Druid doesn't convert "" to null, it's up to the ingestion
                StringTuple tuple = RangeBuckets.tupleOf(dimensions, row, false);
                int expected = lookup.getShardSpec(0L, row).getPartitionNum();

                assertEquals(expected, buckets.bucketOf(tuple), "tuple " + tuple + " with boundaries " + Arrays.deepToString(boundaries));
                assertTrue(buckets.contains(expected, tuple));
            }
        }
    }

    @Test
    void tupleOfARowIsBuiltLikeDruidDoes() {
        List<String> dimensions = Arrays.asList("single", "multi", "missing", "empty", "number");
        Map<String, Object> event = new HashMap<>();
        event.put("single", "a");
        event.put("multi", Arrays.asList("x", "y"));
        event.put("empty", "");
        event.put("number", 12L);
        InputRow row = new MapBasedInputRow(0L, dimensions, event);

        // a single value is the value, no value or many values is null
        assertEquals(StringTuple.create("a", null, null, "", "12"), RangeBuckets.tupleOf(dimensions, row, false));
        // an empty string is null when default values are used for null
        assertEquals(StringTuple.create("a", null, null, null, "12"), RangeBuckets.tupleOf(dimensions, row, true));
    }

    @Test
    void invalidBoundariesAreRejected() {
        assertInvalid("[[\"FI\"]]", "Boundary 0 ([FI]) should have 2 values");
        assertInvalid("[[\"FI\",\"1\",\"x\"]]", "should have 2 values");
        assertInvalid("[null]", "Invalid boundary 0 (null), expected a json array of strings or nulls");
        assertInvalid("[\"FI\"]", "Invalid boundary 0 (\"FI\"), expected a json array of strings or nulls");
        assertInvalid("[[\"US\",\"3\"],[\"FI\",\"12\"]]", "strictly increasing");
        assertInvalid("[[\"FI\",\"12\"],[\"FI\",\"12\"]]", "strictly increasing");
        assertInvalid("[[\"FI\",null],[\"FI\",null]]", "strictly increasing");
        assertInvalid("not json", "Invalid boundaries");
        assertInvalid("null", "Invalid boundaries");
        assertInvalid("{\"a\":1}", "Invalid boundaries");
        assertThrows(IllegalArgumentException.class, () -> RangeBuckets.of(Collections.emptyList(), "[]"));

        // null is a valid element and is smaller than any string
        RangeBuckets.of(DIMENSIONS, "[[\"FI\",null],[\"FI\",\"\"]]");
    }

    @Test
    void valuesOtherThanStringsAndNullsAreRejected() {
        // Jackson would convert these to strings, e.g. [[12]] to [["12"]]
        assertInvalid("[[\"FI\",12]]", "Invalid boundary 0 ([\"FI\",12]), value 1 (12) is neither a string nor null");
        assertInvalid("[[\"FI\",\"1\"],[true,\"2\"]]", "Invalid boundary 1 ([true,\"2\"]), value 0 (true) is neither a string nor null");
        assertInvalid("[[\"FI\",1.5]]", "value 1 (1.5) is neither a string nor null");
        assertInvalid("[[\"FI\",[\"x\"]]]", "value 1 ([\"x\"]) is neither a string nor null");
        assertInvalid("[[\"FI\",{\"a\":1}]]", "value 1 ({\"a\":1}) is neither a string nor null");
    }

    @Test
    void boundariesAreCopiedSoThatChangesOfTheGivenArrayHaveNoEffect() {
        String[][] given = {{"FI", "12"}, {"US", "3"}};
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, given);

        given[0][0] = "ZZ";
        given[1] = new String[]{"AA", "1"};

        assertEquals(StringTuple.create("FI", "12"), buckets.getEnd(0));
        assertEquals(StringTuple.create("US", "3"), buckets.getEnd(1));
        assertEquals(BOUNDARIES, buckets.getBoundariesJson());
        assertEquals(1, buckets.bucketOf(StringTuple.create("FI", "12")));
    }

    @Test
    void serializationKeepsTheBuckets() throws IOException, ClassNotFoundException {
        RangeBuckets buckets = RangeBuckets.of(DIMENSIONS, "[[\"FI\",null],[\"US\",\"3\"]]");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(buckets);
        }
        RangeBuckets copy;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = (RangeBuckets) in.readObject();
        }

        assertEquals(DIMENSIONS, copy.getDimensions());
        assertEquals(3, copy.getNumBuckets());
        assertEquals(StringTuple.create("FI", null), copy.getEnd(0));
        assertEquals(1, copy.bucketOf(StringTuple.create("FI", "a")));
        assertEquals(2, copy.bucketOf(StringTuple.create("US", "3")));
    }

    private static StringTuple randomTuple(Random random, List<String> alphabet) {
        return StringTuple.create(alphabet.get(random.nextInt(alphabet.size())), alphabet.get(random.nextInt(alphabet.size())));
    }

    private static void assertInvalid(String boundariesJson, String expectedMessage) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RangeBuckets.of(DIMENSIONS, boundariesJson));
        assertThat(thrown.getMessage(), containsString(expectedMessage));
    }
}
