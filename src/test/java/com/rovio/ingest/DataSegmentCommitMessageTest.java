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

import org.apache.druid.data.input.StringTuple;
import org.apache.druid.timeline.partition.DimensionRangeShardSpec;
import org.apache.druid.timeline.partition.HashBasedNumberedShardSpec;
import org.apache.druid.timeline.partition.HashPartitionFunction;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSegmentCommitMessageTest {

    /**
     * The shard specs of the segments that are read from the metadata table when appending to an interval that was
     * written by something else than rovio-ingest (e.g. Druid compaction) must be readable with the MAPPER.
     */
    @Test
    void shardSpecsOfDruidSegmentsRoundTrip() throws IOException {
        List<String> dimensions = Arrays.asList("country", "client");
        List<ShardSpec> shardSpecs = Arrays.asList(
                new LinearShardSpec(3),
                new NumberedShardSpec(1000, 3),
                new DimensionRangeShardSpec(dimensions, null, StringTuple.create("FI", "12"), 0, 3),
                new DimensionRangeShardSpec(dimensions, StringTuple.create("FI", "12"), StringTuple.create("US", null), 1, 3),
                new DimensionRangeShardSpec(dimensions, StringTuple.create("US", null), null, 2, 3),
                new SingleDimensionShardSpec("country", "FI", "US", 1, 3),
                new HashBasedNumberedShardSpec(1, 3, 1, 3, dimensions, HashPartitionFunction.MURMUR3_32_ABS, MAPPER));

        for (ShardSpec shardSpec : shardSpecs) {
            String json = MAPPER.writeValueAsString(shardSpec);
            assertEquals(shardSpec, MAPPER.readValue(json, ShardSpec.class), json);
        }
    }

    @Test
    void numberedAndRangeShardSpecsAreWrittenAsInDruid() throws IOException {
        assertEquals(MAPPER.readTree("{\"type\":\"numbered\",\"partitionNum\":1000,\"partitions\":3}"),
                MAPPER.readTree(MAPPER.writeValueAsString(new NumberedShardSpec(1000, 3))));
        assertEquals(MAPPER.readTree("{\"type\":\"range\",\"dimensions\":[\"country\",\"client\"],\"start\":null,"
                        + "\"end\":[\"FI\",\"12\"],\"partitionNum\":0,\"numCorePartitions\":3}"),
                MAPPER.readTree(MAPPER.writeValueAsString(new DimensionRangeShardSpec(
                        Arrays.asList("country", "client"), null, StringTuple.create("FI", "12"), 0, 3))));
    }
}
