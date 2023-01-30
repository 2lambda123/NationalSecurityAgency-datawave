package datawave.query.data.parsers;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;

/**
 * A reusable, dedicated key parser for Field Index keys
 * <p>
 * A Field Index key's components are
 * <ul>
 * <li>row - the shard</li>
 * <li>column family - "fi\0FIELD_NAME"</li>
 * <li>column qualifier - "value\0datatype\0uid"</li>
 * </ul>
 */
public class FieldIndexKey {
    
    private String field;
    private String value;
    private String datatype;
    private String uid;
    
    private ByteSequence cqBytes;
    
    private int firstNull;
    private int secondNull;
    
    private Key key;
    
    /**
     * Sets the key and resets all supporting objects
     *
     * @param k
     *            a field index key
     */
    public void parse(Key k) {
        this.key = k;
        
        this.field = null;
        this.value = null;
        this.datatype = null;
        this.uid = null;
        
        this.cqBytes = null;
        
        this.firstNull = -1;
        this.secondNull = -1;
    }
    
    /**
     * Backwards traversal of the column qualifier to find the two null indices
     */
    private void traverseColumnQualifier() {
        
        if (firstNull != -1 && secondNull != -1) {
            return;
        }
        
        if (cqBytes == null) {
            cqBytes = key.getColumnQualifierData();
        }
        
        for (int i = cqBytes.length() - 1; i >= 0; i--) {
            if (cqBytes.byteAt(i) == 0x00) {
                if (secondNull == -1) {
                    secondNull = i;
                } else {
                    firstNull = i;
                    break;
                }
            }
        }
    }
    
    public String getField() {
        if (field == null) {
            ByteSequence backing = key.getColumnFamilyData();
            field = backing.subSequence(3, backing.length()).toString();
        }
        return field;
    }
    
    public String getValue() {
        if (value == null) {
            traverseColumnQualifier();
            value = cqBytes.subSequence(0, firstNull).toString();
        }
        return value;
    }
    
    public String getDatatype() {
        if (datatype == null) {
            traverseColumnQualifier();
            datatype = cqBytes.subSequence(firstNull + 1, secondNull).toString();
        }
        return datatype;
    }
    
    public String getUid() {
        if (uid == null) {
            traverseColumnQualifier();
            uid = cqBytes.subSequence(secondNull + 1, cqBytes.length()).toString();
        }
        return uid;
    }
}
