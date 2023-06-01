package datawave.query.index.lookup;

import com.google.common.base.Objects;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.jexl.JexlNodeFactory;
import datawave.query.jexl.nodes.ExceededOrThresholdMarkerJexlNode;
import datawave.query.jexl.nodes.ExceededTermThresholdMarkerJexlNode;
import datawave.query.jexl.nodes.ExceededValueThresholdMarkerJexlNode;
import datawave.query.jexl.nodes.IndexHoleMarkerJexlNode;
import datawave.query.jexl.nodes.QueryPropertyMarker;
import datawave.query.jexl.visitors.RebuildingVisitor;
import datawave.query.jexl.visitors.TreeFlatteningRebuildingVisitor;
import datawave.query.language.parser.jexl.JexlNodeSet;
import datawave.webservice.query.service.ServiceConfiguration;
import org.apache.commons.jexl2.parser.ASTDelayedPredicate;
import org.apache.commons.jexl2.parser.ASTOrNode;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.hadoop.io.VIntWritable;
import org.apache.hadoop.io.VLongWritable;
import org.apache.hadoop.io.Writable;
import org.apache.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class represents information about hits in the index.
 * <p>
 * Hits may be represented by individual document ids or by a simple count.
 * <p>
 * The IndexInfo object supports union and intersection operations with other IndexInfo objects.
 */
public class IndexInfo implements Writable, UidIntersector {
    
    private static final Logger log = Logger.getLogger(IndexInfo.class);
    
    protected JexlNode myNode = null;
    // a count of -1 indicates a shard range that datawave created
    // a positive count indicates the number of uids added for this term
    protected long count;
    // a set of document uids. In some cases this list is pruned when a threshold is exceeded
    // In the pruned case, the count will exceed the size of the uid set
    protected ImmutableSortedSet<IndexMatch> uids;
    
    public IndexInfo() {
        this.count = 0;
        this.uids = ImmutableSortedSet.of();
    }
    
    public IndexInfo(long count) {
        this.count = count;
        this.uids = ImmutableSortedSet.of();
    }
    
    public IndexInfo(Iterable<?> ids) {
        Set<IndexMatch> matches = Sets.newTreeSet();
        for (Object id : ids) {
            if (id instanceof IndexMatch) {
                matches.add((IndexMatch) id);
            } else
                matches.add(new IndexMatch(id.toString()));
        }
        this.uids = ImmutableSortedSet.copyOf(matches);
        this.count = this.uids.size();
    }
    
    public boolean onlyEvents() {
        return count > 0 && count == uids.size();
    }
    
    public long count() {
        return count;
    }
    
    public ImmutableSortedSet<IndexMatch> uids() {
        return uids;
    }
    
    @Override
    public void write(DataOutput out) throws IOException {
        new VLongWritable(count).write(out);
        new VIntWritable(uids.size()).write(out);
        for (IndexMatch uid : uids)
            uid.write(out);
    }
    
    public void applyNode(JexlNode node) {
        JexlNode copy = RebuildingVisitor.copy(node);
        copy.jjtSetParent(null);
        myNode = copy;
        for (IndexMatch match : uids) {
            match.add(node);
        }
    }
    
    public JexlNode getNode() {
        return myNode;
    }
    
    @Override
    public void readFields(DataInput in) throws IOException {
        VLongWritable count = new VLongWritable();
        count.readFields(in);
        this.count = count.get();
        
        VIntWritable nUidsReader = new VIntWritable();
        nUidsReader.readFields(in);
        final int nUids = nUidsReader.get();
        
        ImmutableSortedSet.Builder<IndexMatch> setBuilder = ImmutableSortedSet.naturalOrder();
        
        for (int i = 0; i < nUids; ++i) {
            IndexMatch index = new IndexMatch();
            index.readFields(in);
            setBuilder.add(index);
        }
        this.uids = setBuilder.build();
    }
    
