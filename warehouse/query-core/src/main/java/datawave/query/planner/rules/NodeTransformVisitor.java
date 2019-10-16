package datawave.query.planner.rules;

import datawave.query.config.ShardQueryConfiguration;
import datawave.query.jexl.nodes.QueryPropertyMarker;
import datawave.query.jexl.visitors.RebuildingVisitor;
import org.apache.commons.jexl2.parser.ASTAndNode;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTERNode;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.ASTGENode;
import org.apache.commons.jexl2.parser.ASTGTNode;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.jexl2.parser.ASTLENode;
import org.apache.commons.jexl2.parser.ASTLTNode;
import org.apache.commons.jexl2.parser.ASTNENode;
import org.apache.commons.jexl2.parser.ASTNRNode;
import org.apache.commons.jexl2.parser.ASTNotNode;
import org.apache.commons.jexl2.parser.ASTOrNode;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;

import java.util.List;

public class NodeTransformVisitor extends RebuildingVisitor {
    
    private final ShardQueryConfiguration config;
    private final List<NodeTransformRule> rules;
    
    public NodeTransformVisitor(ShardQueryConfiguration config, List<NodeTransformRule> rules) {
        assert (rules != null);
        assert (config != null);
        this.config = config;
        this.rules = rules;
    }
    
    public static ASTJexlScript transform(ASTJexlScript tree, List<NodeTransformRule> rules, ShardQueryConfiguration config) {
        NodeTransformVisitor visitor = new NodeTransformVisitor(config, rules);
        return visitor.apply(tree);
    }
    
    @Override
    public Object visit(ASTOrNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTAndNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTEQNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTNENode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTLTNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTGTNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTLENode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTGENode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTERNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTNRNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTNotNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTFunctionNode node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    @Override
    public Object visit(ASTReference node, Object data) {
        // do not recurse on a marker node
        if (QueryPropertyMarker.instanceOf(node, null)) {
            return applyTransforms(node);
        } else {
            return applyTransforms(super.visit(node, data));
        }
    }
    
    @Override
    public Object visit(ASTReferenceExpression node, Object data) {
        return applyTransforms(super.visit(node, data));
    }
    
    private Object applyTransforms(Object node) {
        if (!rules.isEmpty()) {
            for (NodeTransformRule rule : rules) {
                node = rule.apply((JexlNode) node, config);
            }
        }
        return node;
    }
}
