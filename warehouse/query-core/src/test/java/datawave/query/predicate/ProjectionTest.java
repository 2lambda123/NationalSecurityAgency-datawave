package datawave.query.predicate;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProjectionTest {
    
    @Test
    public void testNoConfiguration() {
        Projection projection = new Projection();
        assertThrows(RuntimeException.class, () -> assertTrue(projection.apply("FIELD_A")));
    }
    
    @Test
    public void testTooMuchConfiguration() {
        Projection projection = new Projection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        assertThrows(RuntimeException.class, () -> projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y")));
        assertTrue(projection.apply("FIELD_A"));
        
    }
    
    @Test
    public void testTooMuchOfTheSameConfiguration() {
        Projection projection = new Projection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        assertThrows(RuntimeException.class, () -> projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B")));
        assertTrue(projection.apply("FIELD_A"));
    }
    
    @Test
    public void testIncludes() {
        Projection projection = new Projection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        
        assertTrue(projection.isUseIncludes());
        assertFalse(projection.isUseExcludes());
        
        assertTrue(projection.apply("FIELD_A"));
        assertTrue(projection.apply("FIELD_B"));
        assertFalse(projection.apply("FIELD_C"));
        assertFalse(projection.apply("FIELD_X"));
        assertFalse(projection.apply("FIELD_Y"));
        assertFalse(projection.apply("FIELD_Z"));
    }
    
    @Test
    public void testIncludesWithGroupingContext() {
        Projection projection = new Projection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        
        assertTrue(projection.isUseIncludes());
        assertFalse(projection.isUseExcludes());
        
        assertTrue(projection.apply("$FIELD_A"));
        assertTrue(projection.apply("FIELD_B.0"));
        assertFalse(projection.apply("FIELD_C"));
        assertFalse(projection.apply("$FIELD_X"));
        assertFalse(projection.apply("FIELD_Y.0"));
        assertFalse(projection.apply("FIELD_Z"));
    }
    
    @Test
    public void testExcludes() {
        Projection projection = new Projection();
        projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y"));
        
        assertFalse(projection.isUseIncludes());
        assertTrue(projection.isUseExcludes());
        
        assertTrue(projection.apply("FIELD_A"));
        assertTrue(projection.apply("FIELD_B"));
        assertTrue(projection.apply("FIELD_C"));
        assertFalse(projection.apply("FIELD_X"));
        assertFalse(projection.apply("FIELD_Y"));
        assertTrue(projection.apply("FIELD_Z"));
    }
    
    @Test
    public void testExcludesWithGroupingContext() {
        Projection projection = new Projection();
        projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y"));
        
        assertFalse(projection.isUseIncludes());
        assertTrue(projection.isUseExcludes());
        
        assertTrue(projection.apply("$FIELD_A"));
        assertTrue(projection.apply("FIELD_B.0"));
        assertTrue(projection.apply("FIELD_C"));
        assertFalse(projection.apply("$FIELD_X"));
        assertFalse(projection.apply("FIELD_Y.0"));
        assertTrue(projection.apply("FIELD_Z"));
    }
    
    @Test
    public void testTheAbsurd() {
        Projection projection = new Projection();
        projection.setIncludes(Sets.newHashSet("PREFIX"));
        assertTrue(projection.apply("$PREFIX.SUFFIX01.SUFFIX.02"));
    }
}
