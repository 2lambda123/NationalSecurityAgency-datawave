package datawave.query.common.grouping;

import datawave.query.attributes.Attribute;
import org.apache.accumulo.core.security.ColumnVisibility;

import java.util.Collection;
import java.util.Set;

/**
 * Provides the methods by which aggregates can be calculated for fields when grouped by other fields.
 * 
 * @param <AGGREGATE>
 *            the aggregate result type
 */
public interface Aggregator<AGGREGATE> {
    
    /**
     * Return the aggregate operation being performed.
     * 
     * @return the aggregate operation
     */
    AggregateOperation getOperation();
    
    /**
     * Return the field being aggregated.
     * 
     * @return the field
     */
    String getField();
    
    /**
     * Returns an unmodifiable set of all distinct column visibilities for each attribute aggregated into this aggregator. Possibly empty, but never null.
     *
     * @return a set of the column visibilities
     */
    Set<ColumnVisibility> getColumnVisibilities();
    
    /**
     * Return the aggregation result.
     *
     * @return the aggregation
     */
    AGGREGATE getAggregation();
    
    /**
     * Aggregate the given value into this aggregator.
     *
     * @param value
     *            the value to aggregate
     */
    void aggregate(Attribute<?> value);
    
    /**
     * Aggregate each value in the given collection into this aggregator.
     *
     * @param values
     *            the values to aggregate
     */
    void aggregateAll(Collection<Attribute<?>> values);
    
    /**
     * Merges the given aggregator into this aggregator
     *
     * @param other
     *            the aggregator to merge
     * @throws IllegalArgumentException
     *             if the other aggregator is not the same type as this aggregator
     */
    void merge(Aggregator<?> other);
}
