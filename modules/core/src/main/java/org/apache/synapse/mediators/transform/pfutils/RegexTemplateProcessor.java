/*
 *Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *WSO2 Inc. licenses this file to you under the Apache License,
 *Version 2.0 (the "License"); you may not use this file except
 *in compliance with the License.
 *You may obtain a copy of the License at
 *
 *http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing,
 *software distributed under the License is distributed on an
 *"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *KIND, either express or implied.  See the License for the
 *specific language governing permissions and limitations
 *under the License.
 */


package org.apache.synapse.mediators.transform.pfutils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMException;
import org.apache.axis2.AxisFault;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.mediators.transform.ArgumentDetails;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.jaxen.JaxenException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLStreamException;

/**
 * TemplateProcessor implementation for Regex based templates
 */
public class RegexTemplateProcessor extends TemplateProcessor {

    private static final Log log = LogFactory.getLog(RegexTemplateProcessor.class);
    // Pattern matches "${...}" (quoted), ${...} (unquoted), and $n
    private final Pattern pattern = Pattern.compile("\"\\$\\{([^}]+)\\}\"|\\$\\{([^}]+)\\}|\\$(\\d+)");

    private final Gson gson = new Gson();
    private final Map<String, SynapseExpression> inlineExpressionCache = new ConcurrentHashMap<>();

    @Override
    public String processTemplate(String template, String mediaType, MessageContext synCtx) {

        StringBuffer result = new StringBuffer();
        replace(template, result, mediaType, synCtx);
        return result.toString();
    }

    @Override
    public void init() throws SynapseException {
        String format = getFormat();
        if (format != null) {
            try {
                InlineExpressionUtil.initInlineSynapseExpressions(format, inlineExpressionCache);
            } catch (JaxenException e) {
                String msg = "Invalid Payload format : " + e.getMessage();
                throw new SynapseException(msg);
            }
        }
        this.readInputFactoryProperties();
    }

