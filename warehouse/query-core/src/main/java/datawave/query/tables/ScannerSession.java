package datawave.query.tables;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import datawave.query.tables.AccumuloResource.ResourceFactory;
import datawave.query.tables.stats.ScanSessionStats.TIMERS;
import datawave.webservice.query.Query;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.log4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This will handles running a scan against a set of ranges. The actual scan is performed in a separate thread which places the results in a result queue. The
 * result queue is polled in the actual next() and hasNext() calls. Note that the uncaughtExceptionHandler from the Query is used to pass exceptions up which
 * will also fail the overall query if something happens. If this is not desired then a local handler should be set.
 */
public class ScannerSession extends  BaseScannerSession<Entry<Key,Value>> {

    private static final Logger log = Logger.getLogger(ScannerSession.class);


    protected Key lastSeenKey;

    
    /**
     * Constructor
     * 
     * @param tableName
     *            incoming table name
     * @param auths
     *            set of authorizations.
     * @param delegator
     *            scanner queue
     * @param maxResults
     *            the max results
     * @param settings
     *            query settings
     */
    public ScannerSession(String tableName, Set<Authorizations> auths, ResourceQueue delegator, int maxResults, Query settings) {
        this(tableName, auths, delegator, maxResults, settings, new SessionOptions(), null);
    }
    
    public ScannerSession(String tableName, Set<Authorizations> auths, ResourceQueue delegator, int maxResults, Query settings, SessionOptions options,
                    Collection<Range> ranges) {

        super(tableName,auths,delegator,maxResults,settings,options,ranges);
    }

    @Override
    protected Class<? extends Resource<Entry<Key, Value>>> getRunningResourceClass() {
        return RunningResource.class;
    }


    @Override
    public boolean equals(Object obj) {
        if (obj instanceof ScannerSession) {
            EqualsBuilder builder = new EqualsBuilder();
            builder.append(ranges, ((ScannerSession) obj).ranges);
            builder.append(tableName, ((ScannerSession) obj).tableName);
            builder.append(auths, ((ScannerSession) obj).auths);
            return builder.isEquals();
        }
        
        return false;
    }
    
