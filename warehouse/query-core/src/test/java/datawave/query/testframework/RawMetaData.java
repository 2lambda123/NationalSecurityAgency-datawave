package datawave.query.testframework;

import datawave.data.normalizer.Normalizer;

/**
 * POJO for handling the metadata for an individual field.
 */
public class RawMetaData {
    final String name;
    final Normalizer normalizer;
    final boolean multiValue;
    
    /**
     *
     * @param fieldName
     *            name of the field
     * @param norm
     *            normailizer for field
     * @param multi
     *            true when field is treated as a multi-value field
     */
    public RawMetaData(final String fieldName, final Normalizer norm, final boolean multi) {
        this.name = fieldName;
        this.normalizer = norm;
        this.multiValue = multi;
    }
    
    @Override
    public String toString() {
        return this.getClass().getSimpleName() + ": name(" + this.name + ") normailizer(" + this.normalizer.getClass().getSimpleName() + ") multi("
                        + this.multiValue + ")";
    }
}
