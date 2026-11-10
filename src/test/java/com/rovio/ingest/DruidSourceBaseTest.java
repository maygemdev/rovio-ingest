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
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.holdenkarau.spark.testing.SharedJavaSparkContext;
import com.rovio.ingest.WriterContext.ConfKeys;
import com.rovio.ingest.util.NormalizeTimeColumnUDF;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.MapBasedInputRow;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.metadata.MetadataStorageTablesConfig;
import org.apache.druid.segment.IndexIO;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexStorageAdapter;
import org.apache.druid.segment.column.ColumnConfig;
import org.apache.druid.segment.realtime.firehose.IngestSegmentFirehose;
import org.apache.druid.segment.realtime.firehose.WindowedStorageAdapter;
import org.apache.druid.segment.transform.TransformSpec;
import org.apache.druid.timeline.SegmentId;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.joda.time.Interval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.rovio.ingest.DataSegmentCommitMessage.MAPPER;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.apache.spark.sql.functions.column;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Testcontainers
public class DruidSourceBaseTest extends SharedJavaSparkContext {

    static {
        // Some Docker daemons enforce a minimum API version (>= 1.40) and reject the
        // docker-java client's default of 1.32. Negotiate a widely-supported value.
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version", "1.41");
        }
    }

    public static final String segmentsTable = MetadataStorageTablesConfig.fromBase("druid").getSegmentsTable();

    public static final String DATA_SOURCE = "temp";
    public static final String DB_NAME = "temp";
    public static final long VERSION_TIME_MILLIS = 1569961771384L;

    public static String dbUser = "user";
    public static String dbPass = "pass";

    protected SparkSession spark;
    protected Map<String, String> options;

    @TempDir
    public File testFolder;

    @Container
    public static MySQLContainer<?> MYSQL = getMySQLContainer();

    public static MySQLContainer<?> getMySQLContainer() {
        // MySQL 8 requires mysql-connector-java 8.x so we test against that.
        // The mysql-connector-java 8.x also works with MySQL 5
        if (Objects.equals(System.getProperty("os.arch"), "aarch64")) {
            DockerImageName myImage = DockerImageName.parse("arm64v8/mysql:8.0.36").asCompatibleSubstituteFor("mysql");
            return new MySQLContainer<>(myImage)
                    .withUsername(dbUser)
                    .withPassword(dbPass)
                    .withDatabaseName(DB_NAME);
        } else {
            return new MySQLContainer<>("mysql:8.0.36")
                    .withUsername(dbUser)
                    .withPassword(dbPass)
                    .withDatabaseName(DB_NAME);
        }
    }

    public static PostgreSQLContainer<?> getPostgreSQLContainer() {
        // 15.1 is the latest postgres image at the time of writing this.
        // The jdbc module versioning (42.2.23 in pom) doesn't seem to follow postgres image versioning.
        return new PostgreSQLContainer<>("postgres:15.1")
                .withUsername(dbUser)
                .withPassword(dbPass)
                .withDatabaseName(DB_NAME);
    }

    @BeforeAll
    public static void beforeClass() throws Exception {
        prepareDatabase(MYSQL);
    }

    public static void prepareDatabase(JdbcDatabaseContainer<?> jdbc) throws SQLException {
        if (jdbc instanceof MySQLContainer) {
            // Druid requires its MySQL database to be created with an UTF8 charset.
            runSql(jdbc, String.format("ALTER DATABASE %s CHARACTER SET utf8mb4", DB_NAME));
            // Could use sqlConnector.createSegmentTable() instead
            runSql(jdbc, String.format("CREATE TABLE %1$s (\n"
                            + "  id VARCHAR(255) NOT NULL,\n"
                            + "  dataSource VARCHAR(255) NOT NULL,\n"
                            + "  created_date VARCHAR(255) NOT NULL,\n"
                            + "  start VARCHAR(255) NOT NULL,\n"
                            + "  `end` VARCHAR(255) NOT NULL,\n"
                            + "  partitioned BOOLEAN NOT NULL,\n"
                            + "  version VARCHAR(255) NOT NULL,\n"
                            + "  used BOOLEAN NOT NULL,\n"
                            + "  payload BLOB NOT NULL,\n"
                            + "  used_status_last_updated VARCHAR(255) NOT NULL,\n"
                            + "  PRIMARY KEY (id)\n"
                            + ")",
                    segmentsTable));
        } else {
            // Could use sqlConnector.createSegmentTable() instead
            runSql(jdbc, String.format("CREATE TABLE %1$s (\n"
                            + "  id VARCHAR(255) NOT NULL,\n"
                            + "  dataSource VARCHAR(255) NOT NULL,\n"
                            + "  created_date VARCHAR(255) NOT NULL,\n"
                            + "  start VARCHAR(255) NOT NULL,\n"
                            + "  \"end\" VARCHAR(255) NOT NULL,\n"
                            + "  partitioned BOOLEAN NOT NULL,\n"
                            + "  version VARCHAR(255) NOT NULL,\n"
                            + "  used BOOLEAN NOT NULL,\n"
                            + "  payload BYTEA NOT NULL,\n"
                            + "  used_status_last_updated VARCHAR(255) NOT NULL,\n"
                            + "  PRIMARY KEY (id)\n"
                            + ")",
                    segmentsTable));
        }
    }

    public static String getConnectionString(JdbcDatabaseContainer<?> jdbc) {
        String connectionString = jdbc.getJdbcUrl();
        if (jdbc instanceof MySQLContainer) {
            if (!connectionString.contains("useSSL=")) {
                String separator = connectionString.contains("?") ? "&" : "?";
                connectionString = connectionString + separator + "useSSL=false";
            }
        }
        return connectionString;
    }

    @BeforeEach
    public void before() {
        DateTimeUtils.setCurrentMillisFixed(VERSION_TIME_MILLIS);

        // Create test dataset.
        spark = SparkSession.builder().sparkContext(sc()).config("spark.sql.session.timeZone", "UTC").config("spark.driver.bindAddress", "127.0.0.1").config("spark.master", "local").getOrCreate();
        // This is for some udf-level unit-testing. JVM API users don't need to register this udf.
        spark.udf().register("normalizeTimeColumn", new NormalizeTimeColumnUDF(), DataTypes.LongType);

        options = getDataSourceOptions(MYSQL);
        options.put(ConfKeys.DEEP_STORAGE_LOCAL_DIRECTORY, testFolder.toString());

        // note: these are ignored when DEEP_STORAGE_TYPE = local
        options.put(ConfKeys.DEEP_STORAGE_S3_BUCKET, "my-bucket");
        options.put(ConfKeys.DEEP_STORAGE_S3_BASE_KEY, "druid/prod/segments");

        NullHandling.initializeForTests();
        EmittingLogger.registerEmitter(Mockito.mock(ServiceEmitter.class));
    }

    public static Map<String, String> getDataSourceOptions(JdbcDatabaseContainer<?> jdbc) {
        Map<String, String> options = new HashMap<>();
        options.put(ConfKeys.DATA_SOURCE, DATA_SOURCE);
        options.put(ConfKeys.METADATA_DB_TYPE, jdbc instanceof MySQLContainer ? "mysql" : "postgres");
        options.put(ConfKeys.METADATA_DB_URI, getConnectionString(jdbc));
        options.put(ConfKeys.METADATA_DB_USERNAME, dbUser);
        options.put(ConfKeys.METADATA_DB_PASSWORD, dbPass);
        options.put(ConfKeys.TIME_COLUMN, "date");
        options.put(ConfKeys.DEEP_STORAGE_TYPE, "local");
        return options;
    }

    @BeforeEach
    public void setUp() throws SQLException {
        setUpDb(MYSQL);
    }

    public static void setUpDb(JdbcDatabaseContainer<?> jdbc) throws SQLException {
        runSql(jdbc, "TRUNCATE TABLE " + segmentsTable);
    }

    @AfterEach
    public void after() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    private static void runSql(JdbcDatabaseContainer<?> jdbc, String sql) throws SQLException {
        try (Connection connection = jdbc.createConnection("");
             Statement statement  = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    static Dataset<Row> loadCsv(SparkSession spark, String fileName) {
        Dataset<Row> dataset = spark
                .read()
                .format("csv")
                .option("header", "true")
                .option("inferSchema", "true")
                .option("timestampFormat", "yyyy-MM-dd")
                .load(DruidSourceBaseTest.class.getResource(fileName).getPath());

        return dataset.withColumn("i_dau", column("dau").cast(DataTypes.IntegerType))
                .withColumn("s_dau", column("dau").cast(DataTypes.ShortType))
                .withColumn("b_dau", column("dau").cast(DataTypes.ByteType))
                .withColumn("f_revenue", column("revenue").cast(DataTypes.FloatType));
    }

    static void verifySegmentPath(Path root, Interval interval, String version, int numShards, boolean isMonth) throws IOException {
        for (DateTime current = interval.getStart(); current.isBefore(interval.getEnd()); current = isMonth ? current.plusMonths(1) : current.plusDays(1)) {
            String relativePath = current + "_" + (isMonth ? current.plusMonths(1) : current.plusDays(1));
            assertEquals(numShards,
                    Files.list(Paths.get(root.toString(), relativePath, version))
                            .filter(p -> Files.isDirectory(p.toAbsolutePath(), NOFOLLOW_LINKS))
                            .count());
        }
    }

    static List<SegmentId> verifySegmentTable(Interval interval, String version, boolean used, int expectedRowCount) {
        String sql = "SELECT id as id from %1s" +
                " where dataSource = :dataSource" +
                " and start < :end" +
                " and \"end\" >= :start" +
                " and version = :version" +
                " and used = :used" +
                " order by id";

        List<SegmentId> segments = DBI
                .open(getConnectionString(MYSQL), dbUser, dbPass)
                .createQuery(String.format(sql, segmentsTable))
                .bind("dataSource", DATA_SOURCE)
                .bind("start", interval.getStart().toString())
                .bind("end", interval.getEnd().toString())
                .bind("version", version)
                .bind("used", used)
                .list()
                .stream()
                .map(r -> SegmentId.tryParse(DATA_SOURCE, String.valueOf(r.get("id"))))
                .collect(Collectors.toList());

        assertEquals(expectedRowCount, segments.size());
        return segments;
    }

  protected Table<Integer, ImmutableMap<String, Object>, ImmutableMap<String, Object>> readSegmentData(Path root,
                                                                                                       Interval interval,
                                                                                                       String version,
                                                                                                       int numShards,
                                                                                                       boolean isMonth) throws IOException {
        Table<Integer, ImmutableMap<String, Object>, ImmutableMap<String, Object>> rows = HashBasedTable.create();
        for (DateTime current = interval.getStart(); current.isBefore(interval.getEnd()); current = isMonth ? current.plusMonths(1) : current.plusDays(1)) {
            String relativePath = current + "_" + (isMonth ? current.plusMonths(1) : current.plusDays(1));
            for (int i = 0; i < numShards; i++) {
                Path segmentDir = Paths.get(root.toString(), relativePath, version, String.valueOf(i), "index");
                ImmutableMap<ImmutableMap<String, Object>, ImmutableMap<String, Object>> data = readSegmentDir(interval, segmentDir);
                for (Map.Entry<ImmutableMap<String, Object>, ImmutableMap<String, Object>> entry : data.entrySet()) {
                    rows.put(i, entry.getKey(), entry.getValue());
                }
            }
        }

        return rows;
    }

    /**
     * Asserts that there are no segment files in the (local) deep storage.
     */
    protected void assertNoSegmentFiles() throws IOException {
        Path root = Paths.get(testFolder.toString(), DATA_SOURCE);
        if (Files.exists(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                assertTrue("Segment files left in deep storage", files.noneMatch(Files::isRegularFile));
            }
        }
    }

    /**
     * Inserts a row to the segments table as is, to be able to set up segments (their payload) that wouldn't be
     * produced by {@link com.rovio.ingest.util.MetadataUpdater#publishSegments}.
     */
    public static void insertSegmentRow(JdbcDatabaseContainer<?> jdbc, String id, Interval interval, String version,
                                        boolean used, String payload) {
        String quote = jdbc instanceof MySQLContainer ? "`" : "\"";
        String sql = String.format("INSERT INTO %1$s (id, dataSource, created_date, start, %2$send%2$s, partitioned, "
                + "version, used, payload, used_status_last_updated) "
                + "VALUES (:id, :dataSource, :version, :start, :end, true, :version, :used, :payload, :version)",
                segmentsTable, quote);
        try (Handle handle = DBI.open(getConnectionString(jdbc), dbUser, dbPass)) {
            handle.createStatement(sql)
                    .bind("id", id)
                    .bind("dataSource", DATA_SOURCE)
                    .bind("version", version)
                    .bind("start", interval.getStart().toString())
                    .bind("end", interval.getEnd().toString())
                    .bind("used", used)
                    .bind("payload", payload.getBytes(StandardCharsets.UTF_8))
                    .execute();
        }
    }

    /**
     * Reads the shard specs, as they are in the segment payloads in the segments table, of the used segments of the
     * given interval and version.
     *
     * @return shard spec json by partition num
     */
    static Map<Integer, JsonNode> findShardSpecs(Interval interval, String version) throws IOException {
        String sql = "SELECT payload FROM %1$s WHERE dataSource = :dataSource AND start = :start AND `end` = :end "
                + "AND version = :version AND used = true";
        List<Map<String, Object>> rows;
        try (Handle handle = DBI.open(getConnectionString(MYSQL), dbUser, dbPass)) {
            rows = handle.createQuery(String.format(sql, segmentsTable))
                    .bind("dataSource", DATA_SOURCE)
                    .bind("start", interval.getStart().toString())
                    .bind("end", interval.getEnd().toString())
                    .bind("version", version)
                    .list();
        }
        Map<Integer, JsonNode> shardSpecs = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            JsonNode shardSpec = MAPPER.readTree((byte[]) row.get("payload")).get("shardSpec");
            shardSpecs.put(shardSpec.get("partitionNum").asInt(), shardSpec);
        }
        return shardSpecs;
    }

    /**
     * @return number of all rows (used or not) in the segments table
     */
    static long countSegments() {
        try (Handle handle = DBI.open(getConnectionString(MYSQL), dbUser, dbPass)) {
            return ((Number) handle.createQuery("SELECT count(*) AS cnt FROM " + segmentsTable).first().get("cnt")).longValue();
        }
    }

    /**
     * Loads a segment of a one day interval from the local deep storage. The caller is responsible for closing the
     * returned index.
     */
    protected QueryableIndex loadSegmentIndex(Path root, DateTime day, String version, int partitionNum) throws IOException {
        String relativePath = day + "_" + day.plusDays(1);
        Path segmentDir = Paths.get(root.toString(), relativePath, version, String.valueOf(partitionNum), "index");
        return new IndexIO(MAPPER, ColumnConfig.DEFAULT).loadIndex(segmentDir.toFile());
    }

    private ImmutableMap<ImmutableMap<String, Object>, ImmutableMap<String, Object>> readSegmentDir(Interval interval, Path segmentDir) throws IOException {
        ImmutableMap.Builder<ImmutableMap<String, Object>, ImmutableMap<String, Object>> values = ImmutableMap.builder();

        IndexIO indexIO = new IndexIO(MAPPER, ColumnConfig.DEFAULT);
        QueryableIndexStorageAdapter queryableIndexStorageAdapter = new QueryableIndexStorageAdapter(indexIO.loadIndex(segmentDir.toFile()));
        WindowedStorageAdapter windowedStorageAdapter = new WindowedStorageAdapter(queryableIndexStorageAdapter, interval);
        try (IngestSegmentFirehose firehose = new IngestSegmentFirehose(
                Collections.singletonList(windowedStorageAdapter),
                TransformSpec.NONE,
                ImmutableList.copyOf(queryableIndexStorageAdapter.getAvailableDimensions()),
                ImmutableList.copyOf(queryableIndexStorageAdapter.getAvailableMetrics()),
                null)) {

            while (firehose.hasMore()) {
                MapBasedInputRow inputRow = (MapBasedInputRow) firehose.nextRow();
                if (inputRow != null) {
                    ImmutableMap.Builder<String, Object> dimensions = ImmutableMap.builder();
                    ImmutableMap.Builder<String, Object> metrics = ImmutableMap.builder();
                    inputRow.getDimensions().forEach(d -> dimensions.put(d, inputRow.getRaw(d)));
                    dimensions.put("__time", inputRow.getTimestamp());
                    queryableIndexStorageAdapter.getAvailableMetrics().forEach(m -> metrics.put(m, inputRow.getEvent().get(m)));
                    values.put(dimensions.build(), metrics.build());
                }
            }
        }
        return values.build();
    }
}
