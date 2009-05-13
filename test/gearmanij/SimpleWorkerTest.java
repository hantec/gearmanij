/*
 * Copyright (C) 2009 by Robert Stewart <robert@wombatnation.com>
 * Copyright (C) 2009 by Eric Herman <eric@freesa.org>
 * Use and distribution licensed under the 
 * GNU Lesser General Public License (LGPL) version 2.1.
 * See the COPYING file in the parent directory for full text.
 */
package gearmanij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import gearmanij.Worker.WorkerOption;
import gearmanij.example.DigestFunction;
import gearmanij.example.ReverseClient;
import gearmanij.example.ReverseFunction;
import gearmanij.util.TestUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SimpleWorkerTest {

  private Worker worker;
  private Connection conn;
  private Connection clientConn;

  @Before
  public void setUp() {
    worker = new SimpleWorker();
  }

  @After
  public void tearDown() {
    conn = null;
    List<Exception> close = Collections.emptyList();
    try {
      close = worker.shutdown();
    } catch (Exception e) {
      e.printStackTrace();
    }
    worker = null;
    for (Exception e : close) {
      e.printStackTrace();
    }
    try {
      if (clientConn != null) {
        clientConn.close();
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      clientConn = null;
    }
  }

  @Test
  public void testWorkerOptions() {
    assertEquals(worker.getWorkerOptions(), EnumSet.noneOf(WorkerOption.class));
  }

  @Test
  public void testRemoveOptions() {
    worker.setWorkerOptions(WorkerOption.NON_BLOCKING, WorkerOption.GRAB_UNIQ);
    Collection<WorkerOption> c;
    c = EnumSet.of(WorkerOption.NON_BLOCKING, WorkerOption.GRAB_UNIQ);
    assertTrue(worker.getWorkerOptions().containsAll(c));
    worker.removeWorkerOptions(WorkerOption.GRAB_UNIQ);
    assertTrue(worker.getWorkerOptions().contains(WorkerOption.NON_BLOCKING));
    assertFalse(worker.getWorkerOptions().contains(WorkerOption.GRAB_UNIQ));
  }

  @Test
  public void testSetWorkerOptions() {
    Collection<WorkerOption> c = null;

    worker.setWorkerOptions(WorkerOption.NON_BLOCKING);
    c = EnumSet.of(WorkerOption.NON_BLOCKING);
    assertTrue(worker.getWorkerOptions().containsAll(c));

    worker.clearWorkerOptions();
    worker.setWorkerOptions(WorkerOption.NON_BLOCKING,
        WorkerOption.NON_BLOCKING);
    c = EnumSet.of(WorkerOption.NON_BLOCKING);
    assertTrue(worker.getWorkerOptions().containsAll(c));
  }

  @Test
  public void testSetWorkerID() {
    newSocketConnection();
    String id = "SimpleWorker";

    worker.setWorkerID(id);
    assertTrue(TestUtil.isWorkerFoundByID(conn, id));
  }

  private void newSocketConnection() {
    conn = new SocketConnection();
    worker.addServer(conn);
  }

  @Test
  public void testRegisterFunction() {
    newSocketConnection();
    JobFunction digest = new DigestFunction();
    String id = "testRegisterFunction";

    worker.setWorkerID(id);
    worker.registerFunction(digest);
    String name = digest.getName();
    assertTrue(TestUtil.isFunctionRegisteredForWorker(conn, id, name));
  }

  /**
   * Registers a function that sleeps for 3 seconds with a timeout of only 1
   * second.
   */
  @Test
  public void testRegisterFunctionWithTimeout() throws Exception {
    clientConn = new SocketConnection();
    final ReverseClient client;
    client = new ReverseClient(new ConnectionClient(clientConn));
    Thread t = TestUtil.startThread("test_reverse_client", new Runnable() {
      public void run() {
        client.reverse("foo");
      }
    });

    newSocketConnection();
    JobFunction reverse = new ReverseFunction();
    int delay = 3;
    int timeout = 1;
    String id = "testRegisterFunctionWithTimeout";
    PacketType type;

    // Set number of seconds to delay execution
    ((ReverseFunction) reverse).setDelay(delay);

    worker.setWorkerID(id);
    worker.registerFunction(reverse, timeout);
    String name = reverse.getName();
    assertTrue(TestUtil.isFunctionRegisteredForWorker(conn, id, name));
    type = worker.grabJob(conn);
    assertTrue(PacketType.JOB_ASSIGN == type);
    worker.unregisterFunction(reverse);
    assertFalse(TestUtil.isFunctionRegisteredForWorker(conn, id, name));
    t.join(100);
  }

  @Test
  public void testUnregisterFunction() {
    newSocketConnection();
    JobFunction digest = new DigestFunction();
    String id = "testUnregisterFunction";

    worker.setWorkerID(id);
    worker.registerFunction(digest);
    String name = digest.getName();
    assertTrue(TestUtil.isFunctionRegisteredForWorker(conn, id, name));
    worker.unregisterFunction(digest);
    assertFalse(TestUtil.isFunctionRegisteredForWorker(conn, id, name));
  }

  /**
   * Note: This crashes gearmand 0.5 if run twice. The crash occurs when digest
   * is registered. So, ignoring this test for now.
   */
  @Test
  @Ignore
  public void testUnregisterAll() {
    newSocketConnection();
    JobFunction reverse = new ReverseFunction();
    String revName = reverse.getName();
    JobFunction digest = new DigestFunction();
    String digName = digest.getName();
    String id = "testUnregisterAll";

    worker.setWorkerID(id);
    worker.registerFunction(reverse);
    worker.registerFunction(digest);
    assertTrue(TestUtil.isFunctionRegisteredForWorker(conn, id, revName));
    assertTrue(TestUtil.isFunctionRegisteredForWorker(conn, id, digName));
    worker.unregisterAll();
    assertFalse(TestUtil.isFunctionRegisteredForWorker(conn, id, revName));
    assertFalse(TestUtil.isFunctionRegisteredForWorker(conn, id, digName));
  }

}
