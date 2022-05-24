package datawave.query.jexl.visitors;

import datawave.query.config.ShardQueryConfiguration;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.JexlNodeFactory;
import datawave.query.jexl.functions.FunctionJexlNodeVisitor;
import datawave.query.parser.JavaRegexAnalyzer;
import datawave.query.parser.JavaRegexAnalyzer.JavaRegexParseException;
import datawave.query.util.MetadataHelper;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.QueryException;
import org.apache.commons.jexl2.parser.ASTFunctionNode;
import org.apache.commons.jexl2.parser.ASTIdentifier;
import org.apache.commons.jexl2.parser.JexlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static datawave.query.jexl.functions.EvaluationPhaseFilterFunctionsDescriptor.EXCLUDE_REGEX;
import static datawave.query.jexl.functions.EvaluationPhaseFilterFunctionsDescriptor.INCLUDE_REGEX;

/**
 * Rewrites evaluation phase filter functions for index only fields into their equivalent regex nodes. For a multi-fielded filter function like
 * <code>filter:includeRegex(FIELD_A||FIELD_B,'regex.*')</code> to be rewritten all fields must be index-only.
 * <ul>
 * <li><code>filter:includeRegex</code> becomes an <code>ASTERNode</code></li>
 * <li><code>filter:excludeRegex</code> becomes an <code>ASTNRNode</code></li>
 * </ul>
 */
public class RegexFunctionVisitor extends FunctionIndexQueryExpansionVisitor {
    
    protected Set<String> nonEventFields;
    
    public RegexFunctionVisitor(ShardQueryConfiguration config, MetadataHelper metadataHelper, Set<String> nonEventFields) {
        super(config, metadataHelper, null);
        this.nonEventFields = nonEventFields;
    }
    
    @SuppressWarnings("unchecked")
    public static <T extends JexlNode> T expandRegex(ShardQueryConfiguration config, MetadataHelper metadataHelper, Set<String> nonEventFields, T script) {
        RegexFunctionVisitor visitor = new RegexFunctionVisitor(config, metadataHelper, nonEventFields);
        JexlNode root = (T) script.jjtAccept(visitor, null);
        root = TreeFlatteningRebuildingVisitor.flatten(root);
        return (T) root;
    }
    
    @Override
    public Object visit(ASTFunctionNode node, Object data) {
        JexlNode returnNode = copy(node);
        FunctionJexlNodeVisitor functionMetadata = new FunctionJexlNodeVisitor();
        node.jjtAccept(functionMetadata, null);
        
        if (functionMetadata.name().equals(INCLUDE_REGEX) || functionMetadata.name().equals(EXCLUDE_REGEX)) {
            List<JexlNode> arguments = functionMetadata.args();
            
            List<ASTIdentifier> identifiers = JexlASTHelper.getIdentifiers(arguments.get(0));
            List<JexlNode> children = new ArrayList<>(identifiers.size());
            
            for (ASTIdentifier identifier : identifiers) {
                JexlNode regexNode = buildRegexNode(identifier, functionMetadata.name(), arguments.get(1).image);
                if (regexNode != null) {
                    children.add(regexNode);
                }
            }
            
            // only re-parent if the same number of regex nodes were built
            if (identifiers.size() == children.size()) {
                switch (identifiers.size()) {
                    case 0:
                        return returnNode;
                    case 1:
                        return children.get(0);
                    default:
                        if (functionMetadata.name().equals(INCLUDE_REGEX)) {
                            return JexlNodeFactory.createOrNode(children);
                        } else {
                            return JexlNodeFactory.createAndNode(children);
                        }
                }
            }
        }
        return returnNode;
    }
    
    /**
     * Builds a regex node given a field name, regex and original function name.
     * 
     * @param identifier
     *            the field
     * @param functionName
     *            the original function name, either <code>includeRegex</code> or <code>excludeRegex</code>
     * @param regex
     *            the regex
     * @return a new regex node, or null if no such regex node could be built
     */
    private JexlNode buildRegexNode(ASTIdentifier identifier, String functionName, String regex) {
        String field = JexlASTHelper.deconstructIdentifier(identifier.image);
        if (nonEventFields.contains(field.toUpperCase())) {
            try {
                JavaRegexAnalyzer jra = new JavaRegexAnalyzer(regex);
                if (!jra.isNgram()) {
                    if (functionName.equals(INCLUDE_REGEX)) {
                        return JexlNodeFactory.buildERNode(field, regex);
                    } else {
                        return JexlNodeFactory.buildNRNode(field, regex);
                    }
                }
            } catch (JavaRegexParseException e) {
                QueryException qe = new QueryException(DatawaveErrorCode.INVALID_REGEX);
                throw new DatawaveFatalQueryException(qe);
            }
        }
        return null;
    }
}
