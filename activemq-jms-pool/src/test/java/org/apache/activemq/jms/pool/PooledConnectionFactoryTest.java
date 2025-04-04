/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.jms.pool;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Session;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ConnectionId;
import org.apache.activemq.util.Wait;
import org.apache.log4j.Logger;

/**
 * Checks the behavior of the PooledConnectionFactory when the maximum amount of
 * sessions is being reached.
 *
 * Older versions simply block in the call to Connection.getSession(), which
 * isn't good. An exception being returned is the better option, so JMS clients
 * don't block. This test succeeds if an exception is returned and fails if the
 * call to getSession() blocks.
 */
public class PooledConnectionFactoryTest extends TestCase {

    public final static Logger LOG = Logger.getLogger(PooledConnectionFactoryTest.class);

    /**
     * Create the test case
     *
     * @param testName
     *            name of the test case
     */
    public PooledConnectionFactoryTest(String testName) {
        super(testName);
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite() {
        return new TestSuite(PooledConnectionFactoryTest.class);
    }

    public void testClearAllConnections() throws Exception {

        ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(3);

        PooledConnection conn1 = (PooledConnection) cf.createConnection();
        PooledConnection conn2 = (PooledConnection) cf.createConnection();
        PooledConnection conn3 = (PooledConnection) cf.createConnection();

        assertNotSame(conn1.getConnection(), conn2.getConnection());
        assertNotSame(conn1.getConnection(), conn3.getConnection());
        assertNotSame(conn2.getConnection(), conn3.getConnection());

        assertEquals(3, cf.getNumConnections());

        cf.clear();

        assertEquals(0, cf.getNumConnections());

        conn1 = (PooledConnection) cf.createConnection();
        conn2 = (PooledConnection) cf.createConnection();
        conn3 = (PooledConnection) cf.createConnection();

        assertNotSame(conn1.getConnection(), conn2.getConnection());
        assertNotSame(conn1.getConnection(), conn3.getConnection());
        assertNotSame(conn2.getConnection(), conn3.getConnection());
    }

    public void testMaxConnectionsAreCreated() throws Exception {

        ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(3);

        PooledConnection conn1 = (PooledConnection) cf.createConnection();
        PooledConnection conn2 = (PooledConnection) cf.createConnection();
        PooledConnection conn3 = (PooledConnection) cf.createConnection();

        assertNotSame(conn1.getConnection(), conn2.getConnection());
        assertNotSame(conn1.getConnection(), conn3.getConnection());
        assertNotSame(conn2.getConnection(), conn3.getConnection());

        assertEquals(3, cf.getNumConnections());
    }

    public void testConnectionsAreRotated() throws Exception {

        ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(10);

        Connection previous = null;

        // Front load the pool.
        for (int i = 0; i < 10; ++i) {
            cf.createConnection();
        }

        for (int i = 0; i < 100; ++i) {
            Connection current = ((PooledConnection) cf.createConnection()).getConnection();
            assertNotSame(previous, current);
            previous = current;
        }
    }

    public void testConnectionsArePooled() throws Exception {

        ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(1);

        PooledConnection conn1 = (PooledConnection) cf.createConnection();
        PooledConnection conn2 = (PooledConnection) cf.createConnection();
        PooledConnection conn3 = (PooledConnection) cf.createConnection();

        assertSame(conn1.getConnection(), conn2.getConnection());
        assertSame(conn1.getConnection(), conn3.getConnection());
        assertSame(conn2.getConnection(), conn3.getConnection());

        assertEquals(1, cf.getNumConnections());
    }

    public void testConnectionsArePooledAsyncCreate() throws Exception {

        final ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        final PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(1);

        final ConcurrentLinkedQueue<PooledConnection> connections = new ConcurrentLinkedQueue<PooledConnection>();

        final PooledConnection primary = (PooledConnection) cf.createConnection();
        final ExecutorService executor = Executors.newFixedThreadPool(10);
        final int numConnections = 100;

        for (int i = 0; i < numConnections; ++i) {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        connections.add((PooledConnection) cf.createConnection());
                    } catch (JMSException e) {
                    }
                }
            });
        }

        assertTrue("", Wait.waitFor(new Wait.Condition() {
            @Override
            public boolean isSatisified() throws Exception {
                return connections.size() == numConnections;
            }
        }));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        for(PooledConnection connection : connections) {
            assertSame(primary.getConnection(), connection.getConnection());
        }

        connections.clear();
        cf.stop();
    }

    public void testConcurrentCreateGetsUniqueConnectionCreateOnDemand() throws Exception {
        doTestConcurrentCreateGetsUniqueConnection(false);
    }

    public void testConcurrentCreateGetsUniqueConnectionCreateOnStart() throws Exception {
        doTestConcurrentCreateGetsUniqueConnection(true);
    }

    private void doTestConcurrentCreateGetsUniqueConnection(boolean createOnStart) throws Exception {

        final int numConnections = 50;

        final ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
        final PooledConnectionFactory cf = new PooledConnectionFactory();
        cf.setConnectionFactory(amq);
        cf.setMaxConnections(numConnections);
        cf.setCreateConnectionOnStartup(createOnStart);

        final ConcurrentHashMap<ConnectionId, Connection> connections =
            new ConcurrentHashMap<ConnectionId, Connection>();
        final ExecutorService executor = Executors.newFixedThreadPool(numConnections / 2);

        for (int i = 0; i < numConnections; ++i) {
            executor.execute(new Runnable() {

                @Override
                public void run() {
                    try {
                        PooledConnection pooled = (PooledConnection) cf.createConnection();
                        ActiveMQConnection amq = (ActiveMQConnection) pooled.getConnection();
                        connections.put(amq.getConnectionInfo().getConnectionId(), pooled);
                    } catch (JMSException e) {
                    }
                }
            });
        }

        assertTrue("Should have all unique connections", Wait.waitFor(new Wait.Condition() {
            @Override
            public boolean isSatisified() throws Exception {
                return connections.size() == numConnections;
            }
        }));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        connections.clear();
        cf.stop();
    }

    /**
     * Tests the behavior of the sessionPool of the PooledConnectionFactory when
     * maximum number of sessions are reached.
     */
    public void testApp() throws Exception {
        // using separate thread for testing so that we can interrupt the test
        // if the call to get a new session blocks.

        // start test runner thread
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> result = executor.submit(new TestRunner());

        // test should not take > 5secs, so test fails i
        Thread.sleep(5 * 1000);

        if (!result.isDone() || !result.get().booleanValue()) {
            PooledConnectionFactoryTest.LOG.error("2nd call to createSession() " +
                                                  "is blocking but should have returned an error instead.");

            executor.shutdownNow();

            fail("SessionPool inside PooledConnectionFactory is blocking if " +
                 "limit is exceeded but should return an exception instead.");
        }
    }

    static class TestRunner implements Callable<Boolean> {

        public final static Logger LOG = Logger.getLogger(TestRunner.class);

        /**
         * @return true if test succeeded, false otherwise
         */
        @Override
        public Boolean call() {

            Connection conn = null;
            Session one = null;

            // wait at most 5 seconds for the call to createSession
            try {
                ActiveMQConnectionFactory amq = new ActiveMQConnectionFactory("vm://broker1?marshal=false&broker.persistent=false");
                PooledConnectionFactory cf = new PooledConnectionFactory();
                cf.setConnectionFactory(amq);
                cf.setMaxConnections(3);
                cf.setMaximumActiveSessionPerConnection(1);
                cf.setBlockIfSessionPoolIsFull(false);

                conn = cf.createConnection();
                one = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);

                Session two = null;
                try {
                    // this should raise an exception as we called
                    // setMaximumActive(1)
                    two = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
                    two.close();

                    LOG.error("Expected JMSException wasn't thrown.");
                    fail("seconds call to Connection.createSession() was supposed" +
                         "to raise an JMSException as internal session pool" +
                         "is exhausted. This did not happen and indiates a problem");
                    return new Boolean(false);
                } catch (JMSException ex) {
                    if (ex.getCause().getClass() == java.util.NoSuchElementException.class) {
                        // expected, ignore but log
                        LOG.info("Caught expected " + ex);
                    } else {
                        LOG.error(ex);
                        return new Boolean(false);
                    }
                } finally {
                    if (one != null)
                        one.close();
                    if (conn != null)
                        conn.close();
                }
            } catch (Exception ex) {
                LOG.error(ex.getMessage());
                return new Boolean(false);
            }

            // all good, test succeeded
            return new Boolean(true);
        }
    }
}
