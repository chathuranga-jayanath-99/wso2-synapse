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

package org.apache.synapse.config.xml;

import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.commons.lang3.StringUtils;
import org.apache.synapse.Mediator;
import org.apache.synapse.SynapseException;
import org.apache.synapse.mediators.eip.Target;
import org.apache.synapse.mediators.v2.ScatterGather;
import org.jaxen.JaxenException;

import java.util.Iterator;
import java.util.Properties;
import javax.xml.namespace.QName;

/**
 * The &lt;scatter-gather&gt; mediator is used to copy messages in Synapse to similar messages but with
 * different message contexts and aggregate the responses back.
 *
 * <pre>
 * &lt;scatter-gather parallel-execution=(true | false) result-target=(body | variable) content-type=(JSON | XML)&gt;
 *   &lt;aggregation value="expression" condition="expression" timeout="long"
 *     min-messages="expression" max-messages="expression"/&gt;
 *   &lt;sequence&gt;
 *     (mediator)+
 *   &lt;/sequence&gt;+
 * &lt;/scatter-gather&gt;
 * </pre>
 */
public class ScatterGatherMediatorFactory extends AbstractMediatorFactory {

    /**
     * This will hold the QName of the clone mediator element in the xml configuration
     */
    private static final QName SCATTER_GATHER_Q
            = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "scatter-gather");
    private static final QName ELEMENT_AGGREGATE_Q
            = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "aggregation");
    private static final QName ATT_AGGREGATE_EXPRESSION = new QName("expression");
    private static final QName ATT_CONDITION = new QName("condition");
    private static final QName ATT_TIMEOUT = new QName("timeout");
    private static final QName ATT_MIN_MESSAGES = new QName("min-messages");
    private static final QName ATT_MAX_MESSAGES = new QName("max-messages");
    private static final QName SEQUENCE_Q = new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "sequence");
    private static final QName PARALLEL_EXEC_Q = new QName("parallel-execution");
    private static final QName ROOT_ELEMENT_Q = new QName("root-element");
    private static final QName CONTENT_TYPE_Q = new QName("content-type");

    private static final SequenceMediatorFactory fac = new SequenceMediatorFactory();

    public Mediator createSpecificMediator(OMElement elem, Properties properties) {

        boolean asynchronousExe = true;

        ScatterGather mediator = new ScatterGather();
        processAuditStatus(mediator, elem);

        OMAttribute parallelExecAttr = elem.getAttribute(PARALLEL_EXEC_Q);
        if (parallelExecAttr != null && parallelExecAttr.getAttributeValue().equals("false")) {
            asynchronousExe = false;
        }
        mediator.setParallelExecution(asynchronousExe);

        OMAttribute contentTypeAttr = elem.getAttribute(CONTENT_TYPE_Q);
        if (contentTypeAttr == null || StringUtils.isBlank(contentTypeAttr.getAttributeValue())) {
            String msg = "The 'content-type' attribute is required for the configuration of a Scatter Gather mediator";
            throw new SynapseException(msg);
        } else {
            if ("JSON".equals(contentTypeAttr.getAttributeValue())) {
                mediator.setContentType(ScatterGather.JSON_TYPE);
            } else if ("XML".equals(contentTypeAttr.getAttributeValue())) {
                OMAttribute rootElementAttr = elem.getAttribute(ROOT_ELEMENT_Q);
                if (rootElementAttr != null && StringUtils.isNotBlank(rootElementAttr.getAttributeValue())) {
                    mediator.setRootElementName(rootElementAttr.getAttributeValue());
                    mediator.setContentType(ScatterGather.XML_TYPE);
                } else {
                    String msg = "The 'root-element' attribute is required for the configuration of a " +
                            "Scatter Gather mediator when the 'content-type' is 'XML'";
                    throw new SynapseException(msg);
                }
            } else {
                String msg = "The 'content-type' attribute should be either 'JSON' or 'XML'";
                throw new SynapseException(msg);
            }
        }

        OMAttribute resultTargetAttr = elem.getAttribute(RESULT_TARGET_Q);
        if (resultTargetAttr == null || StringUtils.isBlank(resultTargetAttr.getAttributeValue())) {
            String msg = "The 'result-target' attribute is required for the configuration of a Scatter Gather mediator";
            throw new SynapseException(msg);
        } else {
            mediator.setResultTarget(resultTargetAttr.getAttributeValue());
        }

        Iterator sequenceListElements = elem.getChildrenWithName(SEQUENCE_Q);
        if (!sequenceListElements.hasNext()) {
            String msg = "A 'sequence' element is required for the configuration of a Scatter Gather mediator";
            throw new SynapseException(msg);
        }
        while (sequenceListElements.hasNext()) {
            OMElement sequence = (OMElement) sequenceListElements.next();
            if (sequence != null) {
                Target target = new Target();
                target.setSequence(fac.createAnonymousSequence(sequence, properties));
                target.setAsynchronous(asynchronousExe);
                mediator.addTarget(target);
            }
        }

        OMElement aggregateElement = elem.getFirstChildWithName(ELEMENT_AGGREGATE_Q);
        if (aggregateElement != null) {
            OMAttribute aggregateExpr = aggregateElement.getAttribute(ATT_AGGREGATE_EXPRESSION);
            if (aggregateExpr != null) {
                try {
                    mediator.setAggregationExpression(
                            SynapsePathFactory.getSynapsePath(aggregateElement, ATT_AGGREGATE_EXPRESSION));
                } catch (JaxenException e) {
                    handleException("Unable to load the aggregating expression", e);
                }
            } else {
                String msg = "The 'expression' attribute is required for the configuration of a Scatter Gather mediator";
                throw new SynapseException(msg);
            }

            OMAttribute conditionExpr = aggregateElement.getAttribute(ATT_CONDITION);
            if (conditionExpr != null) {
                try {
                    mediator.setCorrelateExpression(
                            SynapsePathFactory.getSynapsePath(aggregateElement, ATT_CONDITION));
                } catch (JaxenException e) {
                    handleException("Unable to load the condition expression", e);
                }
            }

            OMAttribute completeTimeout = aggregateElement.getAttribute(ATT_TIMEOUT);
            if (completeTimeout != null) {
                mediator.setCompletionTimeoutMillis(Long.parseLong(completeTimeout.getAttributeValue()));
            }

            OMAttribute minMessages = aggregateElement.getAttribute(ATT_MIN_MESSAGES);
            if (minMessages != null) {
                mediator.setMinMessagesToComplete(new ValueFactory().createValue("min-messages", aggregateElement));
            }

            OMAttribute maxMessages = aggregateElement.getAttribute(ATT_MAX_MESSAGES);
            if (maxMessages != null) {
                mediator.setMaxMessagesToComplete(new ValueFactory().createValue("max-messages", aggregateElement));
            }
        } else {
            String msg = "The 'aggregation' element is required for the configuration of a Scatter Gather mediator";
            throw new SynapseException(msg);
        }
        addAllCommentChildrenToList(elem, mediator.getCommentsList());
        return mediator;
    }

    /**
     * This method will implement the getTagQName method of the MediatorFactory interface
     *
     * @return QName of the clone element in xml configuration
     */
    public QName getTagQName() {

        return SCATTER_GATHER_Q;
    }
}
