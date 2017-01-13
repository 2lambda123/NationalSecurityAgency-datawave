package nsa.datawave.query.jexl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.jexl2.JexlArithmetic;
import org.apache.commons.lang.math.NumberUtils;

@Deprecated
public class DatawaveArithmetic extends JexlArithmetic {
    
    public DatawaveArithmetic(boolean lenient) {
        super(lenient);
    }
    
    /**
     * This method differs from the parent in that we are not calling String.matches() because it does not match on a newline. Instead we are handling this
     * case.
     *
     * @param left
     *            first value
     * @param right
     *            second value
     * @return test result.
     */
    @Override
    public boolean matches(Object left, Object right) {
        if (left == null && right == null) {
            // if both are null L == R
            return true;
        }
        if (left == null || right == null) {
            // we know both aren't null, therefore L != R
            return false;
        }
        final String arg = left.toString();
        if (right instanceof java.util.regex.Pattern) {
            return ((java.util.regex.Pattern) right).matcher(arg).matches();
        } else {
            Pattern p = Pattern.compile(right.toString(), Pattern.DOTALL);
            Matcher m = p.matcher(arg);
            return m.matches();
            
        }
    }
    
    /**
     * This method differs from the parent class in that we are going to try and do a better job of coercing the types. As a last resort we will do a string
     * comparison and try not to throw a NumberFormatException. The JexlArithmetic class performs coercion to a particular type if either the left or the right
     * match a known type. We will look at the type of the right operator and try to make the left of the same type.
     */
    @Override
    public boolean equals(Object left, Object right) {
        Object fixedLeft = fixLeft(left, right);
        return super.equals(fixedLeft, right);
    }
    
    @Override
    public boolean lessThan(Object left, Object right) {
        Object fixedLeft = fixLeft(left, right);
        return super.lessThan(fixedLeft, right);
    }
    
    protected Object fixLeft(Object left, Object right) {
        
        if (null == left || null == right)
            return left;
        
        if (!(right instanceof Number) && left instanceof Number) {
            right = NumberUtils.createNumber(right.toString());
        }
        
        if (right instanceof Number && left instanceof Number) {
            if (right instanceof Double)
                return ((Number) left).doubleValue();
            else if (right instanceof Float)
                return ((Number) left).floatValue();
            else if (right instanceof Long)
                return ((Number) left).longValue();
            else if (right instanceof Integer)
                return ((Number) left).intValue();
            else if (right instanceof Short)
                return ((Number) left).shortValue();
            else if (right instanceof Byte)
                return ((Number) left).byteValue();
            else
                return left;
        }
        if (right instanceof Number && left instanceof String) {
            Number num = NumberUtils.createNumber(left.toString());
            // Let's try to cast left as right's type.
            if (this.isFloatingPointNumber(right) && this.isFloatingPointNumber(left))
                return num;
            else if (this.isFloatingPointNumber(right))
                return num.doubleValue();
            else
                return num.longValue();
        } else if (right instanceof Boolean && left instanceof String) {
            if (left.equals("true") || left.equals("false"))
                return Boolean.parseBoolean(left.toString());
            
            Number num = NumberUtils.createNumber(left.toString());
            if (num.intValue() == 1)
                return true;
            else if (num.intValue() == 0)
                return false;
        }
        return left;
    }
    
}
