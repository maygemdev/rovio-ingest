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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.rovio.ingest.WriterContext;
import com.rovio.ingest.model.DbType;
import org.apache.commons.lang3.StringUtils;
import org.apache.druid.indexer.SQLMetadataStorageUpdaterJobHandler;
import org.apache.druid.metadata.MetadataStorageConnectorConfig;
import org.apache.druid.metadata.MetadataStorageTablesConfig;
import org.apache.druid.metadata.SQLMetadataConnector;
import org.apache.druid.metadata.storage.mysql.MySQLConnector;
import org.apache.druid.metadata.storage.mysql.MySQLConnectorDriverConfig;
import org.apache.druid.metadata.storage.mysql.MySQLConnectorSslConfig;
import org.apache.druid.metadata.storage.postgresql.PostgreSQLConnector;
import org.apache.druid.metadata.storage.postgresql.PostgreSQLConnectorConfig;
import org.apache.druid.metadata.storage.postgresql.PostgreSQLTablesConfig;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.ShardSpec;
import org.joda.time.Interval;
import org.skife.jdbi.v2.PreparedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static java.lang.String.format;

public class MetadataUpdater {
    private static final Logger LOG = LoggerFactory.getLogger(MetadataUpdater.class);

    private static final String SELECT_UNUSED_OLD_SEGMENTS =
            "SELECT id FROM %1$s WHERE dataSource = :dataSource AND version < :version AND used = true AND id NOT IN (:ids)";
    // Used segments of the max used version of an interval.
    private static final String FROM_USED_SEGMENTS_BY_INTERVAL =
            "FROM %1$s WHERE dataSource = :dataSource AND start = :start AND %2$send%2$s = :end AND used = true " +
                    "AND version = (SELECT MAX(version) FROM %1$s WHERE dataSource = :dataSource AND start = :start AND %2$send%2$s = :end AND used = true)";
    private static final String SELECT_USED_SEGMENT_IDS_BY_INTERVAL =
            "SELECT id " + FROM_USED_SEGMENTS_BY_INTERVAL;
    private static final String SELECT_USED_SEGMENT_SHARD_SPECS_BY_INTERVAL =
            "SELECT id, version, payload " + FROM_USED_SEGMENTS_BY_INTERVAL;
    private static final String MARK_SEGMENT_AS_UNUSED_BY_ID =
            "UPDATE %1$s SET used=false WHERE id = :id";

    private final String dataSource;
    private final String version;
    private final boolean initDataSource;
    private final SQLMetadataConnector sqlConnector;
    private final String segmentsTable;
    private final SQLMetadataStorageUpdaterJobHandler metadataStorageUpdaterJobHandler;

    public MetadataUpdater(WriterContext param) {
        Preconditions.checkNotNull(param);
        this.dataSource = param.getDataSource();
        this.version = param.getVersion();
        this.initDataSource = param.isInitDataSource();
        MetadataStorageConnectorConfig metadataStorageConnectorConfig = new MetadataStorageConnectorConfig() {
            @Override
            public String getConnectURI() {
                return param.getMetadataDbUri();
            }

            @Override
            public String getUser() {
                return param.getMetadataDbUser();
            }

            @Override
            public String getPassword() {
                return param.getMetadataDbPass();
            }
        };

        MetadataStorageTablesConfig metadataStorageTablesConfig = MetadataStorageTablesConfig.fromBase(param.getMetadataDbTableBase());
        this.segmentsTable = metadataStorageTablesConfig.getSegmentsTable();

        final DbType dbType = DbType.from(param.getMetadataDbType());
        this.sqlConnector = this.makeSqlConnector(dbType, metadataStorageConnectorConfig, metadataStorageTablesConfig);
        this.metadataStorageUpdaterJobHandler = new SQLMetadataStorageUpdaterJobHandler(sqlConnector);

        testDbConnection();
    }

    private SQLMetadataConnector makeSqlConnector(DbType dbType,
                                                  MetadataStorageConnectorConfig metadataStorageConnectorConfig,
                                                  MetadataStorageTablesConfig metadataStorageTablesConfig) {
        switch (dbType) {
            case Mysql:
                return new MySQLConnector(() -> metadataStorageConnectorConfig,
                        () -> metadataStorageTablesConfig,
                        new MySQLConnectorSslConfig(),
                        new MySQLConnectorDriverConfig());
            case Postgres:
                return new PostgreSQLConnector(() -> metadataStorageConnectorConfig,
                        () -> metadataStorageTablesConfig,
                        new PostgreSQLConnectorConfig(),
                        new PostgreSQLTablesConfig());
            default:
                throw new IllegalArgumentException("Failed to instantiate the SQL connector");
        }
    }

