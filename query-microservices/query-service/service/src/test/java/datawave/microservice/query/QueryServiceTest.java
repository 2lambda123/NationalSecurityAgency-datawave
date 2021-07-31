package datawave.microservice.query;

import com.google.common.collect.Iterables;
import datawave.marking.ColumnVisibilitySecurityMarking;
import datawave.microservice.audit.AuditClient;
import datawave.microservice.authorization.jwt.JWTRestTemplate;
import datawave.microservice.authorization.service.RemoteAuthorizationServiceUserDetailsService;
import datawave.microservice.authorization.user.ProxiedUserDetails;
import datawave.microservice.query.config.QueryProperties;
import datawave.microservice.query.remote.QueryRequest;
import datawave.microservice.query.storage.QueryStatus;
import datawave.microservice.query.storage.QueryStorageCache;
import datawave.microservice.query.storage.Result;
import datawave.microservice.query.storage.TaskKey;
import datawave.microservice.query.storage.TaskStates;
import datawave.microservice.query.storage.queue.TestQueryQueueManager;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.SubjectIssuerDNPair;
import datawave.security.util.ProxiedEntityUtils;
import datawave.webservice.query.Query;
import datawave.webservice.query.exception.QueryExceptionType;
import datawave.webservice.query.result.event.DefaultEvent;
import datawave.webservice.query.result.event.DefaultField;
import datawave.webservice.query.result.logic.QueryLogicDescription;
import datawave.webservice.result.BaseResponse;
import datawave.webservice.result.DefaultEventQueryResponse;
import datawave.webservice.result.GenericResponse;
import datawave.webservice.result.QueryImplListResponse;
import datawave.webservice.result.QueryLogicResponse;
import datawave.webservice.result.VoidResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.bus.event.RemoteQueryRequestEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static datawave.microservice.query.QueryParameters.QUERY_LOGIC_NAME;
import static datawave.microservice.query.QueryParameters.QUERY_MAX_CONCURRENT_TASKS;
import static datawave.microservice.query.QueryParameters.QUERY_MAX_RESULTS_OVERRIDE;
import static datawave.microservice.query.QueryParameters.QUERY_NAME;
import static datawave.microservice.query.QueryParameters.QUERY_PAGESIZE;
import static datawave.security.authorization.DatawaveUser.UserType.USER;
import static datawave.webservice.common.audit.AuditParameters.AUDIT_ID;
import static datawave.webservice.common.audit.AuditParameters.QUERY_AUTHORIZATIONS;
import static datawave.webservice.common.audit.AuditParameters.QUERY_STRING;
import static datawave.webservice.query.QueryImpl.BEGIN_DATE;
import static datawave.webservice.query.QueryImpl.END_DATE;
import static datawave.webservice.query.QueryImpl.PAGESIZE;
import static datawave.webservice.query.QueryImpl.QUERY;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"QueryServiceTest", RemoteAuthorizationServiceUserDetailsService.ACTIVATION_PROFILE})
public class QueryServiceTest {
    private static final String EXPECTED_AUDIT_URI = "http://localhost:11111/audit/v1/audit";
    private static final String TEST_QUERY_STRING = "FIELD:SOME_VALUE";
    private static final String TEST_QUERY_NAME = "The Greatest Query in the World - Tribute";
    private static final String TEST_QUERY_AUTHORIZATIONS = "ALL";
    private static final String TEST_QUERY_BEGIN = "20000101 000000.000";
    private static final String TEST_QUERY_END = "20500101 000000.000";
    private static final String TEST_VISIBILITY_MARKING = "ALL";
    
    @LocalServerPort
    private int webServicePort;
    
    @Autowired
    private RestTemplateBuilder restTemplateBuilder;
    
    private JWTRestTemplate jwtRestTemplate;
    
    private SubjectIssuerDNPair DN;
    private String userDN = "userDn";
    
    private SubjectIssuerDNPair altDN;
    private String altUserDN = "altUserDN";
    
    @Autowired
    private QueryStorageCache queryStorageCache;
    
    @Autowired
    private TestQueryQueueManager queryQueueManager;
    
    @Autowired
    private AuditClient auditClient;
    
    @Autowired
    private QueryProperties queryProperties;
    
    @Autowired
    private LinkedList<RemoteQueryRequestEvent> queryRequestEvents;
    
    private List<String> auditIds;
    private MockRestServiceServer mockServer;
    
    @Before
    public void setup() {
        auditIds = new ArrayList<>();
        
        jwtRestTemplate = restTemplateBuilder.build(JWTRestTemplate.class);
        jwtRestTemplate.setErrorHandler(new NoOpResponseErrorHandler());
        DN = SubjectIssuerDNPair.of(userDN, "issuerDn");
        altDN = SubjectIssuerDNPair.of(altUserDN, "issuerDN");
        
        RestTemplate auditorRestTemplate = (RestTemplate) new DirectFieldAccessor(auditClient).getPropertyValue("jwtRestTemplate");
        mockServer = MockRestServiceServer.createServer(auditorRestTemplate);
        
        queryRequestEvents.clear();
    }
    
    @DirtiesContext
    @Test
    public void testDefineSuccess() throws ParseException, IOException {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        long currentTimeMillis = System.currentTimeMillis();
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.SUCCESSFUL,
                resp);
        // @formatter:on
        
        // verify that a query id was returned
        String queryId = genericResponse.getResult();
        Assert.assertNotNull(queryId);
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.DEFINED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that they query was created correctly
        Query query = queryStatus.getQuery();
        // @formatter:off
        assertQuery(
                TEST_QUERY_STRING,
                TEST_QUERY_NAME,
                TEST_QUERY_AUTHORIZATIONS,
                TEST_QUERY_BEGIN,
                TEST_QUERY_END,
                TEST_VISIBILITY_MARKING,
                query);
        // @formatter:on
        
        // verify that no audit message was sent
        assertAuditNotSent();
        
