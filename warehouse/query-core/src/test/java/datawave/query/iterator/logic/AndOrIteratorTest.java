package datawave.query.iterator.logic;

import com.google.common.collect.Lists;
import datawave.query.iterator.NestedIterator;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AndOrIteratorTest {
    // X AND (!Y OR !Z)
    @Test
    public void testAndNegatedOr() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childExcludes.add(getItr(Lists.newArrayList("b", "c"), false));
        childExcludes.add(getItr(Lists.newArrayList("a", "b"), false));
        
        OrIterator childOr = new OrIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(childOr);
        includes.add(getItr(Lists.newArrayList("a", "b", "c"), false));
        
        NestedIterator iterator = new AndIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertTrue(childOr.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("a", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X AND (Y OR !Z)
    @Test
    public void testAndMixedOr() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childIncludes.add(getItr(Lists.newArrayList("b", "c"), false));
        childExcludes.add(getItr(Lists.newArrayList("a", "b"), false));
        
        OrIterator childOr = new OrIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(childOr);
        includes.add(getItr(Lists.newArrayList("a", "b", "c"), false));
        
        NestedIterator iterator = new AndIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertTrue(childOr.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("b", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X AND (!Y AND !Z)
    @Test
    public void testAndNegatedAnd() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childExcludes.add(getItr(Lists.newArrayList("b", "c"), false));
        childExcludes.add(getItr(Lists.newArrayList("a", "b"), false));
        
        NestedIterator child = new AndIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(child);
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        
        NestedIterator iterator = new AndIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertTrue(child.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("d", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X OR !Y
    @Test(expected = IllegalStateException.class)
    public void testDeferredOrMissingContext() {
        Set<NestedIterator<String>> includes = new HashSet<>();
        Set<NestedIterator<String>> excludes = new HashSet<>();
        
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        excludes.add(getItr(Lists.newArrayList("a"), false));
        
        NestedIterator iterator = new OrIterator(includes, excludes);
        iterator.initialize();
    }
    
    // !X OR !Y
    @Test(expected = IllegalStateException.class)
    public void testDeferredOr() {
        Set<NestedIterator<String>> includes = new HashSet<>();
        Set<NestedIterator<String>> excludes = new HashSet<>();
        
        excludes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        excludes.add(getItr(Lists.newArrayList("a"), false));
        
        NestedIterator iterator = new OrIterator(includes, excludes);
        iterator.initialize();
    }
    
    // !X AND !Y
    @Test(expected = IllegalStateException.class)
    public void testDeferredAnd() {
        Set<NestedIterator<String>> includes = new HashSet<>();
        Set<NestedIterator<String>> excludes = new HashSet<>();
        
        excludes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        excludes.add(getItr(Lists.newArrayList("a"), false));
        
        NestedIterator iterator = new AndIterator(includes, excludes);
        iterator.initialize();
    }
    
    // X AND !Y AND (!Z OR !A)
    @Test
    public void testNegatedNonDeferredInteractionWithDeferred() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childExcludes.add(getItr(Lists.newArrayList("b", "d"), false));
        childExcludes.add(getItr(Lists.newArrayList("c"), false));
        
        NestedIterator<String> child = new OrIterator<>(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        Set<NestedIterator<String>> excludes = new HashSet<>();
        
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        excludes.add(getItr(Lists.newArrayList("a"), false));
        includes.add(child);
        
        NestedIterator iterator = new AndIterator(includes, excludes);
        iterator.initialize();
        
        Assert.assertTrue(child.isDeferred());
        Assert.assertFalse(iterator.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("b", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("d", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X AND (Y OR !Y)
    @Test
    public void testAndAlwaysTrue() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childIncludes.add(getItr(Lists.newArrayList("b", "c"), false));
        childExcludes.add(getItr(Lists.newArrayList("b", "c"), false));
        
        NestedIterator child = new OrIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(child);
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        
        NestedIterator iterator = new AndIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertTrue(child.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("a", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("b", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("d", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X AND (Y AND !Y)
    @Test
    public void testAndNeverTrue() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childIncludes.add(getItr(Lists.newArrayList("b", "c"), false));
        childExcludes.add(getItr(Lists.newArrayList("b", "c"), false));
        
        NestedIterator child = new AndIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(child);
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        
        NestedIterator iterator = new AndIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertFalse(child.isDeferred());
        
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X OR (Y AND Z)
    @Test
    public void testOrAnd() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childIncludes.add(getItr(Lists.newArrayList("a", "c", "f"), false));
        childIncludes.add(getItr(Lists.newArrayList("b", "c", "f"), false));
        
        NestedIterator child = new AndIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(child);
        includes.add(getItr(Lists.newArrayList("a", "b", "c", "d"), false));
        
        NestedIterator iterator = new OrIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertFalse(child.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("a", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("b", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("d", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("f", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    // X OR (Y AND !Z)
    @Test
    public void testOrMixedAnd() {
        Set<NestedIterator<String>> childIncludes = new HashSet<>();
        Set<NestedIterator<String>> childExcludes = new HashSet<>();
        
        childIncludes.add(getItr(Lists.newArrayList("a", "b"), false));
        childExcludes.add(getItr(Lists.newArrayList("b", "f"), false));
        
        NestedIterator child = new AndIterator(childIncludes, childExcludes);
        
        Set<NestedIterator<String>> includes = new HashSet<>();
        includes.add(child);
        includes.add(getItr(Lists.newArrayList("c", "d"), false));
        
        NestedIterator iterator = new OrIterator(includes);
        iterator.initialize();
        
        Assert.assertFalse(iterator.isDeferred());
        Assert.assertFalse(child.isDeferred());
        
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("a", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("c", iterator.next());
        Assert.assertTrue(iterator.hasNext());
        Assert.assertEquals("d", iterator.next());
        Assert.assertFalse(iterator.hasNext());
    }
    
    private NegationFilterTest.Itr<String> getItr(List<String> source, boolean deferred) {
        return new NegationFilterTest.Itr<>(source, deferred);
    }
}
