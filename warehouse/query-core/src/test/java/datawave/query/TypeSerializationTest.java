package datawave.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.collections4.iterators.TransformIterator;
import org.apache.log4j.Logger;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.common.collect.Sets;

import datawave.configuration.spring.SpringBean;
import datawave.helpers.PrintUtility;
import datawave.ingest.data.TypeRegistry;
import datawave.query.function.deserializer.KryoDocumentDeserializer;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.tables.ShardQueryLogic;
import datawave.query.tables.edge.DefaultEdgeEventQueryLogic;
import datawave.query.transformer.DocumentTransformer;
import datawave.query.util.WiseGuysIngest;
import datawave.util.TableName;
import datawave.webservice.edgedictionary.RemoteEdgeDictionary;
import datawave.webservice.query.QueryImpl;
import datawave.webservice.query.configuration.GenericQueryConfiguration;
import datawave.webservice.query.iterator.DatawaveTransformIterator;
import datawave.webservice.query.result.event.DefaultEvent;
import datawave.webservice.query.result.event.DefaultField;
import datawave.webservice.query.result.event.EventBase;
import datawave.webservice.result.BaseQueryResponse;
import datawave.webservice.result.DefaultEventQueryResponse;

/**
 * Asserts correctness of returned webservice events with and without type metadata reduction
 */
public abstract class TypeSerializationTest {

    private static final Logger log = Logger.getLogger(TypeSerializationTest.class);
    private static final Authorizations auths = new Authorizations("ALL");

    @RunWith(Arquillian.class)
    public static class ShardRangeTest extends TypeSerializationTest {
        protected static AccumuloClient client = null;

        @BeforeClass
        public static void setUp() throws Exception {

            // testing tear downs but without consistency, because when we tear it down then we loose the ongoing bloom filter and subsequently the rebuild will
            // start returning
            // different keys.
            QueryTestTableHelper qtth = new QueryTestTableHelper(ShardRangeTest.class.toString(), log,
                            RebuildingScannerTestHelper.TEARDOWN.EVERY_OTHER_SANS_CONSISTENCY, RebuildingScannerTestHelper.INTERRUPT.EVERY_OTHER);
            client = qtth.client;

            WiseGuysIngest.writeItAll(client, WiseGuysIngest.WhatKindaRange.SHARD);
            PrintUtility.printTable(client, auths, TableName.SHARD);
            PrintUtility.printTable(client, auths, TableName.SHARD_INDEX);
            PrintUtility.printTable(client, auths, QueryTestTableHelper.MODEL_TABLE_NAME);
        }

        @Override
        protected void runQuery(String query, Map<String,String> parameters, Set<String> expected) throws Exception {
            super.runQuery(query, parameters, expected, false, client);
        }

        @Override
        protected void runQuery(String query, Map<String,String> parameters, Set<String> expected, boolean reduceTypeMetadata) throws Exception {
            super.runQuery(query, parameters, expected, reduceTypeMetadata, client);
        }
    }

    @RunWith(Arquillian.class)
    public static class DocumentRangeTest extends TypeSerializationTest {
        protected static AccumuloClient client = null;

        @BeforeClass
        public static void setUp() throws Exception {

            // testing tear downs but without consistency, because when we tear it down then we loose the ongoing bloom filter and subsequently the rebuild will
            // start returning
            // different keys.
            QueryTestTableHelper qtth = new QueryTestTableHelper(DocumentRangeTest.class.toString(), log,
                            RebuildingScannerTestHelper.TEARDOWN.EVERY_OTHER_SANS_CONSISTENCY, RebuildingScannerTestHelper.INTERRUPT.EVERY_OTHER);
            client = qtth.client;

            WiseGuysIngest.writeItAll(client, WiseGuysIngest.WhatKindaRange.DOCUMENT);
            PrintUtility.printTable(client, auths, TableName.SHARD);
            PrintUtility.printTable(client, auths, TableName.SHARD_INDEX);
            PrintUtility.printTable(client, auths, QueryTestTableHelper.MODEL_TABLE_NAME);
        }

        @Override
        protected void runQuery(String query, Map<String,String> parameters, Set<String> expected) throws Exception {
            super.runQuery(query, parameters, expected, false, client);
        }