    @Override
    public int hashCode() {
        int result = ranges != null ? ranges.hashCode() : 0;
        result = 31 * result + (tableName != null ? tableName.hashCode() : 0);
        result = 31 * result + (auths != null ? auths.hashCode() : 0);
        return result;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see java.util.Iterator#hasNext()
     * 
     * Note that this method needs to check the uncaught exception handler and propogate any set throwables.
     */
    @Override
    public boolean hasNext() {
        
        /**
         * Let's take a moment to look through all states S
         */
        
        // if we are new, let's start and wait
        if (state() == State.NEW) {
            // we have just started, so let's start and wait
            // until we've completed the start process
            if (null != stats)
                initializeTimers();
            
            // these two guava methods replaced behavior of startAndWait() from version 15 but
            // will now throw an exception if another thread closes the session so catch and ignore
            startAsync();
            try {
                awaitRunning();
            } catch (IllegalStateException e) {
                log.debug("Session was closed while waiting to start up.");
            }
        }
        
        // isFlushNeeded is only in the case of when we are finished
        boolean isFlushNeeded = flushNeeded();
        if ( log.isTraceEnabled())
            log.trace("hasNext" + isRunning());

        try {
            
            if (null != stats)
                stats.getTimer(TIMERS.HASNEXT).resume();

            long max_backoff=100;
            long backoff =getPollTime();
            while (null == currentEntry && (isRunning() || !resultQueue.isEmpty() || isFlushNeeded)) {

                if ( log.isTraceEnabled())
                    log.trace("hasNext" + isRunning());
                
                try {
                    /**
                     * Poll for one second. We're in a do/while loop that will break iff we are no longer running or there is a current entry available.
                     */
                    currentEntry = resultQueue.poll(backoff > max_backoff ? max_backoff : backoff, TimeUnit.MILLISECONDS);
                    
                } catch (InterruptedException e) {
                    log.trace("hasNext" + isRunning() + " interrupted");
                    log.error("Interrupted before finding next", e);
                    throw new RuntimeException(e);
                }
                // if we pulled no data and we are not running, and there is no data in the queue
                // we can flush if needed and retry
                if ( log.isTraceEnabled())
                 log.trace("hasNext " + isRunning() + " " + flushNeeded());

                if (currentEntry == null){
                    if (!isRunning() && resultQueue.isEmpty())
                        isFlushNeeded = flushNeeded();
                    backoff++;
                }
            }

        } finally {
            if (null != stats) {
                try {
                    stats.getTimer(TIMERS.HASNEXT).suspend();
                } catch (Exception e) {
                    log.error("Failed to suspend timer", e);
                }
            }
            if (uncaughtExceptionHandler.getThrowable() != null) {
                log.error("Exception discovered on hasNext call", uncaughtExceptionHandler.getThrowable());
                throw new RuntimeException(uncaughtExceptionHandler.getThrowable());
            }
        }

        return (null != currentEntry);
    }
    


    /**
     * set the resource class.
     * 
     * @param clazz
     *            the class to set
     */
    public void setResourceClass(Class<? extends AccumuloResource> clazz) {
        delegatedResourceInitializer = clazz;
    }
    
    /**
     *
     * @throws Exception
     *             if there are issues
     * 
     */
    @Override
    protected void findTop() throws Exception {
        if (ranges.isEmpty() && lastSeenKey == null) {
            
            if (flushNeeded()) {
                flush();
                return;
            }
            stopAsync();
            
            return;
        }
        
        try {
            
            if (resultQueue.remainingCapacity() == 0) {
                return;
            }
            
            /**
             * Even though we were delegated a resource, we have not actually been provided the plumbing to run it. Note, below, that we initialize the resource
             * through the resource factory from a running resource.
             */
            if (null != stats)
                stats.getTimer(TIMERS.SCANNER_START).resume();
            
            delegatedResource = sessionDelegator.getScannerResource();
            
            // if we have just started or we are at the end of the current range. pop the next range
            if (lastSeenKey == null || (currentRange != null && currentRange.getEndKey() != null && lastSeenKey.compareTo(currentRange.getEndKey()) >= 0)) {
                currentRange = ranges.poll();
                // short circuit and exit
                if (null == currentRange) {
                    lastSeenKey = null;
                    return;
                }
            } else {
                // adjust the end key range.
                currentRange = buildNextRange(lastSeenKey, currentRange);
                
                if (log.isTraceEnabled())
                    log.trace("Building " + currentRange + " from " + lastSeenKey);
            }
            
            if (log.isTraceEnabled()) {
                log.trace(lastSeenKey + ", using current range of " + lastRange);
                log.trace(lastSeenKey + ", using current range of " + currentRange);
            }
            
            delegatedResource = ResourceFactory.initializeResource(delegatedResourceInitializer, (AccumuloResource) delegatedResource, tableName, auths, currentRange).setOptions(
                            options);
            
            Iterator<Entry<Key,Value>> iter = delegatedResource.iterator();
            
            // do not continue if we've reached the end of the corpus
            
            if (!iter.hasNext()) {
                if (log.isTraceEnabled())
                    log.trace("We've started, but we have nothing to do on " + tableName + " " + auths + " " + currentRange);
                if (log.isTraceEnabled())
                    log.trace("We've started, but we have nothing to do");
                lastSeenKey = null;
                return;
            }
            
            int retrievalCount = 0;
            try {
                if (null != stats)
                    stats.getTimer(TIMERS.SCANNER_ITERATE).resume();
                retrievalCount = scannerInvariant(iter);
            } finally {
                if (null != stats) {
                    stats.incrementKeysSeen(retrievalCount);
                    stats.getTimer(TIMERS.SCANNER_ITERATE).suspend();
                }
                
            }
            
        } catch (IllegalArgumentException e) {
            /**
             * If we get an illegal argument exception, we know that the ScannerSession extending class created a start key after our end key, which means that
             * we've finished with this range. As a result, we set lastSeenKey to null, so that on our next pass through, we pop the next range from the queue
             * and continue or finish. We're going to timeslice and come back as know this range is likely finished.
             */
            if (log.isTraceEnabled())
                log.trace(lastSeenKey + " is lastseenKey, previous range is " + currentRange, e);
            
            lastSeenKey = null;
            return;
            
        } catch (Exception e) {
            if (forceClose) {
                // if we force close, then we can ignore the exception
                if (log.isTraceEnabled()) {
                    log.trace("Ignoring exception because we have been closed", e);
                }
            } else {
                log.error("Failed to find top", e);
                throw e;
            }
            
        } finally {
            
            if (null != stats)
                stats.getTimer(TIMERS.SCANNER_START).suspend();
            
            synchronized (sessionDelegator) {
                if (null != delegatedResource) {
                    sessionDelegator.close(delegatedResource);
                    delegatedResource = null;
                }
            }
            
        }
    }

    @Override
    protected int scannerInvariant(final Iterator<Entry<Key,Value>> iter) {
        int retrievalCount = 0;
        
        Entry<Key,Value> myEntry = null;
        Key highest = null;
        while (iter.hasNext()) {
            myEntry = iter.next();
            
            // different underlying scanners may not always guarantee ordered results
            if (highest == null || highest.compareTo(myEntry.getKey()) < 0) {
                highest = myEntry.getKey();
            }
            
            // this creates a bottleneck on the resultQueue size, but guarantees no results will be lost
            boolean accepted = false;
            while (!accepted) {
                try {
                    accepted = resultQueue.offer(myEntry, 10, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // keep trying
                }
            }
            
            retrievalCount++;
        }
        
        lastSeenKey = highest;
        
        return retrievalCount;
    }

    @Override
    protected Key getLastKey() {
        return lastSeenKey;
    }


    public void setDelegatedInitializer(Class<? extends AccumuloResource> delegatedResourceInitializer) {
        this.delegatedResourceInitializer = delegatedResourceInitializer;
    }


    @Override
    public void close() {
        forceClose = true;
        stopAsync();
        synchronized (sessionDelegator) {
            if (null != delegatedResource) {
                try {
                    sessionDelegator.close(delegatedResource);
                    delegatedResource = null;
                } catch (Exception e) {
                    log.error("Failed to close session", e);
                }
            }
        }
    }

    
}
