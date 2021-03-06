//
// ReplicatorWithSyncGatewayDBTest.java
//
// Copyright (c) 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.couchbase.lite.internal.utils.Preconditions;
import com.couchbase.lite.utils.ReplicatorSystemTest;
import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/**
 * Note: https://github.com/couchbase/couchbase-lite-android/tree/master/test/replicator
 * <p>
 * NOTE: To execute ReplicatorWithSyncGatewayDBTest unit tests, please launch
 * Sync Gateway with using test/replicator/config.json configuration file.
 * In case of executing unit test from real device, please use test/replicator/config.nonlocalhost.json
 * configuration file.
 */
public class ReplicatorWithSyncGatewayDBTest extends BaseReplicatorTest {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String DB_NAME = "db";

    private String remoteHost;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        remoteHost = System.getenv().get("couchbase.remoteHost");
        Preconditions.checkArgNotNull(remoteHost, "remoteHost");

        remoteDeleteDb(DB_NAME);
        remotePutDb(DB_NAME);
    }

    @After
    public void tearDown() {
        try { remoteDeleteDb(DB_NAME); }
        catch (IOException e) { Report.log(LogLevel.ERROR,"Failed closing DB: " + DB_NAME, e); }
        super.tearDown();
    }

    @Test
    @ReplicatorSystemTest
    public void testEmptyPushToRemoteDB() throws Exception {
        Endpoint target = getRemoteEndpoint(DB_NAME, false);
        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);
    }

    @Test
    @ReplicatorSystemTest
    public void testPushToRemoteDB() throws Exception {
        // Create 100 docs in local db
        loadJSONResource("names_100.json");

        // target SG URI
        Endpoint target = getRemoteEndpoint(DB_NAME, false);

        // Push replicate from db to SG
        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);

        // Pull replicate from SG to otherDB.
        config = makeConfig(false, true, false, this.otherDB, target);
        run(config, 0, null);
        assertEquals(100, this.otherDB.getCount());
    }

    @Test
    @ReplicatorSystemTest
    public void testProgress() throws Exception {
        timeout = 60;

        final int numDocs = 5000;
        loadNumbers(numDocs);

        // target SG URI
        Endpoint target = getRemoteEndpoint(DB_NAME, false);

        {
            // Push replicate from db to SG
            ReplicatorConfiguration config = makeConfig(true, false, false, target);
            Replicator r = new Replicator(config);
            final CountDownLatch progressLatch = new CountDownLatch(1);
            ListenerToken token = r.addChangeListener(executor, new ReplicatorChangeListener() {
                @Override
                public void changed(ReplicatorChange change) {
                    Replicator.Status status = change.getStatus();
                    Replicator.Progress progress = status.getProgress();
                    if (progress.getCompleted() >= numDocs && progress.getCompleted() == progress.getTotal()) {
                        progressLatch.countDown();
                    }
                }
            });
            run(r, 0, null);
            r.removeChangeListener(token);
            assertTrue(progressLatch.await(20, TimeUnit.SECONDS));
        }

        // Pull replicate from SG to otherDB.
        {
            ReplicatorConfiguration config = makeConfig(false, true, false, this.otherDB, target);
            Replicator r = new Replicator(config);
            final CountDownLatch progressLatch = new CountDownLatch(1);
            ListenerToken token = r.addChangeListener(executor, new ReplicatorChangeListener() {
                @Override
                public void changed(ReplicatorChange change) {
                    Replicator.Status status = change.getStatus();
                    Replicator.Progress progress = status.getProgress();
                    if (progress.getCompleted() >= numDocs && progress.getCompleted() == progress.getTotal()) {
                        progressLatch.countDown();
                    }
                }
            });
            run(r, 0, null);
            r.removeChangeListener(token);
            assertTrue(progressLatch.await(20, TimeUnit.SECONDS));
            assertEquals(numDocs, this.otherDB.getCount());
        }
    }

    /**
     * How to test reachability.
     * 1. Run sync gateway
     * 2. Disable Wifi with the device
     * 3. Run  testContinuousPush()
     * 4. Confirm if the replicator stops
     * 5. Enable Wifi
     * 6. Confirm if the replicator starts
     * 7. Confirm if sync gateway receives some messages
     */
    @Test
    @ReplicatorSystemTest
    public void testContinuousPush() throws Exception {
        loadJSONResource("names_100.json");

        timeout = 180; // 3min
        Endpoint target = getRemoteEndpoint(DB_NAME, false);
        ReplicatorConfiguration config = makeConfig(true, false, true, target);
        Replicator repl = run(config, 0, null);
        stopContinuousReplicator(repl);
    }

    @Test
    @ReplicatorSystemTest
    public void testChannelPull() throws CouchbaseLiteException, InterruptedException, URISyntaxException {
        assertEquals(0, otherDB.getCount());
        db.inBatch(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5; i++) {
                    String docID = String.format(Locale.ENGLISH, "doc-%d", i);
                    MutableDocument doc = new MutableDocument(docID);
                    doc.setValue("foo", "var");
                    try {
                        db.save(doc);
                    }
                    catch (CouchbaseLiteException e) {
                        fail();
                    }
                }
                for (int i = 0; i < 10; i++) {
                    String docID = String.format(Locale.ENGLISH, "doc-%d", i + 5);
                    MutableDocument doc = new MutableDocument(docID);
                    doc.setValue("channels", "my_channel");
                    try {
                        db.save(doc);
                    }
                    catch (CouchbaseLiteException e) {
                        fail();
                    }
                }
            }
        });

        Endpoint target = getRemoteEndpoint(DB_NAME, false);
        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);

        config = makeConfig(false, true, false, otherDB, target);
        config.setChannels(Arrays.asList("my_channel"));
        run(config, 0, null);
        assertEquals(10, otherDB.getCount());
    }

    /**
     * Push and Pull replication against Sync Gateway with Document which has attachment.
     * https://github.com/couchbase/couchbase-lite-core/issues/354
     */
    @Test
    @ReplicatorSystemTest
    public void testPushToRemoteDBWithAttachment() throws Exception {
        // store doc with attachment into db.
        {
            // 2.39MB image -> causes `Compression buffer overflow`
            //InputStream is = getAsset("image.jpg");
            // 507KB image -> works fine.
            InputStream is = getAsset("attachment.png");
            try {
                Blob blob = new Blob("image/jpg", is);
                MutableDocument doc1 = new MutableDocument("doc1");
                doc1.setValue("name", "Tiger");
                doc1.setBlob("image.jpg", blob);
                save(doc1);
            }
            finally {
                is.close();
            }
            assertEquals(1, db.getCount());
        }

        // target SG URI
        Endpoint target = getRemoteEndpoint(DB_NAME, false);

        // Push replicate from db to SG
        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);

        // Pull replicate from SG to otherDB.
        config = makeConfig(false, true, false, this.otherDB, target);
        run(config, 0, null);
        assertEquals(1, this.otherDB.getCount());
        Document doc = otherDB.getDocument("doc1");
        assertNotNull(doc);
        Blob blob = doc.getBlob("image.jpg");
        assertNotNull(blob);
    }

    @Test
    @Ignore("This test never stops!")
    public void testContinuousPushNeverending() throws URISyntaxException {
        // NOTE: This test never stops even after the replication goes idle.
        // It can be used to test the response to connectivity issues like killing the remote server.

        // target SG URI
        Endpoint target = getRemoteEndpoint(DB_NAME, false);

        // Push replicate from db to SG
        ReplicatorConfiguration config = makeConfig(true, false, true, target);
        final Replicator repl = run(config, 0, null);
        repl.addChangeListener(executor, new ReplicatorChangeListener() {
            @Override
            public void changed(ReplicatorChange change) {
                Report.log(LogLevel.INFO, "changed() change -> " + change);
            }
        });

        try { Thread.sleep(3 * 60 * 1000); }
        catch (InterruptedException ignore) { }
    }

    // https://github.com/couchbase/couchbase-lite-android/issues/1545
    @Test
    @ReplicatorSystemTest
    public void testPushDocAndDocChangeListener()
        throws CouchbaseLiteException, URISyntaxException, InterruptedException {

        String docID = "doc1";

        // 1. save new Document
        MutableDocument mDoc = new MutableDocument(docID);
        Document doc = save(mDoc);

        // 2. Set document change listner
        final CountDownLatch latch1 = new CountDownLatch(1);
        DocumentChangeListener listener1 = new DocumentChangeListener() {
            @Override
            public void changed(DocumentChange change) {
                assertNotNull(change);
                latch1.countDown();
            }
        };
        ListenerToken token1 = db.addDocumentChangeListener(docID, listener1);

        // 3. Setup Push&Pull continuous replicator
        timeout = 180; // 3min
        Endpoint target = getRemoteEndpoint(DB_NAME, false);
        ReplicatorConfiguration config = makeConfig(true, true, true, target);
        Replicator repl = new Replicator(config);

        // 4. Set replicator change listener to detect replicator IDLE state.
        final CountDownLatch latch2 = new CountDownLatch(1); // for before update doc
        final CountDownLatch latch3 = new CountDownLatch(2); // for after update doc
        ReplicatorChangeListener listener2 = new ReplicatorChangeListener() {
            @Override
            public void changed(ReplicatorChange change) {
                Replicator.Status status = change.getStatus();
                if (status.getActivityLevel() == Replicator.ActivityLevel.IDLE) {
                    latch2.countDown();
                    latch3.countDown();
                }
            }
        };
        ListenerToken token2 = repl.addChangeListener(executor, listener2);

        // 5. Start Replicator
        repl.start();

        // 6. Wait replicator becomes IDLE state
        assertTrue(latch2.await(10, TimeUnit.SECONDS));

        // 7. Update document
        mDoc = doc.toMutable();
        mDoc.setString("hello", "world");
        save(mDoc);

        // 8. Wait replicator becomes IDLE state
        assertTrue(latch3.await(10, TimeUnit.SECONDS));

        // 9. Stop replicator
        repl.removeChangeListener(token2);
        stopContinuousReplicator(repl);
        db.removeChangeListener(token1);

        // 10. Pull replicate from SG to otherDB. And verify the document
        config = makeConfig(false, true, false, this.otherDB, target);
        run(config, 0, null);
        assertEquals(1, this.otherDB.getCount());
        doc = otherDB.getDocument(docID);
        assertNotNull(doc);
        assertEquals("world", doc.getString("hello"));
    }

    @Test
    @ReplicatorSystemTest
    public void testPullReplicateMultipleDocs() throws IOException, URISyntaxException {
        // create multiple documents on sync gateway
        final int N = 10;
        for (int i = 1; i <= N; i++) {
            String docID = String.format(Locale.ENGLISH, "doc%d", i);
            String jsonBody = String.format(Locale.ENGLISH, "{\"type\":\"text\",\"idx\":%d}", i);
            assertTrue(remotePutDb(DB_NAME, docID, jsonBody));
        }

        db.addChangeListener(new DatabaseChangeListener() {
            @Override
            public void changed(DatabaseChange change) {
                // check getDocumentIDs values
                if (change.getDocumentIDs() != null) { assertEquals(N, change.getDocumentIDs().size()); }


                // check query result
                Query q = QueryBuilder.select(SelectResult.expression(Meta.id))
                    .from(DataSource.database(db))
                    .where(Expression.property("type").equalTo(Expression.string("text")));
                try {
                    ResultSet rs = q.execute();
                    List<Result> results = rs.allResults();
                    assertEquals(N, results.size());
                }
                catch (CouchbaseLiteException e) {
                    fail("Error in Query.execute(): " + e.getMessage());
                }
            }
        });

        // target SG URI
        Endpoint target = getRemoteEndpoint(DB_NAME, false);

        // Pull replicate from SG to otherDB.
        ReplicatorConfiguration config = makeConfig(false, true, false, this.db, target);
        run(config, 0, null);
        assertEquals(N, this.db.getCount());
        for (int i = 1; i <= N; i++) {
            String docID = String.format(Locale.ENGLISH, "doc%d", i);
            Document doc = db.getDocument(docID);
            assertNotNull(doc);
            assertEquals(i, doc.getInt("idx"));
        }
    }

    @Test
    @ReplicatorSystemTest
    public void testPullConflictDeleteWins_SG() throws Exception {
        URLEndpoint target = getRemoteEndpoint(DB_NAME, false);

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setValue("species", "Tiger");
        db.save(doc1);

        // push to SG

        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);

        // Get doc form SG:
        JSONObject json = sendRequestToEndpoint(target, "GET", doc1.getId(), null, null);
        Report.log(LogLevel.INFO, "----> Common ancestor revision is " + json.get("_rev"));

        // Update doc on SG:
        JSONObject copy = new JSONObject(json.toString());
        copy.put("species", "Cat");
        json = sendRequestToEndpoint(target, "PUT", doc1.getId(), "application/json", copy.toString().getBytes());
        Report.log(LogLevel.INFO, "json -> " + json.toString());
        Report.log(LogLevel.INFO, "----> Conflicting server revision is " + json.get("rev"));

        // Delete local doc:
        db.delete(doc1);
        assertNull(db.getDocument(doc1.getId()));

        // Start pull replicator:
        Report.log(LogLevel.INFO, "-------- Starting pull replication to pick up conflict --------");
        config = makeConfig(false, true, false, target);
        run(config, 0, null);

        // Verify local doc should be null
        assertNull(db.getDocument(doc1.getId()));
    }

    // https://github.com/couchbase/couchbase-lite-android/issues/1674
    // SG does not support document ID filter with continuous mode
    // Currently CBL ignore the error wight setting  code 26 (Unknown error).
    @Test
    @ReplicatorSystemTest
    public void testDocIDFilterSG() throws Exception {
        URLEndpoint target = getRemoteEndpoint(DB_NAME, false);

        // Document 1
        String docID1 = "doc1";
        String body1 = "{\"index\":1}";
        JSONObject json1 = sendRequestToEndpoint(target, "PUT", docID1, "application/json", body1.getBytes());
        assertNotNull(json1);
        assertTrue(json1.getBoolean("ok"));
        assertEquals(docID1, json1.getString("id"));

        // Document 2
        String docID2 = "doc2";
        String body2 = "{\"index\":2}";
        JSONObject json2 = sendRequestToEndpoint(target, "PUT", docID2, "application/json", body2.getBytes());
        assertNotNull(json2);
        assertTrue(json2.getBoolean("ok"));
        assertEquals(docID2, json2.getString("id"));

        ReplicatorConfiguration config = makeConfig(true, true, true, target);
        config.setDocumentIDs(Arrays.asList(docID1));
        Replicator repl = run(config, 26, null);
        stopContinuousReplicator(repl);
    }

    // https://github.com/couchbase/couchbase-lite-core/issues/447
    @Test
    @ReplicatorSystemTest
    public void testResetCheckpoint() throws CouchbaseLiteException, InterruptedException, URISyntaxException {
        URLEndpoint target = getRemoteEndpoint(DB_NAME, false);

        MutableDocument doc1 = new MutableDocument("doc1");
        doc1.setString("species", "Tiger");
        doc1.setString("name", "Hobbes");
        db.save(doc1);

        MutableDocument doc2 = new MutableDocument("doc2");
        doc2.setString("species", "Tiger");
        doc2.setString("pattern", "striped");
        db.save(doc2);

        // push
        ReplicatorConfiguration config = makeConfig(true, false, false, target);
        run(config, 0, null);

        // pull
        config = makeConfig(false, true, false, target);
        run(config, 0, null);

        assertEquals(2L, db.getCount());

        Document doc = db.getDocument("doc1");
        db.purge(doc);

        doc = db.getDocument("doc2");
        db.purge(doc);

        // "because the documents were purged"
        assertEquals(0L, db.getCount());
        run(config, 0, null);

        // "because the documents were purged and the replicator is already past them"
        assertEquals(0L, db.getCount());
        run(config, 0, null, false, true, null);

        // "because the replicator was reset"
        assertEquals(2L, db.getCount());
    }

    private JSONObject sendRequestToEndpoint(
        URLEndpoint endpoint,
        String method,
        String path,
        String mediaType,
        byte[] body)
        throws Exception {
        URI endpointURI = endpoint.getURL();

        String _scheme = endpointURI.getScheme().equals(URLEndpoint.SCHEME_TLS) ? "https" : "http";
        String _host = endpointURI.getHost();
        int _port = endpointURI.getPort() + 1;
        path = (path != null) ? (path.startsWith("/") ? path : "/" + path) : "";
        String _path = String.format(Locale.ENGLISH, "%s%s", endpointURI.getPath(), path);
        URI uri = new URI(_scheme, null, _host, _port, _path, null, null);

        OkHttpClient client = new OkHttpClient();
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(uri.toURL());

        RequestBody requestBody = null;
        if (body instanceof byte[]) { requestBody = RequestBody.create(MediaType.parse(mediaType), body); }
        builder.method(method, requestBody);
        okhttp3.Request request = builder.build();
        Response response = client.newCall(request).execute();
        if (response.isSuccessful()) {
            Report.log(LogLevel.INFO,
                "Send request succeeded; URL=" + uri + " Method=" + method + " Status=" + response.code());
            return new JSONObject(response.body().string());
        }
        else {
            Report.log(LogLevel.ERROR,
                "Failed to send request; URL=" + uri + " Method=" + method + " Status=" + response.code());
            return null;
        }
    }

    private boolean remotePutDb(String db) throws IOException {
        OkHttpClient client = new OkHttpClient();
        String url = String.format(Locale.ENGLISH, "http://%s:4985/%s/", remoteHost, db);
        RequestBody body = RequestBody.create(
            JSON,
            "{\"server\": \"walrus:\", \"users\": { \"GUEST\": { \"disabled\": false, \"admin_channels\": [\"*\"] } "
                + "}, \"unsupported\": {\"replicator_2\":true}}");
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(url)
            .put(body)
            .build();
        Response response = client.newCall(request).execute();
        return response.code() >= 200 && response.code() < 300;
    }

    private boolean remoteDeleteDb(String db) throws IOException {
        OkHttpClient client = new OkHttpClient();
        String url = String.format(Locale.ENGLISH, "http://%s:4985/%s/", remoteHost, db);
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(url)
            .delete()
            .build();
        Response response = client.newCall(request).execute();
        return response.code() >= 200 && response.code() < 300;
    }

    private boolean remotePutDb(String db, String docID, String jsonBody) throws IOException {
        OkHttpClient client = new OkHttpClient();
        String url = String.format(Locale.ENGLISH, "http://%s:4984/%s/%s", remoteHost, db, docID);
        RequestBody body = RequestBody.create(JSON, jsonBody);
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(url)
            .put(body)
            .build();
        Response response = client.newCall(request).execute();
        return response.code() >= 200 && response.code() < 300;
    }
}