    /**
     * Replaces the payload format with SynapsePath arguments which are evaluated using getArgValues().
     *
     * @param format
     * @param result
     * @param synCtx
     */
    private void replace(String format, StringBuffer result, String mediaType, MessageContext synCtx) {

        Map<String, Object> inlineExpressionResults = new ConcurrentHashMap<>();
        HashMap<String, ArgumentDetails>[] argValues = getArgValues(mediaType, synCtx);
        HashMap<String, ArgumentDetails> replacement;
        Map.Entry<String, ArgumentDetails> replacementEntry;
        String replacementValue;
        Matcher matcher;

        if (JSON_TYPE.equals(mediaType) || TEXT_TYPE.equals(mediaType)) {
            matcher = pattern.matcher(format);
        } else {
            matcher = pattern.matcher("<pfPadding>" + format + "</pfPadding>");
        }
        try {
            while (matcher.find()) {
                if (matcher.group(1) != null) {
                    // Handle "${...}" pattern (with quotes)
                    String expression = matcher.group(1);
                    Object expressionResult = evaluateExpression(expression, synCtx, inlineExpressionResults);
                    if (expressionResult instanceof JsonPrimitive) {
                        replacementValue = prepareJSONPrimitiveReplacementValue(expressionResult, mediaType);
                    } else if (expressionResult instanceof JsonElement) {
                        // Escape JSON object and Arrays since we need to consider it as
                        replacementValue = escapeJson(Matcher.quoteReplacement(gson.toJson(expressionResult)));
                        if (XML_TYPE.equals(mediaType)) {
                            replacementValue = convertJsonToXML(replacementValue);
                        }
                    } else {
                        replacementValue = expressionResult.toString();
                        if (XML_TYPE.equals(mediaType)) {
                            replacementValue = StringEscapeUtils.escapeXml10(replacementValue);
                        } else if (JSON_TYPE.equals(mediaType)) {
                            if (isXML(replacementValue)) {
                                // consider the replacement value as a literal XML
                                replacementValue = escapeSpecialChars(Matcher.quoteReplacement(replacementValue));
                            } else {
                                replacementValue = escapeSpecialCharactersOfJson(replacementValue);
                            }
                        }
                    }
                    matcher.appendReplacement(result, "\"" + replacementValue + "\"");
                } else if (matcher.group(2) != null) {
                    // Handle ${...} pattern (without quotes)
                    String expression = matcher.group(2);
                    Object expressionResult = evaluateExpression(expression, synCtx, inlineExpressionResults);
                    replacementValue = expressionResult.toString();
                    if (expressionResult instanceof JsonPrimitive) {
                        replacementValue = prepareJSONPrimitiveReplacementValue(expressionResult, mediaType);
                    } else if (expressionResult instanceof JsonElement) {
                        if (XML_TYPE.equals(mediaType)) {
                            replacementValue = convertJsonToXML(replacementValue);
                            replacementValue = Matcher.quoteReplacement(replacementValue);
                        } else {
                            replacementValue = Matcher.quoteReplacement(gson.toJson(expressionResult));
                        }
                    } else {
                        if (JSON_TYPE.equals(mediaType) && isXML(replacementValue)) {
                            replacementValue = convertXMLToJSON(replacementValue);
                        } else {
                            if (XML_TYPE.equals(mediaType) && !isXML(replacementValue)) {
                                replacementValue = StringEscapeUtils.escapeXml10(replacementValue);
                            }
                            replacementValue = Matcher.quoteReplacement(replacementValue);
                        }
                    }
                    matcher.appendReplacement(result, replacementValue);
                } else if (matcher.group(3) != null) {
                    // Handle $n pattern
                    String matchSeq = matcher.group(3);
                    replacement = getReplacementValue(argValues, matchSeq);
                    replacementEntry = replacement.entrySet().iterator().next();
                    replacementValue = prepareReplacementValue(mediaType, synCtx, replacementEntry);
                    matcher.appendReplacement(result, replacementValue);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            log.error("#replace. Mis-match detected between number of formatters and arguments", e);
        } catch (JaxenException e) {
            throw new SynapseException("Error evaluating expression" , e);
        }
        matcher.appendTail(result);
    }

    private String prepareJSONPrimitiveReplacementValue(Object expressionResult, String mediaType) {

        String replacementValue = ((JsonPrimitive) expressionResult).getAsString();
        replacementValue = escapeSpecialChars(Matcher.quoteReplacement(replacementValue));
        if (XML_TYPE.equals(mediaType)) {
            replacementValue = StringEscapeUtils.escapeXml10(replacementValue);
        }
        return replacementValue;
    }

    /**
     * Evaluates the expression and returns the result as a string or an object.
     * If the expression contains "xpath(", we meed to evaluate it as a string.
     *
     * @param expression expression to evaluate
     * @param synCtx     message context
     * @return evaluated result
     * @throws JaxenException if an error occurs while evaluating the expression
     */
    private Object evaluateExpression(String expression, MessageContext synCtx,
                                      Map<String, Object> inlineExpressionResults) throws JaxenException {

        SynapseExpression expressionObj = inlineExpressionCache.get(expression);
        if (expressionObj == null) {
            expressionObj = new SynapseExpression(expression);
            inlineExpressionCache.put(expression, expressionObj);
        }
        if (inlineExpressionResults.containsKey(expression)) {
            return inlineExpressionResults.get(expression);
        } else {
            Object result;
            if (expression.contains("xpath(")) {
                result = expressionObj.stringValueOf(synCtx);
            } else {
                result = expressionObj.objectValueOf(synCtx);
            }
            inlineExpressionResults.put(expression, result);
            return result;
        }
    }

    private String escapeJson(String value) {
        // Manual escape for JSON: escaping double quotes and backslashes
        return value.replace("\"", "\\\"").replace("\\", "\\\\");
    }

    private String convertJsonToXML(String replacementValue) {

        try {
//            replacementValue = Matcher.quoteReplacement(replacementValue);
            OMElement omXML = JsonUtil.toXml(IOUtils.toInputStream(replacementValue), false);
            if (JsonUtil.isAJsonPayloadElement(omXML)) { // remove <jsonObject/> from result.
                Iterator children = omXML.getChildElements();
                String childrenStr = "";
                while (children.hasNext()) {
                    childrenStr += (children.next()).toString().trim();
                }
                replacementValue = childrenStr;
            } else {
                replacementValue = omXML.toString();
            }
        } catch (AxisFault e) {
            handleException(
                    "Error converting JSON to XML, please check your expressions return valid JSON: ");
        }
        return escapeSpecialCharactersOfXml(replacementValue);
    }

    private String convertXMLToJSON(String replacementValue) {

        try {
            replacementValue = "<jsonObject>" + replacementValue + "</jsonObject>";
            OMElement omXML = convertStringToOM(replacementValue);
            replacementValue = JsonUtil.toJsonString(omXML).toString();
            replacementValue = escapeSpecialCharactersOfJson(replacementValue);
        } catch (XMLStreamException e) {
            handleException(
                    "Error parsing XML for JSON conversion, please check your expressions return valid XML: ");
        } catch (AxisFault e) {
            handleException("Error converting XML to JSON");
        } catch (OMException e) {
            // if the logic comes to this means, it was tried as a XML, which means it has
            // "<" as starting element and ">" as end element, so basically if the logic comes here, that means
            // value is a string value, that means No conversion required, as path evaluates to regular String.
            replacementValue = escapeSpecialChars(replacementValue);

        }
        return replacementValue;
    }

    private HashMap<String, ArgumentDetails> getReplacementValue(HashMap<String, ArgumentDetails>[] argValues,
                                                                 String matchSeq) {

        HashMap<String, ArgumentDetails> replacement;
        int argIndex;
        try {
            argIndex = Integer.parseInt(matchSeq);
        } catch (NumberFormatException e) {
            argIndex = Integer.parseInt(matchSeq.substring(2, matchSeq.length() - 1));
        }
        replacement = argValues[argIndex - 1];
        return replacement;
    }

    /**
     * Get whether the template format was received
     *
     * @return Status of the format of the template
     */
    public boolean getTemplateStatus(){
        // since this method is added to the freemarker template, it is here for consistency
        return true;
    }

}