    public IndexInfo union(IndexInfo o) {
        return union(o, new ArrayList<>());
    }
    
    /**
     * Let's be clear about what we are doing. In this case we are dealing with a union of many nodes, or in some cases a single OrNode. If the latter, we
     * simply use that node as our node. Otherwise, we will need to create an or node manually.
     * 
     * @param first
     *            an IndexInfo
     * @param o
     *            another IndexInfo
     * @param delayedNodes
     *            a list of delayed terms
     * @return the union of two IndexInfo objects and the list of delayed nodes
     */
    public IndexInfo union(IndexInfo first, IndexInfo o, List<JexlNode> delayedNodes) {
        IndexInfo merged = new IndexInfo();
        JexlNodeSet nodeSet = new JexlNodeSet();
        
        if (null != first.myNode && first.myNode != o.myNode) {
            
            JexlNode sourceNode = getSourceNode(first.myNode);
            JexlNode topLevelOr = getOrNode(sourceNode);
            
            if (null == topLevelOr) {
                nodeSet.add(sourceNode);
            } else {
                for (int i = 0; i < topLevelOr.jjtGetNumChildren(); i++) {
                    nodeSet.add(topLevelOr.jjtGetChild(i));
                }
            }
        }
        
        if (null != o.myNode) {
            JexlNode sourceNode = getSourceNode(o.myNode);
            JexlNode topLevelOr = getOrNode(sourceNode);
            
            if (null == topLevelOr) {
                nodeSet.add(o.myNode);
            } else {
                for (int i = 0; i < topLevelOr.jjtGetNumChildren(); i++) {
                    nodeSet.add(topLevelOr.jjtGetChild(i));
                }
            }
        }
        
        // Add delayed nodes.
        nodeSet.addAll(delayedNodes);
        
        merged.count = -1;
        merged.uids = ImmutableSortedSet.of();
        if (nodeSet.isEmpty()) {
            merged.myNode = null;
        } else {
            merged.myNode = JexlNodeFactory.createUnwrappedOrNode(nodeSet.getNodes());
        }
        
        return merged;
    }
    
    private JexlNode getOrNode(JexlNode node) {
        if (node instanceof ASTOrNode) {
            return node;
        } else if (node instanceof ASTReference) {
            return getOrNode(node.jjtGetChild(0));
        } else if (node instanceof ASTReferenceExpression) {
            return getOrNode(node.jjtGetChild(0));
        }
        return null;
    }
    
    public static JexlNode getSourceNode(JexlNode delayedNode) {
        QueryPropertyMarker.Instance instance = QueryPropertyMarker.findInstance(delayedNode);
        if (instance.isAnyTypeOf(ASTDelayedPredicate.class, ExceededOrThresholdMarkerJexlNode.class, ExceededValueThresholdMarkerJexlNode.class,
                        ExceededTermThresholdMarkerJexlNode.class, IndexHoleMarkerJexlNode.class)) {
            return instance.getSource();
        } else {
            return delayedNode;
        }
    }
    
