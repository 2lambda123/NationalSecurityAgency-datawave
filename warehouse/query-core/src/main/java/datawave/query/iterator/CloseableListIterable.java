package datawave.query.iterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import datawave.query.CloseableIterable;

public class CloseableListIterable<T> extends ArrayList<T> implements CloseableIterable<T> {
    private static final long serialVersionUID = 0l;

    public CloseableListIterable(Collection<T> iter) {
        super(iter);
    }

    @Override
    public void close() throws IOException {

    }

}
