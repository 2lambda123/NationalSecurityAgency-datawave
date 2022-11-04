package datawave.query.predicate;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.accumulo.core.data.Key;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KeyProjectionTest {
    
    private List<Entry<Key,String>> fiData;
    private List<Entry<Key,String>> eventData;
    
    @BeforeEach
    public void setup() {
        fiData = new ArrayList<>();
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_A"), "data"));
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_B"), "data"));
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_C"), "data"));
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_X"), "data"));
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_Y"), "data"));
        fiData.add(Maps.immutableEntry(new Key("20200314_1", "fi\0FIELD_Z"), "data"));
        
        eventData = new ArrayList<>();
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_A\0value_a"), "data"));
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_B\0value_b"), "data"));
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_C\0value_c"), "data"));
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_X\0value_x"), "data"));
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_Y\0value_y"), "data"));
        eventData.add(Maps.immutableEntry(new Key("20200314_1", "datatype\0uid", "FIELD_Z\0value_z"), "data"));
    }
    
    @Test
    public void testNoConfiguration() {
        KeyProjection projection = new KeyProjection();
        
        Iterator<Entry<Key,String>> iter = fiData.iterator();
        assertThrows(RuntimeException.class, () -> assertTrue(projection.apply(iter.next())));
    }
    
    @Test
    public void testTooMuchConfiguration() {
        KeyProjection projection = new KeyProjection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        assertThrows(RuntimeException.class, () -> projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y")));
        
        Iterator<Entry<Key,String>> iter = fiData.iterator();
        assertTrue(projection.apply(iter.next()));
    }
    
    @Test
    public void testTooMuchOfTheSameConfiguration() {
        KeyProjection projection = new KeyProjection();
        projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y"));
        assertThrows(RuntimeException.class, () -> projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y")));
        
        Iterator<Entry<Key,String>> iter = fiData.iterator();
        assertTrue(projection.apply(iter.next()));
    }
    
    @Test
    public void testIncludes() {
        KeyProjection projection = new KeyProjection();
        projection.setIncludes(Sets.newHashSet("FIELD_A", "FIELD_B"));
        
        assertTrue(projection.getProjection().isUseIncludes());
        assertFalse(projection.getProjection().isUseExcludes());
        
        // test against field index data
        Iterator<Entry<Key,String>> iter = fiData.iterator();
        assertTrue(projection.apply(iter.next())); // FIELD_A
        assertTrue(projection.apply(iter.next())); // FIELD_B
        assertFalse(projection.apply(iter.next())); // FIELD_C
        
        assertFalse(projection.apply(iter.next())); // FIELD_X
        assertFalse(projection.apply(iter.next())); // FIELD_Y
        assertFalse(projection.apply(iter.next())); // FIELD_Z
        
        // test against event data
        iter = eventData.iterator();
        assertTrue(projection.apply(iter.next())); // FIELD_A
        assertTrue(projection.apply(iter.next())); // FIELD_B
        assertFalse(projection.apply(iter.next())); // FIELD_C
        
        assertFalse(projection.apply(iter.next())); // FIELD_X
        assertFalse(projection.apply(iter.next())); // FIELD_Y
        assertFalse(projection.apply(iter.next())); // FIELD_Z
    }
    
    @Test
    public void testExcludes() {
        KeyProjection projection = new KeyProjection();
        projection.setExcludes(Sets.newHashSet("FIELD_X", "FIELD_Y"));
        
        assertFalse(projection.getProjection().isUseIncludes());
        assertTrue(projection.getProjection().isUseExcludes());
        
        Iterator<Entry<Key,String>> iter = fiData.iterator();
        assertTrue(projection.apply(iter.next())); // FIELD_A
        assertTrue(projection.apply(iter.next())); // FIELD_B
        assertTrue(projection.apply(iter.next())); // FIELD_C
        
        assertFalse(projection.apply(iter.next())); // FIELD_X
        assertFalse(projection.apply(iter.next())); // FIELD_Y
        assertTrue(projection.apply(iter.next())); // FIELD_Z
        
        // test against event data
        iter = eventData.iterator();
        assertTrue(projection.apply(iter.next())); // FIELD_A
        assertTrue(projection.apply(iter.next())); // FIELD_B
        assertTrue(projection.apply(iter.next())); // FIELD_C
        
        assertFalse(projection.apply(iter.next())); // FIELD_X
        assertFalse(projection.apply(iter.next())); // FIELD_Y
        assertTrue(projection.apply(iter.next())); // FIELD_Z
    }
}
