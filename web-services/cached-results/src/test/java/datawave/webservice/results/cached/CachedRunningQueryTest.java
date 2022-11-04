package datawave.webservice.results.cached;

import datawave.microservice.querymetric.QueryMetricFactoryImpl;
import datawave.webservice.query.QueryImpl;
import datawave.webservice.query.cachedresults.CacheableQueryRowImpl;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@ExtendWith(MockitoExtension.class)
public class CachedRunningQueryTest {
    
    @Mock
    QueryImpl query;
    
    CachedRunningQuery crq;
    
    private String fixedColumns = StringUtils.join(CacheableQueryRowImpl.getFixedColumnSet(), ",");
    
    @BeforeEach
    public void setup() throws Exception {
        crq = new CachedRunningQuery(query, null, "", "", "", "", 0, "", null, null, new QueryMetricFactoryImpl());
        ReflectionTestUtils.setField(crq, "variableFields", new TreeSet<>());
        
        List<String> columns = new ArrayList<>();
        columns.add("foo.bar");
        columns.add("hey.there");
        columns.add("hi.there");
        columns.add("ho.there");
        columns.add("test1");
        columns.add("UPTIME.0");
        ReflectionTestUtils.setField(crq, "viewColumnNames", columns);
        // Add the fields we will be testing with
        crq.setVariableFields(Collections.singleton("foo.bar"));
    }
    
    @Test
    public void testNullFields() throws Exception {
        String sql = crq.generateSql("v", null, null, null, null, "me", null);
        Assertions.assertEquals("SELECT * FROM v WHERE _user_ = 'me'", sql);
    }
    
    @Test
    public void testEmptyFields() throws Exception {
        String sql = crq.generateSql("v", "", null, null, null, "me", null);
        Assertions.assertEquals("SELECT * FROM v WHERE _user_ = 'me'", sql);
    }
    
    @Test
    public void testSelectStar() throws Exception {
        String sql = crq.generateSql("v", "*", null, null, null, "me", null);
        Assertions.assertEquals("SELECT * FROM v WHERE _user_ = 'me'", sql);
    }
    
