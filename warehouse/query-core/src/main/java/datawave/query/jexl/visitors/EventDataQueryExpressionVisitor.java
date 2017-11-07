package datawave.query.jexl.visitors;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import datawave.query.attributes.AttributeFactory;
import datawave.data.type.Type;
import datawave.query.data.parsers.DatawaveKey;
import datawave.query.attributes.*;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.LiteralRange;
import datawave.query.predicate.Filter;
import org.apache.accumulo.core.data.Key;
import org.apache.commons.jexl2.parser.*;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EventDataQueryExpressionVisitor extends BaseVisitor {
    private static final Logger log = Logger.getLogger(EventDataQueryExpressionVisitor.class);
    
    public static class ExpressionFilter implements Predicate<Key> {
        final AttributeFactory attributeFactory;
        final String fieldName;
        final Set<String> fieldValues;
        final Set<Matcher> fieldPatterns;
        final Set<LiteralRange> fieldRanges;
        final boolean isWhitelist;
        
        public ExpressionFilter(AttributeFactory attributeFactory, String fieldName, boolean isWhitelist) {
            this.attributeFactory = attributeFactory;
            this.fieldName = fieldName;
            this.isWhitelist = isWhitelist;
            this.fieldValues = new HashSet<String>();
            this.fieldPatterns = new HashSet<Matcher>();
            this.fieldRanges = new HashSet<LiteralRange>();
        }
        
        public String getFieldName() {
            return this.fieldName;
        }
        
        public Set<String> getFieldValues() {
            return ImmutableSet.copyOf(fieldValues);
        }
        
        public void addFieldValue(String value) {
            fieldValues.add(value);
        }
        
        public void addFieldPattern(String pattern) {
            Pattern p = Pattern.compile(pattern);
            Matcher m = p.matcher("");
            fieldPatterns.add(m);
        }
        
        public void addFieldRange(LiteralRange range) {
            fieldRanges.add(range);
        }
        
        public boolean apply(Key key) {
            final DatawaveKey datawaveKey = new DatawaveKey(key);
            final String keyFieldName = JexlASTHelper.deconstructIdentifier(datawaveKey.getFieldName(), false);
            final String keyFieldValue = datawaveKey.getFieldValue();
            final Set<String> normalizedFieldValues = EventDataQueryExpressionVisitor.extractNormalizedAttributes(attributeFactory, keyFieldName,
                            keyFieldValue, key);
            
            for (String normalizedFieldValue : normalizedFieldValues) {
                if (fieldName.equals(keyFieldName)) {
                    if (fieldValues.contains(normalizedFieldValue)) {
                        // field name matches and field value matches, keep if whitelist, reject if blacklist.
                        return isWhitelist;
                    }
                    
                    for (Matcher m : fieldPatterns) {
                        m.reset(normalizedFieldValue);
                        if (m.matches()) {
                            // field name matches and field pattern matches, keep if whitelist, reject if blacklist.
                            return isWhitelist;
                        }
                    }
                    
                    for (LiteralRange r : fieldRanges) {
                        if (r.contains(normalizedFieldValue)) {
                            // field name patches and value is within range, keep if whitelist, reject if blacklist.
                            return isWhitelist;
                        }
                    }
                }
            }
            
            // field name does not match, reject if whitelist, keep if blacklist
            return !isWhitelist;
        }
    }
    
    public static Map<String,ExpressionFilter> getExpressionFilters(ASTJexlScript script, AttributeFactory factory) {
        final EventDataQueryExpressionVisitor v = new EventDataQueryExpressionVisitor(factory);
        
        script.jjtAccept(v, "");
        
        return v.getFilterMap();
    }
    
    final AttributeFactory attributeFactory;
    final Map<String,ExpressionFilter> filterMap;
    
    private EventDataQueryExpressionVisitor(AttributeFactory factory) {
        this.attributeFactory = factory;
        this.filterMap = new HashMap<>();
    }
    
    private Map<String,ExpressionFilter> getFilterMap() {
        return this.filterMap;
    }
    
    @Override
    public Object visit(ASTEQNode node, Object data) {
        simpleValueFilter(node);
        return super.visit(node, data);
    }
    
    @Override
    public Object visit(ASTNENode node, Object data) {
        simpleValueFilter(node);
        return super.visit(node, data);
    }
    
    @Override
    public Object visit(ASTERNode node, Object data) {
        simplePatternFilter(node);
        return super.visit(node, data);
    }
    
    @Override
    public Object visit(ASTNRNode node, Object data) {
        simplePatternFilter(node);
        return super.visit(node, data);
    }
    
    @Override
    public Object visit(ASTAndNode node, Object data) {
        List<JexlNode> otherNodes = new ArrayList<JexlNode>();
        Map<LiteralRange<?>,List<JexlNode>> ranges = JexlASTHelper.getBoundedRangesIndexAgnostic(node, otherNodes, true);
        if (ranges.size() == 1 && otherNodes.isEmpty()) {
            LiteralRange<?> range = ranges.keySet().iterator().next();
            simpleRangeFilter(range);
        } else {
            super.visit(node, data);
        }
        
        return null;
    }
    
    protected void simpleValueFilter(JexlNode node) {
        final JexlASTHelper.IdentifierOpLiteral iol = JexlASTHelper.getIdentifierOpLiteral(node);
        generateValueFilter(iol, false);
    }
    
    protected void simplePatternFilter(JexlNode node) {
        final JexlASTHelper.IdentifierOpLiteral iol = JexlASTHelper.getIdentifierOpLiteral(node);
        generateValueFilter(iol, true);
    }
    
    protected void simpleRangeFilter(LiteralRange<?> range) {
        final String fieldName = JexlASTHelper.deconstructIdentifier(range.getFieldName(), false);
        ExpressionFilter f = filterMap.get(fieldName);
        if (f == null) {
            filterMap.put(fieldName, f = createExpressionFilter(fieldName));
        }
        
        f.addFieldRange(range);
    }
    
    protected void generateValueFilter(JexlASTHelper.IdentifierOpLiteral iol, boolean isPattern) {
        if (iol != null) {
            final String fieldName = JexlASTHelper.deconstructIdentifier(iol.getIdentifier().image, false);
            final String fieldValue = iol.getLiteralValue().toString();
            ExpressionFilter f = filterMap.get(fieldName);
            if (f == null) {
                filterMap.put(fieldName, f = createExpressionFilter(fieldName));
            }
            
            if (isPattern) {
                f.addFieldPattern(fieldValue);
            } else {
                f.addFieldValue(fieldValue);
            }
        } else {
            throw new NullPointerException("Null IdentifierOpLiteral");
        }
    }
    
    protected ExpressionFilter createExpressionFilter(String fieldName) {
        return new ExpressionFilter(attributeFactory, fieldName, true);
    }
    
    private static String print(JexlASTHelper.IdentifierOpLiteral iol) {
        if (iol == null)
            return "null";
        
        return iol.getIdentifier() + " " + iol.getOp() + " " + iol.getLiteral();
    }
    
    public static Set<String> extractNormalizedAttributes(AttributeFactory attrFactory, String fieldName, String fieldValue, Key key) {
        final Set<String> normalizedAttributes = new HashSet<String>();
        
        final Queue<Attribute<?>> attrQueue = new LinkedList<>();
        attrQueue.add(attrFactory.create(fieldName, fieldValue, key, true));
        
        Attribute<?> attr;
        
        while ((attr = attrQueue.poll()) != null) {
            if (TypeAttribute.class.isAssignableFrom(attr.getClass())) {
                TypeAttribute dta = (TypeAttribute) attr;
                Type t = dta.getType();
                normalizedAttributes.add(t.getNormalizedValue());
            } else if (AttributeBag.class.isAssignableFrom(attr.getClass())) {
                attrQueue.addAll(((AttributeBag<?>) attr).getAttributes());
            } else {
                log.warn("Unexpected attribute type when extracting normalized values: " + attr.getClass().getCanonicalName());
            }
        }
        return normalizedAttributes;
    }
}
