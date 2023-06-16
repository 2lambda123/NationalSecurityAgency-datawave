package datawave.query.function;

import com.google.common.collect.Maps;
import datawave.query.attributes.Document;
import org.apache.accumulo.core.data.Key;
import org.apache.log4j.Logger;

import javax.annotation.Nullable;
import java.util.Map.Entry;

public class DocumentMetadata implements DocumentPermutation {

    private static final Logger log = Logger.getLogger(DocumentMetadata.class);

    @Override
    @Nullable
    public Entry<Key,Document> apply(@Nullable Entry<Key,Document> input) {
        Key origKey = input.getKey();
        Document d = input.getValue();
        d.invalidateMetadata();
        Key k = new Key(origKey.getRow(), origKey.getColumnFamily(), origKey.getColumnQualifier(), d.getColumnVisibility(), d.getTimestamp());
        return Maps.immutableEntry(k, d);
    }
}