        @Override
        protected void runQuery(String query, Map<String,String> parameters, Set<String> expected, boolean reduceTypeMetadata) throws Exception {
            super.runQuery(query, parameters, expected, reduceTypeMetadata, client);
        }
    }

    protected Set<Authorizations> authSet = Collections.singleton(auths);

    @Inject
    @SpringBean(name = "EventQuery")
    protected ShardQueryLogic logic;

    protected KryoDocumentDeserializer deserializer;

    private final DateFormat format = new SimpleDateFormat("yyyyMMdd");

    @Deployment
    public static JavaArchive createDeployment() throws Exception {

        return ShrinkWrap.create(JavaArchive.class)
                        .addPackages(true, "org.apache.deltaspike", "io.astefanutti.metrics.cdi", "datawave.query", "org.jboss.logging",
                                        "datawave.webservice.query.result.event")
                        .deleteClass(DefaultEdgeEventQueryLogic.class).deleteClass(RemoteEdgeDictionary.class)
                        .deleteClass(datawave.query.metrics.QueryMetricQueryLogic.class).deleteClass(datawave.query.metrics.ShardTableQueryMetricHandler.class)
                        .addAsManifestResource(new StringAsset(
                                        "<alternatives>" + "<stereotype>datawave.query.tables.edge.MockAlternative</stereotype>" + "</alternatives>"),
                                        "beans.xml");
    }

    @AfterClass
    public static void teardown() {
        TypeRegistry.reset();
    }

    @Before
    public void setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT"));

