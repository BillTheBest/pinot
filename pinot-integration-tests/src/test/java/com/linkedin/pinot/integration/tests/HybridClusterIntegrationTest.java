/**
 * Copyright (C) 2014-2016 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.integration.tests;

import java.io.File;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.helix.ExternalViewChangeListener;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.NotificationContext;
import org.apache.helix.model.ExternalView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import com.linkedin.pinot.common.config.TableNameBuilder;
import com.linkedin.pinot.common.data.FieldSpec;
import com.linkedin.pinot.common.data.Schema;
import com.linkedin.pinot.common.utils.FileUploadUtils;
import com.linkedin.pinot.common.utils.KafkaStarterUtils;
import com.linkedin.pinot.common.utils.TarGzCompressionUtils;
import com.linkedin.pinot.common.utils.ZkStarter;
import com.linkedin.pinot.util.TestUtils;
import junit.framework.Assert;
import kafka.server.KafkaServerStartable;


/**
 * Hybrid cluster integration test that uploads 8 months of data as offline and 6 months of data as realtime (with a
 * two month overlap).
 *
 */
public class HybridClusterIntegrationTest extends BaseClusterIntegrationTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeClusterIntegrationTest.class);
  private static final String TENANT_NAME = "TestTenant";
  protected final File _tmpDir = new File("/tmp/HybridClusterIntegrationTest");
  protected final File _segmentDir = new File("/tmp/HybridClusterIntegrationTest/segmentDir");
  protected final File _tarDir = new File("/tmp/HybridClusterIntegrationTest/tarDir");
  protected static final String KAFKA_TOPIC = "hybrid-integration-test";
  private static final String TABLE_NAME = "mytable";

  private int segmentCount = 12;
  private int offlineSegmentCount = 8;
  private int realtimeSegmentCount = 6;
  private Random random = new Random();
  private Schema schema;
  private String tableName;

  private KafkaServerStartable kafkaStarter;

  protected void setSegmentCount(int segmentCount) {
    this.segmentCount = segmentCount;
  }

  protected void setOfflineSegmentCount(int offlineSegmentCount) {
    this.offlineSegmentCount = offlineSegmentCount;
  }

  protected void setRealtimeSegmentCount(int realtimeSegmentCount) {
    this.realtimeSegmentCount = realtimeSegmentCount;
  }

  protected int getOfflineSegmentCount() {
    return offlineSegmentCount;
  }

  // In case inherited tests set up a different table name they can override this method.
  protected String getTableName() {
    return tableName;
  }

  @BeforeClass
  public void setUp() throws Exception {
    //Clean up
    ensureDirectoryExistsAndIsEmpty(_tmpDir);
    ensureDirectoryExistsAndIsEmpty(_segmentDir);
    ensureDirectoryExistsAndIsEmpty(_tarDir);
    tableName = TABLE_NAME;

    // Start Zk, Kafka and Pinot
    startHybridCluster(10);

    // Unpack the Avro files
    TarGzCompressionUtils.unTar(
        new File(TestUtils.getFileFromResourceUrl(OfflineClusterIntegrationTest.class.getClassLoader().getResource(
            "On_Time_On_Time_Performance_2014_100k_subset_nonulls.tar.gz"))), _tmpDir);

    _tmpDir.mkdirs();

    final List<File> avroFiles = getAllAvroFiles();

    File schemaFile = getSchemaFile();
    schema = Schema.fromFile(schemaFile);
    addSchema(schemaFile, schema.getSchemaName());
    final List<String> invertedIndexColumns = makeInvertedIndexColumns();
    final String sortedColumn = makeSortedColumn();

    // Create Pinot table
    addHybridTable(tableName, "DaysSinceEpoch", "daysSinceEpoch", KafkaStarterUtils.DEFAULT_ZK_STR, KAFKA_TOPIC,
        schema.getSchemaName(), TENANT_NAME, TENANT_NAME, avroFiles.get(0), sortedColumn, invertedIndexColumns, null,
        false);
    LOGGER.info("Running with Sorted column=" + sortedColumn + " and inverted index columns = " + invertedIndexColumns);

    // Create a subset of the first 8 segments (for offline) and the last 6 segments (for realtime)
    final List<File> offlineAvroFiles = getOfflineAvroFiles(avroFiles);
    final List<File> realtimeAvroFiles = getRealtimeAvroFiles(avroFiles);

    // Load data into H2
    ExecutorService executor = Executors.newCachedThreadPool();
    setupH2AndInsertAvro(avroFiles, executor);

    // Create segments from Avro data
    LOGGER.info("Creating offline segments from avro files " + offlineAvroFiles);
    buildSegmentsFromAvro(offlineAvroFiles, executor, 0, _segmentDir, _tarDir, tableName, false, null);

    // Initialize query generator
    setupQueryGenerator(avroFiles, executor);

    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.MINUTES);

    // Set up a Helix spectator to count the number of segments that are uploaded and unlock the latch once 12 segments are online
    final CountDownLatch latch = new CountDownLatch(1);
    HelixManager manager =
        HelixManagerFactory.getZKHelixManager(getHelixClusterName(), "test_instance", InstanceType.SPECTATOR,
            ZkStarter.DEFAULT_ZK_STR);
    manager.connect();
    manager.addExternalViewChangeListener(new ExternalViewChangeListener() {
      @Override
      public void onExternalViewChange(List<ExternalView> externalViewList, NotificationContext changeContext) {
        for (ExternalView externalView : externalViewList) {
          if (externalView.getId().contains(tableName)) {

            Set<String> partitionSet = externalView.getPartitionSet();
            if (partitionSet.size() == offlineSegmentCount) {
              int onlinePartitionCount = 0;

              for (String partitionId : partitionSet) {
                Map<String, String> partitionStateMap = externalView.getStateMap(partitionId);
                if (partitionStateMap.containsValue("ONLINE")) {
                  onlinePartitionCount++;
                }
              }

              if (onlinePartitionCount == offlineSegmentCount) {
//                System.out.println("Got " + offlineSegmentCount + " online tables, unlatching the main thread");
                latch.countDown();
              }
            }
          }
        }
      }
    });

    // Upload the segments
    int i = 0;
    for (String segmentName : _tarDir.list()) {
//      System.out.println("Uploading segment " + (i++) + " : " + segmentName);
      File file = new File(_tarDir, segmentName);
      FileUploadUtils.sendSegmentFile("localhost", "8998", segmentName, file, file.length());
    }

    // Wait for all offline segments to be online
    latch.await();

    // Load realtime data into Kafka
    LOGGER.info("Pushing data from realtime avro files " + realtimeAvroFiles);
    pushAvroIntoKafka(realtimeAvroFiles, KafkaStarterUtils.DEFAULT_KAFKA_BROKER, KAFKA_TOPIC);

    // Wait until the Pinot event count matches with the number of events in the Avro files
    int pinotRecordCount, h2RecordCount;
    long timeInFiveMinutes = System.currentTimeMillis() + 5 * 60 * 1000L;

    Statement statement = _connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    statement.execute("select count(*) from " + tableName);
    ResultSet rs = statement.getResultSet();
    rs.first();
    h2RecordCount = rs.getInt(1);
    rs.close();

    waitForRecordCountToStabilizeToExpectedCount(h2RecordCount, timeInFiveMinutes);
  }

  protected boolean shouldUseLlc() {
    return false;
  }

  /**
   * Pick one column at random (or null) out of dimensions and return it as a sorted column.
   * @note: Change this method to return a specific sorted column (or null) to debug failed tests
   *
   * @return sorted column name or null if none is to be used for this run.
   */
  private String makeSortedColumn() {
    List<String> dimensions = schema.getDimensionNames();
    final int nDimensions = dimensions.size();
    int ntries = nDimensions;
    int rand = random.nextInt();
    if (rand % 5 == 0) {
      // Return no sorted column 20% of the time
      return null;
    }

    while (ntries-- > 0) {
      int dimPos = random.nextInt(dimensions.size() + 1);
      if (dimPos == nDimensions) {
        continue;
      }
      String sortedColumn = dimensions.get(dimPos);
      FieldSpec fieldSpec = schema.getFieldSpecFor(sortedColumn);
      if (fieldSpec.isSingleValueField()) {
        return sortedColumn;
      }
    }
    return null;
  }

  /**
   * Pick one or two inverted index columns from the list of dimension columns, and return them as list of
   * inverted index columns.
   * @note: Change this method to return a specific list of columns (or null) as needed to debug a testcase
   *
   * @return list of inverted index columns or null is no inv index is to be used for this run
   */
  private List<String> makeInvertedIndexColumns() {
    List<String> dimensions = schema.getDimensionNames();
    final int nDimensions = dimensions.size();
    int dimPos = random.nextInt(dimensions.size()+1);
    List<String> invIndexColumns = new ArrayList<String>(2);
    invIndexColumns.add("DestStateFips");
    invIndexColumns.add("OriginStateFips");

    if (dimPos == nDimensions) {
      return null;
    }
    invIndexColumns.add(dimensions.get(dimPos));
    dimPos = random.nextInt(dimensions.size()+1);
    if (dimPos == nDimensions || dimensions.get(dimPos).equals(invIndexColumns.get(0))) {
      return invIndexColumns;
    }
    invIndexColumns.add(dimensions.get(dimPos));
    return invIndexColumns;
  }

  protected List<File> getAllAvroFiles() {
    final List<File> avroFiles = new ArrayList<File>(segmentCount);
    for (int segmentNumber = 1; segmentNumber <= segmentCount; ++segmentNumber) {
      avroFiles.add(new File(_tmpDir.getPath() + "/On_Time_On_Time_Performance_2014_" + segmentNumber + ".avro"));
    }
    return avroFiles;
  }

  protected List<File> getRealtimeAvroFiles(List<File> avroFiles) {
    final List<File> realtimeAvroFiles = new ArrayList<File>(realtimeSegmentCount);
    for (int i = segmentCount - realtimeSegmentCount; i < segmentCount; i++) {
      realtimeAvroFiles.add(avroFiles.get(i));
    }
    return realtimeAvroFiles;
  }

  protected List<File> getOfflineAvroFiles(List<File> avroFiles) {
    final List<File> offlineAvroFiles = new ArrayList<File>(offlineSegmentCount);
    for (int i = 0; i < offlineSegmentCount; i++) {
      offlineAvroFiles.add(avroFiles.get(i));
    }
    return offlineAvroFiles;
  }

  protected void startHybridCluster(int partitionCount) throws Exception {
    // Start Zk and Kafka
    startZk();
    kafkaStarter =
        KafkaStarterUtils.startServer(KafkaStarterUtils.DEFAULT_KAFKA_PORT, KafkaStarterUtils.DEFAULT_BROKER_ID,
            KafkaStarterUtils.DEFAULT_ZK_STR, KafkaStarterUtils.getDefaultKafkaConfiguration());

    // Create Kafka topic
    KafkaStarterUtils.createTopic(KAFKA_TOPIC, KafkaStarterUtils.DEFAULT_ZK_STR, partitionCount);

    // Start the Pinot cluster
    startController(true);
    startBroker();
    startServers(2);

    // Create tenants
    createBrokerTenant(TENANT_NAME, 1);
    createServerTenant(TENANT_NAME, 1, 1);
  }

  @Test
  public void testBrokerDebugOutput() throws Exception {
    if (getTableName() != null) {
      Assert.assertNotNull(getDebugInfo("debug/timeBoundary" + ""));
      Assert.assertNotNull(getDebugInfo("debug/timeBoundary/" + tableName));
      Assert.assertNotNull(getDebugInfo("debug/timeBoundary/" + TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(tableName)));
      Assert.assertNotNull(getDebugInfo("debug/timeBoundary/" + TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(tableName)));
      Assert.assertNotNull(getDebugInfo("debug/routingTable/" + tableName));
      Assert.assertNotNull(getDebugInfo("debug/routingTable/" + TableNameBuilder.OFFLINE_TABLE_NAME_BUILDER.forTable(tableName)));
      Assert.assertNotNull(getDebugInfo("debug/routingTable/" + TableNameBuilder.REALTIME_TABLE_NAME_BUILDER.forTable(tableName)));
    }
  }


  @AfterClass
  public void tearDown() throws Exception {
    stopBroker();
    stopController();
    stopServer();
    KafkaStarterUtils.stopServer(kafkaStarter);
    try {
      stopZk();
    } catch (Exception e) {
      // Swallow ZK Exceptions.
    }
    cleanup();
  }

  protected void cleanup() throws  Exception {
    FileUtils.deleteDirectory(_tmpDir);
  }
}
