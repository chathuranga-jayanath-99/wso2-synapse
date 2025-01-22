/*
 *  Copyright (c) 2024, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.synapse.util.xpath;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;

import org.apache.synapse.config.xml.SynapsePath;
import org.apache.synapse.util.synapse.expression.exception.SyntaxError;
import org.apache.synapse.util.synapse_expression.ExpressionLexer;
import org.apache.synapse.util.synapse_expression.ExpressionParser;
import org.apache.synapse.util.synapse.expression.ast.ExpressionNode;
import org.apache.synapse.util.synapse.expression.ast.ExpressionResult;
import org.apache.synapse.util.synapse.expression.context.EvaluationContext;
import org.apache.synapse.util.synapse.expression.exception.EvaluationException;
import org.apache.synapse.util.synapse.expression.exception.SyntaxErrorListener;
import org.apache.synapse.util.synapse.expression.visitor.ExpressionVisitor;
import org.jaxen.JaxenException;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a Synapse Expression
 * Syntax ${ + expression +  } ex: expression="${vars.test + 5}"
 */
public class SynapseExpression extends SynapsePath {
    private static final Log log = LogFactory.getLog(SynapseExpression.class);
    private final ExpressionNode expressionNode;
    private final Map<String, String> namespaceMap = new HashMap<>();
    private final boolean isContentAware;

    public SynapseExpression(String synapseExpression) throws JaxenException {
        super(synapseExpression, org.apache.synapse.config.xml.SynapsePath.JSON_PATH, log);

        CharStream input = CharStreams.fromString(expression);
        ExpressionLexer lexer = new ExpressionLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ExpressionParser parser = new ExpressionParser(tokens);
        parser.removeErrorListeners();
        SyntaxErrorListener errorListener = new SyntaxErrorListener();
        parser.addErrorListener(errorListener);

        ParseTree tree = parser.expression();
        // if there are any tokens left after parsing the expression, throw an exception
        if (tokens.LA(1) != Token.EOF) {
            throw new JaxenException("Parse error: leftover input after parsing expression in " + synapseExpression);
        }

        ExpressionVisitor visitor = new ExpressionVisitor();
        expressionNode = visitor.visit(tree);
        if (errorListener.hasErrors()) {
            StringBuilder errorMessage = new StringBuilder("Syntax error in expression: " + synapseExpression);
            for (SyntaxError error : errorListener.getErrors()) {
                errorMessage.append(" ").append(error.getMessage());
            }
            throw new JaxenException(errorMessage.toString());
        }
        isContentAware = SynapseExpressionUtils.isSynapseExpressionContentAware(synapseExpression);
        this.setPathType(SynapsePath.SYNAPSE_EXPRESSIONS_PATH);
    }

    @Override
    public String stringValueOf(MessageContext synCtx) {
        if (log.isDebugEnabled()) {
            log.debug("Evaluating expression (stringValueOf): " + expression);
        }
        EvaluationContext context = new EvaluationContext();
        context.setNamespaceMap(namespaceMap);
        context.setSynCtx(synCtx);
        ExpressionResult result = evaluateExpression(context, false);
        return result != null ? result.asString() : "";
    }

    @Override
    public Object objectValueOf(MessageContext synCtx) {
        if (log.isDebugEnabled()) {
            log.debug("Evaluating expression (objectValueOf): " + expression);
        }
        EvaluationContext context = new EvaluationContext();
        context.setNamespaceMap(namespaceMap);
        context.setSynCtx(synCtx);
        ExpressionResult result = evaluateExpression(context, true);
        return result != null ? result.getValue() : "";
    }

    private ExpressionResult evaluateExpression(EvaluationContext context, boolean isObjectValue) {
        try {
            return expressionNode.evaluate(context, isObjectValue);
        } catch (EvaluationException e) {
            log.warn("Error evaluating expression: " + expression + " cause : " + e.getMessage());
            return null;
        }
    }

    public void addNamespace(String var1, String var2) throws JaxenException {
        namespaceMap.put(var1, var2);
    }

    public boolean isContentAware() {
        return this.isContentAware;
    }
}
