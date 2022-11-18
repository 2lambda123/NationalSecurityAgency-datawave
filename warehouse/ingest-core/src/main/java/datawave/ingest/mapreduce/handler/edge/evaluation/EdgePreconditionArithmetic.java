package datawave.ingest.mapreduce.handler.edge.evaluation;

import datawave.attribute.EventField;
import datawave.attribute.EventFieldValueTuple;
import org.apache.commons.jexl2.JexlArithmetic;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EdgePreconditionArithmetic extends JexlArithmetic {
    
    private Map<String,Set<String>> matchingGroups = new HashMap<>();
    
    public EdgePreconditionArithmetic() {
        super(false);
    }
    
    /*
     * Currently only EQ options are supplied.
     */
    
    @Override
    public boolean equals(final Object left, final Object right) {
        boolean matches = false;
        
        if (left instanceof List && !(right instanceof List)) {
            Object newRight = EventFieldValueTuple.getValue(right);
            
            Iterator iter = ((List) left).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newLeft = EventFieldValueTuple.getValue(tuple);
                if (super.equals(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if (!(left instanceof List) && (right instanceof List)) {
            Object newLeft = EventFieldValueTuple.getValue(left);
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newRight = EventFieldValueTuple.getValue(tuple);
                if (super.equals(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if ((left instanceof List) && (right instanceof List)) {
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object lefttuple = iter.next();
                Iterator iter2 = ((List) left).iterator();
                while (iter2.hasNext()) {
                    Object righttuple = iter2.next();
                    Object newLeft = EventFieldValueTuple.getValue(lefttuple);
                    Object newRight = EventFieldValueTuple.getValue(righttuple);
                    if (super.equals(newLeft, newRight)) {
                        addMatchingGroup(righttuple);
                        addMatchingGroup(lefttuple);
                        matches = true;
                    }
                }
            }
        } else {
            Object newLeft = EventFieldValueTuple.getValue(left);
            Object newRight = EventFieldValueTuple.getValue(right);
            
            if (super.equals(newLeft, newRight)) {
                addMatchingGroup(newLeft);
                addMatchingGroup(newRight);
                matches = true;
                
            }
        }
        
        return matches;
    }
    
    @Override
    public boolean lessThan(final Object left, final Object right) {
        boolean matches = false;
        
        if (left instanceof List && !(right instanceof List)) {
            Object newRight = EventFieldValueTuple.getValue(right);
            
            Iterator iter = ((List) left).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.lessThan(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if (right instanceof Set) {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newRight = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.lessThan(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
        }
        
        else {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            if (super.lessThan(newLeft, newRight)) {
                addMatchingGroup(newLeft);
                addMatchingGroup(newRight);
                matches = true;
                
            }
        }
        
        return matches;
    }
    
    @Override
    public boolean lessThanOrEqual(final Object left, final Object right) {
        boolean matches = false;
        
        if (left instanceof List && !(right instanceof List)) {
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            Iterator iter = ((List) left).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.lessThanOrEqual(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if (right instanceof Set) {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newRight = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.lessThanOrEqual(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
        }
        
        else {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            if (super.lessThanOrEqual(newLeft, newRight)) {
                addMatchingGroup(newLeft);
                addMatchingGroup(newRight);
                matches = true;
                
            }
        }
        
        return matches;
    }
    
    @Override
    public boolean greaterThan(final Object left, final Object right) {
        boolean matches = false;
        
        if (left instanceof List && !(right instanceof List)) {
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            Iterator iter = ((List) left).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.greaterThan(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if (right instanceof Set) {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newRight = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.greaterThan(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
        }
        
        else {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            if (super.greaterThan(newLeft, newRight)) {
                addMatchingGroup(newLeft);
                addMatchingGroup(newRight);
                matches = true;
                
            }
        }
        
        return matches;
    }
    
    @Override
    public boolean greaterThanOrEqual(final Object left, final Object right) {
        boolean matches = false;
        
        if (left instanceof List && !(right instanceof List)) {
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            Iterator iter = ((List) left).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.greaterThanOrEqual(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
            
        } else if (right instanceof Set) {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            
            Iterator iter = ((List) right).iterator();
            while (iter.hasNext()) {
                Object tuple = iter.next();
                Object newRight = Long.parseLong(EventFieldValueTuple.getValue(tuple));
                if (super.greaterThanOrEqual(newLeft, newRight)) {
                    addMatchingGroup(tuple);
                    matches = true;
                }
            }
        }
        
        else {
            Object newLeft = Long.parseLong(EventFieldValueTuple.getValue(left));
            Object newRight = Long.parseLong(EventFieldValueTuple.getValue(right));
            
            if (super.greaterThanOrEqual(newLeft, newRight)) {
                addMatchingGroup(newLeft);
                addMatchingGroup(newRight);
                matches = true;
                
            }
        }
        
        return matches;
    }
    
    private void addMatchingGroup(Object o) {
        if (o instanceof EventFieldValueTuple) {
            String fieldName = EventFieldValueTuple.getFieldName(o);
            String commonality = EventField.getCommonality(fieldName);
            String group = EventField.getGroupingContext(fieldName);
            Set<String> groups = matchingGroups.get(commonality);
            if (groups == null) {
                groups = new HashSet<>();
                groups.add(group);
                matchingGroups.put(commonality, groups);
            } else
                groups.add(group);
        }
    }
    
    public Map<String,Set<String>> getMatchingGroups() {
        return matchingGroups;
    }
    
    public void clearMatchingGroups() {
        matchingGroups = new HashMap<>();
    }
    
}
