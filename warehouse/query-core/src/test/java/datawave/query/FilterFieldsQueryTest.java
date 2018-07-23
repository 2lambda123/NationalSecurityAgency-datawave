package datawave.query;

import datawave.query.language.parser.lucene.LuceneQueryParser;
import datawave.query.testframework.AbstractFunctionalQuery;
import datawave.query.testframework.AccumuloSetupHelper;
import datawave.query.testframework.CitiesDataType;
import datawave.query.testframework.CitiesDataType.CityEntry;
import datawave.query.testframework.CitiesDataType.CityField;
import datawave.query.testframework.GenericCityFields;
import datawave.query.testframework.IDataTypeHadoopConfig;
import datawave.query.testframework.IFieldConfig;
import datawave.query.testframework.QueryJexl;
import datawave.query.testframework.QueryLogicTestHarness;
import datawave.query.testframework.ResponseFieldChecker;
import org.apache.log4j.Logger;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs query test where specific returned fields are specified setting the {@link QueryParameters#RETURN_FIELDS} and
 * {@link QueryParameters#BLACKLISTED_FIELDS} parameter.
 */
public class FilterFieldsQueryTest extends AbstractFunctionalQuery {
    
    private static final Logger log = Logger.getLogger(FilterFieldsQueryTest.class);
    
    @BeforeClass
    public static void filterSetup() throws Exception {
        Collection<IDataTypeHadoopConfig> dataTypes = new ArrayList<>();
        IFieldConfig generic = new GenericCityFields();
        dataTypes.add(new CitiesDataType(CityEntry.generic, generic));
        
        final AccumuloSetupHelper helper = new AccumuloSetupHelper(dataTypes);
        connector = helper.loadTables(log);
    }
    
    public FilterFieldsQueryTest() {
        super(CitiesDataType.getManager());
    }
    
    @Test
    public void testEqCityAndEqState() throws Exception {
        log.info("------  testEqCityAndEqContinent  ------");
        
        for (final TestCities city : TestCities.values()) {
            String cont = "ohio";
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + CityField.STATE.name() + " == '" + cont + "'";
            runTest(query, true, false);
        }
    }
    
    @Test
    public void testEqCityAndEqContinentHitList() throws Exception {
        log.info("------  testEqCityAndEqContinentHitList  ------");
        
        String cont = "north america";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + CityField.CONTINENT.name() + " == '" + cont + "'";
            runTest(query, true, true);
        }
    }
    
    @Test
    public void testAnyField() throws Exception {
        log.info("------  testAnyField  ------");
        
        for (final TestCities city : TestCities.values()) {
            String cityPhrase = " == '" + city.name() + "'";
            String query = Constants.ANY_FIELD + cityPhrase;
            String expect = this.dataManager.convertAnyField(cityPhrase);
            runTest(query, expect, true, false);
        }
    }
    
    @Test
    public void testDisjunctionAnyField() throws Exception {
        log.info("------  testDisjunctionAnyField  ------");
        String noMatchPhrase = " == 'no-match-found'";
        String nothingPhrase = " == 'nothing-here'";
        String op = " or ";
        String query = Constants.ANY_FIELD + noMatchPhrase + op + Constants.ANY_FIELD + nothingPhrase;
        String anyNoMatch = this.dataManager.convertAnyField(noMatchPhrase);
        String anyNothing = this.dataManager.convertAnyField(nothingPhrase);
        String expect = anyNoMatch + op + anyNothing;
        runTest(query, expect, true, false);
    }
    
    @Test
    public void testConjunctionAnyField() throws Exception {
        log.info("------  testConjunctionAnyField  ------");
        String noMatchPhrase = " == 'no-match-found'";
        String nothingPhrase = " == 'nothing-here'";
        String op = " and ";
        String query = Constants.ANY_FIELD + noMatchPhrase + op + Constants.ANY_FIELD + nothingPhrase;
        String anyNoMatch = this.dataManager.convertAnyField(noMatchPhrase);
        String anyNothing = this.dataManager.convertAnyField(nothingPhrase);
        String expect = anyNoMatch + op + anyNothing;
        runTest(query, expect, true, false);
    }
    
    @Test
    public void testAnyFieldFilterIncludeRegex() throws Exception {
        log.info("------  testAnyFieldFilterIncludeRegex  ------");
        String anyState = this.dataManager.convertAnyField(" == 'ohio'");
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + " filter:includeRegex(" + Constants.ANY_FIELD + ",'ohio')";
            String expectQuery = CityField.CITY.name() + " == '" + city.name() + "' and " + anyState;
            runTest(query, expectQuery, true, false);
        }
    }
    
    // WIP
    @Ignore
    @Test
    public void testAnyFieldLuceneInclude() throws Exception {
        log.info("------  testAnyFieldLuceneInclude  ------");
        String anyState = this.dataManager.convertAnyField(" == 'ohio'");
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + ":" + city.name() + " and " + " #INCLUDE(" + Constants.ANY_FIELD + ",ohio)";
            String expectQuery = CityField.CITY.name() + " == '" + city.name() + "' and " + anyState;
            this.logic.setParser(new LuceneQueryParser());
            runTest(query, expectQuery, true, false);
        }
    }
    
    @Test
    public void testOccurrenceFunction() throws Exception {
        log.info("------  testOccurrenceFunction  ------");
        String cont = "europe";
        String query = CityField.CONTINENT.name() + " == '" + cont + "' and " + " filter:occurrence(" + CityField.CONTINENT.name() + ",'<', 2)";
        String expectQuery = CityField.CONTINENT.name() + " == '" + cont + "'";
        runTest(query, expectQuery, true, false);
    }
    
    @Test
    public void testZeroOccurrenceFunction() throws Exception {
        log.info("------  test  ------");
        String cont = "europe";
        String query = CityField.CONTINENT.name() + " == '" + cont + "' and " + " filter:occurrence(" + CityField.CONTINENT.name() + ",'>', 1)";
        String expectQuery = CityField.CONTINENT.name() + " == 'no-such-name'";
        runTest(query, expectQuery, true, false);
    }
    
    @Test
    public void testBlackListMultiValueIncluded() throws Exception {
        log.info("------  testBlackListMultiValueIncluded  ------");
        
        String cont = "europe";
        String state = "mississippi";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + "(" + CityField.CONTINENT.name() + " == '" + cont + "'" + " or "
                            + CityField.STATE.name() + " == '" + state + "')";
            final Set<String> fields = CityField.getRandomReturnFields(false);
            // remove CITY field
            fields.remove(CityField.CITY.name());
            runTest(query, false, false, fields);
        }
    }
    
    @Test
    public void testBlackListMultiValueExcluded() throws Exception {
        log.info("------  testBlackListMultiValueExcluded  ------");
        
        String cont = "europe";
        String state = "mississippi";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + "(" + CityField.CONTINENT.name() + " == '" + cont + "'" + " or "
                            + CityField.STATE.name() + " == '" + state + "')";
            final Set<String> fields = CityField.getRandomReturnFields(false);
            // include CITY field
            fields.add(CityField.CITY.name());
            runTest(query, false, false, fields);
        }
    }
    
    @Test
    public void testEqCityAndEqContinentBlackList() throws Exception {
        log.info("------  testEqCityAndEqContinentBlackList  ------");
        
        String state = "ohio";
        String mizzu = "missouri";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + "(" + CityField.STATE.name() + " == '" + state + "'" + " or "
                            + CityField.STATE.name() + " == '" + mizzu + "')";
            runTest(query, false, false);
        }
    }
    
    @Test
    public void testEqCityAndEqContinentBlackListWithHitList() throws Exception {
        log.info("------  testEqCityAndEqContinentBlackListWithHitList  ------");
        
        String cont = "europe";
        String state = "missouri";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + "(" + CityField.STATE.name() + " == '" + state + "'" + " or "
                            + CityField.CONTINENT.name() + " == '" + cont + "')";
            runTest(query, false, true);
        }
    }
    
    @Test
    public void testWhiteListWithMultiValueIncluded() throws Exception {
        log.info("------  testWhiteListWithMultiValueIncluded  ------");
        
        String cont = "north america";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + CityField.CONTINENT.name() + " == '" + cont + "'";
            final Set<String> fields = CityField.getRandomReturnFields(true);
            // include STATE field
            fields.add(CityField.STATE.name());
            runTest(query, true, true, fields);
        }
    }
    
    @Test
    public void testWhiteListWithMultiValueExcluded() throws Exception {
        log.info("------  testWhiteListWithMultiValueExcluded  ------");
        
        String cont = "north america";
        for (final TestCities city : TestCities.values()) {
            String query = CityField.CITY.name() + " == '" + city.name() + "' and " + CityField.CONTINENT.name() + " == '" + cont + "'";
            final Set<String> fields = CityField.getRandomReturnFields(true);
            // remove STATE field
            fields.remove(CityField.STATE.name());
            runTest(query, true, true, fields);
        }
    }
    
    // end of unit tests
    // ============================================
    
    // ============================================
    // implemented abstract methods
    protected void testInit() {
        this.auths = CitiesDataType.getTestAuths();
        this.documentKey = CityField.EVENT_ID.name();
    }
    
    // ============================================
    // private methods
    
    private void runTest(final String query, final String expectQuery, final boolean whiteList, final boolean hitList) throws Exception {
        Date[] startEndDate = this.dataManager.getShardStartEndDate();
        final Set<String> fields = CityField.getRandomReturnFields(whiteList);
        runTest(query, expectQuery, startEndDate[0], startEndDate[1], whiteList, hitList, fields);
    }
    
    private void runTest(final String query, final boolean whiteList, final boolean hitList) throws Exception {
        Date[] startEndDate = this.dataManager.getShardStartEndDate();
        final Set<String> fields = CityField.getRandomReturnFields(whiteList);
        runTest(query, query, startEndDate[0], startEndDate[1], whiteList, hitList, fields);
    }
    
    private void runTest(final String query, final boolean whiteList, final boolean hitList, Set<String> fields) throws Exception {
        Date[] startEndDate = this.dataManager.getShardStartEndDate();
        runTest(query, query, startEndDate[0], startEndDate[1], whiteList, hitList, fields);
    }
    
    /**
     * Base helper method for execution of a unit test.
     *
     * @param query
     *            query string for execution
     * @param expectQuery
     *            query string to use to calculate expected results
     * @param startDate
     *            start date of query
     * @param endDate
     *            end date of query
     * @param whiteList
     *            true to return specific fields; false to specify blacklist fields
     * @param hitList
     *            when true the option {@link QueryParameters#HIT_LIST} is set to true
     * @param fields
     *            return fields or blacklist fields
     * @throws Exception
     *             something failed - go figure it out
     */
    private void runTest(final String query, final String expectQuery, final Date startDate, final Date endDate, final boolean whiteList,
                    final boolean hitList, final Set<String> fields) throws Exception {
        
        QueryJexl jexl = new QueryJexl(expectQuery, this.dataManager, startDate, endDate);
        final Set<Map<String,String>> allData = jexl.evaluate();
        final Set<String> expected = this.dataManager.getKeys(allData);
        
        final Set<String> otherFields = new HashSet<>(this.dataManager.getHeaders());
        otherFields.removeAll(fields);
        
        final String queryFields = String.join(",", fields);
        
        Map<String,String> options = new HashMap<>();
        final List<QueryLogicTestHarness.DocumentChecker> queryChecker = new ArrayList<>();
        if (whiteList) {
            // NOTE CityField.EVENT_ID MUST be included in blacklisted fields
            options.put(QueryParameters.RETURN_FIELDS, queryFields);
            queryChecker.add(new ResponseFieldChecker(fields, otherFields));
        } else {
            // NOTE CityField.EVENT_ID CANNOT be included in blacklisted fields
            options.put(QueryParameters.BLACKLISTED_FIELDS, queryFields);
            queryChecker.add(new ResponseFieldChecker(otherFields, fields));
        }
        if (hitList) {
            options.put(QueryParameters.HIT_LIST, "true");
        }
        
        runTestQuery(expected, query, startDate, endDate, options, queryChecker);
    }
}
