package datawave.query.cardinality;

import datawave.query.model.QueryModel;
import datawave.webservice.model.Direction;
import datawave.webservice.model.FieldMapping;
import datawave.webservice.model.Model;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TestCardinalityConfiguration {
    
    private static QueryModel QUERY_MODEL = null;
    static Map<String,String> reverseMap = null;
    
    @BeforeAll
    static void init() throws Exception {
        
        URL mUrl = TestCardinalityConfiguration.class.getResource("/models/CardinalityModel.xml");
        JAXBContext ctx = JAXBContext.newInstance(datawave.webservice.model.Model.class);
        Unmarshaller u = ctx.createUnmarshaller();
        Model MODEL = (datawave.webservice.model.Model) u.unmarshal(mUrl);
        
        QUERY_MODEL = new QueryModel();
        for (FieldMapping mapping : MODEL.getFields()) {
            if (mapping.getDirection().equals(Direction.FORWARD)) {
                QUERY_MODEL.addTermToModel(mapping.getModelFieldName(), mapping.getFieldName());
            } else {
                QUERY_MODEL.addTermToReverseModel(mapping.getFieldName(), mapping.getModelFieldName());
            }
        }
        
        reverseMap = new HashMap<>();
        reverseMap.put("FIELD_1", "UUID");
        reverseMap.put("FIELD_3", "R_LABEL");
        reverseMap.put("FIELD_4", "FIELD_4B");
        reverseMap.put("FIELD_2", "PROTOCOL");
    }
    
    private Set<String> asSet(String[] fields) {
        return new HashSet<>(Arrays.asList(fields));
    }
    
    @Test
    public void testRemoveCardinalityFieldsFromBlacklist1() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"QUERY_USER", "QUERY_SYSTEM_FROM", "QUERY_LOGIC_NAME", "RESULT_DATA_AGE", "RESULT_DATATYPE", "R_LABEL",
                "FIELD_4B", "QUERY_USER|PROTOCOL"}));
        
        // FIELD_2A is in the forward AND reverse model for field FIELD_2
        Set<String> originalBlacklistFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD_2A", "FIELD3"});
        Set<String> revisedBlacklist = config.getRevisedBlacklistFields(QUERY_MODEL, originalBlacklistFieldsSet);
        
        Assertions.assertEquals(3, revisedBlacklist.size());
        Assertions.assertFalse(revisedBlacklist.contains("FIELD_2A"));
    }
    
    @Test
    public void testRemoveCardinalityFieldsFromBlacklist2() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"QUERY_USER", "QUERY_SYSTEM_FROM", "QUERY_LOGIC_NAME", "RESULT_DATA_AGE", "RESULT_DATATYPE", "R_LABEL",
                "FIELD_4B", "QUERY_USER|PROTOCOL"}));
        
        // FIELD_2B is only in the forward model for field FIELD_2
        Set<String> originalBlacklistFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD_2B", "FIELD3"});
        Set<String> revisedBlacklist = config.getRevisedBlacklistFields(QUERY_MODEL, originalBlacklistFieldsSet);
        
        Assertions.assertEquals(3, revisedBlacklist.size());
        Assertions.assertFalse(revisedBlacklist.contains("FIELD_2B"));
    }
    
    @Test
    public void testRemoveCardinalityFieldsFromBlacklist3() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"QUERY_USER", "QUERY_SYSTEM_FROM", "QUERY_LOGIC_NAME", "RESULT_DATA_AGE", "RESULT_DATATYPE", "R_LABEL",
                "FIELD_4B", "QUERY_USER|PROTOCOL"}));
        
        // FIELD_2B is only in the forward model for field FIELD_2
        // FIELD_3A is only in the forward model for FIELD_3
        // FIELD_3 included twice -- once directly and once by model
        Set<String> originalBlacklistFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD_3", "FIELD_2B", "FIELD_3A", "FIELD3"});
        Set<String> revisedBlacklist = config.getRevisedBlacklistFields(QUERY_MODEL, originalBlacklistFieldsSet);
        
        Assertions.assertEquals(3, revisedBlacklist.size());
        Assertions.assertFalse(revisedBlacklist.contains("FIELD_2B"));
        Assertions.assertFalse(revisedBlacklist.contains("FIELD_3A"));
        Assertions.assertFalse(revisedBlacklist.contains("FIELD_3"));
    }
    
    @Test
    public void testAddCardinalityFieldsToProjectFields1() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"QUERY_USER", "QUERY_SYSTEM_FROM", "QUERY_LOGIC_NAME", "RESULT_DATA_AGE", "RESULT_DATATYPE", "R_LABEL",
                "FIELD_4B", "QUERY_USER|PROTOCOL"}));
        
        Set<String> originalProjectFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD3"});
        Set<String> revisedProjectFields = config.getRevisedProjectFields(QUERY_MODEL, originalProjectFieldsSet);
        
        Assertions.assertEquals(7, revisedProjectFields.size());
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_2A"));
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_3C"));
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_4A"));
        Assertions.assertTrue(revisedProjectFields.contains("UUID"));
    }
    
    @Test
    public void testAddCardinalityFieldsToProjectFields2() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"R_LABEL", "QUERY_USER|PROTOCOL"}));
        
        Set<String> originalProjectFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD3"});
        Set<String> revisedProjectFields = config.getRevisedProjectFields(QUERY_MODEL, originalProjectFieldsSet);
        
        Assertions.assertEquals(6, revisedProjectFields.size());
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_2A"));
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_3C"));
        Assertions.assertTrue(revisedProjectFields.contains("UUID"));
    }
    
    @Test
    public void testAddCardinalityFieldsToProjectFieldsNoModel() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"R_LABEL", "QUERY_USER|PROTOCOL"}));
        
        Set<String> originalProjectFieldsSet = asSet(new String[] {"FIELD1", "FIELD2", "FIELD3"});
        Set<String> revisedProjectFields = config.getRevisedProjectFields(null, originalProjectFieldsSet);
        
        Assertions.assertEquals(6, revisedProjectFields.size());
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_1"));
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_2"));
        Assertions.assertTrue(revisedProjectFields.contains("FIELD_3"));
    }
    
    @Test
    public void testAddCardinalityFieldsToProjectFieldsNoWhitelist() {
        
        CardinalityConfiguration config = new CardinalityConfiguration();
        config.setCardinalityUidField("UUID");
        config.setCardinalityFieldReverseMapping(reverseMap);
        config.setCardinalityFields(asSet(new String[] {"R_LABEL", "QUERY_USER|PROTOCOL"}));
        
        Set<String> originalProjectFieldsSet = Collections.emptySet();
        Set<String> revisedProjectFields = config.getRevisedProjectFields(QUERY_MODEL, originalProjectFieldsSet);
        
        Assertions.assertEquals(0, revisedProjectFields.size());
    }
    
}
