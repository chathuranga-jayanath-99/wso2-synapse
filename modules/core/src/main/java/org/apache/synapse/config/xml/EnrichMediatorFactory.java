/*
 *  Copyright (c) 2005-2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.synapse.config.xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMText;
import org.apache.axiom.om.impl.llom.OMTextImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.Mediator;
import org.apache.synapse.SynapseException;
import org.apache.synapse.mediators.elementary.EnrichMediator;
import org.apache.synapse.mediators.elementary.Source;
import org.apache.synapse.mediators.elementary.Target;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.xpath.SynapseJsonPath;
import org.jaxen.JaxenException;

import java.util.Properties;
import javax.xml.namespace.QName;

/**
 * Factory for {@link EnrichMediator} instances.
 */
public class EnrichMediatorFactory extends AbstractMediatorFactory {

    private static final QName XML_Q = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "enrich");
    private static final QName ATT_PROPERTY = new QName("property");
    private static final QName ATT_XPATH = new QName("xpath");
    private static final QName ATT_TYPE = new QName("type");
    private static final QName ATT_CLONE = new QName("clone");
    private static final QName ATT_ACTION = new QName("action");
    private static final QName ATT_KEY = new QName("key");

    public static final QName SOURCE_Q = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "source");
    public static final QName TARGET_Q = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "target");

    public static final String CUSTOM = "custom";
    public static final String PROPERTY = "property";
    public static final String ENVELOPE = "envelope";
    public static final String BODY = "body";
    public static final String INLINE = "inline";
    public static final String KEY = "key";

    @Override
    protected Mediator createSpecificMediator(OMElement elem, Properties properties) {
        if (!XML_Q.equals(elem.getQName())) {
            handleException("Unable to create the enrich mediator. "
                    + "Unexpected element as the enrich mediator configuration");
        }

        EnrichMediator enrich = new EnrichMediator();
        processAuditStatus(enrich, elem);

        OMElement sourceEle = elem.getFirstChildWithName(SOURCE_Q);
        if (sourceEle == null) {
            handleException("source element is mandatory");
        }
        Source source = new Source();
        enrich.setSource(source);

        OMElement targetEle = elem.getFirstChildWithName(TARGET_Q);
        if (targetEle == null) {
            handleException("target element is mandatory");
        }
        Target target = new Target();
        enrich.setTarget(target);

        validateTypeCombination(sourceEle, targetEle);

        populateSource(source, sourceEle);
        populateTarget(target, targetEle);

        // check whether the inline element of the source is XML
        boolean isInlineSourceXML = false;
        String inlineString = null;
        if (source.getInlineOMNode() != null) {
            if (source.getInlineOMNode() instanceof OMText) {
                inlineString = ((OMTextImpl) source.getInlineOMNode()).getText();
                JsonParser parser = new JsonParser();
                try {
                    JsonElement element = parser.parse(inlineString);
                    if (!(element instanceof JsonObject || element instanceof JsonArray ||
                            element instanceof JsonPrimitive)) {
                        isInlineSourceXML = true;
                    }
                } catch (JsonSyntaxException ex) {
                    // JSON string fails to parse when it contains an inline expression
                    // such as {"company2": {json-eval($.SamplePayload.name)}}
                    // Therefore, we will check if it is JSON by checking for {}
                    if (!(inlineString.trim().startsWith("{") && inlineString.trim().endsWith("}"))) {
                        // not a JSON. Going ahead with XML
                        isInlineSourceXML = true;
                    }
                }
            } else if (source.getInlineOMNode() instanceof OMElement) {
                inlineString = ((OMElement) source.getInlineOMNode()).getText();
                isInlineSourceXML = true;
            }

            if (!StringUtils.isEmpty(inlineString)) {
                source.setContainsInlineExpressions(InlineExpressionUtil.checkForInlineExpressions(inlineString));
            }
        }

        // Check the enrich mediator configuration to see whether it can support JSON natively
        boolean sourceHasCustom = (source.getSourceType() == EnrichMediator.CUSTOM);
        boolean targetHasCustom = (target.getTargetType() == EnrichMediator.CUSTOM);
        boolean enrichHasCustom = (sourceHasCustom || targetHasCustom);
        boolean sourceHasEnvelope = (source.getSourceType() == EnrichMediator.ENVELOPE);
        boolean targetHasEnvelope = (target.getTargetType() == EnrichMediator.ENVELOPE);
        boolean enrichHasEnvelope = (sourceHasEnvelope || targetHasEnvelope);

        boolean sourceHasACustomJsonPath = false;
        boolean targetHasACustomJsonPath = false;

        if (sourceHasCustom) {
            sourceHasACustomJsonPath = SynapsePath.JSON_PATH.equals(source.getXpath().getPathType());
        }

        if (targetHasCustom) {
            targetHasACustomJsonPath = SynapsePath.JSON_PATH.equals(target.getXpath().getPathType());
        }

        // conditions where native-json-processing is supported
        boolean condition1 = (!enrichHasCustom);
        boolean condition2 = (sourceHasACustomJsonPath && !targetHasCustom);
        boolean condition3 = (!sourceHasCustom && targetHasACustomJsonPath);
        boolean condition4 = (sourceHasACustomJsonPath && targetHasACustomJsonPath);
        boolean condition5 = !enrichHasEnvelope;

        enrich.setNativeJsonSupportEnabled(
                !isInlineSourceXML && condition5 && (condition1 || condition2 || condition3 ||
                                                                   condition4));
        addAllCommentChildrenToList(elem, enrich.getCommentsList());

        return enrich;
    }

    private void populateSource(Source source, OMElement sourceEle) {

        // type attribute
        OMAttribute typeAttr = sourceEle.getAttribute(ATT_TYPE);
        if (typeAttr != null && typeAttr.getAttributeValue() != null) {
            source.setSourceType(convertTypeToInt(typeAttr.getAttributeValue()));
        }

        OMAttribute cloneAttr = sourceEle.getAttribute(ATT_CLONE);
        if (cloneAttr != null && cloneAttr.getAttributeValue() != null) {
            source.setClone(Boolean.parseBoolean(cloneAttr.getAttributeValue()));
        }

        if (source.getSourceType() == EnrichMediator.CUSTOM) {
            OMAttribute xpathAttr = sourceEle.getAttribute(ATT_XPATH);
            if (xpathAttr != null && xpathAttr.getAttributeValue() != null) {
                try {
                    source.setXpath(SynapsePathFactory.getSynapsePath(sourceEle, ATT_XPATH));
                } catch (JaxenException e) {
                    handleException("Invalid XPath expression: " + xpathAttr);
                }
            } else {
                handleException("xpath attribute is required for CUSTOM type");
            }
        } else if (source.getSourceType() == EnrichMediator.PROPERTY) {
            OMAttribute propertyAttr = sourceEle.getAttribute(ATT_PROPERTY);
            if (propertyAttr != null && propertyAttr.getAttributeValue() != null) {
                source.setProperty(propertyAttr.getAttributeValue());
            } else {
                handleException("xpath attribute is required for CUSTOM type");
            }
        } else if (source.getSourceType() == EnrichMediator.INLINE) {
            OMElement inlineElem = null;
            if (sourceEle.getFirstElement() != null) {
                inlineElem = sourceEle.getFirstElement().cloneOMElement();
            }

            if (inlineElem != null) {
                source.setInlineOMNode(inlineElem);
            } else if (!StringUtils.isBlank(sourceEle.getText())) {
                source.setInlineOMNode(OMAbstractFactory.getOMFactory().createOMText(sourceEle.getText()));
            } else if (sourceEle.getAttributeValue(ATT_KEY) != null) {
                source.setInlineKey(sourceEle.getAttributeValue(ATT_KEY));
            } else {
                handleException("XML element is required for INLINE type");
            }
        }
    }

    private void populateTarget(Target target, OMElement sourceEle) {
        // type attribute
        OMAttribute typeAttr = sourceEle.getAttribute(ATT_TYPE);
        OMAttribute actionAttr = sourceEle.getAttribute(ATT_ACTION);

        if (actionAttr != null && actionAttr.getAttributeValue() != null) {
            target.setAction(actionAttr.getAttributeValue());
        } else {
            target.setAction("replace");
        }

        if (typeAttr != null && typeAttr.getAttributeValue() != null) {
            int type = convertTypeToInt(typeAttr.getAttributeValue());
            if (type >= 0) {
                target.setTargetType(type);
                if (type == 1) {
                    if (!target.getAction().equals("replace")) {
                        throw new SynapseException("Invalid target action");
                    }
                }
            } else {
                handleException("Un-expected type : " + typeAttr.getAttributeValue());
            }
        }

        if (target.getTargetType() == EnrichMediator.CUSTOM) {
            OMAttribute xpathAttr = sourceEle.getAttribute(ATT_XPATH);
            if (xpathAttr != null && xpathAttr.getAttributeValue() != null) {
                try {
                    target.setXpath(SynapsePathFactory.getSynapsePath(sourceEle, ATT_XPATH));
                } catch (JaxenException e) {
                    handleException("Invalid XPath expression: " + xpathAttr);
                }
                SynapsePath targetXPath = target.getXpath();
                if (target.getAction().equals(Target.ACTION_REPLACE) && (targetXPath instanceof SynapseJsonPath)
                        && ("$".equals(((SynapseJsonPath) targetXPath).expression) ||
                        "$.".equals(((SynapseJsonPath) targetXPath).expression))) {
                    handleException("Acting replace is not supported for root path in type custom. " +
                            "Please use type body action replace instead");
                }

            } else {
                handleException("xpath attribute is required for CUSTOM type");
            }
        } else if (target.getTargetType() == EnrichMediator.PROPERTY) {
            OMAttribute propertyAttr = sourceEle.getAttribute(ATT_PROPERTY);
            if (propertyAttr != null && propertyAttr.getAttributeValue() != null) {
                target.setProperty(propertyAttr.getAttributeValue());
            } else {
                handleException("xpath attribute is required for CUSTOM type");
            }
        } else if (target.getTargetType() == EnrichMediator.KEY) {
            OMAttribute xpathAttr = sourceEle.getAttribute(ATT_XPATH);
            if (xpathAttr != null && xpathAttr.getAttributeValue() != null) {
                try {
                    target.setXpath(SynapsePathFactory.getSynapsePath(sourceEle, ATT_XPATH));
                } catch (JaxenException e) {
                    handleException("Invalid XPath expression: " + xpathAttr);
                }
                if (!target.getAction().equals(Target.ACTION_REPLACE)) {
                    handleException("Only the replace action is supported for the target type 'key'.");
                }
            } else {
                handleException("Xpath must be defined for the target type 'key'.");
            }
        }
    }

    private int convertTypeToInt(String type) {
        if (type.equals(ENVELOPE)) {
            return EnrichMediator.ENVELOPE;
        } else if (type.equals(BODY)) {
            return EnrichMediator.BODY;
        } else if (type.equals(PROPERTY)) {
            return EnrichMediator.PROPERTY;
        } else if (type.equals(CUSTOM)) {
            return EnrichMediator.CUSTOM;
        } else if (type.equals(INLINE)) {
            return EnrichMediator.INLINE;
        } else if (type.equals(KEY)) {
            return EnrichMediator.KEY;
        }
        return -1;
    }

    // Adding this method to be consistent with convertTypeToInt
    private int convertActionToInt(String action) {
        switch (action) {
            case Target.ACTION_REPLACE: {
                return 0;
            }
            case Target.ACTION_ADD_CHILD: {
                return 1;
            }
            case Target.ACTION_ADD_SIBLING: {
                return 2;
            }
            case Target.ACTION_REMOVE: {
                return 3;
            }
            default:
                return -1;
        }
    }

    public QName getTagQName() {
        return XML_Q;
    }

    /**
     * Check the combination of the source and target types are valid or not and throw proper exception.
     * Here the integers 0-4 refer as below
     * 0-custom, 1-envelope, 2-body, 3-property, 4-inline
     *
     * @param sourceElement
     * @param targetElement
     */
    private void validateTypeCombination(OMElement sourceElement, OMElement targetElement) {
        int sourceType = -1;
        int targetType = -1;
        int targetAction = 0;

        // source type attribute
        OMAttribute sourceTypeAttr = sourceElement.getAttribute(ATT_TYPE);
        if (sourceTypeAttr != null && sourceTypeAttr.getAttributeValue() != null) {
            sourceType = convertTypeToInt(sourceTypeAttr.getAttributeValue());

            // check if source type is different form the existing
            if (sourceType < 0) {
                throw new SynapseException("Unexpected source type");
            }
        } else {
            // when type = custom we don't need to specify that in configs
            sourceType = 0;
        }
        // target type attribute
        OMAttribute targetTypeAttr = targetElement.getAttribute(ATT_TYPE);
        if (targetTypeAttr != null && targetTypeAttr.getAttributeValue() != null) {
            targetType = convertTypeToInt(targetTypeAttr.getAttributeValue());

            // check if target type is different form the existing
            if (targetType < 0) {
                throw new SynapseException("Unexpected target type");
            }
            // check if target type is 4-inline
            if (targetType == 4) {
                throw new SynapseException("Inline not support for target attribute");
            }
        }
        /*
            check the wrong combination such as
            sourceType = 1-envelope and targetType = 0-custom
            sourceType = 1-envelope and targetType = 2-body
            sourceType = 2-body and targetType = 2-body
            sourceType = 0-custom and targetType = 1-envelope
            sourceType = 1-envelope and targetType = 1-envelope
            sourceType = 2-body and targetType = 1-envelope

         */
        if (sourceType == 1) {
            if (targetType == 0 || targetType == 1 || targetType == 2) {
                throw new SynapseException("Wrong combination of source and target type");
            }
        } else if (sourceType == 2) {
            if (targetType == 1 || targetType == 2) {
                throw new SynapseException("Wrong combination of source and target type");
            }
        } else if (sourceType == 0 && targetType == 1) {
            throw new SynapseException("Wrong combination of source and target type");
        }

        // target action attribute
        // 0 - replace 1 - child 2 - sibling 3 - remove
        OMAttribute targetActionAttr = targetElement.getAttribute(ATT_ACTION);
        if (targetActionAttr != null && targetActionAttr.getAttributeValue() != null) {
            targetAction = convertActionToInt(targetActionAttr.getAttributeValue());
            // check if source type is different form the existing
            if (targetAction < 0) {
                throw new SynapseException("Unexpected target action");
            }
        }

        // validations for remove action
        if (targetAction == 3) {
            if (sourceType != 0) {
                throw new SynapseException("Wrong combination of source type and target action");
            }
            if (targetType == 0 || targetType == 1 || targetType == 4) {
                throw new SynapseException("Wrong combination of target type and target action");
            }
        }

        // validations for new "key" type
        if (targetType == 5) {
            if (sourceType == 2) {
                throw new SynapseException("Wrong combination of source type and target type");
            }
            if (targetAction != 0) {
                throw new SynapseException("Wrong combination of target type and target action");
            }
        }
    }
}
