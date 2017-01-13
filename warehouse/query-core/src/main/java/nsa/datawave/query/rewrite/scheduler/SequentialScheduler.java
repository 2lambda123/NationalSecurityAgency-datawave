package nsa.datawave.query.rewrite.scheduler;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import nsa.datawave.query.rewrite.config.RefactoredShardQueryConfiguration;
import nsa.datawave.query.rewrite.tables.RefactoredShardQueryLogic;
import nsa.datawave.query.tables.ScannerFactory;
import nsa.datawave.query.tables.stats.ScanSessionStats;
import nsa.datawave.webservice.common.logging.ThreadConfigurableLogger;
import nsa.datawave.webservice.query.configuration.QueryData;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.log4j.Logger;

/**
 * 
 */
public class SequentialScheduler extends Scheduler {
    private static final Logger log = ThreadConfigurableLogger.getLogger(SequentialScheduler.class);
    
    protected final RefactoredShardQueryConfiguration config;
    protected final ScannerFactory scannerFactory;
    protected final AtomicInteger count = new AtomicInteger(0);
    
    protected SequentialSchedulerIterator iterator = null;
    
    /**
     * Statistics used for validation.
     */
    protected int rangesSeen = 0;
    
    public SequentialScheduler(RefactoredShardQueryConfiguration config, ScannerFactory scannerFactory) {
        this.config = config;
        this.scannerFactory = scannerFactory;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Iterable#iterator()
     */
    @Override
    public Iterator<Entry<Key,Value>> iterator() {
        if (null == this.config) {
            throw new IllegalArgumentException("Null configuration provided");
        }
        
        this.iterator = new SequentialSchedulerIterator(this.config, this.scannerFactory);
        
        return this.iterator;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see java.io.Closeable#close()
     */
    @Override
    public void close() throws IOException {
        if (null != this.iterator) {
            this.iterator.close();
        }
        
        log.debug("Ran " + count.get() + " queries for a single user query");
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see nsa.datawave.query.rewrite.scheduler.Scheduler#createBatchScanner(nsa.datawave.query.rewrite.config.RefactoredShardQueryConfiguration,
     * nsa.datawave.query.tables.ScannerFactory, nsa.datawave.webservice.query.configuration.QueryData)
     */
    @Override
    public BatchScanner createBatchScanner(RefactoredShardQueryConfiguration config, ScannerFactory scannerFactory, QueryData qd) throws TableNotFoundException {
        return RefactoredShardQueryLogic.createBatchScanner(config, scannerFactory, qd);
    }
    
    public class SequentialSchedulerIterator implements Iterator<Entry<Key,Value>> {
        
        protected final RefactoredShardQueryConfiguration config;
        protected final ScannerFactory scannerFactory;
        
        protected Iterator<QueryData> queries = null;
        protected Entry<Key,Value> currentEntry = null;
        protected BatchScanner currentBS = null;
        protected Iterator<Entry<Key,Value>> currentIter = null;
        
        protected volatile boolean closed = false;
        
        public SequentialSchedulerIterator(RefactoredShardQueryConfiguration config, ScannerFactory scannerFactory) {
            this.config = config;
            this.scannerFactory = scannerFactory;
            this.queries = config.getQueries();
        }
        
        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#hasNext()
         */
        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            
            if (null != this.currentEntry) {
                return true;
            } else if (null != this.currentBS && null != this.currentIter) {
                if (this.currentIter.hasNext()) {
                    this.currentEntry = this.currentIter.next();
                    
                    return hasNext();
                } else {
                    this.currentBS.close();
                }
            }
            
            QueryData newQueryData = null;
            while (true) {
                if (this.queries.hasNext()) {
                    // Keep track of how many QueryData's we make
                    QueryData qd = this.queries.next();
                    if (null != qd.getRanges())
                        rangesSeen += qd.getRanges().size();
                    count.incrementAndGet();
                    if (null == newQueryData)
                        newQueryData = new QueryData(qd);
                    else {
                        newQueryData.getRanges().addAll(qd.getRanges());
                    }
                    
                } else
                    break;
            }
            
            if (null != newQueryData) {
                
                try {
                    this.currentBS = createBatchScanner(this.config, this.scannerFactory, newQueryData);
                } catch (TableNotFoundException e) {
                    throw new RuntimeException(e);
                }
                
                this.currentIter = this.currentBS.iterator();
                
                return hasNext();
            }
            
            return false;
        }
        
        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#next()
         */
        @Override
        public Entry<Key,Value> next() {
            if (closed) {
                return null;
            }
            
            if (hasNext()) {
                Entry<Key,Value> cur = this.currentEntry;
                this.currentEntry = null;
                return cur;
            }
            
            return null;
        }
        
        /*
         * (non-Javadoc)
         * 
         * @see java.util.Iterator#remove()
         */
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
        
        public void close() {
            if (!closed) {
                closed = true;
            }
            if (null != this.currentBS) {
                this.currentBS.close();
            }
        }
    }
    
    @Override
    public ScanSessionStats getSchedulerStats() {
        return null;
    }
    
    public int getRangesSeen() {
        return rangesSeen;
    }
    
    public int getQueryDataSeen() {
        return count.get();
    }
    
}
