//package datawave.security.authorization.simple;
//
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.Collection;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.LinkedHashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Map.Entry;
//
//import com.google.common.collect.HashMultimap;
//import com.google.common.collect.Multimap;
//import datawave.configuration.DatawaveEmbeddedProjectStageHolder;
//import datawave.security.authorization.BasePrincipalFactory;
//import datawave.security.authorization.DatawavePrincipal;
//import datawave.security.authorization.DatawavePrincipal.UserType;
//import datawave.security.util.DnUtils;
//import org.apache.deltaspike.core.api.exclude.Exclude;
//import org.apache.log4j.Logger;
//
//@Exclude(ifProjectStage = DatawaveEmbeddedProjectStageHolder.DatawaveEmbedded.class)
//public class SimplePrincipalFactory extends BasePrincipalFactory {
//
//    private static final long serialVersionUID = 1L;
//    private static Logger log = Logger.getLogger(SimplePrincipalFactory.class);
//    private Map<String,String> roleToAuthorizationMap = new HashMap<>();
//
//    @Override
//    public DatawavePrincipal createPrincipal(String userName, String[] rawRoles) {
//
//        String[] roles = remapRoles(userName, rawRoles);
//        if (null != roles && roles.length > 1)
//            Arrays.sort(roles);
//        log.debug("We remapped the raw roles roles for " + userName + " into the following: " + Arrays.toString(roles));
//
//        Multimap<String,String> roleToAuthMapping = HashMultimap.create();
//        String[] auths = toAccumuloAuthorizations(roles, roleToAuthMapping);
//        Arrays.sort(auths);
//        log.debug("We computed the following ACCUMULO authorizations for " + userName + ": " + Arrays.toString(auths));
//
//        List<String> rolesList = (null != roles ? Arrays.asList(roles) : Collections.emptyList());
//        DatawavePrincipal datawavePrincipal = new DatawavePrincipal(userName);
//        datawavePrincipal.setUserRoles(userName, rolesList);
//        datawavePrincipal.setRoleSets(rolesList);
//        datawavePrincipal.setRoleToAuthMapping(roleToAuthMapping);
//        List<String> authList = new ArrayList<>(Arrays.asList(auths));
//        datawavePrincipal.setAuthorizations(userName, authList);
//        return datawavePrincipal;
//    }
//
//    @Override
//    public void mergePrincipals(DatawavePrincipal target, DatawavePrincipal additional) {
//
//        // A "user" user type will win over a server user type
//        if (additional.getUserType() == UserType.USER) {
//            target.setUserType(UserType.USER);
//        }
//
//        String additionalDNPair = additional.getName();
//
//        List<String> tgtRoleSets = target.getRoleSets();
//        List<String> addRoleSets = additional.getRoleSets();
//        if (addRoleSets != null) {
//            if (tgtRoleSets == null) {
//                target.setRoleSets(addRoleSets);
//            } else {
//                String[] tgtRoles = tgtRoleSets.toArray(new String[0]);
//                String[] addRoles = addRoleSets.toArray(new String[0]);
//                String[] mergedRoles = mergeRoles(tgtRoles, addRoles);
//                target.setRoleSets(Arrays.asList(mergedRoles));
//            }
//        }
//
//        Collection<? extends Collection<String>> additionalRoles = additional.getUserRoles();
//        if (additionalRoles != null && !additionalRoles.isEmpty()) {
//            target.setUserRoles(additionalDNPair, additionalRoles.iterator().next());
//        }
//
//        Collection<? extends Collection<String>> additionalAccumuloAuths = additional.getAuthorizations();
//        if (additionalAccumuloAuths != null && !additionalAccumuloAuths.isEmpty())
//            target.setAuthorizations(additionalDNPair, additionalAccumuloAuths.iterator().next());
//    }
//
//    @Override
//    public String[] toAccumuloAuthorizations(String[] roles, Multimap<String,String> roleToAuthMapping) {
//        for (String role : roles) {
//            roleToAuthMapping.put(role, role);
//        }
//        return roles;
//    }
//
//    @Override
//    public String[] remapRoles(String userName, String[] originalRoles) {
//
//        String[] dns = DnUtils.splitProxiedSubjectIssuerDNs(userName);
//        String subjectDN = dns[0];
//
//        LinkedHashSet<String> remappableRoles = new LinkedHashSet<>();
//        Collections.addAll(remappableRoles, originalRoles);
//
//        LinkedHashSet<String> additionalRoles = new LinkedHashSet<>();
//
//        additionalRoles.addAll(getDNTRoles(subjectDN));
//        remappableRoles.addAll(getOURoles(subjectDN));
//
//        LinkedHashSet<String> remappedRoles = new LinkedHashSet<>();
//        // map remappable roles to remapped roles if desired
//        remappedRoles.addAll(remappableRoles);
//
//        remappedRoles.addAll(additionalRoles);
//
//        // Add any additional requested roles based on having a combination of
//        // roles returned by the authorization service.
//        for (Entry<String,String> entry : roleToAuthorizationMap.entrySet()) {
//            String roleSet = entry.getKey();
//            if (remappedRoles.containsAll(Arrays.asList(roleSet.split("\\s*,\\s*"))))
//                remappedRoles.addAll(Arrays.asList(entry.getValue().split("\\s*,\\s*")));
//        }
//        return remappedRoles.toArray(new String[remappedRoles.size()]);
//    }
//
//    public String[] mergeRoles(String[] target, String[] additional) {
//        LinkedHashSet<String> s1 = asSet(target);
//        LinkedHashSet<String> s2 = asSet(additional);
//
//        LinkedHashSet<String> finalSet = new LinkedHashSet<>();
//        finalSet.addAll(s1);
//        finalSet.retainAll(s2);
//
//        return finalSet.toArray(new String[finalSet.size()]);
//    }
//
//    @SafeVarargs
//    private static <T> LinkedHashSet<T> asSet(T... vals) {
//        LinkedHashSet<T> set = new LinkedHashSet<>(Math.max(2 * vals.length, 11));
//        Collections.addAll(set, vals);
//        return set;
//    }
//
//    public Map<String,String> getRoleToAuthorizationMap() {
//        return roleToAuthorizationMap;
//    }
//
//    public void setRoleToAuthorizationMap(Map<String,String> roleToAuthorizationMap) {
//        this.roleToAuthorizationMap = roleToAuthorizationMap;
//    }
// }
