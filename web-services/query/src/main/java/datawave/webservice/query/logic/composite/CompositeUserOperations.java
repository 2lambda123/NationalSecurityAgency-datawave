package datawave.webservice.query.logic.composite;

import com.google.common.collect.Sets;
import datawave.security.authorization.AuthorizationException;
import datawave.security.authorization.DatawavePrincipal;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.SubjectIssuerDNPair;
import datawave.security.util.AuthorizationsUtil;
import datawave.user.AuthorizationsListBase;
import datawave.security.authorization.UserOperations;
import datawave.webservice.query.exception.QueryException;
import datawave.webservice.query.exception.QueryExceptionType;
import datawave.webservice.query.result.event.ResponseObjectFactory;
import datawave.webservice.result.GenericResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CompositeUserOperations implements UserOperations {
    final ResponseObjectFactory responseObjectFactory;
    final List<UserOperations> userOperations;
    final boolean includeLocal;
    
    public CompositeUserOperations(List<UserOperations> remoteOperations, boolean includeLocal, ResponseObjectFactory responseObjectFactory) {
        this.responseObjectFactory = responseObjectFactory;
        this.userOperations = remoteOperations;
        this.includeLocal = includeLocal;
    }
    
    @Override
    public AuthorizationsListBase listEffectiveAuthorizations(Object callerObject) throws AuthorizationException {
        AuthorizationsListBase auths = responseObjectFactory.getAuthorizationsList();
        if (callerObject instanceof DatawavePrincipal) {
            DatawavePrincipal principal = (DatawavePrincipal) callerObject;
            Map<AuthorizationsListBase.SubjectIssuerDNPair,Set<String>> authMap = new HashMap<>();
            if (includeLocal) {
                principal.getProxiedUsers().forEach(u -> authMap.put(dn(u.getDn()), new HashSet<>(u.getAuths())));
            }
            for (UserOperations ops : userOperations) {
                AuthorizationsListBase remoteAuths = ops.listEffectiveAuthorizations(callerObject);
                AuthorizationsListBase.SubjectIssuerDNPair userDn = new AuthorizationsListBase.SubjectIssuerDNPair(remoteAuths.getUserDn(),
                                remoteAuths.getIssuerDn());
                authMap.put(userDn, Sets.union(authMap.containsKey(userDn) ? authMap.get(userDn) : Collections.emptySet(), remoteAuths.getAllAuths()));
                Map<AuthorizationsListBase.SubjectIssuerDNPair,Set<String>> remoteAuthMap = remoteAuths.getAuths();
                for (Map.Entry<AuthorizationsListBase.SubjectIssuerDNPair,Set<String>> entry : remoteAuthMap.entrySet()) {
                    AuthorizationsListBase.SubjectIssuerDNPair dn = entry.getKey();
                    authMap.put(dn, Sets.union(authMap.containsKey(dn) ? authMap.get(dn) : Collections.emptySet(), entry.getValue()));
                }
            }
            DatawaveUser primaryUser = principal.getPrimaryUser();
            AuthorizationsListBase.SubjectIssuerDNPair primaryDn = dn(primaryUser.getDn());
            auths.setUserAuths(primaryDn.subjectDN, primaryDn.issuerDN, authMap.get(dn(primaryUser.getDn())));
            authMap.entrySet().stream().filter(e -> !e.getKey().equals(primaryDn))
                            .forEach(e -> auths.addAuths(e.getKey().subjectDN, e.getKey().issuerDN, e.getValue()));
        }
        return auths;
    }
    
    public static AuthorizationsListBase.SubjectIssuerDNPair dn(SubjectIssuerDNPair dn) {
        return new AuthorizationsListBase.SubjectIssuerDNPair(dn.subjectDN(), dn.issuerDN());
    }
    
    @Override
    public GenericResponse<String> flushCachedCredentials(Object callerObject) throws AuthorizationException {
        GenericResponse<String> response = new GenericResponse<>();
        response.setResult("");
        String separator = "";
        for (UserOperations ops : userOperations) {
            GenericResponse<String> remoteResponse = ops.flushCachedCredentials(callerObject);
            if (remoteResponse.getHasResults()) {
                response.setHasResults(true);
                response.setResult(response.getResult() + separator + remoteResponse.getResult());
                separator = "\n";
            }
            if (remoteResponse.getExceptions() != null) {
                for (QueryExceptionType e : remoteResponse.getExceptions()) {
                    response.addException(getException(e));
                }
            }
            if (remoteResponse.getMessages() != null) {
                for (String message : remoteResponse.getMessages()) {
                    response.addMessage(message);
                }
            }
            response.setOperationTimeMS(response.getOperationTimeMS() + remoteResponse.getOperationTimeMS());
        }
        return response;
    }
    
    @Override
    public DatawavePrincipal getRemoteUser(DatawavePrincipal principal) throws AuthorizationException {
        List<DatawavePrincipal> principals = new ArrayList<>();
        if (includeLocal) {
            principals.add(principal);
        }
        for (UserOperations ops : userOperations) {
            principals.add(ops.getRemoteUser(principal));
        }
        
        return AuthorizationsUtil.mergePrincipals(principals.toArray(new DatawavePrincipal[0]));
    }
    
    public static Exception getException(QueryExceptionType qet) {
        if (qet.getCode() != null) {
            if (qet.getCause() != null) {
                return new QueryException(qet.getMessage(), new RuntimeException(qet.getCause()), qet.getCode());
            } else {
                return new QueryException(qet.getMessage(), qet.getCode());
            }
        } else {
            return new RuntimeException(qet.getMessage());
        }
    }
}