    @Test
    public void testColumnsThatNeedEscaping() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, null, null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me'", sql);
    }
    
    @Test
    public void testColumnsThatNeedEscapingWithBackTicks() throws Exception {
        String sql = crq.generateSql("v", "`foo.bar`", null, null, null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me'", sql);
    }
    
    @Test
    public void testDateFunctionInOrder() throws Exception {
        String startDateString = "Tue Apr 8 10:30:57 GMT 2014";
        String endDateString = "Wed Apr 9 10:30:57 GMT 2014";
        String UPTIME = "UPTIME.0";
        String dateFormat = "%a %b %e %H:%i:%s GMT %Y";
        String colName = "STR_TO_DATE(`" + UPTIME + "`, '" + dateFormat + "')";
        String conditions = colName + " BETWEEN STR_TO_DATE('" + startDateString + "', '%a %b %e %H:%i:%s GMT %Y') AND STR_TO_DATE('" + endDateString
                        + "', '%a %b %e %H:%i:%s GMT %Y')";
        String order = "STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y')";
        String sql = crq.generateSql("v", null, conditions, null, order + " DESC," + order + " ASC", "me", null);
        String want = "SELECT * FROM v WHERE _user_ = 'me' AND (STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y') BETWEEN STR_TO_DATE('Tue Apr 8 10:30:57 GMT 2014', '%a %b %e %H:%i:%s GMT %Y') AND STR_TO_DATE('Wed Apr 9 10:30:57 GMT 2014', '%a %b %e %H:%i:%s GMT %Y')) ORDER BY STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y') DESC,STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y') ASC";
        Assertions.assertEquals(want, sql);
    }
    
    @Test
    public void testDateFunction() throws Exception {
        String startDateString = "Tue Apr 8 10:30:57 GMT 2014";
        String endDateString = "Wed Apr 9 10:30:57 GMT 2014";
        String UPTIME = "UPTIME.";
        String dateFormat = "%a %b %e %H:%i:%s GMT %Y";
        String colName = "STR_TO_DATE(`" + UPTIME + "`, '" + dateFormat + "')";
        String conditions = colName + " BETWEEN STR_TO_DATE('" + startDateString + "', '%a %b %e %H:%i:%s GMT %Y') AND STR_TO_DATE('" + endDateString
                        + "', '%a %b %e %H:%i:%s GMT %Y')";
        String order = null;
        String sql = crq.generateSql("v", null, conditions, null, order, "me", null);
        Assertions.assertEquals(
                        "SELECT * FROM v WHERE _user_ = 'me' AND (STR_TO_DATE(`UPTIME.`, '%a %b %e %H:%i:%s GMT %Y') BETWEEN STR_TO_DATE('Tue Apr 8 10:30:57 GMT 2014', '%a %b %e %H:%i:%s GMT %Y') AND STR_TO_DATE('Wed Apr 9 10:30:57 GMT 2014', '%a %b %e %H:%i:%s GMT %Y'))",
                        sql);
    }
    
    @Test
    public void testWhereClause() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", "foo.bar = 'ABC'", null, null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' AND (`foo.bar` = 'ABC')", sql);
    }
    
    @Test
    public void testWhereClauseWithBackTicks() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", "`foo.bar` = 'ABC'", null, null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' AND (`foo.bar` = 'ABC')", sql);
    }
    
    @Test
    public void testColumnThatNeedEscapingInOrderBy() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, null, "foo.bar", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' ORDER BY `foo.bar`", sql);
    }
    
    @Test
    public void testColumnThatNeedEscapingInOrderByWithBackTicks() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, null, "`foo.bar`", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' ORDER BY `foo.bar`", sql);
    }
    
    @Test
    public void testColumnThatNeedEscapingInGroupBy() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, "foo.bar", null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' GROUP BY `foo.bar`", sql);
    }
    
    @Test
    public void testColumnThatNeedEscapingInGroupByWithBackTicks() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, "`foo.bar`", null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' GROUP BY `foo.bar`", sql);
    }
    
    @Test
    public void testEscapingWithFunctions() throws Exception {
        String sql = crq.generateSql("v", "foo.bar, MIN(foo.bar), COUNT(foo.bar)", "COUNT(foo.bar) > 1", "MIN(foo.bar)", "COUNT(foo.bar)", "me", null);
        Assertions.assertEquals(
                        "SELECT "
                                        + fixedColumns
                                        + ",`foo.bar`,MIN(`foo.bar`),COUNT(`foo.bar`) FROM v WHERE _user_ = 'me' AND (COUNT(`foo.bar`) > 1) GROUP BY MIN(`foo.bar`) ORDER BY COUNT(`foo.bar`)",
                        sql);
    }
    
    @Test
    public void testEscapingWithFunctionsAsterisk() throws Exception {
        String sql = crq.generateSql("v", "*, COUNT(*)", "COUNT(*) > 1", "MIN(*)", "COUNT(*)", "me", null);
        Assertions.assertEquals("SELECT " + "*,COUNT(*) FROM v WHERE _user_ = 'me' AND (COUNT(*) > 1) GROUP BY MIN(*) ORDER BY COUNT(*)", sql);
    }
    
    @Test
    public void testOrderByWithDirection() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, null, "foo.bar DESC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' ORDER BY `foo.bar` DESC", sql);
    }
    
    @Test
    public void testOrderByWithDirectionWithBackTicks() throws Exception {
        String sql = crq.generateSql("v", "foo.bar", null, null, "`foo.bar` DESC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' ORDER BY `foo.bar` DESC", sql);
    }
    
    @Test
    public void testSumKeyword() throws Exception {
        String sql = crq.generateSql("v", "SUM(foo.bar)", null, null, "`foo.bar` DESC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",SUM(`foo.bar`) FROM v WHERE _user_ = 'me' ORDER BY `foo.bar` DESC", sql);
    }
    
    @Test
    public void testCountKeyword() throws Exception {
        String sql = crq.generateSql("v", "COUNT(foo.bar)", null, null, "`foo.bar` DESC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",COUNT(`foo.bar`) FROM v WHERE _user_ = 'me' ORDER BY `foo.bar` DESC", sql);
    }
    
    @Test
    public void testAlias() throws Exception {
        String sql = crq.generateSql("v", "COUNT(foo.bar) as temp", null, "temp", "temp ASC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",COUNT(`foo.bar`) as temp FROM v WHERE _user_ = 'me' GROUP BY temp ORDER BY temp ASC", sql);
    }
    
    @Test
    public void testComplexConditions() throws Exception {
        Set<String> variableFields = new HashSet<>();
        variableFields.add("foo.bar");
        variableFields.add("FIELD1.10.1.0");
        variableFields.add("FIELD2.30.5.1");
        variableFields.add("FIELD3.20.5.1");
        crq.setVariableFields(variableFields);
        
        String sql = crq.generateSql("v", "foo.bar", "((( FIELD1.10.1.0 = 'value.1' ) or ( FIELD2.30.5.1 = 'george' )) and  FIELD3.20.5.1 = 'someday' )", null,
                        null, "me", null);
        Assertions.assertEquals(
                        "SELECT "
                                        + fixedColumns
                                        + ",`foo.bar` FROM v WHERE _user_ = 'me' AND (((( `FIELD1.10.1.0` = 'value.1' ) or ( `FIELD2.30.5.1` = 'george' )) and  `FIELD3.20.5.1` = 'someday' ))",
                        sql);
    }
    
    @Test
    public void testUpdate() throws Exception {
        
        String sql = crq.generateSql("v", "foo.bar", null, null, null, "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me'", sql);
        
        ReflectionTestUtils.setField(crq, "view", "v");
        ReflectionTestUtils.setField(crq, "fields", "foo.bar");
        
        Assertions.assertTrue(crq.update(null, null, null, "`foo.bar` DESC", null));
        
        String sql2 = crq.generateSql("v", "foo.bar", null, null, "`foo.bar` DESC", "me", null);
        Assertions.assertEquals("SELECT " + fixedColumns + ",`foo.bar` FROM v WHERE _user_ = 'me' ORDER BY `foo.bar` DESC", sql2);
    }
    
    @Test
    public void testIsFunction() throws Exception {
        Method m = CachedRunningQuery.class.getDeclaredMethod("isFunction", String.class);
        m.setAccessible(true);
        Object result = m.invoke(crq, "MIN(foo.bar)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "COUNT(foo.bar)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "MAX(foo.bar)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "SUM(foo.bar)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "MIN(STR_TO_DATE(foo.bar, '%Y'))");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "MAX(STR_TO_DATE(foo.bar, '%Y'))");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "foo.bar");
        Assertions.assertFalse((Boolean) result);
        result = m.invoke(crq, "INET_ATON('1.2.3.4')");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "INET_NTOA(10000)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "CONVERT('5',UNSIGNED)");
        Assertions.assertTrue((Boolean) result);
        result = m.invoke(crq, "STR_TO_DATE(foo.bar, '%Y')");
        Assertions.assertTrue((Boolean) result);
    }
    
    @Test
    public void testQuoteField() throws Exception {
        Method m = CachedRunningQuery.class.getDeclaredMethod("quoteField", String.class);
        m.setAccessible(true);
        Object result = m.invoke(crq, "foo.bar");
        Assertions.assertEquals("`foo.bar`", result.toString());
        result = m.invoke(crq, "MIN(foo.bar)");
        Assertions.assertEquals("MIN(`foo.bar`)", result.toString());
        result = m.invoke(crq, "MAX(foo.bar)");
        Assertions.assertEquals("MAX(`foo.bar`)", result.toString());
        result = m.invoke(crq, "SUM(foo.bar)");
        Assertions.assertEquals("SUM(`foo.bar`)", result.toString());
        result = m.invoke(crq, "COUNT(foo.bar)");
        Assertions.assertEquals("COUNT(`foo.bar`)", result.toString());
    }
    
    @Test
    public void testInvalidFunction() throws Exception {
        Method m = CachedRunningQuery.class.getDeclaredMethod("isFunction", String.class);
        m.setAccessible(true);
        InvocationTargetException e = Assertions.assertThrows(InvocationTargetException.class, () -> m.invoke(crq, "SIN(foo.bar)"));
        Assertions.assertTrue(e.getCause() instanceof IllegalArgumentException);
    }
    
    @Test
    public void testDSGroupByQuery() throws Exception {
        
        List<String> columns = new ArrayList<>();
        columns.add("TIME.0");
        columns.add("TIME.0");
        ReflectionTestUtils.setField(crq, "viewColumnNames", columns);
        // Add the fields we will be testing with
        crq.setVariableFields(new TreeSet<>(columns));
        
        String sql = crq.generateSql("v", "*,COUNT(*) AS GROUP_TOTAL, MIN(STR_TO_DATE(`TIME.0`, '%a %b %e %H:%i:%s GMT %Y')) as FIRST_SEEN,"
                        + "MAX(STR_TO_DATE(TIME.0, '%a %b %e %H:%i:%s GMT %Y')) AS LAST_SEEN", null, "GROUP_FIELD.10.5.0", null, "me", null);
        Assertions.assertEquals("SELECT *,COUNT(*) AS GROUP_TOTAL,MIN(STR_TO_DATE(`TIME.0`, '%a %b %e %H:%i:%s GMT %Y')) as FIRST_SEEN,"
                        + "MAX(STR_TO_DATE(`TIME.0`, '%a %b %e %H:%i:%s GMT %Y')) AS LAST_SEEN FROM v WHERE _user_ = 'me'" + " GROUP BY `GROUP_FIELD.10.5.0`",
                        sql);
        
    }
    
    @Test
    public void testSplitStringOnCommaButNotInParentheses() throws IOException {
        String fields = "*,COUNT(*) AS GROUP_TOTAL, MIN(STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y')) as FIRST_SEEN," + "MAX(UPTIME.0) AS LAST_SEEN";
        
        List<String> result = new ArrayList<>();
        int start = 0;
        boolean inParens = false;
        for (int current = 0; current < fields.length(); current++) {
            if (fields.charAt(current) == '(')
                inParens = true;
            if (fields.charAt(current) == ')')
                inParens = false;
            boolean atLastChar = (current == fields.length() - 1);
            if (atLastChar) {
                result.add(fields.substring(start));
            } else if (fields.charAt(current) == ',' && !inParens) {
                result.add(fields.substring(start, current));
                start = current + 1;
            }
        }
        
        Assertions.assertEquals(4, result.size());
        Assertions.assertEquals("*", result.get(0));
        Assertions.assertEquals("COUNT(*) AS GROUP_TOTAL", result.get(1));
        Assertions.assertEquals(" MIN(STR_TO_DATE(`UPTIME.0`, '%a %b %e %H:%i:%s GMT %Y')) as FIRST_SEEN", result.get(2));
        Assertions.assertEquals("MAX(UPTIME.0) AS LAST_SEEN", result.get(3));
        
    }
    
    @Test
    public void testOrderClauseParsing() {
        String[] ins = {"STR_TO_DATE(1,2) ASC,INET_ATON(3,4) DSC", "STR_TO_DATE(1, 2) ASC,INET_ATON(3, 4) DSC", "STR_TO_DATE(1, 2) ,INET_ATON(3, 4) DSC",
                "STR_TO_DATE(hey.there, 2) ,INET_ATON(3, 4) ", "STR_TO_DATE(hi.there, 2) ,INET_ATON(`hi.there`, 4) ",
                "MIN(STR_TO_DATE(ho.there, 2))   ASC ,INET_ATON(3, 4) "};
        String[] expected = {"STR_TO_DATE(1,2) ASC,INET_ATON(3,4) DSC", "STR_TO_DATE(1, 2) ASC,INET_ATON(3, 4) DSC", "STR_TO_DATE(1, 2),INET_ATON(3, 4) DSC",
                "STR_TO_DATE(`hey.there`, 2),INET_ATON(3, 4)", "STR_TO_DATE(`hi.there`, 2),INET_ATON(`hi.there`, 4)",
                "MIN(STR_TO_DATE(`ho.there`, 2)) ASC,INET_ATON(3, 4)"
        
        };
        for (int i = 0; i < ins.length; i++) {
            String got = crq.buildOrderClause(ins[i]);
            Assertions.assertEquals(expected[i], got);
        }
    }
}