    /**
     * Return the union of an IndexInfo object and a list of delayed nodes.
     *
     * @param o
     *            an IndexInfo object
     * @param delayedNodes
     *            a list of delayed nodes
     * @return the resulting union
     */
    public IndexInfo union(IndexInfo o, List<JexlNode> delayedNodes) {
        
        if (isInfinite()) {
            return union(this, o, delayedNodes);
        } else if (o.isInfinite()) {
            return union(o, this, delayedNodes);
        }
        
        IndexInfo merged = new IndexInfo();
        JexlNodeSet nodeSet = new JexlNodeSet();
        
        if (null != myNode && myNode != o.myNode && !isInfinite()) {
            JexlNode sourceNode = getSourceNode(myNode);
            
            JexlNode topLevelOr = getOrNode(sourceNode);
            if (null == topLevelOr) {
                nodeSet.add(myNode);
            } else {
                for (int i = 0; i < topLevelOr.jjtGetNumChildren(); i++) {
                    nodeSet.add(topLevelOr.jjtGetChild(i));
                }
            }
        }
        
        if (null != o.myNode && !o.isInfinite()) {
            
            JexlNode sourceNode = getSourceNode(o.myNode);
            JexlNode topLevelOr = getOrNode(sourceNode);
            if (null == topLevelOr) {
                nodeSet.add(o.myNode);
            } else {
                for (int i = 0; i < topLevelOr.jjtGetNumChildren(); i++) {
                    nodeSet.add(topLevelOr.jjtGetChild(i));
                }
            }
        }
        
        // Add delayed nodes.
        nodeSet.addAll(delayedNodes);
        
        if (!onlyEvents() || !o.onlyEvents()) {
            /*
             * We are dealing with high cardinality terms. Sum the counts and return a parent node.
             */
            merged.count = count + o.count;
            merged.uids = ImmutableSortedSet.of();
        } else {
            HashMultimap<String,JexlNode> ids = HashMultimap.create();
            
            /*
             * Concatenate all UIDs and merge the individual nodes
             */
            for (IndexMatch match : Iterables.concat(uids, o.uids)) {
                
                JexlNode newNode = match.getNode();
                if (null != newNode)
                    ids.put(match.uid, newNode);
            }
            
            Set<IndexMatch> matches = Sets.newHashSet();
            
            for (String uid : ids.keySet()) {
                Set<JexlNode> nodes = Sets.newHashSet(ids.get(uid));
                if (!nodes.isEmpty()) {
                    nodes.addAll(delayedNodes);
                    matches.add(new IndexMatch(nodes, uid, IndexMatchType.OR));
                }
                
            }
            merged.uids = ImmutableSortedSet.copyOf(matches);
            merged.count = merged.uids.size();
        }
        
        /*
         * If there are multiple levels within a union we could have an ASTOrNode. We cannot prune OrNodes as we would with an intersection, so propagate the
         * OrNode.
         */
        if (log.isTraceEnabled()) {
            for (String node : nodeSet.getNodeKeys()) {
                log.trace("internalNodeList node  is " + node);
            }
        }
        if (nodeSet.isEmpty()) {
            merged.myNode = null;
        } else {
            merged.myNode = TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createUnwrappedOrNode(nodeSet.getNodes()));
        }
        return merged;
    }
    
    /**
     * Find the intersection of a list of delayed nodes.
     * 
     * @param delayedNodes
     *            a list of delayed nodes.
     * @return true if any child nodes were added to this IndexInfo object.
     */
    public boolean intersect(List<JexlNode> delayedNodes) {
        
        if (!onlyEvents() || isInfinite()) {
            return false;
        }
        for (IndexMatch match : uids) {
            JexlNode newNode = match.getNode();
            if (null == newNode)
                continue;
            
            Set<JexlNode> nodeSet = Sets.newHashSet(delayedNodes);
            nodeSet.add(match.getNode());
            
            match.set(TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createAndNode(nodeSet)));
            // TODO this may need to be of type AND for nested logic to be correct
        }
        
        if (null != myNode || null != delayedNodes) {
            Set<JexlNode> internalNodeList = Sets.newHashSet();
            if (null != myNode)
                internalNodeList.add(myNode);
            if (null != delayedNodes)
                internalNodeList.addAll(delayedNodes);
            
            if (!internalNodeList.isEmpty()) {
                myNode = TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createAndNode(internalNodeList));
            }
        }
        
        return true;
    }

    public IndexInfo intersect(IndexInfo o) {
        return intersect(o, new ArrayList<>(), this, ServiceConfiguration.getDefaultInstance());
    }


    /**
     * The intersection logic is relatively straightforward as we only have three types of nodes
     * <ol>
     * <li>{@code INFINITE -- assume a hit exists}</li>
     * <li>{@code LARGE -- uids > 20, were pruned and a count remains}</li>
     * <li>{@code SMALL -- uids < 20}</li>
     * </ol>
     * See {@link #isInfinite()} for the definition of an infinite range.
     * <p>
     * We retain uids if and only if uids exist on both sides of the intersection
     * <p>
     * A uid and shard range will always produce a shard range. Never assume the uid hit exists on the other side.
     * <p>
     * In the case of two infinite ranges, we persist the -1 count for posterity
     *
     * @param o
     *            another {@link IndexInfo}
     * @param delayedNodes
     *            a list of delayed terms
     * @param uidIntersector
     *            a class that helps intersect uids
     * @return the result of an intersection operation on this IndexInfo and the other IndexInfo
     */
    public IndexInfo intersect(IndexInfo o, List<JexlNode> delayedNodes, UidIntersector uidIntersector, ServiceConfiguration queryConfiguration) {

        // infinite = (count == -1)
        // onlyEvents = (count == uids.size())

        if (!queryConfiguration.getIndexingConfiguration().isEnableIndexInfoUidToDayIntersectionBypass()) {
            if (isInfinite() && !o.isInfinite()) {
                /*
                 * A) we are intersecting UNKNOWN AND small
                 */
                if (o.onlyEvents())
                    return intersect(Math.max(count, o.count), o.uids(), o.getNode(), Lists.newArrayList(getNode()), delayedNodes);

            } else if (o.isInfinite() && !this.isInfinite()) {
                /*
                 * B) We are intersecting small and unknown.
                 */
                if (onlyEvents())
                    return intersect(Math.max(count, o.count), uids, getNode(), Lists.newArrayList(o.getNode()), delayedNodes);
            }
        }

        IndexInfo merged = new IndexInfo();



        if (queryConfiguration.getIndexingConfiguration().isEnableIndexInfoUidToDayIntersectionBypass()) {
            if (onlyEvents() && o.onlyEvents()) {

                // if both sides are uid-only perform an intersection on the uids, propagating delayed nodes
                merged.uids = ImmutableSortedSet.copyOf(uidIntersector.intersect(uids, o.uids, delayedNodes));
                merged.count = merged.uids.size();
            } else if (isInfinite() && o.isInfinite()) {

                // if both sides are infinite ranges generated by us (-1 count), persist the -1 count
                merged.count = -1;
                merged.uids = ImmutableSortedSet.of();

            } else {
                // else we are left countably infinite shard ranges. Take the minimum count
                merged.count = Math.min(count, o.count);
                merged.uids = ImmutableSortedSet.of();

            }
        }
        else{
            if (onlyEvents() && o.onlyEvents()) {
                /*
                 * C) Both are small, so we have an easy case where we can prune much of this sub query. Must propagate delayed nodes, though.
                 */
                merged.uids = ImmutableSortedSet.copyOf(uidIntersector.intersect(uids, o.uids, delayedNodes));
                merged.count = merged.uids.size();

            } else {

                if (o.isInfinite() && isInfinite()) {
                    /*
                     * D) Both sub trees are UNKNOWN, so we must propagate everything
                     */
                    merged.count = -1;
                    merged.uids = ImmutableSortedSet.of();
                } else {
                    if (onlyEvents()) {
                        /*
                         * E) We have small AND LARGE
                         */
                        merged.count = count;

                        HashMultimap<String,JexlNode> ids = HashMultimap.create();
                        for (IndexMatch match : uids) {
                            JexlNode newNode = match.getNode();
                            if (null != newNode)
                                ids.put(match.uid, newNode);
                        }

                        JexlNodeSet ourDelayedNodes = new JexlNodeSet();
                        ourDelayedNodes.addAll(delayedNodes);
                        // we may actually have no node on o
                        if (null != o.getNode())
                            ourDelayedNodes.add(o.getNode());

                        Set<IndexMatch> matches = buildNodeList(ids, IndexMatchType.AND, true, Lists.newArrayList(ourDelayedNodes.getNodes()));

                        merged.uids = ImmutableSortedSet.copyOf(matches);
                        merged.count = merged.uids.size();
                    } else if (o.onlyEvents()) {
                        /*
                         * E) We have LARGE AND SMALL
                         */
                        HashMultimap<String,JexlNode> ids = HashMultimap.create();
                        for (IndexMatch match : o.uids) {
                            JexlNode newNode = match.getNode();
                            if (null != newNode)
                                ids.put(match.uid, newNode);
                        }

                        JexlNodeSet ourDelayedNodes = new JexlNodeSet();
                        ourDelayedNodes.addAll(delayedNodes);
                        // possible, depending on how query is processed that we have no node.
                        if (null != getNode())
                            ourDelayedNodes.add(getNode());

                        Set<IndexMatch> matches = buildNodeList(ids, IndexMatchType.AND, true, Lists.newArrayList(ourDelayedNodes.getNodes()));
                        merged.uids = ImmutableSortedSet.copyOf(matches);
                        merged.count = merged.uids.size();
                    } else {

                        merged.count = Math.min(count, o.count);
                        merged.uids = ImmutableSortedSet.of();
                    }
                }
            }
        }
        // now handle updating the top level node
        JexlNodeSet nodes = new JexlNodeSet();
        if (this.getNode() != null) {
            nodes.add(this.getNode());
        }
        if (o.getNode() != null) {
            nodes.add(o.getNode());
        }
        nodes.addAll(delayedNodes);
        merged.myNode = TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createAndNode(nodes.getNodes()));
        return merged;
    }

    /**
     * Pivot on the iterable within matchIterable
     *
     * @param maxPossibilities
     * @param matchIterable
     * @param matchNode
     *            to be used for constructing the merged node when there are no IndexMatch objects
     * @param otherInfiniteNodes
     * @param delayedNodes
     * @return
     */
    protected IndexInfo intersect(long maxPossibilities, Iterable<IndexMatch> matchIterable, JexlNode matchNode, List<JexlNode> otherInfiniteNodes,
                                  List<JexlNode> delayedNodes) {
        HashMultimap<String,JexlNode> ids = HashMultimap.create();
        Set<IndexMatch> matches = Sets.newHashSet();

        // must be true or we shouldn't be here
        assert otherInfiniteNodes != null;
        assert delayedNodes != null;
        assert otherInfiniteNodes.size() + delayedNodes.size() > 0;

        for (IndexMatch match : matchIterable) {
            JexlNode newNode = match.getNode();
            if (null != newNode)
                ids.put(match.uid, newNode);
        }

        JexlNodeSet infiniteNodeSet = new JexlNodeSet();
        infiniteNodeSet.addAll(delayedNodes);
        for (JexlNode node : otherInfiniteNodes) {
            if (null != node)
                infiniteNodeSet.add(node);
        }

        IndexInfo merged = new IndexInfo();
        if (ids.keySet().isEmpty()) {
            merged.count = maxPossibilities;
        } else {
            for (String uid : ids.keySet()) {
                JexlNodeSet nodeSet = new JexlNodeSet();
                nodeSet.addAll(ids.get(uid));
                nodeSet.addAll(infiniteNodeSet);

                IndexMatch currentMatch = new IndexMatch(Sets.newHashSet(nodeSet.getNodes()), uid, IndexMatchType.AND);
                matches.add(currentMatch);
            }
            merged.count = matches.size();
        }

        JexlNode newNode;
        if (matches.size() > 1) {
            // get the unique node sets
            JexlNodeSet nodeSet = new JexlNodeSet();
            for (IndexMatch match : matches) {
                nodeSet.add(match.getNode());
            }

            // it is counter intuitive that this is an OR, but since each indexMatch is actually a potential different query path an or is appropriate here
            // example (A || B) && C
            // IndexMatch - A == 'a'
            // IndexMatch - B == 'b'
            // IndexMatch - C == infinite
            // the merge node's matches actually represent (A && C) || (A && B)
            // it may be possible to reduce the tree due to the IndexMatches only coming from one side
            // IndexMatch - A == 'a'
            // NoData - B == 'c'
            // IndexMatch - C == infinite
            // the merge nodes matches now just represent (A && C)
            if (nodeSet.size() > 1) {
                newNode = TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createOrNode(nodeSet.getNodes()));
            } else {
                newNode = TreeFlatteningRebuildingVisitor.flatten(nodeSet.getNodes().iterator().next());
            }
        } else if (matches.size() == 1) {
            newNode = TreeFlatteningRebuildingVisitor.flatten(matches.iterator().next().getNode());
        } else {
            JexlNodeSet nodeSet = new JexlNodeSet();
            nodeSet.addAll(infiniteNodeSet);
            nodeSet.add(matchNode);

            newNode = TreeFlatteningRebuildingVisitor.flatten(JexlNodeFactory.createAndNode(nodeSet.getNodes()));
        }

        merged.myNode = newNode;
        merged.uids = ImmutableSortedSet.copyOf(matches);

        return merged;
    }

    @Override
    public Set<IndexMatch> intersect(Set<IndexMatch> uids1, Set<IndexMatch> uids2, List<JexlNode> delayedNodes) {
        HashMultimap<String,JexlNode> ids = HashMultimap.create();
        for (IndexMatch match : Iterables.concat(uids1, uids2)) {
            JexlNode newNode = match.getNode();
            if (null != newNode)
                ids.put(match.uid, newNode);
        }
        
        // Do the actual merge of ids here; only ids with more than one JexlNode will make it through this method.
        return buildNodeList(ids, IndexMatchType.AND, false, delayedNodes);
    }
    
    protected Set<IndexMatch> buildNodeList(HashMultimap<String,JexlNode> ids, IndexMatchType type, boolean allowsDelayed, List<JexlNode> delayedNodes) {
        Set<IndexMatch> matches = Sets.newHashSet();
        for (String uid : ids.keySet()) {
            
            Set<JexlNode> nodes = ids.get(uid);
            // make sure that we have nodes, otherwise we are pruned to nothing
            if (nodes.size() > 1 || (allowsDelayed && (nodes.size() + delayedNodes.size()) > 1)) {
                JexlNodeSet nodeSet = new JexlNodeSet();
                nodeSet.addAll(nodes);
                nodeSet.addAll(delayedNodes);
                
                IndexMatch currentMatch = new IndexMatch(Sets.newHashSet(nodeSet.getNodes()), uid, type);
                matches.add(currentMatch);
            }
        }
        return matches;
    }
    
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof IndexInfo) {
            IndexInfo other = (IndexInfo) o;
            return count() == other.count() && uids().equals(other.uids());
        } else {
            return false;
        }
    }
    
    @Override
    public int hashCode() {
        return Objects.hashCode(count(), uids());
    }
    
    public String toString() {
        return "{ \"count\": " + count() + " - " + uids.size() + " }";
    }
    
    /**
     * An infinite range is one that datawave created. This can happen several ways
     * <ol>
     * <li>{@link RangeStream#createFullFieldIndexScanList(ShardQueryConfiguration, JexlNode)}</li>
     * <li>{@link RangeStream#createIndexScanList(String[])}</li>
     * <li>{@link ShardLimitingIterator#next()}</li>
     * <li>{@link Union} of all delayed terms</li>
     * <li>RangeStreamScanner if certain thresholds are exceeded</li>
     * </ol>
     *
     * @return true if the count is equal to negative one
     */
    private boolean isInfinite() {
        return count == -1L;
    }
    
    public void setNode(JexlNode currNode) {
        myNode = currNode;
        
        // need to update the nodes for the underlying index matches in order
        // to persist delayed terms. In the case of a document specific range
        // the query plan is pulled from indexMatch.getNode()
        for (IndexMatch uid : uids) {
            uid.set(myNode);
        }
    }
}
