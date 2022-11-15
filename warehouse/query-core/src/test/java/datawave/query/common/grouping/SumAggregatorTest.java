package datawave.query.common.grouping;

import datawave.query.attributes.Content;
import datawave.query.attributes.Numeric;
import org.apache.accumulo.core.data.Key;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link SumAggregator}.
 */
public class SumAggregatorTest {
    
    private SumAggregator aggregator;
    
    @Before
    public void setUp() throws Exception {
        aggregator = new SumAggregator("FIELD");
    }
    
    /**
     * Verify the initial sum is 0.
     */
    @Test
    public void testInitialSum() {
        assertSum(0d);
    }
    
    /**
     * Verify that if given a non-numeric value, that an exception is thrown.
     */
    @Test
    public void testNonNumericValue() {
        Content content = new Content("i am content", new Key(), true);
        
        IllegalArgumentException exception = Assert.assertThrows(IllegalArgumentException.class, () -> aggregator.aggregate(content));
        assertEquals("Unable to calculate a sum for an attribute of type datawave.query.attributes.Content", exception.getMessage());
    }
    
    /**
     * Verify that given additional numeric values, that the sum is correctly calculated.
     */
    @Test
    public void testAggregation() {
        aggregator.aggregate(createNumeric("4"));
        assertSum(4d);
        
        aggregator.aggregate(createNumeric("1"));
        aggregator.aggregate(createNumeric("1"));
        assertSum(6d);
        
        aggregator.aggregate(createNumeric("4.5"));
        assertSum(10.5d);
    }
    
    private Numeric createNumeric(String number) {
        return new Numeric(number, new Key(), true);
    }
    
    private void assertSum(Double sum) {
        assertEquals(sum, aggregator.getAggregation());
    }
}