        // verify that the results queue wasn't created
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that query tasks weren't created
        assertTasksNotCreated(queryId);
    }
    
    @Test
    public void testDefineFailure_paramValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the query param to induce a parameter validation failure
        map.remove(QUERY_STRING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Missing one or more required QueryParameters",
                "java.lang.IllegalArgumentException: Missing one or more required QueryParameters",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_authValidation() {
        ProxiedUserDetails authUser = createUserDetails(Collections.singleton("AuthorizedUser"), Collections.emptyList());
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "java.lang.IllegalArgumentException: User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_queryLogicValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the beginDate param to induce a query logic validation failure
        map.remove(BEGIN_DATE);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter begin not found",
                "java.lang.IllegalArgumentException: Required parameter begin not found",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxPageSize() {
        ProxiedUserDetails authUser = createUserDetails(Arrays.asList("AuthorizedUser", queryProperties.getPrivilegedRole()), null);
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid page size override
        map.set(QUERY_PAGESIZE, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Page size is larger than configured max. Max = 10,000.",
                "Exception with no cause caught",
                "400-6",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxResultsOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid max results override
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(Long.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max results override value. Max = 369.",
                "Exception with no cause caught",
                "400-43",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxConcurrentTasksOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // add an invalid max results override
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max concurrent tasks override value. Max = 10.",
                "Exception with no cause caught",
                "400-44",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_roleValidation() {
        // create a user without the required role
        ProxiedUserDetails authUser = createUserDetails(Collections.emptyList(), Collections.singletonList("ALL"));
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User does not have the required roles.",
                "datawave.webservice.query.exception.UnauthorizedQueryException: User does not have the required roles.",
                "400-5",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_markingValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the column visibility param to induce a security marking validation failure
        map.remove(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter columnVisibility not found",
                "java.lang.IllegalArgumentException: Required parameter columnVisibility not found",
                "400-4",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @DirtiesContext
    @Test
    public void testCreateSuccess() throws ParseException, IOException {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditSentSetup();
        
        long currentTimeMillis = System.currentTimeMillis();
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                resp);
        // @formatter:on
        
        // verify that a query id was returned
        String queryId = genericResponse.getResult();
        Assert.assertNotNull(queryId);
        
        // verify that the create event was published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that they query was created correctly
        Query query = queryStatus.getQuery();
        
        // @formatter:off
        assertQuery(
                TEST_QUERY_STRING,
                TEST_QUERY_NAME,
                TEST_QUERY_AUTHORIZATIONS,
                TEST_QUERY_BEGIN,
                TEST_QUERY_END,
                TEST_VISIBILITY_MARKING,
                query);
        // @formatter:on
        
        // verify that an audit message was sent and the the audit id matches the query id
        assertAuditSent(queryId);
        
        // verify that the results queue was created
        Assert.assertTrue(queryQueueManager.queueExists(queryId));
        
        // verify that query tasks were created
        assertTasksCreated(queryId);
    }
    
    @Test
    public void testCreateFailure_paramValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the query param to induce a parameter validation failure
        map.remove(QUERY_STRING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Missing one or more required QueryParameters",
                "java.lang.IllegalArgumentException: Missing one or more required QueryParameters",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_authValidation() {
        ProxiedUserDetails authUser = createUserDetails(Collections.singleton("AuthorizedUser"), Collections.emptyList());
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "java.lang.IllegalArgumentException: User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_queryLogicValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the beginDate param to induce a query logic validation failure
        map.remove(BEGIN_DATE);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter begin not found",
                "java.lang.IllegalArgumentException: Required parameter begin not found",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxPageSize() {
        ProxiedUserDetails authUser = createUserDetails(Arrays.asList("AuthorizedUser", queryProperties.getPrivilegedRole()), null);
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid page size override
        map.set(QUERY_PAGESIZE, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Page size is larger than configured max. Max = 10,000.",
                "Exception with no cause caught",
                "400-6",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxResultsOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid max results override
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(Long.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max results override value. Max = 369.",
                "Exception with no cause caught",
                "400-43",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxConcurrentTasksOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // add an invalid max results override
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max concurrent tasks override value. Max = 10.",
                "Exception with no cause caught",
                "400-44",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_roleValidation() {
        // create a user without the required role
        ProxiedUserDetails authUser = createUserDetails(Collections.emptyList(), Collections.singletonList("ALL"));
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User does not have the required roles.",
                "datawave.webservice.query.exception.UnauthorizedQueryException: User does not have the required roles.",
                "400-5",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_markingValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the column visibility param to induce a security marking validation failure
        map.remove(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<BaseResponse> resp = jwtRestTemplate.exchange(requestEntity, BaseResponse.class);
        
        // @formatter:off
        BaseResponse baseResponse = assertBaseResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertFalse(baseResponse.getHasResults());
        
        // verify that an exception was returned
        Assert.assertEquals(1, baseResponse.getExceptions().size());
        
        QueryExceptionType queryException = baseResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter columnVisibility not found",
                "java.lang.IllegalArgumentException: Required parameter columnVisibility not found",
                "400-4",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @DirtiesContext
    @Test
    public void testNextSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // pump enough results into the queue to trigger a complete page
        int pageSize = queryStorageCache.getQueryStatus(queryId).getQuery().getPagesize();
        
        // test field value pairings
        MultiValueMap<String,String> fieldValues = new LinkedMultiValueMap<>();
        fieldValues.add("LOKI", "ALLIGATOR");
        fieldValues.add("LOKI", "CLASSIC");
        
        // @formatter:off
        publishEventsToQueue(
                queryId,
                (int)(1.5*pageSize),
                fieldValues,
                "ALL");
        // @formatter:on
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // verify some headers
        Assert.assertEquals("1", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-page-number"))));
        Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-Partial-Results"))));
        Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-last-page"))));
        
        DefaultEventQueryResponse queryResponse = (DefaultEventQueryResponse) response.getBody();
        
        // verify the query response
        // @formatter:off
        assertQueryResponse(
                queryId,
                "EventQuery",
                1,
                false,
                Long.parseLong(Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-OperationTimeInMS")))),
                1,
                Collections.singletonList("LOKI"),
                pageSize,
                Objects.requireNonNull(queryResponse));
        // @formatter:on
        
        // validate one of the events
        DefaultEvent event = (DefaultEvent) queryResponse.getEvents().get(0);
        // @formatter:off
        assertDefaultEvent(
                Arrays.asList("LOKI", "LOKI"),
                Arrays.asList("ALLIGATOR", "CLASSIC"),
                event);
        // @formatter:on
        
        // verify that the next event was published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextSuccess_multiplePages() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // pump enough results into the queue to trigger two complete pages
        int pageSize = queryStorageCache.getQueryStatus(queryId).getQuery().getPagesize();
        
        // test field value pairings
        MultiValueMap<String,String> fieldValues = new LinkedMultiValueMap<>();
        fieldValues.add("LOKI", "ALLIGATOR");
        fieldValues.add("LOKI", "CLASSIC");
        
        // verify that the create event was published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:off

        for (int page = 1; page <= 2; page++) {
            // TODO: We have to generate the results in between next calls because the test queue manager does not handle requeueing of unused messages :(
            // @formatter:off
            publishEventsToQueue(
                    queryId,
                    pageSize,
                    fieldValues,
                    "ALL");
            // @formatter:on
            
            // make the next call asynchronously
            Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
            
            // the response should come back right away
            ResponseEntity<BaseResponse> response = future.get();
            
            Assert.assertEquals(200, response.getStatusCodeValue());
            
            // verify some headers
            Assert.assertEquals(Integer.toString(page), Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-page-number"))));
            Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-Partial-Results"))));
            Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-last-page"))));
            
            DefaultEventQueryResponse queryResponse = (DefaultEventQueryResponse) response.getBody();
            
            // verify the query response
            // @formatter:off
            assertQueryResponse(
                    queryId,
                    "EventQuery",
                    page,
                    false,
                    Long.parseLong(Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-OperationTimeInMS")))),
                    1,
                    Collections.singletonList("LOKI"),
                    pageSize,
                    Objects.requireNonNull(queryResponse));
            // @formatter:on
            
            // validate one of the events
            DefaultEvent event = (DefaultEvent) queryResponse.getEvents().get(0);
            // @formatter:off
            assertDefaultEvent(
                    Arrays.asList("LOKI", "LOKI"),
                    Arrays.asList("ALLIGATOR", "CLASSIC"),
                    event);
            // @formatter:on
            
            // verify that the next event was published
            Assert.assertEquals(1, queryRequestEvents.size());
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.NEXT,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
    }
    
    @DirtiesContext
    @Test
    public void testNextSuccess_cancelPartialResults() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // pump enough results into the queue to trigger a complete page
        int pageSize = queryStorageCache.getQueryStatus(queryId).getQuery().getPagesize();
        
        // test field value pairings
        MultiValueMap<String,String> fieldValues = new LinkedMultiValueMap<>();
        fieldValues.add("LOKI", "ALLIGATOR");
        fieldValues.add("LOKI", "CLASSIC");
        
        int numEvents = (int) (0.5 * pageSize);
        
        // @formatter:off
        publishEventsToQueue(
                queryId,
                numEvents,
                fieldValues,
                "ALL");
        // @formatter:on
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> nextFuture = nextQuery(authUser, queryId);
        
        // make sure all events were consumed before canceling
        while (queryQueueManager.getQueueSize(queryId) != 0) {
            Thread.sleep(100);
        }
        
        // cancel the query so that it returns partial results
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // the response should come back right away
        ResponseEntity<BaseResponse> nextResponse = nextFuture.get();
        
        Assert.assertEquals(200, nextResponse.getStatusCodeValue());
        
        // verify some headers
        Assert.assertEquals("1", Iterables.getOnlyElement(Objects.requireNonNull(nextResponse.getHeaders().get("X-query-page-number"))));
        Assert.assertEquals("true", Iterables.getOnlyElement(Objects.requireNonNull(nextResponse.getHeaders().get("X-Partial-Results"))));
        Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(nextResponse.getHeaders().get("X-query-last-page"))));
        
        DefaultEventQueryResponse queryResponse = (DefaultEventQueryResponse) nextResponse.getBody();
        
        // verify the query response
        // @formatter:off
        assertQueryResponse(
                queryId,
                "EventQuery",
                1,
                true,
                Long.parseLong(Iterables.getOnlyElement(Objects.requireNonNull(nextResponse.getHeaders().get("X-OperationTimeInMS")))),
                1,
                Collections.singletonList("LOKI"),
                numEvents,
                Objects.requireNonNull(queryResponse));
        // @formatter:on
        
        // validate one of the events
        DefaultEvent event = (DefaultEvent) queryResponse.getEvents().get(0);
        // @formatter:off
        assertDefaultEvent(
                Arrays.asList("LOKI", "LOKI"),
                Arrays.asList("ALLIGATOR", "CLASSIC"),
                event);
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(4, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextSuccess_maxResults() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // pump enough results into the queue to trigger two complete pages
        int pageSize = queryStorageCache.getQueryStatus(queryId).getQuery().getPagesize();
        
        // test field value pairings
        MultiValueMap<String,String> fieldValues = new LinkedMultiValueMap<>();
        fieldValues.add("LOKI", "ALLIGATOR");
        fieldValues.add("LOKI", "CLASSIC");
        
        // verify that the create event was published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
        
        for (int page = 1; page <= 3; page++) {
            // TODO: We have to generate the results in between next calls because the test queue manager does not handle requeueing of unused messages :(
            // @formatter:off
            publishEventsToQueue(
                    queryId,
                    pageSize,
                    fieldValues,
                    "ALL");
            // @formatter:on
            
            // make the next call asynchronously
            Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
            
            // the response should come back right away
            ResponseEntity<BaseResponse> response = future.get();
            
            Assert.assertEquals(200, response.getStatusCodeValue());
            
            // verify some headers
            Assert.assertEquals(Integer.toString(page), Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-page-number"))));
            Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-Partial-Results"))));
            
            if (page != 4) {
                Assert.assertEquals("false", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-last-page"))));
            } else {
                Assert.assertEquals("true", Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-query-last-page"))));
            }
            
            DefaultEventQueryResponse queryResponse = (DefaultEventQueryResponse) response.getBody();
            
            // verify the query response
            // @formatter:off
            assertQueryResponse(
                    queryId,
                    "EventQuery",
                    page,
                    false,
                    Long.parseLong(Iterables.getOnlyElement(Objects.requireNonNull(response.getHeaders().get("X-OperationTimeInMS")))),
                    1,
                    Collections.singletonList("LOKI"),
                    pageSize,
                    Objects.requireNonNull(queryResponse));
            // @formatter:on
            
            // validate one of the events
            DefaultEvent event = (DefaultEvent) queryResponse.getEvents().get(0);
            // @formatter:off
            assertDefaultEvent(
                    Arrays.asList("LOKI", "LOKI"),
                    Arrays.asList("ALLIGATOR", "CLASSIC"),
                    event);
            // @formatter:on
            
            // verify that the next event was published
            Assert.assertEquals(1, queryRequestEvents.size());
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.NEXT,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No results found for query. " + queryId,
                "Exception with no cause caught",
                "404-4",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextSuccess_noResults() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // remove the task states to make it appear that the executor has finished
        TaskStates taskStates = queryStorageCache.getTaskStates(queryId);
        taskStates.getTaskStates().remove(TaskStates.TASK_STATE.READY);
        queryStorageCache.updateTaskStates(taskStates);
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No results found for query. " + queryId,
                "Exception with no cause caught",
                "404-4",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next event was published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @Test
    public void testNextFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_queryNotRunning() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query so that it returns partial results
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call next on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // make the next call as an alternate user asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(altAuthUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_timeout() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back after the configured timeout (5 seconds)
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(500, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Query timed out. " + queryId + " timed out.",
                "Exception with no cause caught",
                "500-27",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_nextOnDefined() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        // make the next call
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call next on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_nextOnClosed() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call next on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testNextFailure_nextOnCanceled() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // make the next call asynchronously
        Future<ResponseEntity<BaseResponse>> future = nextQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = future.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call next on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the cancel event was published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testCancelSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the cancel event was published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    // TODO: This test has periodic failures when running all tests
    @DirtiesContext
    @Test
    public void testCancelSuccess_activeNextCall() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // call next on the query
        Future<ResponseEntity<BaseResponse>> nextFuture = nextQuery(authUser, queryId);
        
        boolean nextCallActive = queryStorageCache.getQueryStatus(queryId).getActiveNextCalls() > 0;
        while (!nextCallActive) {
            try {
                nextFuture.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                nextCallActive = queryStorageCache.getQueryStatus(queryId).getActiveNextCalls() > 0;
                if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - currentTimeMillis) > 5) {
                    throw e;
                }
            }
        }
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                1,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // wait for the next call to return
        nextFuture.get();
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the close event was published
        Assert.assertEquals(4, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @Test
    public void testCancelFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(404, cancelResponse.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(cancelResponse.getBody().getExceptions()));
        // @formatter:on
        
    }
    
    @DirtiesContext
    @Test
    public void testCancelFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // make the cancel call as an alternate user asynchronously
        Future<ResponseEntity<VoidResponse>> future = cancelQuery(altAuthUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = future.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testCancelFailure_queryNotRunning() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // try to cancel the query again
        cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(400, cancelResponse.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call cancel on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(cancelResponse.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCancelSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails adminUser = createAltUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query as the admin user
        Future<ResponseEntity<VoidResponse>> cancelFuture = adminCancelQuery(adminUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the cancel event was published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCancelFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        UriComponents uri = createUri(queryId + "/adminCancel");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // cancel the query
        Future<ResponseEntity<String>> closeFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> closeResponse = closeFuture.get();
        
        Assert.assertEquals(403, closeResponse.getStatusCodeValue());
        
        // verify that the create event was published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCancelAllSuccess() throws Exception {
        ProxiedUserDetails adminUser = createUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // create a bunch of queries
        List<String> queryIds = new ArrayList<>();
        long currentTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            String queryId = createQuery(adminUser, createParams());
            mockServer.reset();
            
            queryIds.add(queryId);
            
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CREATE,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // close all queries as the admin user
        Future<ResponseEntity<VoidResponse>> cancelFuture = adminCancelAllQueries(adminUser);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        for (QueryStatus queryStatus : queryStatusList) {
            // @formatter:off
            assertQueryStatus(
                    QueryStatus.QUERY_STATE.CANCELED,
                    0,
                    0,
                    0,
                    0,
                    currentTimeMillis,
                    queryStatus);
            // @formatter:on
            
            String queryId = queryStatus.getQueryKey().getQueryId();
            
            // verify that the result queue is gone
            Assert.assertFalse(queryQueueManager.queueExists(queryStatus.getQueryKey().getQueryId()));
            
            // verify that the query tasks are still present
            assertTasksCreated(queryStatus.getQueryKey().getQueryId());
            
            // @formatter:off
            assertQueryRequestEvent(
                    "/query:**",
                    QueryRequest.Method.CANCEL,
                    queryId,
                    queryRequestEvents.removeLast());
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CANCEL,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // verify that there are no more events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminCancelAllFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a bunch of queries
        List<String> queryIds = new ArrayList<>();
        long currentTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            String queryId = createQuery(authUser, createParams());
            mockServer.reset();
            
            queryIds.add(queryId);
            
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CREATE,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // cancel all queries as the admin user
        UriComponents uri = createUri("/adminCancelAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        Future<ResponseEntity<String>> cancelFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(403, cancelResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        // verify that none of the queries were canceled
        for (QueryStatus queryStatus : queryStatusList) {
            // @formatter:off
            assertQueryStatus(
                    QueryStatus.QUERY_STATE.CREATED,
                    0,
                    0,
                    0,
                    0,
                    currentTimeMillis,
                    queryStatus);
            // @formatter:on
            
            // verify that the result queue is still present
            Assert.assertTrue(queryQueueManager.queueExists(queryStatus.getQueryKey().getQueryId()));
            
            // verify that the query tasks are still present
            assertTasksCreated(queryStatus.getQueryKey().getQueryId());
        }
        
        // verify that there are no more events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testCloseSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CLOSED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the close event was published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testCloseSuccess_activeNextCall() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // call next on the query
        Future<ResponseEntity<BaseResponse>> nextFuture = nextQuery(authUser, queryId);
        
        boolean nextCallActive = queryStorageCache.getQueryStatus(queryId).getActiveNextCalls() > 0;
        while (!nextCallActive) {
            try {
                nextFuture.get(500, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                nextCallActive = queryStorageCache.getQueryStatus(queryId).getActiveNextCalls() > 0;
                if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - currentTimeMillis) > 5) {
                    throw e;
                }
            }
        }
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CLOSED,
                0,
                0,
                1,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is still present
        Assert.assertTrue(queryQueueManager.queueExists(queryId));
        
        // send enough results to return a page
        // pump enough results into the queue to trigger a complete page
        int pageSize = queryStorageCache.getQueryStatus(queryId).getQuery().getPagesize();
        
        // test field value pairings
        MultiValueMap<String,String> fieldValues = new LinkedMultiValueMap<>();
        fieldValues.add("LOKI", "ALLIGATOR");
        fieldValues.add("LOKI", "CLASSIC");
        
        // @formatter:off
        publishEventsToQueue(
                queryId,
                pageSize,
                fieldValues,
                "ALL");
        // @formatter:on
        
        // wait for the next call to return
        nextFuture.get();
        
        // verify that the result queue is now gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the close event was published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @Test
    public void testCloseFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(404, closeResponse.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(closeResponse.getBody().getExceptions()));
        // @formatter:on
        
    }
    
    @DirtiesContext
    @Test
    public void testCloseFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // make the close call as an alternate user asynchronously
        Future<ResponseEntity<VoidResponse>> future = closeQuery(altAuthUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = future.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testCloseFailure_queryNotRunning() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // try to close the query again
        closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        closeResponse = closeFuture.get();
        
        Assert.assertEquals(400, closeResponse.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Cannot call close on a query that is not running",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(closeResponse.getBody().getExceptions()));
        // @formatter:on
        
        // verify that the next events were published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCloseSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails adminUser = createAltUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // close the query as the admin user
        Future<ResponseEntity<VoidResponse>> closeFuture = adminCloseQuery(adminUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CLOSED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that the result queue is gone
        Assert.assertFalse(queryQueueManager.queueExists(queryId));
        
        // verify that the query tasks are still present
        assertTasksCreated(queryId);
        
        // verify that the close event was published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCloseFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        UriComponents uri = createUri(queryId + "/adminClose");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // close the query
        Future<ResponseEntity<String>> closeFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> closeResponse = closeFuture.get();
        
        Assert.assertEquals(403, closeResponse.getStatusCodeValue());
        
        // verify that the create event was published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testAdminCloseAllSuccess() throws Exception {
        ProxiedUserDetails adminUser = createUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // create a bunch of queries
        long currentTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            String queryId = createQuery(adminUser, createParams());
            mockServer.reset();
            
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CREATE,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // close all queries as the admin user
        Future<ResponseEntity<VoidResponse>> closeFuture = adminCloseAllQueries(adminUser);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        for (QueryStatus queryStatus : queryStatusList) {
            // @formatter:off
            assertQueryStatus(
                    QueryStatus.QUERY_STATE.CLOSED,
                    0,
                    0,
                    0,
                    0,
                    currentTimeMillis,
                    queryStatus);
            // @formatter:on
            
            String queryId = queryStatus.getQueryKey().getQueryId();
            
            // verify that the result queue is gone
            Assert.assertFalse(queryQueueManager.queueExists(queryStatus.getQueryKey().getQueryId()));
            
            // verify that the query tasks are still present
            assertTasksCreated(queryStatus.getQueryKey().getQueryId());
            
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CLOSE,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // verify that there are no more events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminCloseAllFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a bunch of queries
        List<String> queryIds = new ArrayList<>();
        long currentTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            String queryId = createQuery(authUser, createParams());
            mockServer.reset();
            
            queryIds.add(queryId);
            
            // @formatter:off
            assertQueryRequestEvent(
                    "executor-unassigned:**",
                    QueryRequest.Method.CREATE,
                    queryId,
                    queryRequestEvents.removeLast());
            // @formatter:on
        }
        
        // close all queries as the admin user
        UriComponents uri = createUri("/adminCloseAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        Future<ResponseEntity<String>> closeFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> closeResponse = closeFuture.get();
        
        Assert.assertEquals(403, closeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        // verify that none of the queries were canceled
        for (QueryStatus queryStatus : queryStatusList) {
            // @formatter:off
            assertQueryStatus(
                    QueryStatus.QUERY_STATE.CREATED,
                    0,
                    0,
                    0,
                    0,
                    currentTimeMillis,
                    queryStatus);
            // @formatter:on
            
            // verify that the result queue is still present
            Assert.assertTrue(queryQueueManager.queueExists(queryStatus.getQueryKey().getQueryId()));
            
            // verify that the query tasks are still present
            assertTasksCreated(queryStatus.getQueryKey().getQueryId());
        }
        
        // verify that there are no more events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testResetSuccess_resetOnDefined() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = defineQuery(authUser, createParams());
        
        mockServer.reset();
        auditSentSetup();
        
        // reset the query
        Future<ResponseEntity<GenericResponse>> resetFuture = resetQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = resetFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // @formatter:off
        assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                response);
        // @formatter:on
        
        String resetQueryId = (String) response.getBody().getResult();
        
        // verify that a new query id was created
        Assert.assertNotEquals(queryId, resetQueryId);
        
        // verify that an audit record was sent
        assertAuditSent(resetQueryId);
        
        // verify that original query was canceled
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.DEFINED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that new query was created
        QueryStatus resetQueryStatus = queryStorageCache.getQueryStatus(resetQueryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                resetQueryStatus);
        // @formatter:on
        
        // make sure the queries are equal (ignoring the query id)
        queryStatus.getQuery().setId(resetQueryStatus.getQuery().getId());
        Assert.assertEquals(queryStatus.getQuery(), resetQueryStatus.getQuery());
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                resetQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testResetSuccess_resetOnCreated() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        mockServer.reset();
        auditSentSetup();
        
        // reset the query
        Future<ResponseEntity<GenericResponse>> resetFuture = resetQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = resetFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // @formatter:off
        assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                response);
        // @formatter:on
        
        String resetQueryId = (String) response.getBody().getResult();
        
        // verify that a new query id was created
        Assert.assertNotEquals(queryId, resetQueryId);
        
        // verify that an audit record was sent
        assertAuditSent(resetQueryId);
        
        // verify that original query was canceled
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that new query was created
        QueryStatus resetQueryStatus = queryStorageCache.getQueryStatus(resetQueryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                resetQueryStatus);
        // @formatter:on
        
        // make sure the queries are equal (ignoring the query id)
        queryStatus.getQuery().setId(resetQueryStatus.getQuery().getId());
        Assert.assertEquals(queryStatus.getQuery(), resetQueryStatus.getQuery());
        
        // verify that events were published
        Assert.assertEquals(4, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                resetQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testResetSuccess_resetOnClosed() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        mockServer.reset();
        auditSentSetup();
        
        // reset the query
        Future<ResponseEntity<GenericResponse>> resetFuture = resetQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = resetFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // @formatter:off
        assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                response);
        // @formatter:on
        
        String resetQueryId = (String) response.getBody().getResult();
        
        // verify that a new query id was created
        Assert.assertNotEquals(queryId, resetQueryId);
        
        // verify that an audit record was sent
        assertAuditSent(resetQueryId);
        
        // verify that original query was closed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CLOSED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that new query was created
        QueryStatus resetQueryStatus = queryStorageCache.getQueryStatus(resetQueryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                resetQueryStatus);
        // @formatter:on
        
        // make sure the queries are equal (ignoring the query id)
        queryStatus.getQuery().setId(resetQueryStatus.getQuery().getId());
        Assert.assertEquals(queryStatus.getQuery(), resetQueryStatus.getQuery());
        
        // verify that events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                resetQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testResetSuccess_resetOnCanceled() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        mockServer.reset();
        auditSentSetup();
        
        // reset the query
        Future<ResponseEntity<GenericResponse>> resetFuture = resetQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = resetFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // @formatter:off
        assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                response);
        // @formatter:on
        
        String resetQueryId = (String) response.getBody().getResult();
        
        // verify that a new query id was created
        Assert.assertNotEquals(queryId, resetQueryId);
        
        // verify that an audit record was sent
        assertAuditSent(resetQueryId);
        
        // verify that original query was canceled
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that new query was created
        QueryStatus resetQueryStatus = queryStorageCache.getQueryStatus(resetQueryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                resetQueryStatus);
        // @formatter:on
        
        // make sure the queries are equal (ignoring the query id)
        queryStatus.getQuery().setId(resetQueryStatus.getQuery().getId());
        Assert.assertEquals(queryStatus.getQuery(), resetQueryStatus.getQuery());
        
        // verify that events were published
        Assert.assertEquals(4, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                resetQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testResetFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        auditNotSentSetup();
        
        // reset the query
        UriComponents uri = createUri(queryId + "/reset");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // close the query
        Future<ResponseEntity<BaseResponse>> resetFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, BaseResponse.class));
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = resetFuture.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // make sure no audits were sent
        assertAuditNotSent();
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testResetFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // define a valid query
        String queryId = createQuery(authUser, createParams());
        
        mockServer.reset();
        auditNotSentSetup();
        
        // reset the query
        UriComponents uri = createUri(queryId + "/reset");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(altAuthUser, null, null, HttpMethod.PUT, uri);
        
        // close the query
        Future<ResponseEntity<BaseResponse>> resetFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, BaseResponse.class));
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = resetFuture.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // make sure no audits were sent
        assertAuditNotSent();
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testRemoveSuccess_removeOnDefined() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = removeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // verify that original query was removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testRemoveSuccess_removeOnClosed() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = removeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // verify that original query was removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testRemoveSuccess_removeOnCanceled() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = removeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // verify that original query was removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testRemoveFailure_removeOnCreated() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = removeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        Assert.assertEquals("Cannot remove a running query.", Iterables.getOnlyElement(response.getBody().getExceptions()).getMessage());
        
        // verify that original query was not removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNotNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testRemoveFailure_removeOnClosedActiveNext() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        // call next on the query
        nextQuery(authUser, queryId);
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = removeQuery(authUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        Assert.assertEquals("Cannot remove a running query.", Iterables.getOnlyElement(response.getBody().getExceptions()).getMessage());
        
        // verify that original query was not removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNotNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.NEXT,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @Test
    public void testRemoveFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        // remove the query
        UriComponents uri = createUri(queryId + "/remove");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // close the query
        Future<ResponseEntity<BaseResponse>> resetFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, BaseResponse.class));
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = resetFuture.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testRemoveFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        // remove the query
        UriComponents uri = createUri(queryId + "/remove");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(altAuthUser, null, null, HttpMethod.DELETE, uri);
        
        // close the query
        Future<ResponseEntity<BaseResponse>> resetFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, BaseResponse.class));
        
        // the response should come back right away
        ResponseEntity<BaseResponse> response = resetFuture.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminRemoveSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails adminUser = createAltUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        // remove the query
        Future<ResponseEntity<VoidResponse>> removeFuture = adminRemoveQuery(adminUser, queryId);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = removeFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        // verify that original query was removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminRemoveFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        // remove the query
        UriComponents uri = createUri(queryId + "/adminRemove");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // remove the queries
        Future<ResponseEntity<String>> removeFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> response = removeFuture.get();
        
        Assert.assertEquals(403, response.getStatusCodeValue());
        
        // verify that original query was not removed
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        Assert.assertNotNull(queryStatus);
        
        // verify that events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminRemoveAllSuccess() throws Exception {
        ProxiedUserDetails adminUser = createUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        // define a bunch of queries
        for (int i = 0; i < 10; i++) {
            defineQuery(adminUser, createParams());
        }
        
        // remove all queries as the admin user
        Future<ResponseEntity<VoidResponse>> removeFuture = adminRemoveAllQueries(adminUser);
        
        // the response should come back right away
        ResponseEntity<VoidResponse> removeResponse = removeFuture.get();
        
        Assert.assertEquals(200, removeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        Assert.assertEquals(0, queryStatusList.size());
        
        // verify that there are no events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testAdminRemoveAllFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a bunch of queries
        for (int i = 0; i < 10; i++) {
            defineQuery(authUser, createParams());
        }
        
        UriComponents uri = createUri("/adminRemoveAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // remove the queries
        Future<ResponseEntity<String>> removeFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        // the response should come back right away
        ResponseEntity<String> removeResponse = removeFuture.get();
        
        Assert.assertEquals(403, removeResponse.getStatusCodeValue());
        
        // verify that query status was created correctly
        List<QueryStatus> queryStatusList = queryStorageCache.getQueryStatus();
        
        Assert.assertEquals(10, queryStatusList.size());
        
        // verify that there are no events
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testUpdateSuccess_updateOnDefined() throws Exception {
        ProxiedUserDetails authUser = createUserDetails(null, Arrays.asList("ALL", "NONE"));
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        String newQuery = "SOME_OTHER_FIELD:SOME_OTHER_VALUE";
        String newAuths = "ALL,NONE";
        String newBegin = "20100101 000000.000";
        String newEnd = "20600101 000000.000";
        String newLogic = "AltEventQuery";
        int newPageSize = 100;
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY, newQuery);
        updateParams.set(QUERY_AUTHORIZATIONS, newAuths);
        updateParams.set(BEGIN_DATE, newBegin);
        updateParams.set(END_DATE, newEnd);
        updateParams.set(QUERY_LOGIC_NAME, newLogic);
        updateParams.set(PAGESIZE, Integer.toString(newPageSize));
        
        // update the query
        Future<ResponseEntity<GenericResponse>> updateFuture = updateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = updateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was updated
        Assert.assertEquals(newQuery, queryStatus.getQuery().getQuery());
        Assert.assertEquals(newAuths, queryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(newBegin, DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(newEnd, DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()));
        Assert.assertEquals(newLogic, queryStatus.getQuery().getQueryLogicName());
        Assert.assertEquals(newPageSize, queryStatus.getQuery().getPagesize());
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testUpdateSuccess_updateOnCreated() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        int newPageSize = 100;
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(PAGESIZE, Integer.toString(newPageSize));
        
        // update the query
        Future<ResponseEntity<GenericResponse>> updateFuture = updateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = updateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was updated
        Assert.assertEquals(newPageSize, queryStatus.getQuery().getPagesize());
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_unsafeParamUpdateQuery() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        String newQuery = "SOME_OTHER_FIELD:SOME_OTHER_VALUE";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY, newQuery);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals(TEST_QUERY_STRING, queryStatus.getQuery().getQuery());
        
        // @formatter:off
        assertQueryException(
                "Cannot update the following parameters for a running query: query",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_unsafeParamUpdateDate() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        String newBegin = "20100101 000000.000";
        String newEnd = "20600101 000000.000";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(BEGIN_DATE, newBegin);
        updateParams.set(END_DATE, newEnd);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals(TEST_QUERY_BEGIN, DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(TEST_QUERY_END, DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()));
        
        // @formatter:off
        assertQueryException(
                "Cannot update the following parameters for a running query: begin, end",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_unsafeParamUpdateLogic() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        String newLogic = "AltEventQuery";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY_LOGIC_NAME, newLogic);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals("EventQuery", queryStatus.getQuery().getQueryLogicName());
        
        // @formatter:off
        assertQueryException(
                "Cannot update the following parameters for a running query: logicName",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_unsafeParamUpdateAuths() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        String newAuths = "ALL,NONE";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY_AUTHORIZATIONS, newAuths);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals(TEST_QUERY_AUTHORIZATIONS, queryStatus.getQuery().getQueryAuthorizations());
        
        // @formatter:off
        assertQueryException(
                "Cannot update the following parameters for a running query: auths",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_nullParams() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        String queryId = createQuery(authUser, createParams());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No parameters specified for update.",
                "Exception with no cause caught",
                "400-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @Test
    public void testUpdateFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY_STRING, TEST_QUERY_STRING);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testUpdateFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        String newQuery = "SOME_OTHER_FIELD:SOME_OTHER_VALUE";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY, newQuery);
        
        // update the query
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(altAuthUser, updateParams, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        Future<ResponseEntity<VoidResponse>> updateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = updateFuture.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals(TEST_QUERY_STRING, queryStatus.getQuery().getQuery());
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateSuccess_duplicateOnDefined() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = defineQuery(authUser, createParams());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditSentSetup();
        
        // duplicate the query
        Future<ResponseEntity<GenericResponse>> duplicateFuture = duplicateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        String dupeQueryId = (String) response.getBody().getResult();
        
        // make sure an audit message was sent
        assertAuditSent(dupeQueryId);
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.DEFINED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        QueryStatus dupeQueryStatus = queryStorageCache.getQueryStatus(dupeQueryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                dupeQueryStatus);
        // @formatter:on
        
        // make sure the queries are identical
        Assert.assertEquals(queryStatus.getQuery().getQuery(), dupeQueryStatus.getQuery().getQuery());
        Assert.assertEquals(queryStatus.getQuery().getQueryAuthorizations(), dupeQueryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getEndDate()));
        Assert.assertEquals(queryStatus.getQuery().getQueryLogicName(), dupeQueryStatus.getQuery().getQueryLogicName());
        Assert.assertEquals(queryStatus.getQuery().getPagesize(), dupeQueryStatus.getQuery().getPagesize());
        
        // verify that no events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                dupeQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateSuccess_duplicateOnCreated() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditSentSetup();
        
        // duplicate the query
        Future<ResponseEntity<GenericResponse>> duplicateFuture = duplicateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        String dupeQueryId = (String) response.getBody().getResult();
        
        // make sure an audit message was sent
        assertAuditSent(dupeQueryId);
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        QueryStatus dupeQueryStatus = queryStorageCache.getQueryStatus(dupeQueryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                dupeQueryStatus);
        // @formatter:on
        
        // make sure the queries are identical
        Assert.assertEquals(queryStatus.getQuery().getQuery(), dupeQueryStatus.getQuery().getQuery());
        Assert.assertEquals(queryStatus.getQuery().getQueryAuthorizations(), dupeQueryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getEndDate()));
        Assert.assertEquals(queryStatus.getQuery().getQueryLogicName(), dupeQueryStatus.getQuery().getQueryLogicName());
        Assert.assertEquals(queryStatus.getQuery().getPagesize(), dupeQueryStatus.getQuery().getPagesize());
        
        // verify that no events were published
        Assert.assertEquals(2, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                dupeQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateSuccess_duplicateOnCanceled() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // cancel the query
        Future<ResponseEntity<VoidResponse>> cancelFuture = cancelQuery(authUser, queryId);
        
        // this should return immediately
        ResponseEntity<VoidResponse> cancelResponse = cancelFuture.get();
        
        Assert.assertEquals(200, cancelResponse.getStatusCodeValue());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditSentSetup();
        
        // duplicate the query
        Future<ResponseEntity<GenericResponse>> duplicateFuture = duplicateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        String dupeQueryId = (String) response.getBody().getResult();
        
        // make sure an audit message was sent
        assertAuditSent(dupeQueryId);
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CANCELED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        QueryStatus dupeQueryStatus = queryStorageCache.getQueryStatus(dupeQueryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                dupeQueryStatus);
        // @formatter:on
        
        // make sure the queries are identical
        Assert.assertEquals(queryStatus.getQuery().getQuery(), dupeQueryStatus.getQuery().getQuery());
        Assert.assertEquals(queryStatus.getQuery().getQueryAuthorizations(), dupeQueryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getEndDate()));
        Assert.assertEquals(queryStatus.getQuery().getQueryLogicName(), dupeQueryStatus.getQuery().getQueryLogicName());
        Assert.assertEquals(queryStatus.getQuery().getPagesize(), dupeQueryStatus.getQuery().getPagesize());
        
        // verify that no events were published
        Assert.assertEquals(4, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "/query:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CANCEL,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                dupeQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateSuccess_duplicateOnClosed() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // create a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = createQuery(authUser, createParams());
        
        // close the query
        Future<ResponseEntity<VoidResponse>> closeFuture = closeQuery(authUser, queryId);
        
        // this should return immediately
        ResponseEntity<VoidResponse> closeResponse = closeFuture.get();
        
        Assert.assertEquals(200, closeResponse.getStatusCodeValue());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditSentSetup();
        
        // duplicate the query
        Future<ResponseEntity<GenericResponse>> duplicateFuture = duplicateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        String dupeQueryId = (String) response.getBody().getResult();
        
        // make sure an audit message was sent
        assertAuditSent(dupeQueryId);
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CLOSED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        QueryStatus dupeQueryStatus = queryStorageCache.getQueryStatus(dupeQueryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                dupeQueryStatus);
        // @formatter:on
        
        // make sure the queries are identical
        Assert.assertEquals(queryStatus.getQuery().getQuery(), dupeQueryStatus.getQuery().getQuery());
        Assert.assertEquals(queryStatus.getQuery().getQueryAuthorizations(), dupeQueryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()),
                        DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getEndDate()));
        Assert.assertEquals(queryStatus.getQuery().getQueryLogicName(), dupeQueryStatus.getQuery().getQueryLogicName());
        Assert.assertEquals(queryStatus.getQuery().getPagesize(), dupeQueryStatus.getQuery().getPagesize());
        
        // verify that no events were published
        Assert.assertEquals(3, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CLOSE,
                queryId,
                queryRequestEvents.removeLast());
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                dupeQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateSuccess_update() throws Exception {
        ProxiedUserDetails authUser = createUserDetails(null, Arrays.asList("ALL", "NONE"));
        
        // define a valid query
        long currentTimeMillis = System.currentTimeMillis();
        String queryId = defineQuery(authUser, createParams());
        
        String newQuery = "SOME_OTHER_FIELD:SOME_OTHER_VALUE";
        String newAuths = "ALL,NONE";
        String newBegin = "20100101 000000.000";
        String newEnd = "20600101 000000.000";
        String newLogic = "AltEventQuery";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY, newQuery);
        updateParams.set(QUERY_AUTHORIZATIONS, newAuths);
        updateParams.set(BEGIN_DATE, newBegin);
        updateParams.set(END_DATE, newEnd);
        updateParams.set(QUERY_LOGIC_NAME, newLogic);
        
        mockServer.reset();
        auditSentSetup();
        
        // duplicate the query
        Future<ResponseEntity<GenericResponse>> duplicateFuture = duplicateQuery(authUser, queryId, updateParams);
        
        // the response should come back right away
        ResponseEntity<GenericResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        String dupeQueryId = (String) response.getBody().getResult();
        
        // make sure an audit message was sent
        assertAuditSent(dupeQueryId);
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.DEFINED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        QueryStatus dupeQueryStatus = queryStorageCache.getQueryStatus(dupeQueryId);
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                dupeQueryStatus);
        // @formatter:on
        
        // make sure the original query is unchanged
        Assert.assertEquals(TEST_QUERY_STRING, queryStatus.getQuery().getQuery());
        Assert.assertEquals(TEST_QUERY_AUTHORIZATIONS, queryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(TEST_QUERY_BEGIN, DefaultQueryParameters.formatDate(queryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(TEST_QUERY_END, DefaultQueryParameters.formatDate(queryStatus.getQuery().getEndDate()));
        Assert.assertEquals("EventQuery", queryStatus.getQuery().getQueryLogicName());
        
        // make sure the duplicated query is updated
        Assert.assertEquals(newQuery, dupeQueryStatus.getQuery().getQuery());
        Assert.assertEquals(newAuths, dupeQueryStatus.getQuery().getQueryAuthorizations());
        Assert.assertEquals(newBegin, DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getBeginDate()));
        Assert.assertEquals(newEnd, DefaultQueryParameters.formatDate(dupeQueryStatus.getQuery().getEndDate()));
        Assert.assertEquals(newLogic, dupeQueryStatus.getQuery().getQueryLogicName());
        
        // verify that no events were published
        Assert.assertEquals(1, queryRequestEvents.size());
        // @formatter:off
        assertQueryRequestEvent(
                "executor-unassigned:**",
                QueryRequest.Method.CREATE,
                dupeQueryId,
                queryRequestEvents.removeLast());
        // @formatter:on
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateFailure_invalidUpdate() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        String newLogic = "SomeBogusLogic";
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        updateParams.set(QUERY_LOGIC_NAME, newLogic);
        
        mockServer.reset();
        auditNotSentSetup();
        
        // duplicate the query
        UriComponents uri = createUri(queryId + "/duplicate");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.POST, uri);
        
        // make the duplicate call asynchronously
        Future<ResponseEntity<VoidResponse>> duplicateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(400, response.getStatusCodeValue());
        
        // make sure an audit message wasn't sent
        assertAuditNotSent();
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @Test
    public void testDuplicateFailure_queryNotFound() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String queryId = UUID.randomUUID().toString();
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditNotSentSetup();
        
        // duplicate the query
        UriComponents uri = createUri(queryId + "/duplicate");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, updateParams, null, HttpMethod.POST, uri);
        
        // make the duplicate call asynchronously
        Future<ResponseEntity<VoidResponse>> duplicateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(404, response.getStatusCodeValue());
        
        // @formatter:off
        assertQueryException(
                "No query object matches this id. " + queryId,
                "Exception with no cause caught",
                "404-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        // make sure an audit message wasn't sent
        assertAuditNotSent();
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testDuplicateFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // define a valid query
        String queryId = defineQuery(authUser, createParams());
        
        MultiValueMap<String,String> updateParams = new LinkedMultiValueMap<>();
        
        mockServer.reset();
        auditNotSentSetup();
        
        // duplicate the query
        UriComponents uri = createUri(queryId + "/duplicate");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(altAuthUser, updateParams, null, HttpMethod.POST, uri);
        
        // make the duplicate call asynchronously
        Future<ResponseEntity<VoidResponse>> duplicateFuture = Executors.newSingleThreadExecutor()
                        .submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
        
        // the response should come back right away
        ResponseEntity<VoidResponse> response = duplicateFuture.get();
        
        Assert.assertEquals(401, response.getStatusCodeValue());
        
        // make sure an audit message wasn't sent
        assertAuditNotSent();
        
        // @formatter:off
        assertQueryException(
                "Current user does not match user that defined query. altuserdn != userdn",
                "Exception with no cause caught",
                "401-1",
                Iterables.getOnlyElement(response.getBody().getExceptions()));
        // @formatter:on
        
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // make sure the query was not updated
        Assert.assertEquals(TEST_QUERY_STRING, queryStatus.getQuery().getQuery());
        
        // verify that no events were published
        Assert.assertEquals(0, queryRequestEvents.size());
    }
    
    @DirtiesContext
    @Test
    public void testListSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String queryId = createQuery(authUser, createParams());
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // define a bunch of queries as the alternate user
        List<String> altQueryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String queryId = defineQuery(altAuthUser, createParams());
            mockServer.reset();
            
            altQueryIds.add(queryId);
        }
        
        // list queries as the original user
        Future<ResponseEntity<QueryImplListResponse>> listFuture = listQueries(authUser, null, null);
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(5, result.getNumResults());
        
        List<String> actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Collections.sort(queryIds);
        Collections.sort(actualQueryIds);
        
        Assert.assertEquals(queryIds, actualQueryIds);
    }
    
    @DirtiesContext
    @Test
    public void testListSuccess_filterOnQueryId() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String queryId = createQuery(authUser, createParams());
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // list queries
        Future<ResponseEntity<QueryImplListResponse>> listFuture = listQueries(authUser, queryIds.get(0), null);
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        List<String> actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
    }
    
    @DirtiesContext
    @Test
    public void testListSuccess_filterOnQueryName() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String uniqueQueryName = "Unique Query";
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultiValueMap<String,String> params = createParams();
            if (i == 0) {
                params.set(QUERY_NAME, uniqueQueryName);
            }
            
            String queryId = createQuery(authUser, params);
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // list queries
        Future<ResponseEntity<QueryImplListResponse>> listFuture = listQueries(authUser, null, uniqueQueryName);
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        List<String> actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
    }
    
    @DirtiesContext
    @Test
    public void testListSuccess_filterOnMultiple() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        String uniqueQueryName = "Unique Query";
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultiValueMap<String,String> params = createParams();
            if (i == 0) {
                params.set(QUERY_NAME, uniqueQueryName);
            }
            
            String queryId = createQuery(authUser, params);
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // list queries with just the query ID and a bogus name
        Future<ResponseEntity<QueryImplListResponse>> listFuture = listQueries(authUser, queryIds.get(0), "bogus name");
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(0, result.getNumResults());
        
        // list queries with just the query name and a bogus ID
        listFuture = listQueries(authUser, UUID.randomUUID().toString(), uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(0, result.getNumResults());
        
        // list queries with just the query name and a bogus ID
        listFuture = listQueries(authUser, queryIds.get(0), uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        List<String> actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
    }
    
    @DirtiesContext
    @Test
    public void testListFailure_ownershipFailure() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        String uniqueQueryName = "Unique Query";
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultiValueMap<String,String> params = createParams();
            if (i == 0) {
                params.set(QUERY_NAME, uniqueQueryName);
            }
            
            String queryId = createQuery(authUser, params);
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // list queries with just the query ID and a bogus name
        Future<ResponseEntity<QueryImplListResponse>> listFuture = listQueries(altAuthUser, queryIds.get(0), "bogus name");
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(0, result.getNumResults());
        
        // list queries with just the query name and a bogus ID
        listFuture = listQueries(altAuthUser, UUID.randomUUID().toString(), uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(0, result.getNumResults());
        
        // list queries with the query name and query ID
        listFuture = listQueries(altAuthUser, queryIds.get(0), uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(0, result.getNumResults());
    }
    
    @DirtiesContext
    @Test
    public void testAdminListSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails adminUser = createAltUserDetails(Arrays.asList("AuthorizedUser", "Administrator"), null);
        
        String user = ProxiedEntityUtils.getShortName(authUser.getPrimaryUser().getDn().subjectDN());
        
        String uniqueQueryName = "Unique Query";
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultiValueMap<String,String> params = createParams();
            if (i == 0) {
                params.set(QUERY_NAME, uniqueQueryName);
            }
            
            String queryId = createQuery(authUser, params);
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        // list queries with just the query ID
        Future<ResponseEntity<QueryImplListResponse>> listFuture = adminListQueries(adminUser, queryIds.get(0), user, null);
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        List<String> actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
        
        // list queries with just the query name
        listFuture = adminListQueries(adminUser, null, user, uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
        
        // list queries with the query name and query ID
        listFuture = adminListQueries(adminUser, queryIds.get(0), user, uniqueQueryName);
        
        // this should return immediately
        listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        actualQueryIds = result.getQuery().stream().map(Query::getId).map(UUID::toString).collect(Collectors.toList());
        
        Assert.assertEquals(queryIds.get(0), actualQueryIds.get(0));
    }
    
    @DirtiesContext
    @Test
    public void testAdminListFailure_notAdminUser() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        ProxiedUserDetails altAuthUser = createAltUserDetails();
        
        String user = ProxiedEntityUtils.getShortName(authUser.getPrimaryUser().getDn().subjectDN());
        
        String uniqueQueryName = "Unique Query";
        
        // define a bunch of queries as the original user
        List<String> queryIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            MultiValueMap<String,String> params = createParams();
            if (i == 0) {
                params.set(QUERY_NAME, uniqueQueryName);
            }
            
            String queryId = createQuery(authUser, params);
            mockServer.reset();
            
            queryIds.add(queryId);
        }
        
        UriComponentsBuilder uriBuilder = uriBuilder("/adminList");
        UriComponents uri = uriBuilder.build();
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(altAuthUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        Future<ResponseEntity<String>> listFuture = Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, String.class));
        
        ResponseEntity<String> listResponse = listFuture.get();
        
        Assert.assertEquals(403, listResponse.getStatusCodeValue());
    }
    
    @DirtiesContext
    @Test
    public void testGetQuerySuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        // define a query
        String queryId = createQuery(authUser, createParams());
        mockServer.reset();
        
        // get the query
        Future<ResponseEntity<QueryImplListResponse>> listFuture = getQuery(authUser, queryId);
        
        // this should return immediately
        ResponseEntity<QueryImplListResponse> listResponse = listFuture.get();
        
        Assert.assertEquals(200, listResponse.getStatusCodeValue());
        
        QueryImplListResponse result = listResponse.getBody();
        
        Assert.assertEquals(1, result.getNumResults());
        
        Assert.assertEquals(queryId, result.getQuery().get(0).getId().toString());
    }
    
    @Test
    public void testListQueryLogicSuccess() throws Exception {
        ProxiedUserDetails authUser = createUserDetails();
        
        Future<ResponseEntity<QueryLogicResponse>> future = listQueryLogic(authUser);
        
        ResponseEntity<QueryLogicResponse> response = future.get();
        
        Assert.assertEquals(200, response.getStatusCodeValue());
        
        QueryLogicResponse qlResponse = response.getBody();
        
        Assert.assertEquals(2, qlResponse.getQueryLogicList().size());
        
        List<String> qlNames = qlResponse.getQueryLogicList().stream().map(QueryLogicDescription::getName).sorted().collect(Collectors.toList());
        
        Assert.assertEquals(Arrays.asList("AltEventQuery", "EventQuery"), qlNames);
    }
    
    private void publishEventsToQueue(String queryId, int numEvents, MultiValueMap<String,String> fieldValues, String visibility) throws Exception {
        for (int resultId = 0; resultId < numEvents; resultId++) {
            DefaultEvent[] events = new DefaultEvent[1];
            events[0] = new DefaultEvent();
            long currentTime = System.currentTimeMillis();
            List<DefaultField> fields = new ArrayList<>();
            for (Map.Entry<String,List<String>> entry : fieldValues.entrySet()) {
                for (String value : entry.getValue()) {
                    fields.add(new DefaultField(entry.getKey(), visibility, currentTime, value));
                }
            }
            events[0].setFields(fields);
            queryQueueManager.sendMessage(queryId, new Result(Integer.toString(resultId), events));
        }
    }
    
    private String createQuery(ProxiedUserDetails authUser, MultiValueMap<String,String> map) {
        return newQuery(authUser, map, "create");
    }
    
    private String defineQuery(ProxiedUserDetails authUser, MultiValueMap<String,String> map) {
        return newQuery(authUser, map, "define");
    }
    
    private String newQuery(ProxiedUserDetails authUser, MultiValueMap<String,String> map, String createOrDefine) {
        UriComponents uri = createUri("EventQuery/" + createOrDefine);
        
        // not testing audit with this method
        auditIgnoreSetup();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        return (String) resp.getBody().getResult();
    }
    
    private Future<ResponseEntity<BaseResponse>> nextQuery(ProxiedUserDetails authUser, String queryId) {
        UriComponents uri = createUri(queryId + "/next");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, BaseResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> adminCloseQuery(ProxiedUserDetails authUser, String queryId) {
        return stopQuery(authUser, queryId, "adminClose");
    }
    
    private Future<ResponseEntity<VoidResponse>> closeQuery(ProxiedUserDetails authUser, String queryId) {
        return stopQuery(authUser, queryId, "close");
    }
    
    private Future<ResponseEntity<VoidResponse>> adminCancelQuery(ProxiedUserDetails authUser, String queryId) {
        return stopQuery(authUser, queryId, "adminCancel");
    }
    
    private Future<ResponseEntity<VoidResponse>> cancelQuery(ProxiedUserDetails authUser, String queryId) {
        return stopQuery(authUser, queryId, "cancel");
    }
    
    private Future<ResponseEntity<VoidResponse>> stopQuery(ProxiedUserDetails authUser, String queryId, String closeOrCancel) {
        UriComponents uri = createUri(queryId + "/" + closeOrCancel);
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> adminCloseAllQueries(ProxiedUserDetails authUser) {
        UriComponents uri = createUri("/adminCloseAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> adminCancelAllQueries(ProxiedUserDetails authUser) {
        UriComponents uri = createUri("/adminCancelAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<GenericResponse>> resetQuery(ProxiedUserDetails authUser, String queryId) {
        UriComponents uri = createUri(queryId + "/reset");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.PUT, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, GenericResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> removeQuery(ProxiedUserDetails authUser, String queryId) {
        UriComponents uri = createUri(queryId + "/remove");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> adminRemoveQuery(ProxiedUserDetails authUser, String queryId) {
        UriComponents uri = createUri(queryId + "/adminRemove");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<VoidResponse>> adminRemoveAllQueries(ProxiedUserDetails authUser) {
        UriComponents uri = createUri("/adminRemoveAll");
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.DELETE, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, VoidResponse.class));
    }
    
    private Future<ResponseEntity<GenericResponse>> updateQuery(ProxiedUserDetails authUser, String queryId, MultiValueMap<String,String> map) {
        UriComponents uri = createUri(queryId + "/update");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.PUT, uri);
        
        // make the update call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, GenericResponse.class));
    }
    
    private Future<ResponseEntity<GenericResponse>> duplicateQuery(ProxiedUserDetails authUser, String queryId, MultiValueMap<String,String> map) {
        UriComponents uri = createUri(queryId + "/duplicate");
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // make the update call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, GenericResponse.class));
    }
    
    private Future<ResponseEntity<QueryImplListResponse>> listQueries(ProxiedUserDetails authUser, String queryId, String queryName) {
        UriComponentsBuilder uriBuilder = uriBuilder("/list");
        if (queryId != null) {
            uriBuilder.queryParam("queryId", queryId);
        }
        if (queryName != null) {
            uriBuilder.queryParam("queryName", queryName);
        }
        UriComponents uri = uriBuilder.build();
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, QueryImplListResponse.class));
    }
    
    private Future<ResponseEntity<QueryImplListResponse>> getQuery(ProxiedUserDetails authUser, String queryId) {
        UriComponents uri = createUri(queryId);
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, QueryImplListResponse.class));
    }
    
    private Future<ResponseEntity<QueryLogicResponse>> listQueryLogic(ProxiedUserDetails authUser) {
        UriComponents uri = createUri("/listQueryLogic");
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, QueryLogicResponse.class));
    }
    
    private Future<ResponseEntity<QueryImplListResponse>> adminListQueries(ProxiedUserDetails authUser, String queryId, String user, String queryName) {
        UriComponentsBuilder uriBuilder = uriBuilder("/adminList");
        if (queryId != null) {
            uriBuilder.queryParam("queryId", queryId);
        }
        if (queryName != null) {
            uriBuilder.queryParam("queryName", queryName);
        }
        if (user != null) {
            uriBuilder.queryParam("user", user);
        }
        UriComponents uri = uriBuilder.build();
        
        RequestEntity requestEntity = jwtRestTemplate.createRequestEntity(authUser, null, null, HttpMethod.GET, uri);
        
        // make the next call asynchronously
        return Executors.newSingleThreadExecutor().submit(() -> jwtRestTemplate.exchange(requestEntity, QueryImplListResponse.class));
    }
    
    private ProxiedUserDetails createUserDetails() {
        return createUserDetails(null, null);
    }
    
    private ProxiedUserDetails createUserDetails(Collection<String> roles, Collection<String> auths) {
        Collection<String> userRoles = roles != null ? roles : Collections.singleton("AuthorizedUser");
        Collection<String> userAuths = auths != null ? auths : Collections.singleton("ALL");
        DatawaveUser datawaveUser = new DatawaveUser(DN, USER, userAuths, userRoles, null, System.currentTimeMillis());
        return new ProxiedUserDetails(Collections.singleton(datawaveUser), datawaveUser.getCreationTime());
    }
    
    private ProxiedUserDetails createAltUserDetails() {
        return createAltUserDetails(null, null);
    }
    
    private ProxiedUserDetails createAltUserDetails(Collection<String> roles, Collection<String> auths) {
        Collection<String> userRoles = roles != null ? roles : Collections.singleton("AuthorizedUser");
        Collection<String> userAuths = auths != null ? auths : Collections.singleton("ALL");
        DatawaveUser datawaveUser = new DatawaveUser(altDN, USER, userAuths, userRoles, null, System.currentTimeMillis());
        return new ProxiedUserDetails(Collections.singleton(datawaveUser), datawaveUser.getCreationTime());
    }
    
    private UriComponentsBuilder uriBuilder(String path) {
        return UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/query/v1/" + path);
    }
    
    private UriComponents createUri(String path) {
        return uriBuilder(path).build();
    }
    
    private MultiValueMap<String,String> createParams() {
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.set(DefaultQueryParameters.QUERY_STRING, TEST_QUERY_STRING);
        map.set(DefaultQueryParameters.QUERY_NAME, TEST_QUERY_NAME);
        map.set(DefaultQueryParameters.QUERY_AUTHORIZATIONS, TEST_QUERY_AUTHORIZATIONS);
        map.set(DefaultQueryParameters.QUERY_BEGIN, TEST_QUERY_BEGIN);
        map.set(DefaultQueryParameters.QUERY_END, TEST_QUERY_END);
        map.set(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING, TEST_VISIBILITY_MARKING);
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(1));
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(369));
        map.set(QUERY_PAGESIZE, Long.toString(123));
        return map;
    }
    
    private void assertDefaultEvent(List<String> fields, List<String> values, DefaultEvent event) {
        Assert.assertEquals(fields, event.getFields().stream().map(DefaultField::getName).collect(Collectors.toList()));
        Assert.assertEquals(values, event.getFields().stream().map(DefaultField::getValueString).collect(Collectors.toList()));
    }
    
    private void assertQueryResponse(String queryId, String logicName, long pageNumber, boolean partialResults, long operationTimeInMS, int numFields,
                    List<String> fieldNames, int numEvents, DefaultEventQueryResponse queryResponse) {
        Assert.assertEquals(queryId, queryResponse.getQueryId());
        Assert.assertEquals(logicName, queryResponse.getLogicName());
        Assert.assertEquals(pageNumber, queryResponse.getPageNumber());
        Assert.assertEquals(partialResults, queryResponse.isPartialResults());
        Assert.assertEquals(operationTimeInMS, queryResponse.getOperationTimeMS());
        Assert.assertEquals(numFields, queryResponse.getFields().size());
        Assert.assertEquals(fieldNames, queryResponse.getFields());
        Assert.assertEquals(numEvents, queryResponse.getEvents().size());
    }
    
    private void assertQueryRequestEvent(String destination, QueryRequest.Method method, String queryId, RemoteQueryRequestEvent queryRequestEvent) {
        Assert.assertEquals(destination, queryRequestEvent.getDestinationService());
        Assert.assertEquals(queryId, queryRequestEvent.getRequest().getQueryId());
        Assert.assertEquals(method, queryRequestEvent.getRequest().getMethod());
    }
    
    private void assertQueryStatus(QueryStatus.QUERY_STATE queryState, long numResultsReturned, long numResultsGenerated, long activeNextCalls,
                    long lastPageNumber, long lastCallTimeMillis, QueryStatus queryStatus) {
        Assert.assertEquals(queryState, queryStatus.getQueryState());
        Assert.assertEquals(numResultsReturned, queryStatus.getNumResultsReturned());
        Assert.assertEquals(numResultsGenerated, queryStatus.getNumResultsGenerated());
        Assert.assertEquals(activeNextCalls, queryStatus.getActiveNextCalls());
        Assert.assertEquals(lastPageNumber, queryStatus.getLastPageNumber());
        Assert.assertTrue(queryStatus.getLastUsedMillis() > lastCallTimeMillis);
        Assert.assertTrue(queryStatus.getLastUpdatedMillis() > lastCallTimeMillis);
    }
    
    private void assertQuery(String queryString, String queryName, String authorizations, String begin, String end, String visibility, Query query)
                    throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat(DefaultQueryParameters.formatPattern);
        Assert.assertEquals(queryString, query.getQuery());
        Assert.assertEquals(queryName, query.getQueryName());
        Assert.assertEquals(authorizations, query.getQueryAuthorizations());
        Assert.assertEquals(sdf.parse(begin), query.getBeginDate());
        Assert.assertEquals(sdf.parse(end), query.getEndDate());
        Assert.assertEquals(visibility, query.getColumnVisibility());
    }
    
    private void assertTasksCreated(String queryId) throws IOException {
        // verify that the query task states were created
        TaskStates taskStates = queryStorageCache.getTaskStates(queryId);
        Assert.assertNotNull(taskStates);
        
        // verify that a query task was created
        List<TaskKey> taskKeys = queryStorageCache.getTasks(queryId);
        Assert.assertFalse(taskKeys.isEmpty());
    }
    
    private void assertTasksNotCreated(String queryId) throws IOException {
        // verify that the query task states were not created
        TaskStates taskStates = queryStorageCache.getTaskStates(queryId);
        Assert.assertNull(taskStates);
        
        // verify that a query task was not created
        List<TaskKey> taskKeys = queryStorageCache.getTasks(queryId);
        Assert.assertTrue(taskKeys.isEmpty());
    }
    
    public RequestMatcher auditIdGrabber() {
        return request -> {
            List<NameValuePair> params = URLEncodedUtils.parse(request.getBody().toString(), Charset.defaultCharset());
            params.stream().filter(p -> p.getName().equals(AUDIT_ID)).forEach(p -> auditIds.add(p.getValue()));
        };
    }
    
    private void auditIgnoreSetup() {
        mockServer.expect(anything()).andRespond(withSuccess());
    }
    
    private void auditSentSetup() {
        mockServer.expect(requestTo(EXPECTED_AUDIT_URI)).andExpect(auditIdGrabber()).andRespond(withSuccess());
    }
    
    private void auditNotSentSetup() {
        mockServer.expect(never(), requestTo(EXPECTED_AUDIT_URI)).andExpect(auditIdGrabber()).andRespond(withSuccess());
    }
    
    private void assertAuditSent(String queryId) {
        mockServer.verify();
        Assert.assertEquals(1, auditIds.size());
        Assert.assertEquals(queryId, auditIds.get(0));
    }
    
    private void assertAuditNotSent() {
        mockServer.verify();
        Assert.assertEquals(0, auditIds.size());
    }
    
    private void assertQueryException(String message, String cause, String code, QueryExceptionType queryException) {
        Assert.assertEquals(message, queryException.getMessage());
        Assert.assertEquals(cause, queryException.getCause());
        Assert.assertEquals(code, queryException.getCode());
    }
    
    private BaseResponse assertBaseResponse(boolean hasResults, HttpStatus.Series series, ResponseEntity<BaseResponse> response) {
        Assert.assertEquals(series, response.getStatusCode().series());
        Assert.assertNotNull(response);
        BaseResponse baseResponse = response.getBody();
        Assert.assertNotNull(baseResponse);
        Assert.assertEquals(hasResults, baseResponse.getHasResults());
        return baseResponse;
    }
    
    @SuppressWarnings("unchecked")
    private GenericResponse<String> assertGenericResponse(boolean hasResults, HttpStatus.Series series, ResponseEntity<GenericResponse> response) {
        Assert.assertEquals(series, response.getStatusCode().series());
        Assert.assertNotNull(response);
        GenericResponse<String> genericResponse = (GenericResponse<String>) response.getBody();
        Assert.assertNotNull(genericResponse);
        Assert.assertEquals(hasResults, genericResponse.getHasResults());
        return genericResponse;
    }
    
    private static class NoOpResponseErrorHandler extends DefaultResponseErrorHandler {
        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            // do nothing
        }
    }
    
    @Configuration
    @Profile("QueryServiceTest")
    @ComponentScan(basePackages = "datawave.microservice")
    public static class QueryServiceTestConfiguration {
        @Bean
        public LinkedList<RemoteQueryRequestEvent> queryRequestEvents() {
            return new LinkedList<>();
        }
        
        @Bean
        @Primary
        public ApplicationEventPublisher eventPublisher(ApplicationEventPublisher eventPublisher) {
            return new ApplicationEventPublisher() {
                @Override
                public void publishEvent(ApplicationEvent event) {
                    saveEvent(event);
                }
                
                @Override
                public void publishEvent(Object event) {
                    saveEvent(event);
                }
                
                private void saveEvent(Object event) {
                    if (event instanceof RemoteQueryRequestEvent) {
                        queryRequestEvents().push(((RemoteQueryRequestEvent) event));
                    }
                }
            };
        }
    }
}