    private void testDbConnection() {
        boolean tableExists = this.sqlConnector.retryWithHandle(h -> this.sqlConnector.tableExists(h, this.segmentsTable));
        if (!tableExists) {
            throw new IllegalStateException(format("Required druid segment table \"%s\" does not exists", this.segmentsTable));
        }
    }

    /**
     * Updates segments in Metadata segment table using {@link org.apache.druid.indexer.SQLMetadataStorageUpdaterJobHandler#publishSegments},
     * with additional handling for (re-)init.
     */
    public void publishSegments(List<DataSegment> dataSegments) {
        if (dataSegments.isEmpty()) {
            LOG.warn("No segments created, skipping metadata update.");
            return;
        }

        metadataStorageUpdaterJobHandler.publishSegments(segmentsTable, dataSegments, MAPPER);
        LOG.info("All segments published");

        if (initDataSource) {
            // Mark old segments as unused for the datasource.
            // This done in 2 steps:
            // 1. List all used old segments for the datasource
            // 2. Update these segments as unused.
            // This is done this way to avoid accidentally locking the entire segment table.

            sqlConnector.getDBI().withHandle(handle -> {
                List<String> oldSegmentIds = handle
                        .createQuery(String.format(SELECT_UNUSED_OLD_SEGMENTS, segmentsTable))
                        .bind("dataSource", dataSource)
                        .bind("version", version)
                        .bind("ids",
                                dataSegments
                                        .stream()
                                        .map(d -> StringUtils.wrap(d.getId().toString(), "'"))
                                        .collect(Collectors.joining(",")))
                        .list()
                        .stream()
                        .map(m -> m.get("id").toString())
                        .collect(Collectors.toList());

                if (!oldSegmentIds.isEmpty()) {
                    PreparedBatch batch = handle.prepareBatch(String.format(MARK_SEGMENT_AS_UNUSED_BY_ID, segmentsTable));
                    for (String id : oldSegmentIds) {
                        batch.add(new ImmutableMap.Builder<String, Object>()
                                .put("id", id)
                                .build());
                    }

                    LOG.info(String.format("Marking %s old segments as ununsed", oldSegmentIds.size()));
                    batch.execute();
                }

                return null;
            });

        }
    }

    /**
     * @deprecated Returns only the ids of the segments. Use {@link #findUsedSegmentShardSpecs} instead, which also
     * tells the shard specs of the segments.
     */
    @Deprecated(since = "1.0.9", forRemoval = true)
    public List<String> findUsedSegments(String dataSource, Interval interval) {
        return sqlConnector.getDBI().withHandle(handle -> {
            return handle.createQuery(String.format(SELECT_USED_SEGMENT_IDS_BY_INTERVAL, segmentsTable, sqlConnector.getQuoteString()))
                    .bind("dataSource", dataSource)
                    .bind("start", interval.getStart().toString())
                    .bind("end", interval.getEnd().toString())
                    .list()
                    .stream()
                    .map(m -> m.get("id").toString())
                    .collect(Collectors.toList());
        });
    }

    /**
     * Finds the used segments of the max used version of the given interval, with their version and shard spec.
     * <p>
     * Only the {@code shardSpec} is read from the segment payload (not the whole {@link DataSegment}), so that
     * unrelated parts of the payload, such as {@code lastCompactionState} of compacted segments, can't make this fail.
     */
    public List<VersionedShardSpec> findUsedSegmentShardSpecs(String dataSource, Interval interval) {
        List<Map<String, Object>> rows = sqlConnector.getDBI().withHandle(handle -> {
            return handle.createQuery(String.format(SELECT_USED_SEGMENT_SHARD_SPECS_BY_INTERVAL, segmentsTable, sqlConnector.getQuoteString()))
                    .bind("dataSource", dataSource)
                    .bind("start", interval.getStart().toString())
                    .bind("end", interval.getEnd().toString())
                    .list();
        });
        // Parse outside of the handle callback, so that failures are not wrapped by the DBI.
        return rows.stream()
                .map(m -> new VersionedShardSpec(m.get("version").toString(), readShardSpec(m.get("id").toString(), m.get("payload"))))
                .collect(Collectors.toList());
    }

    private static ShardSpec readShardSpec(String segmentId, Object payload) {
        try {
            // The payload is a BLOB in MySQL and BYTEA in PostgreSQL, both are returned as byte[].
            JsonNode shardSpec = MAPPER.readTree((byte[]) payload).get("shardSpec");
            if (shardSpec == null) {
                throw new IllegalStateException(format("Segment %s has no shardSpec in its payload", segmentId));
            }
            return MAPPER.treeToValue(shardSpec, ShardSpec.class);
        } catch (IOException e) {
            throw new IllegalStateException(format("Failed to read the shardSpec of segment %s", segmentId), e);
        }
    }

    /**
     * Version and shard spec of a used segment.
     */
    public record VersionedShardSpec(String version, ShardSpec shardSpec) {
    }
}
