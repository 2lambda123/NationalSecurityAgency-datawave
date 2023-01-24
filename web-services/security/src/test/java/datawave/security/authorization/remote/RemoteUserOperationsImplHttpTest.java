package datawave.security.authorization.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datawave.security.authorization.DatawavePrincipal;
import datawave.security.authorization.remote.RemoteUserOperationsImpl;
import datawave.security.util.DnUtils;
import datawave.user.AuthorizationsListBase;
import datawave.user.DefaultAuthorizationsList;
import datawave.webservice.common.json.DefaultMapperDecorator;
import datawave.webservice.query.Query;
import datawave.webservice.query.cachedresults.CacheableQueryRow;
import datawave.webservice.query.result.EdgeQueryResponseBase;
import datawave.webservice.query.result.edge.EdgeBase;
import datawave.webservice.query.result.event.EventBase;
import datawave.webservice.query.result.event.FacetsBase;
import datawave.webservice.query.result.event.FieldBase;
import datawave.webservice.query.result.event.FieldCardinalityBase;
import datawave.webservice.query.result.event.ResponseObjectFactory;
import datawave.webservice.query.result.metadata.MetadataFieldBase;
import datawave.webservice.response.objects.KeyBase;
import datawave.webservice.result.EventQueryResponseBase;
import datawave.webservice.result.FacetQueryResponseBase;
import datawave.webservice.result.GenericResponse;
import datawave.webservice.results.datadictionary.DataDictionaryBase;
import datawave.webservice.results.datadictionary.DescriptionBase;
import datawave.webservice.results.datadictionary.FieldsBase;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jboss.security.JSSESecurityDomain;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.ws.rs.core.MediaType;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class RemoteUserOperationsImplHttpTest {
    
    private static final int keysize = 2048;
    
    private static final String commonName = "cn=www.test.us";
    private static final String alias = "tomcat";
    private static final char[] keyPass = "changeit".toCharArray();
    
    private X500Name x500Name;
    
    private static final int PORT = 0;
    
    private HttpServer server;
    
    private RemoteUserOperationsImpl remote;
    
    @Before
    public void setup() throws Exception {
        final ObjectMapper objectMapper = new DefaultMapperDecorator().decorate(new ObjectMapper());
        System.setProperty(DnUtils.SUBJECT_DN_PATTERN_PROPERTY, ".*ou=server.*");
        KeyPairGenerator generater = KeyPairGenerator.getInstance("RSA");
        generater.initialize(keysize);
        KeyPair keypair = generater.generateKeyPair();
        PrivateKey privKey = keypair.getPrivate();
        final X509Certificate[] chain = new X509Certificate[1];
        x500Name = new X500Name(commonName);
        SubjectPublicKeyInfo subPubKeyInfo = SubjectPublicKeyInfo.getInstance(keypair.getPublic().getEncoded());
        final Date start = new Date();
        final Date until = Date.from(LocalDate.now().plus(365, ChronoUnit.DAYS).atStartOfDay().toInstant(ZoneOffset.UTC));
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(x500Name, new BigInteger(10, new SecureRandom()), start, until, x500Name, subPubKeyInfo);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").setProvider(new BouncyCastleProvider()).build(keypair.getPrivate());
        final X509CertificateHolder holder = builder.build(signer);
        
        chain[0] = new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider()).getCertificate(holder);
        
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(null);
        server.start();
        
        DefaultAuthorizationsList listEffectiveAuthResponse = new DefaultAuthorizationsList();
        listEffectiveAuthResponse.setUserAuths("testuserDn", "testissuerDn", Arrays.asList("auth1", "auth2"));
        listEffectiveAuthResponse.setAuthMapping(new HashMap<>());
        
        HttpHandler listEffectiveAuthorizationsHandler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String responseBody = objectMapper.writeValueAsString(listEffectiveAuthResponse);
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, responseBody.length());
                IOUtils.write(responseBody, exchange.getResponseBody(), Charset.forName("UTF-8"));
                exchange.close();
            }
        };
        
        GenericResponse<String> flushResponse = new GenericResponse<>();
        flushResponse.setResult("test flush result");
        
        HttpHandler flushHandler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String responseBody = objectMapper.writeValueAsString(flushResponse);
                exchange.getResponseHeaders().add("Content-Type", MediaType.APPLICATION_JSON);
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, responseBody.length());
                IOUtils.write(responseBody, exchange.getResponseBody(), Charset.forName("UTF-8"));
                exchange.close();
            }
        };
        
        server.createContext("/Security/User/listEffectiveAuthorizations", listEffectiveAuthorizationsHandler);
        server.createContext("/Security/User/flushCachedCredentials", flushHandler);
        
        // create a remote event query logic that has our own server behind it
        remote = new RemoteUserOperationsImpl();
        remote.setQueryServiceURI("/Security/User/");
        remote.setQueryServiceScheme("http");
        remote.setQueryServiceHost("localhost");
        remote.setQueryServicePort(server.getAddress().getPort());
        remote.setExecutorService(null);
        remote.setObjectMapperDecorator(new DefaultMapperDecorator());
        remote.setResponseObjectFactory(new MockResponseObjectFactory());
        remote.setJsseSecurityDomain(new JSSESecurityDomain() {
            @Override
            public KeyStore getKeyStore() throws SecurityException {
                try {
                    KeyStore keyStore = KeyStore.getInstance("JKS");
                    keyStore.load(null, null);
                    keyStore.setKeyEntry(alias, privKey, keyPass, chain);
                    keyStore.store(new FileOutputStream(".keystore"), keyPass);
                    return keyStore;
                } catch (Exception e) {
                    throw new SecurityException(e);
                }
            }
            
            @Override
            public KeyManager[] getKeyManagers() throws SecurityException {
                KeyManager[] managers = new KeyManager[1];
                managers[0] = new X509KeyManager() {
                    @Override
                    public String[] getClientAliases(String keyType, Principal[] issuers) {
                        return new String[0];
                    }
                    
                    @Override
                    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                        return null;
                    }
                    
                    @Override
                    public String[] getServerAliases(String keyType, Principal[] issuers) {
                        return new String[0];
                    }
                    
                    @Override
                    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                        return null;
                    }
                    
                    @Override
                    public X509Certificate[] getCertificateChain(String alias) {
                        return chain;
                    }
                    
                    @Override
                    public PrivateKey getPrivateKey(String alias) {
                        return privKey;
                    }
                };
                return managers;
            }
            
            @Override
            public KeyStore getTrustStore() throws SecurityException {
                try {
                    KeyStore keyStore = KeyStore.getInstance("JKS");
                    keyStore.load(null, null);
                    keyStore.setKeyEntry(alias, privKey, keyPass, chain);
                    keyStore.store(new FileOutputStream(".keystore"), keyPass);
                    return keyStore;
                } catch (Exception e) {
                    throw new SecurityException(e);
                }
            }
            
            @Override
            public TrustManager[] getTrustManagers() throws SecurityException {
                return new TrustManager[0];
            }
            
            @Override
            public void reloadKeyAndTrustStore() throws Exception {
                
            }
            
            @Override
            public String getServerAlias() {
                return null;
            }
            
            @Override
            public String getClientAlias() {
                return null;
            }
            
            @Override
            public boolean isClientAuth() {
                return false;
            }
            
            @Override
            public Key getKey(String s, String s1) throws Exception {
                return null;
            }
            
            @Override
            public Certificate getCertificate(String s) throws Exception {
                return null;
            }
            
            @Override
            public String[] getCipherSuites() {
                return new String[0];
            }
            
            @Override
            public String[] getProtocols() {
                return new String[0];
            }
            
            @Override
            public Properties getAdditionalProperties() {
                return null;
            }
            
            @Override
            public String getSecurityDomain() {
                return null;
            }
        });
    }
    
    @After
    public void after() {
        if (server != null) {
            server.stop(0);
        }
    }
    
    @Test
    public void testRemoteUserOperations() throws Exception {
        DatawavePrincipal principal = new DatawavePrincipal(commonName);
        
        AuthorizationsListBase auths = remote.listEffectiveAuthorizations(principal);
        assertEquals(2, auths.getAllAuths().size());
        
        GenericResponse flush = remote.flushCachedCredentials(principal);
        assertEquals("test flush result", flush.getResult());
    }
    
    public static class MockResponseObjectFactory extends ResponseObjectFactory {
        
        @Override
        public EventBase getEvent() {
            return null;
        }
        
        @Override
        public FieldBase getField() {
            return null;
        }
        
        @Override
        public EventQueryResponseBase getEventQueryResponse() {
            return null;
        }
        
        @Override
        public CacheableQueryRow getCacheableQueryRow() {
            return null;
        }
        
        @Override
        public EdgeBase getEdge() {
            return null;
        }
        
        @Override
        public EdgeQueryResponseBase getEdgeQueryResponse() {
            return null;
        }
        
        @Override
        public FacetQueryResponseBase getFacetQueryResponse() {
            return null;
        }
        
        @Override
        public FacetsBase getFacets() {
            return null;
        }
        
        @Override
        public FieldCardinalityBase getFieldCardinality() {
            return null;
        }
        
        @Override
        public KeyBase getKey() {
            return null;
        }
        
        @Override
        public AuthorizationsListBase getAuthorizationsList() {
            return new DefaultAuthorizationsList();
        }
        
        @Override
        public Query getQueryImpl() {
            return null;
        }
        
        @Override
        public DataDictionaryBase getDataDictionary() {
            return null;
        }
        
        @Override
        public FieldsBase getFields() {
            return null;
        }
        
        @Override
        public DescriptionBase getDescription() {
            return null;
        }
        
        @Override
        public MetadataFieldBase getMetadataField() {
            return null;
        }
    }
    
}
