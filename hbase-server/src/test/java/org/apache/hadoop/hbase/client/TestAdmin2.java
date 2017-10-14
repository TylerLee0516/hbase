/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterStatus.Option;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.TableNotDisabledException;
import org.apache.hadoop.hbase.TableNotEnabledException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.UnknownRegionException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.constraint.ConstraintException;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.assignment.AssignmentManager;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.wal.AbstractFSWALProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;


/**
 * Class to test HBaseAdmin.
 * Spins up the minicluster once at test start and then takes it down afterward.
 * Add any testing of HBaseAdmin functionality here.
 */
@Category({LargeTests.class, ClientTests.class})
public class TestAdmin2 {
  private static final Log LOG = LogFactory.getLog(TestAdmin2.class);
  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private Admin admin;

  @Rule
  public TestName name = new TestName();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.getConfiguration().setInt("hbase.regionserver.msginterval", 100);
    TEST_UTIL.getConfiguration().setInt("hbase.client.pause", 250);
    TEST_UTIL.getConfiguration().setInt("hbase.client.retries.number", 6);
    TEST_UTIL.getConfiguration().setInt("hbase.regionserver.metahandler.count", 30);
    TEST_UTIL.getConfiguration().setBoolean(
        "hbase.master.enabletable.roundrobin", true);
    TEST_UTIL.startMiniCluster(3);
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    this.admin = TEST_UTIL.getHBaseAdmin();
  }

  @After
  public void tearDown() throws Exception {
    for (HTableDescriptor htd : this.admin.listTables()) {
      TEST_UTIL.deleteTable(htd.getTableName());
    }
  }

  @Test (timeout=300000)
  public void testCreateBadTables() throws IOException {
    String msg = null;
    try {
      this.admin.createTable(new HTableDescriptor(TableName.META_TABLE_NAME));
    } catch(TableExistsException e) {
      msg = e.toString();
    }
    assertTrue("Unexcepted exception message " + msg, msg != null &&
      msg.startsWith(TableExistsException.class.getName()) &&
      msg.contains(TableName.META_TABLE_NAME.getNameAsString()));

    // Now try and do concurrent creation with a bunch of threads.
    final HTableDescriptor threadDesc = new HTableDescriptor(TableName.valueOf(name.getMethodName()));
    threadDesc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    int count = 10;
    Thread [] threads = new Thread [count];
    final AtomicInteger successes = new AtomicInteger(0);
    final AtomicInteger failures = new AtomicInteger(0);
    final Admin localAdmin = this.admin;
    for (int i = 0; i < count; i++) {
      threads[i] = new Thread(Integer.toString(i)) {
        @Override
        public void run() {
          try {
            localAdmin.createTable(threadDesc);
            successes.incrementAndGet();
          } catch (TableExistsException e) {
            failures.incrementAndGet();
          } catch (IOException e) {
            throw new RuntimeException("Failed threaded create" + getName(), e);
          }
        }
      };
    }
    for (int i = 0; i < count; i++) {
      threads[i].start();
    }
    for (int i = 0; i < count; i++) {
      while(threads[i].isAlive()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          // continue
        }
      }
    }
    // All threads are now dead.  Count up how many tables were created and
    // how many failed w/ appropriate exception.
    assertEquals(1, successes.get());
    assertEquals(count - 1, failures.get());
  }

  /**
   * Test for hadoop-1581 'HBASE: Unopenable tablename bug'.
   * @throws Exception
   */
  @Test (timeout=300000)
  public void testTableNameClash() throws Exception {
    final String name = this.name.getMethodName();
    HTableDescriptor htd1 = new HTableDescriptor(TableName.valueOf(name + "SOMEUPPERCASE"));
    HTableDescriptor htd2 = new HTableDescriptor(TableName.valueOf(name));
    htd1.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    htd2.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(htd1);
    admin.createTable(htd2);
    // Before fix, below would fail throwing a NoServerForRegionException.
    TEST_UTIL.getConnection().getTable(htd2.getTableName()).close();
  }

  /***
   * HMaster.createTable used to be kind of synchronous call
   * Thus creating of table with lots of regions can cause RPC timeout
   * After the fix to make createTable truly async, RPC timeout shouldn't be an
   * issue anymore
   * @throws Exception
   */
  @Test (timeout=300000)
  public void testCreateTableRPCTimeOut() throws Exception {
    final String name = this.name.getMethodName();
    int oldTimeout = TEST_UTIL.getConfiguration().
      getInt(HConstants.HBASE_RPC_TIMEOUT_KEY, HConstants.DEFAULT_HBASE_RPC_TIMEOUT);
    TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, 1500);
    try {
      int expectedRegions = 100;
      // Use 80 bit numbers to make sure we aren't limited
      byte [] startKey = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
      byte [] endKey =   { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 };
      Admin hbaseadmin = TEST_UTIL.getHBaseAdmin();
      HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(name));
      htd.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
      hbaseadmin.createTable(htd, startKey, endKey, expectedRegions);
    } finally {
      TEST_UTIL.getConfiguration().setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, oldTimeout);
    }
  }

  /**
   * Test read only tables
   * @throws Exception
   */
  @Test (timeout=300000)
  public void testReadOnlyTable() throws Exception {
    final TableName name = TableName.valueOf(this.name.getMethodName());
    Table table = TEST_UTIL.createTable(name, HConstants.CATALOG_FAMILY);
    byte[] value = Bytes.toBytes("somedata");
    // This used to use an empty row... That must have been a bug
    Put put = new Put(value);
    put.addColumn(HConstants.CATALOG_FAMILY, HConstants.CATALOG_FAMILY, value);
    table.put(put);
    table.close();
  }

  /**
   * Test that user table names can contain '-' and '.' so long as they do not
   * start with same. HBASE-771
   * @throws IOException
   */
  @Test (timeout=300000)
  public void testTableNames() throws IOException {
    byte[][] illegalNames = new byte[][] {
        Bytes.toBytes("-bad"),
        Bytes.toBytes(".bad")
    };
    for (byte[] illegalName : illegalNames) {
      try {
        new HTableDescriptor(TableName.valueOf(illegalName));
        throw new IOException("Did not detect '" +
            Bytes.toString(illegalName) + "' as an illegal user table name");
      } catch (IllegalArgumentException e) {
        // expected
      }
    }
    byte[] legalName = Bytes.toBytes("g-oo.d");
    try {
      new HTableDescriptor(TableName.valueOf(legalName));
    } catch (IllegalArgumentException e) {
      throw new IOException("Legal user table name: '" +
        Bytes.toString(legalName) + "' caused IllegalArgumentException: " +
        e.getMessage());
    }
  }

  /**
   * For HADOOP-2579
   * @throws IOException
   */
  @Test (expected=TableExistsException.class, timeout=300000)
  public void testTableExistsExceptionWithATable() throws IOException {
    final TableName name = TableName.valueOf(this.name.getMethodName());
    TEST_UTIL.createTable(name, HConstants.CATALOG_FAMILY).close();
    TEST_UTIL.createTable(name, HConstants.CATALOG_FAMILY);
  }

  /**
   * Can't disable a table if the table isn't in enabled state
   * @throws IOException
   */
  @Test (expected=TableNotEnabledException.class, timeout=300000)
  public void testTableNotEnabledExceptionWithATable() throws IOException {
    final TableName name = TableName.valueOf(this.name.getMethodName());
    TEST_UTIL.createTable(name, HConstants.CATALOG_FAMILY).close();
    this.admin.disableTable(name);
    this.admin.disableTable(name);
  }

  /**
   * Can't enable a table if the table isn't in disabled state
   * @throws IOException
   */
  @Test (expected=TableNotDisabledException.class, timeout=300000)
  public void testTableNotDisabledExceptionWithATable() throws IOException {
    final TableName name = TableName.valueOf(this.name.getMethodName());
    Table t = TEST_UTIL.createTable(name, HConstants.CATALOG_FAMILY);
    try {
    this.admin.enableTable(name);
    }finally {
       t.close();
    }
  }

  /**
   * For HADOOP-2579
   * @throws IOException
   */
  @Test (expected=TableNotFoundException.class, timeout=300000)
  public void testTableNotFoundExceptionWithoutAnyTables() throws IOException {
    TableName tableName = TableName
        .valueOf("testTableNotFoundExceptionWithoutAnyTables");
    Table ht = TEST_UTIL.getConnection().getTable(tableName);
    ht.get(new Get("e".getBytes()));
  }

  @Test (timeout=300000)
  public void testShouldUnassignTheRegion() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    createTableWithDefaultConf(tableName);

    RegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(tableName);
    List<RegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (RegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.getTable().isSystemTable()) {
        info = regionInfo;
        admin.unassign(regionInfo.getRegionName(), true);
      }
    }
    boolean isInList = ProtobufUtil.getOnlineRegions(
      rs.getRSRpcServices()).contains(info);
    long timeout = System.currentTimeMillis() + 10000;
    while ((System.currentTimeMillis() < timeout) && (isInList)) {
      Thread.sleep(100);
      isInList = ProtobufUtil.getOnlineRegions(
        rs.getRSRpcServices()).contains(info);
    }

    assertFalse("The region should not be present in online regions list.",
      isInList);
  }

  @Test (timeout=300000)
  public void testCloseRegionIfInvalidRegionNameIsPassed() throws Exception {
    final String name = this.name.getMethodName();
    byte[] tableName = Bytes.toBytes(name);
    createTableWithDefaultConf(tableName);

    RegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(TableName.valueOf(tableName));
    List<RegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (RegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.isMetaRegion()) {
        if (regionInfo.getRegionNameAsString().contains(name)) {
          info = regionInfo;
          try {
            admin.unassign(Bytes.toBytes("sample"), true);
          } catch (UnknownRegionException nsre) {
            // expected, ignore it
          }
        }
      }
    }
    onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    assertTrue("The region should be present in online regions list.",
        onlineRegions.contains(info));
  }

  @Test (timeout=300000)
  public void testCloseRegionThatFetchesTheHRIFromMeta() throws Exception {
    final TableName tableName = TableName.valueOf(name.getMethodName());
    createTableWithDefaultConf(tableName);

    RegionInfo info = null;
    HRegionServer rs = TEST_UTIL.getRSForFirstRegionInTable(tableName);
    List<RegionInfo> onlineRegions = ProtobufUtil.getOnlineRegions(rs.getRSRpcServices());
    for (RegionInfo regionInfo : onlineRegions) {
      if (!regionInfo.isMetaRegion()) {
        if (regionInfo.getRegionNameAsString().contains("TestHBACloseRegion2")) {
          info = regionInfo;
          admin.unassign(regionInfo.getRegionName(), true);
        }
      }
    }

    boolean isInList = ProtobufUtil.getOnlineRegions(
      rs.getRSRpcServices()).contains(info);
    long timeout = System.currentTimeMillis() + 10000;
    while ((System.currentTimeMillis() < timeout) && (isInList)) {
      Thread.sleep(100);
      isInList = ProtobufUtil.getOnlineRegions(
        rs.getRSRpcServices()).contains(info);
    }

    assertFalse("The region should not be present in online regions list.",
      isInList);
  }

  private HBaseAdmin createTable(TableName tableName) throws IOException {
    HBaseAdmin admin = TEST_UTIL.getHBaseAdmin();

    HTableDescriptor htd = new HTableDescriptor(tableName);
    HColumnDescriptor hcd = new HColumnDescriptor("value");

    htd.addFamily(hcd);
    admin.createTable(htd, null);
    return admin;
  }

  private void createTableWithDefaultConf(byte[] TABLENAME) throws IOException {
    createTableWithDefaultConf(TableName.valueOf(TABLENAME));
  }

  private void createTableWithDefaultConf(TableName TABLENAME) throws IOException {
    HTableDescriptor htd = new HTableDescriptor(TABLENAME);
    HColumnDescriptor hcd = new HColumnDescriptor("value");
    htd.addFamily(hcd);

    admin.createTable(htd, null);
  }

  /**
   * For HBASE-2556
   * @throws IOException
   */
  @Test (timeout=300000)
  public void testGetTableRegions() throws IOException {
    final TableName tableName = TableName.valueOf(name.getMethodName());

    int expectedRegions = 10;

    // Use 80 bit numbers to make sure we aren't limited
    byte [] startKey = { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 };
    byte [] endKey =   { 9, 9, 9, 9, 9, 9, 9, 9, 9, 9 };


    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc, startKey, endKey, expectedRegions);

    List<RegionInfo> RegionInfos = admin.getRegions(tableName);

    assertEquals("Tried to create " + expectedRegions + " regions " +
        "but only found " + RegionInfos.size(),
        expectedRegions, RegionInfos.size());
 }

  @Test (timeout=300000)
  public void testMoveToPreviouslyAssignedRS() throws IOException, InterruptedException {
    MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
    HMaster master = cluster.getMaster();
    final TableName tableName = TableName.valueOf(name.getMethodName());
    Admin localAdmin = createTable(tableName);
    List<RegionInfo> tableRegions = localAdmin.getRegions(tableName);
    RegionInfo hri = tableRegions.get(0);
    AssignmentManager am = master.getAssignmentManager();
    ServerName server = am.getRegionStates().getRegionServerOfRegion(hri);
    localAdmin.move(hri.getEncodedNameAsBytes(), Bytes.toBytes(server.getServerName()));
    assertEquals("Current region server and region server before move should be same.", server,
      am.getRegionStates().getRegionServerOfRegion(hri));
  }

  @Test (timeout=300000)
  public void testWALRollWriting() throws Exception {
    setUpforLogRolling();
    String className = this.getClass().getName();
    StringBuilder v = new StringBuilder(className);
    while (v.length() < 1000) {
      v.append(className);
    }
    byte[] value = Bytes.toBytes(v.toString());
    HRegionServer regionServer = startAndWriteData(TableName.valueOf(name.getMethodName()), value);
    LOG.info("after writing there are "
        + AbstractFSWALProvider.getNumRolledLogFiles(regionServer.getWAL(null)) + " log files");

    // flush all regions
    for (HRegion r : regionServer.getOnlineRegionsLocalContext()) {
      r.flush(true);
    }
    admin.rollWALWriter(regionServer.getServerName());
    int count = AbstractFSWALProvider.getNumRolledLogFiles(regionServer.getWAL(null));
    LOG.info("after flushing all regions and rolling logs there are " +
        count + " log files");
    assertTrue(("actual count: " + count), count <= 2);
  }

  private void setUpforLogRolling() {
    // Force a region split after every 768KB
    TEST_UTIL.getConfiguration().setLong(HConstants.HREGION_MAX_FILESIZE,
        768L * 1024L);

    // We roll the log after every 32 writes
    TEST_UTIL.getConfiguration().setInt("hbase.regionserver.maxlogentries", 32);

    TEST_UTIL.getConfiguration().setInt(
        "hbase.regionserver.logroll.errors.tolerated", 2);
    TEST_UTIL.getConfiguration().setInt("hbase.rpc.timeout", 10 * 1000);

    // For less frequently updated regions flush after every 2 flushes
    TEST_UTIL.getConfiguration().setInt(
        "hbase.hregion.memstore.optionalflushcount", 2);

    // We flush the cache after every 8192 bytes
    TEST_UTIL.getConfiguration().setInt(HConstants.HREGION_MEMSTORE_FLUSH_SIZE,
        8192);

    // Increase the amount of time between client retries
    TEST_UTIL.getConfiguration().setLong("hbase.client.pause", 10 * 1000);

    // Reduce thread wake frequency so that other threads can get
    // a chance to run.
    TEST_UTIL.getConfiguration().setInt(HConstants.THREAD_WAKE_FREQUENCY,
        2 * 1000);

    /**** configuration for testLogRollOnDatanodeDeath ****/
    // lower the namenode & datanode heartbeat so the namenode
    // quickly detects datanode failures
    TEST_UTIL.getConfiguration().setInt("dfs.namenode.heartbeat.recheck-interval", 5000);
    TEST_UTIL.getConfiguration().setInt("dfs.heartbeat.interval", 1);
    // the namenode might still try to choose the recently-dead datanode
    // for a pipeline, so try to a new pipeline multiple times
    TEST_UTIL.getConfiguration().setInt("dfs.client.block.write.retries", 30);
    TEST_UTIL.getConfiguration().setInt(
        "hbase.regionserver.hlog.tolerable.lowreplication", 2);
    TEST_UTIL.getConfiguration().setInt(
        "hbase.regionserver.hlog.lowreplication.rolllimit", 3);
  }

  private HRegionServer startAndWriteData(TableName tableName, byte[] value)
  throws IOException, InterruptedException {
    // When the hbase:meta table can be opened, the region servers are running
    TEST_UTIL.getConnection().getTable(TableName.META_TABLE_NAME).close();

    // Create the test table and open it
    HTableDescriptor desc = new HTableDescriptor(tableName);
    desc.addFamily(new HColumnDescriptor(HConstants.CATALOG_FAMILY));
    admin.createTable(desc);
    Table table = TEST_UTIL.getConnection().getTable(tableName);

    HRegionServer regionServer = TEST_UTIL.getRSForFirstRegionInTable(tableName);
    for (int i = 1; i <= 256; i++) { // 256 writes should cause 8 log rolls
      Put put = new Put(Bytes.toBytes("row" + String.format("%1$04d", i)));
      put.addColumn(HConstants.CATALOG_FAMILY, null, value);
      table.put(put);
      if (i % 32 == 0) {
        // After every 32 writes sleep to let the log roller run
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          // continue
        }
      }
    }

    table.close();
    return regionServer;
  }

  /**
   * Check that we have an exception if the cluster is not there.
   */
  @Test (timeout=300000)
  public void testCheckHBaseAvailableWithoutCluster() {
    Configuration conf = new Configuration(TEST_UTIL.getConfiguration());

    // Change the ZK address to go to something not used.
    conf.setInt(HConstants.ZOOKEEPER_CLIENT_PORT,
      conf.getInt(HConstants.ZOOKEEPER_CLIENT_PORT, 9999)+10);

    long start = System.currentTimeMillis();
    try {
      HBaseAdmin.available(conf);
      assertTrue(false);
    } catch (ZooKeeperConnectionException ignored) {
    } catch (IOException ignored) {
    }
    long end = System.currentTimeMillis();

    LOG.info("It took "+(end-start)+" ms to find out that" +
      " HBase was not available");
  }

  @Test (timeout=300000)
  public void testDisableCatalogTable() throws Exception {
    try {
      this.admin.disableTable(TableName.META_TABLE_NAME);
      fail("Expected to throw ConstraintException");
    } catch (ConstraintException e) {
    }
    // Before the fix for HBASE-6146, the below table creation was failing as the hbase:meta table
    // actually getting disabled by the disableTable() call.
    HTableDescriptor htd = new HTableDescriptor(TableName.valueOf(name.getMethodName().getBytes()));
    HColumnDescriptor hcd = new HColumnDescriptor("cf1".getBytes());
    htd.addFamily(hcd);
    TEST_UTIL.getHBaseAdmin().createTable(htd);
  }

  @Test (timeout=300000)
  public void testIsEnabledOrDisabledOnUnknownTable() throws Exception {
    try {
      admin.isTableEnabled(TableName.valueOf(name.getMethodName()));
      fail("Test should fail if isTableEnabled called on unknown table.");
    } catch (IOException e) {
    }

    try {
      admin.isTableDisabled(TableName.valueOf(name.getMethodName()));
      fail("Test should fail if isTableDisabled called on unknown table.");
    } catch (IOException e) {
    }
  }

  @Test (timeout=300000)
  public void testGetRegion() throws Exception {
    // We use actual HBaseAdmin instance instead of going via Admin interface in
    // here because makes use of an internal HBA method (TODO: Fix.).
    HBaseAdmin rawAdmin = TEST_UTIL.getHBaseAdmin();

    final TableName tableName = TableName.valueOf(name.getMethodName());
    LOG.info("Started " + tableName);
    Table t = TEST_UTIL.createMultiRegionTable(tableName, HConstants.CATALOG_FAMILY);

    try (RegionLocator locator = TEST_UTIL.getConnection().getRegionLocator(tableName)) {
      HRegionLocation regionLocation = locator.getRegionLocation(Bytes.toBytes("mmm"));
      RegionInfo region = regionLocation.getRegionInfo();
      byte[] regionName = region.getRegionName();
      Pair<RegionInfo, ServerName> pair = rawAdmin.getRegion(regionName);
      assertTrue(Bytes.equals(regionName, pair.getFirst().getRegionName()));
      pair = rawAdmin.getRegion(region.getEncodedNameAsBytes());
      assertTrue(Bytes.equals(regionName, pair.getFirst().getRegionName()));
    }
  }

  @Test(timeout = 30000)
  public void testBalancer() throws Exception {
    boolean initialState = admin.isBalancerEnabled();

    // Start the balancer, wait for it.
    boolean prevState = admin.setBalancerRunning(!initialState, true);

    // The previous state should be the original state we observed
    assertEquals(initialState, prevState);

    // Current state should be opposite of the original
    assertEquals(!initialState, admin.isBalancerEnabled());

    // Reset it back to what it was
    prevState = admin.setBalancerRunning(initialState, true);

    // The previous state should be the opposite of the initial state
    assertEquals(!initialState, prevState);
    // Current state should be the original state again
    assertEquals(initialState, admin.isBalancerEnabled());
  }

  @Test(timeout = 30000)
  public void testRegionNormalizer() throws Exception {
    boolean initialState = admin.isNormalizerEnabled();

    // flip state
    boolean prevState = admin.setNormalizerRunning(!initialState);

    // The previous state should be the original state we observed
    assertEquals(initialState, prevState);

    // Current state should be opposite of the original
    assertEquals(!initialState, admin.isNormalizerEnabled());

    // Reset it back to what it was
    prevState = admin.setNormalizerRunning(initialState);

    // The previous state should be the opposite of the initial state
    assertEquals(!initialState, prevState);
    // Current state should be the original state again
    assertEquals(initialState, admin.isNormalizerEnabled());
  }

  @Test(timeout = 30000)
  public void testAbortProcedureFail() throws Exception {
    Random randomGenerator = new Random();
    long procId = randomGenerator.nextLong();

    boolean abortResult = admin.abortProcedure(procId, true);
    assertFalse(abortResult);
  }

  @Test(timeout = 300000)
  public void testGetProcedures() throws Exception {
    String procList = admin.getProcedures();
    assertTrue(procList.startsWith("["));
  }

  @Test(timeout = 300000)
  public void testGetLocks() throws Exception {
    String lockList = admin.getLocks();
    assertTrue(lockList.startsWith("["));
  }

  /*
   * This test drains all regions so cannot be run in parallel with other tests.
   */
  @Ignore @Test(timeout = 30000)
  public void testDrainRegionServers() throws Exception {
    List<ServerName> drainingServers = admin.listDrainingRegionServers();
    assertTrue(drainingServers.isEmpty());

    // Drain all region servers.
    Collection<ServerName> clusterServers =
        admin.getClusterStatus(EnumSet.of(Option.LIVE_SERVERS)).getServers();
    drainingServers = new ArrayList<>();
    for (ServerName server : clusterServers) {
      drainingServers.add(server);
    }
    admin.drainRegionServers(drainingServers);

    // Check that drain lists all region servers.
    drainingServers = admin.listDrainingRegionServers();
    assertEquals(clusterServers.size(), drainingServers.size());
    for (ServerName server : clusterServers) {
      assertTrue(drainingServers.contains(server));
    }

    // Try for 20 seconds to create table (new region). Will not complete because all RSs draining.
    final TableName hTable = TableName.valueOf(name.getMethodName());
    final HTableDescriptor htd = new HTableDescriptor(hTable);
    htd.addFamily(new HColumnDescriptor("cf"));

    final Runnable createTable = new Thread() {
      @Override
      public void run() {
        try {
          admin.createTable(htd);
        } catch (IOException ioe) {
          assertTrue(false); // Should not get IOException.
        }
      }
    };

    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final java.util.concurrent.Future<?> future = executor.submit(createTable);
    executor.shutdown();
    try {
      future.get(20, TimeUnit.SECONDS);
    } catch (TimeoutException ie) {
      assertTrue(true); // Expecting timeout to happen.
    }

    // Kill executor if still processing.
    if (!executor.isTerminated()) {
      executor.shutdownNow();
      assertTrue(true);
    }

    // Remove drain list.
    admin.removeDrainFromRegionServers(drainingServers);
    drainingServers = admin.listDrainingRegionServers();
    assertTrue(drainingServers.isEmpty());

  }
}