        logic.setFullTableScanEnabled(true);
        logic.setQueryExecutionForPageTimeout(300000000000000L);
        deserializer = new KryoDocumentDeserializer();
    }

    @After
    public void after() {
        logic.setReduceTypeMetadata(false);
        logic.setReduceTypeMetadataPerShard(false);
    }

    protected abstract void runQuery(String query, Map<String,String> parameters, Set<String> expected) throws Exception;

    protected abstract void runQuery(String query, Map<String,String> parameters, Set<String> expected, boolean reduceTypeMetadata) throws Exception;

    protected void runQuery(String query, Map<String,String> parameters, Set<String> expected, boolean reduceTypeMetadata, AccumuloClient client)
                    throws Exception {
        log.debug("run query");

        Date startDate = format.parse("20091231");
        Date endDate = format.parse("20150101");

        QueryImpl settings = new QueryImpl();
        settings.setBeginDate(startDate);
        settings.setEndDate(endDate);
        settings.setPagesize(Integer.MAX_VALUE);
        settings.setQueryAuthorizations(auths.serialize());
        settings.setQuery(query);
        settings.setParameters(parameters);
        settings.setId(UUID.randomUUID());

        log.debug("query: " + settings.getQuery());
        log.debug("logic: " + settings.getQueryLogicName());

        logic.setReduceTypeMetadata(reduceTypeMetadata);

        GenericQueryConfiguration config = logic.initialize(client, settings, authSet);
        logic.setupQuery(config);

        DocumentTransformer transformer = (DocumentTransformer) (logic.getTransformer(settings));
        TransformIterator iter = new DatawaveTransformIterator(logic.iterator(), transformer);
        List<Object> eventList = new ArrayList<>();
        while (iter.hasNext()) {
            eventList.add(iter.next());
        }

        BaseQueryResponse response = transformer.createResponse(eventList);

        // un-comment to look at the json output
        // ObjectMapper mapper = new ObjectMapper();
        // mapper.enable(MapperFeature.USE_WRAPPER_NAME_AS_PROPERTY_NAME);
        // mapper.writeValue(new File("/tmp/grouped2.json"), response);

        assertTrue(response instanceof DefaultEventQueryResponse);
        DefaultEventQueryResponse eventQueryResponse = (DefaultEventQueryResponse) response;

        Set<String> foundIds = new HashSet<>();
        Set<String> unexpectedIds = new HashSet<>();
        for (EventBase event : eventQueryResponse.getEvents()) {
            String internalId = event.getMetadata().getInternalId();
            if (expected.contains(internalId)) {
                foundIds.add(internalId);
            } else {
                unexpectedIds.add(internalId);
            }
            assertEvent(event);
        }

        if (!unexpectedIds.isEmpty()) {
            log.error("Found unexpected ids: " + unexpectedIds);
        }

        Set<String> idsNotFound = Sets.difference(expected, foundIds);
        if (!idsNotFound.isEmpty()) {
            fail("Expected ids not found: " + idsNotFound);
        }

        assertEquals(expected, foundIds);
    }

    @SuppressWarnings("rawtypes")
    protected void assertEvent(EventBase base) {
        if (!(base instanceof DefaultEvent)) {
            fail("EventBase was not of type DefaultEvent");
        }

        DefaultEvent event = (DefaultEvent) base;
        for (DefaultField field : event.getFields()) {
            assertField(field);
        }
    }

    protected void assertField(DefaultField field) {
        String name = JexlASTHelper.deconstructIdentifier(field.getName());
        switch (name) {
            case "GEN":
            case "GEO":
            case "NAM":
            case "NUMBER":
            case "ONE_NULL":
            case "RECORD_ID":
            case "QUOTE":
                assertEquals("xs:string", field.getTypedValue().getType());
                break;
            case "BIRTH_DATE":
            case "DEATH_DATE":
                assertEquals("xs:dateTime", field.getTypedValue().getType());
                break;
            case "AG":
            case "MAGIC":
                assertEquals("xs:decimal", field.getTypedValue().getType());
                break;
            default:
                log.info(field.getName() + "  " + field.getTypedValue().getType());
                fail("unhandled field " + name + " of type " + field.getTypedValue().getType());
        }
    }

    @Test
    public void testSopranoAllTypes() throws Exception {
        String queryString = "UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");

        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.sopranoUID);

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testSopranoDateTypes() throws Exception {
        String queryString = "UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "BIRTH_DATE,DEATH_DATE");

        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.sopranoUID);

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testSopranoDecimalTypes() throws Exception {
        String queryString = "UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "AG,MAGIC");

        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.sopranoUID);

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testSopranoDecimalTypesWithModel() throws Exception {
        // relevant model info
        // fwd: AG = [AGE, ETA]
        // rev: AGE = AG
        // rev: ETA = AG
        String queryString = "UUID == 'soprano' && AGE == 16";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "AGE");

        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.sopranoUID);

        // query field AGE and return field AGE
        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);

        // query field AGE and return field AG
        queryString = "UUID == 'soprano' && AGE == 16";
        extraParameters.put("return.fields", "AG");
        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);

        // query field AG and return field AGE
        queryString = "UUID == 'soprano' && AG == 16";
        extraParameters.put("return.fields", "AGE");
        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);

        // query field AG and return field AG
        queryString = "UUID == 'soprano' && AG == 16";
        extraParameters.put("return.fields", "AG");
        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testSopranoStringTypes() throws Exception {
        String queryString = "UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "GEN,GEO,NAM,ONE_NULL,RECORD_ID,QUOTE");

        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.sopranoUID);

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testAllUidsAllTypes() throws Exception {
        String queryString = "UUID == 'capone' || UUID == 'corleone' || UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "BIRTH_DATE,DEATH_DATE");

        Set<String> expected = expectAll();

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testAllUidsDateTypes() throws Exception {
        String queryString = "UUID == 'capone' || UUID == 'corleone' || UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");

        Set<String> expected = expectAll();

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testAllUidsDecimalTypes() throws Exception {
        String queryString = "UUID == 'capone' || UUID == 'corleone' || UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "AG,MAGIC");

        Set<String> expected = expectAll();

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    @Test
    public void testAllUidsStringTypes() throws Exception {
        String queryString = "UUID == 'capone' || UUID == 'corleone' || UUID == 'soprano'";

        Map<String,String> extraParameters = new HashMap<>();
        extraParameters.put("include.grouping.context", "true");
        extraParameters.put("return.fields", "GEN,GEO,NAM,ONE_NULL,RECORD_ID,QUOTE");

        Set<String> expected = expectAll();

        runQuery(queryString, extraParameters, expected);
        runQuery(queryString, extraParameters, expected, true);
    }

    private Set<String> expectAll() {
        Set<String> expected = new HashSet<>();
        expected.add(WiseGuysIngest.caponeUID);
        expected.add(WiseGuysIngest.corleoneUID);
        expected.add(WiseGuysIngest.sopranoUID);
        return expected;
    }
}
