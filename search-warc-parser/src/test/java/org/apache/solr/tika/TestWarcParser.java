/*

 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.solr.tika;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.solr.SolrJettyTestBase;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.tika.metadata.Metadata;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class TestWarcParser extends SolrJettyTestBase {

  private SolrIndexer indexer;

  private static final boolean TEST_WITH_EMBEDDED_SOLR_SERVER = false;
  private static final String EXTERNAL_SOLR_SERVER_URL = System.getProperty("externalSolrServer");
  private static final String RESOURCES_DIR = "target/test-classes";
  private static final AtomicInteger SEQ_NUM = new AtomicInteger();
  private static final Logger LOGGER = LoggerFactory.getLogger(TestTikaIndexer.class);
  private static final String sampleWarcFile = "/IAH-20080430204825-00000-blackbook.warc.gz";

  @BeforeClass
  public static void beforeClass() throws Exception {
    initCore(
        RESOURCES_DIR + "/solr/collection1/conf/solrconfig.xml",
        RESOURCES_DIR + "/solr/collection1/conf/schema.xml",
        RESOURCES_DIR + "/solr"
        );
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    final Map<String, String> context = new HashMap();
    context.put(TikaIndexer.TIKA_CONFIG_LOCATION, RESOURCES_DIR + "/tika-config.xml");
    context.put(SolrInspector.SOLR_COLLECTION_LIST + ".testcoll." + SolrInspector.SOLR_CLIENT_HOME, RESOURCES_DIR + "/solr/collection1");
    // tell the TikaIndexer to pass a  GZIPInputStream to tika.  This is temporary until CDH-10671 is addressed.
    context.put("tika.autoGUNZIP", "true");
    
    final SolrServer solrServer;
    if (EXTERNAL_SOLR_SERVER_URL != null) {
      solrServer = new SafeConcurrentUpdateSolrServer(EXTERNAL_SOLR_SERVER_URL, 2, 2);
    } else {
      if (TEST_WITH_EMBEDDED_SOLR_SERVER) {
        solrServer = new TestEmbeddedSolrServer(h.getCoreContainer(), "");
      } else {
        solrServer = new TestSolrServer(getSolrServer());
      }
    }

    List<DocumentLoader> testServers = Collections.singletonList((DocumentLoader) new SolrServerDocumentLoader(solrServer));
    Config config = ConfigFactory.parseMap(context);
    indexer = new TikaIndexer(new SolrInspector().createSolrCollections(config, testServers), config);
    
    deleteAllDocuments();
  }
  
  private void deleteAllDocuments() throws SolrServerException, IOException {
    for (SolrCollection collection : indexer.getSolrCollections().values()) {
      SolrServer s = ((SolrServerDocumentLoader)collection.getDocumentLoader()).getSolrServer();
      s.deleteByQuery("*:*"); // delete everything!
      s.commit();
    }
  }

  @After
  @Override
  public void tearDown() throws Exception {
    try {
      if (indexer != null) {
        indexer.stop();
        indexer = null;
      }
    } finally {
      super.tearDown();
    }
  }
  
  /**
   * Returns a HashMap of expectedKey -> expectedValue.
   * File format is required to alternate lines of keys and values.
   *
   * @param file
   * @throws IOException
   */
  private HashMap<String, ExpectedResult> getExpectedOutput(String file) throws IOException{
    HashMap<String, ExpectedResult> map = new HashMap<String, ExpectedResult>();
    List<String> lines = Files.readLines(new File(file), Charsets.UTF_8);
    Iterator<String> it = lines.iterator();
    String contains = "#contains";
    while (it.hasNext()) {
      String key = it.next();
      if (!it.hasNext()) {
        throw new IOException("Unexpected file format for " + file
          + ".  Expected alternativing key/value lines");
      }
      String value = it.next();
      ExpectedResult.CompareType compareType = ExpectedResult.CompareType.equals;
      if (key.endsWith(contains)) {
        key = key.substring(0, key.length() - contains.length());
        compareType = ExpectedResult.CompareType.contains;
      }
      HashSet<String> values = null;
      ExpectedResult existing = map.get(key);
      if (existing != null) {
        values = existing.getFieldValues();
        values.add(value);
      }
      else {
        values = new HashSet<String>();
      }
      values.add(value);
      map.put(key, new ExpectedResult(values, compareType));
    }
    return map;
  }

  /**
   * Test that Solr queries on a parsed warc document
   * return the expected content and fields.
   */
  @Test
  public void testWARCFileContent() throws Exception {
    String path = RESOURCES_DIR + "/test-documents";
    String testFilePrefix = "sample_html";
    String testFile = testFilePrefix + ".warc.gz";
    String expectedFile = testFilePrefix + ".gold";
    String[] files = new String[] {
      path + "/" + testFile
    };
    testDocumentTypesInternal(files);
    HashMap<String, ExpectedResult> expectedResultMap = getExpectedOutput(path + "/" + expectedFile);
    testDocumentContent(expectedResultMap);
  }

  /**
   * Test that a multi document warc file is parsed
   * into the correct number of documents.
   */
  @Test
  public void testWARCFileMultiDocCount() throws Exception {
    String path = RESOURCES_DIR + "/test-documents";
    String[] files = new String[] {
      path + sampleWarcFile
    };
    testDocumentTypesInternal(files);
  }

  /**
   * Test that Solr queries on a parsed multi-doc warc document
   * return the expected content and fields.
   */
  @Test
  public void testWARCFileMultiDocContent() throws Exception {
    String path = RESOURCES_DIR + "/test-documents";
    String[] files = new String[] {
      path + sampleWarcFile
    };
    testDocumentTypesInternal(files);
    String testFileSuffix = ".warc.gz";
    String expectedFileSuffix = ".gold";
    String expectedFile = sampleWarcFile.substring(0, sampleWarcFile.length() - testFileSuffix.length()) + expectedFileSuffix;
    HashMap<String, ExpectedResult> expectedResultMap = getExpectedOutput(path + "/" + expectedFile);
    testDocumentContent(expectedResultMap);
  }

  private void testDocumentContent(HashMap<String, ExpectedResult> expectedResultMap) throws Exception {
    SolrCollection collection = indexer.getSolrCollections().values().iterator().next();
    QueryResponse rsp = ((SolrServerDocumentLoader)collection.getDocumentLoader()).getSolrServer().query(new SolrQuery("*:*").setRows(Integer.MAX_VALUE));
    // Check that every expected field/values shows up in the actual query
    for (Entry<String, ExpectedResult> current : expectedResultMap.entrySet()) {
      String field = current.getKey();
      for (String expectedFieldValue : current.getValue().getFieldValues()) {
        ExpectedResult.CompareType compareType = current.getValue().getCompareType();
        boolean foundField = false;

        for (SolrDocument doc : rsp.getResults()) {
          Collection<Object> actualFieldValues = doc.getFieldValues(field);
          if (compareType == ExpectedResult.CompareType.equals) {
            if (actualFieldValues != null && actualFieldValues.contains(expectedFieldValue)) {
              foundField = true;
              break;
            }
          }
          else {
            for (Iterator<Object> it = actualFieldValues.iterator(); it.hasNext(); ) {
              String actualValue = it.next().toString();  // test only supports string comparison
              if (actualFieldValues != null && actualValue.contains(expectedFieldValue)) {
                foundField = true;
                break;
              }
            }
          }
        }
        assert(foundField); // didn't find expected field/value in query
      }
    }
  }

  private void testDocumentTypesInternal(String[] files) throws Exception {
    int numDocs = 0;
    long startTime = System.currentTimeMillis();
    
    assertEquals(numDocs, queryResultSetSize("*:*"));
    for (int i = 0; i < 1; i++) {
      String path = RESOURCES_DIR + "/test-documents";
      Map<String,Integer> numRecords = new HashMap();
      numRecords.put(path + sampleWarcFile, 140);
      
      for (String file : files) {
        File f = new File(file);
        byte[] body = FileUtils.readFileToByteArray(f);
        StreamEvent event = new StreamEvent(new ByteArrayInputStream(body), new HashMap());
        event.getHeaders().put(Metadata.RESOURCE_NAME_KEY, f.getName());
        load(event);
        Integer count = numRecords.get(file);
        if (count != null) {
          numDocs += count;
        } else {
          numDocs++;
        }
        assertEquals(numDocs, queryResultSetSize("*:*"));
      }
      LOGGER.trace("iter: {}", i);
    }
    LOGGER.trace("all done with put at {}", System.currentTimeMillis() - startTime);
    assertEquals(numDocs, queryResultSetSize("*:*"));
    LOGGER.trace("indexer: ", indexer);
  }

  private void load(StreamEvent event) throws IOException, SolrServerException {
    event = new StreamEvent(event.getBody(), new HashMap(event.getHeaders()));
    event.getHeaders().put("id", "" + SEQ_NUM.getAndIncrement());
    indexer.process(event);
  }

  private void commit() throws SolrServerException, IOException {
    for (SolrCollection collection : indexer.getSolrCollections().values()) {
      ((SolrServerDocumentLoader)collection.getDocumentLoader()).getSolrServer().commit(false, true, true);
    }
  }
  
  private int queryResultSetSize(String query) throws SolrServerException, IOException {
    commit();
    int size = 0;
    for (SolrCollection collection : indexer.getSolrCollections().values()) {
      QueryResponse rsp = ((SolrServerDocumentLoader)collection.getDocumentLoader()).getSolrServer().query(new SolrQuery(query).setRows(Integer.MAX_VALUE));
      size += rsp.getResults().size();
    }
    return size;
  }

  /**
   * Representation of the expected output of a SolrQuery.
   */
  private static class ExpectedResult {
    private HashSet<String> fieldValues;
    private enum CompareType {
      equals,    // Compare with equals, i.e. actual.equals(expected)
      contains;  // Compare with contains, i.e. actual.contains(expected)
    }
    private CompareType compareType;

    public ExpectedResult(HashSet<String> fieldValues, CompareType compareType) {
      this.fieldValues = fieldValues;
      this.compareType = compareType;
    }
    public HashSet<String> getFieldValues() { return fieldValues; }
    public CompareType getCompareType() { return compareType; }
  }
}
