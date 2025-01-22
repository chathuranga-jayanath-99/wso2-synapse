/*
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

package org.apache.synapse.mediators.builtin;

import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.ConfigurationContextFactory;
import org.apache.commons.lang.StringUtils;
import org.apache.synapse.ContinuationState;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.aspects.AspectConfiguration;
import org.apache.synapse.aspects.ComponentType;
import org.apache.synapse.aspects.flow.statistics.StatisticIdentityGenerator;
import org.apache.synapse.aspects.flow.statistics.data.artifact.ArtifactHolder;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.continuation.ContinuationStackManager;
import org.apache.synapse.continuation.SeqContinuationState;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.endpoints.AbstractEndpoint;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.endpoints.Endpoint;
import org.apache.synapse.endpoints.EndpointDefinition;
import org.apache.synapse.endpoints.IndirectEndpoint;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.mediators.elementary.EnrichMediator;
import org.apache.synapse.mediators.elementary.Source;
import org.apache.synapse.mediators.elementary.Target;
import org.apache.synapse.message.senders.blocking.BlockingMsgSender;
import org.apache.synapse.util.MediatorEnrichUtil;
import org.apache.synapse.util.MessageHelper;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

/**
 * Call Mediator sends a message using specified semantics. If it contains an endpoint it will
 * send the message to that endpoint. Once a message is sent to the endpoint further sending
 * behaviors are completely governed by that endpoint. If there is no endpoint available,
 * CallMediator will send the message to the implicitly stated destination.
 * <p/>
 * Even though Call mediator leverages the non-blocking transports which is same as Send mediator,
 * response will be mediated from the next mediator placed after the Call mediator. Behaviour is
 * very much same as the Callout Mediator.
 * So Call mediator can be considered as a non-blocking Callout mediator.
 * <p/>
 * To implement this behaviour, important states in the mediation flow is stored in the
 * message context.
 * An important state in the mediation flow is represented by the
 * {@link org.apache.synapse.ContinuationState} which are stored in the ContinuationStateStack
 * which resides in the MessageContext.
 * <p/>
 * These ContinuationStates are used to mediate the response message and continue the message flow.
 */
public class CallMediator extends AbstractMediator implements ManagedLifecycle {

    public final static String DEFAULT_CLIENT_REPO = "./repository/deployment/client";
    public final static String DEFAULT_AXIS2_XML;

    static {
        String confPath = System.getProperty("conf.location");
        if (confPath == null) {
            confPath = System.getProperty("carbon.config.dir.path");
            if (confPath == null) {
                confPath = Paths.get("repository", "conf").toString();
            }
        }
        DEFAULT_AXIS2_XML = Paths.get(confPath, "axis2", "axis2_blocking_client.xml").toString();
    }

    private BlockingMsgSender blockingMsgSender = null;

    private ConfigurationContext configCtx = null;

    private Endpoint endpoint = null;

    private boolean blocking = false;

    private SynapseEnvironment synapseEnv;

    private boolean initClientOptions = true;

    private String clientRepository = null;

    private String axis2xml = null;

    private boolean isSourceAvailable = false;
    private boolean isTargetAvailable = false;
    private String sourceMessageType = null;

    private Source sourceForOutboundPayload;
    private Target targetForInboundPayload;

    public static final String INTERMEDIATE_ORIGINAL_BODY = "_INTERMEDIATE_ORIGINAL_BODY";
    public static final String TARGET_FOR_INBOUND_PAYLOAD = "_TARGET_FOR_INBOUND_PAYLOAD";
    public static final String ORIGINAL_MESSAGE_TYPE = "_ORIGINAL_MESSAGE_TYPE";
    public static final String SOURCE_MESSAGE_TYPE = "_SOURCE_MESSAGE_TYPE";
    public static final String IS_SOURCE_AVAILABLE = "_IS_SOURCE_AVAILABLE";
    public static final String IS_TARGET_AVAILABLE = "IS_TARGET_AVAILABLE";
    public static final String ORIGINAL_TRANSPORT_HEADERS = "_ORIGINAL_TRANSPORT_HEADERS";
    public static final String ORIGINAL_CONTENT_TYPE = "_ORIGINAL_CONTENT_TYPE";

    public static final String JSON_TYPE = "application/json";


    //State whether actual endpoint(when null) is wrapped by a default endpoint
    private boolean isWrappingEndpointCreated;

    /**
     * This will call the send method on the messages with implicit message parameters
     * or else if there is an endpoint, with that endpoint parameters
     *
     * @param synInCtx the current message to be sent
     * @return false for in-out invocations as flow should put on to hold after the Call mediator,
     * true for out only invocations
     */
    public boolean mediate(MessageContext synInCtx) {

        if (synInCtx.getEnvironment().isDebuggerEnabled()) {
            MessageHelper.setWireLogHolderProperties(synInCtx, isBreakPoint(), getRegisteredMediationFlowPoint()); //this needs to be set only in mediators where outgoing messages are present
            if (super.divertMediationRoute(synInCtx)) {
                return true;
            }
        }
        org.apache.axis2.context.MessageContext axis2MessageContext =
                ((Axis2MessageContext) synInCtx).getAxis2MessageContext();
        Object messageType = axis2MessageContext.getProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE);
        Object contentType = axis2MessageContext.getProperty(Constants.Configuration.CONTENT_TYPE);
        Object headers = axis2MessageContext.getProperty(org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
        Map originalTransportHeaders = (Map) headers;
        String originalMessageType = (String) messageType;
        String originalContentType = (String) contentType;

        if (sourceMessageType == null) {
            sourceMessageType = originalMessageType;
        }
        if (isSourceAvailable) {
            MediatorEnrichUtil.buildMessage(synInCtx);
        }
        if (isSourceAvailable && isTargetAvailable) {
            Source sourceForInboundPayload = MediatorEnrichUtil.createSourceWithBody();
            Target targetForOriginalPayload =
                    MediatorEnrichUtil.createTargetWithProperty(INTERMEDIATE_ORIGINAL_BODY);
            Target targetForOutboundPayload = MediatorEnrichUtil.createTargetWithBody();
            sourceForInboundPayload.setClone(true);
            MediatorEnrichUtil
                    .doEnrich(synInCtx, sourceForInboundPayload, targetForOriginalPayload, originalMessageType);
            if (!(EnrichMediator.BODY == sourceForOutboundPayload.getSourceType() &&
                EnrichMediator.BODY == targetForOutboundPayload.getTargetType())) {
                MediatorEnrichUtil
                        .doEnrich(synInCtx, sourceForOutboundPayload, targetForOutboundPayload, getSourceMessageType());
            }
            if (!sourceMessageType.equalsIgnoreCase(originalMessageType)) {
                MediatorEnrichUtil.setContentType(synInCtx, sourceMessageType, sourceMessageType);
                if (originalMessageType.equalsIgnoreCase(JSON_TYPE)) {
                    JsonUtil.removeJsonStream(axis2MessageContext);
                }
            }
        } else if (isSourceAvailable) {
            Target targetForOutboundPayload = MediatorEnrichUtil.createTargetWithBody();
            MediatorEnrichUtil
                    .doEnrich(synInCtx, sourceForOutboundPayload, targetForOutboundPayload, getSourceMessageType());
            if (!sourceMessageType.equalsIgnoreCase(originalMessageType)) {
                MediatorEnrichUtil.setContentType(synInCtx, sourceMessageType, sourceMessageType);
                if (originalMessageType.equalsIgnoreCase(JSON_TYPE)) {
                    JsonUtil.removeJsonStream(axis2MessageContext);
                }
            }
        }
        synInCtx.setProperty(TARGET_FOR_INBOUND_PAYLOAD, targetForInboundPayload);
        synInCtx.setProperty(SOURCE_MESSAGE_TYPE, sourceMessageType);
        synInCtx.setProperty(ORIGINAL_MESSAGE_TYPE, originalMessageType);
        synInCtx.setProperty(IS_SOURCE_AVAILABLE, isSourceAvailable);
        synInCtx.setProperty(IS_TARGET_AVAILABLE, isTargetAvailable);
        synInCtx.setProperty(ORIGINAL_CONTENT_TYPE, originalContentType);
        synInCtx.setProperty(ORIGINAL_TRANSPORT_HEADERS, originalTransportHeaders);

        if (blocking) {
            return handleBlockingCall(synInCtx, originalMessageType, originalContentType, originalTransportHeaders);
        } else {
            return handleNonBlockingCall(synInCtx);
        }
    }

    /**
     * Send request in blocking manner
     *
     * @param synInCtx message context
     * @return  continue the mediation flow or not
     */
    private boolean handleBlockingCall(MessageContext synInCtx, String originalMessageType, String originalContentType,
                                       Map originalTransportHeaders) {

        SynapseLog synLog = getLog(synInCtx);

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Start : Call mediator - Blocking Call");
            if (synLog.isTraceTraceEnabled()) {
                synLog.traceTrace("Message : " + synInCtx.getEnvelope());
            }
        }

        //set initClientOption with blockingMsgsender
        blockingMsgSender.setInitClientOptions(initClientOptions);
        // Set the blockingMsgSender with synapse message Context
        synInCtx.setProperty(SynapseConstants.BLOCKING_MSG_SENDER, blockingMsgSender);
        // Clear the message context properties related to endpoint in last service invocation
        Set keySet = synInCtx.getPropertyKeySet();
        if (keySet != null) {
            keySet.remove(SynapseConstants.RECEIVING_SEQUENCE);
            keySet.remove(EndpointDefinition.DYNAMIC_URL_VALUE);
            keySet.remove(SynapseConstants.LAST_ENDPOINT);
            keySet.remove(SynapseConstants.BLOCKING_SENDER_ERROR);
        }

        Object faultHandlerBeforeInvocation = getLastSequenceFaultHandler(synInCtx);
        synInCtx.setProperty(SynapseConstants.LAST_SEQ_FAULT_HANDLER, faultHandlerBeforeInvocation);

        // fixing ESBJAVA-4976, if no endpoint is defined in call mediator, this is required to avoid NPEs in
        // blocking sender.
        if (endpoint == null) {
            EndpointDefinition endpointDefinition = new EndpointDefinition();
            synInCtx.getEnvironment().send(endpointDefinition, synInCtx);
        } else {
            endpoint.send(synInCtx);
        }

        // check whether fault sequence is already invoked
        if (faultHandlerBeforeInvocation != getLastSequenceFaultHandler(synInCtx)) {
            return false;
        }

        if (!("true".equals(synInCtx.getProperty(SynapseConstants.BLOCKING_SENDER_ERROR)))) {
            if (synInCtx.getProperty(SynapseConstants.OUT_ONLY) == null || "false"
                    .equals(synInCtx.getProperty(SynapseConstants.OUT_ONLY))) {
                if (synInCtx.getEnvelope() != null) {
                    if (synLog.isTraceTraceEnabled()) {
                        synLog.traceTrace("Response payload received : " + synInCtx.getEnvelope());
                    }
                    if (synLog.isTraceOrDebugEnabled()) {
                        synLog.traceOrDebug("End : Call mediator - Blocking Call");
                    }
                } else {
                    if (synLog.isTraceOrDebugEnabled()) {
                        synLog.traceOrDebug("Service returned a null response");
                    }
                }
            }
        } else {
            log.error("Error while performing the call operation in blocking mode");
            return false;
        }
        postMediate(synInCtx, originalMessageType, originalContentType, originalTransportHeaders);
        return true;
    }

    public void postMediate(MessageContext response, String originalMessageType, String originalContentType,
                            Map originalTransportHeaders) {
        if (isTargetAvailable()) {
            MediatorEnrichUtil.buildMessage(response);
        }
        if (isTargetAvailable && isSourceAvailable) {
            Source sourceForResponsePayload = MediatorEnrichUtil.createSourceWithBody();
            Source sourceForOriginalPayload =
                    MediatorEnrichUtil.createSourceWithProperty(INTERMEDIATE_ORIGINAL_BODY);
            Target targetForResponsePayload = MediatorEnrichUtil.createTargetWithBody();
            MediatorEnrichUtil.doEnrich(response, sourceForResponsePayload, targetForInboundPayload,
                    getSourceMessageType());
            MediatorEnrichUtil.doEnrich(response, sourceForOriginalPayload, targetForResponsePayload,
                    originalMessageType);
            MediatorEnrichUtil.preservetransportHeaders(response, originalTransportHeaders);
            if (!sourceMessageType.equalsIgnoreCase(originalMessageType)) {
                MediatorEnrichUtil.setContentType(response, originalMessageType, originalContentType);
                if (sourceMessageType.equalsIgnoreCase(JSON_TYPE)) {
                    JsonUtil.removeJsonStream(((Axis2MessageContext) response).getAxis2MessageContext());
                }
            }
        } else if (isTargetAvailable) {
            Source sourceForResponsePayload = MediatorEnrichUtil.createSourceWithBody();
            MediatorEnrichUtil.doEnrich(response, sourceForResponsePayload, targetForInboundPayload,
                    getSourceMessageType());
        }
    }

    /**
     * Send request in non-blocking manner
     *
     * @param synInCtx message context
     * @return  continue the mediation flow or not
     */
    private boolean handleNonBlockingCall(MessageContext synInCtx) {
        SynapseLog synLog = getLog(synInCtx);

        if (synInCtx.getContinuationStateStack().isEmpty()) {
            //ideally this place should not be reached
            throw new SynapseException("Continuation Stack Empty! Cannot proceed with the call");
        } else {
            if (synLog.isTraceOrDebugEnabled()) {
                synLog.traceOrDebug("Start : Contents of Continuation Stack");
                for (ContinuationState state : synInCtx.getContinuationStateStack()) {
                    SeqContinuationState seqstate = (SeqContinuationState) state;
                    synLog.traceOrDebug(
                            "Sequence Type : " + seqstate.getSeqType() + " Sequence Name : " + seqstate.getSeqName());
                }
                synLog.traceOrDebug("End : Contents of Continuation Stack");
            }
        }

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Start : Call mediator - Non Blocking Call");
            if (synLog.isTraceTraceEnabled()) {
                synLog.traceTrace("Message : " + synInCtx.getEnvelope());
            }
        }

        // Set the last sequence fault handler for future use
        Object faultHandlerBeforeInvocation = getLastSequenceFaultHandler(synInCtx);
        synInCtx.setProperty(SynapseConstants.LAST_SEQ_FAULT_HANDLER, faultHandlerBeforeInvocation);

        // clear the message context properties related to endpoint in last service invocation
        Set keySet = synInCtx.getPropertyKeySet();
        if (keySet != null) {
            keySet.remove(SynapseConstants.RECEIVING_SEQUENCE);
            keySet.remove(EndpointDefinition.DYNAMIC_URL_VALUE);
            keySet.remove(SynapseConstants.LAST_ENDPOINT);
            keySet.remove(SynapseConstants.BLOCKING_MSG_SENDER);
            keySet.remove(SynapseConstants.BLOCKING_SENDER_ERROR);
        }

        boolean outOnlyMessage = "true".equals(synInCtx.getProperty(SynapseConstants.OUT_ONLY));

        // Prepare the outgoing message context
        MessageContext synOutCtx = null;
        if (outOnlyMessage) {
            try {
                synOutCtx = MessageHelper.cloneMessageContext(synInCtx, true, true, false, false);
            } catch (AxisFault axisFault) {
                handleException("Error occurred while cloning msg context", axisFault, synInCtx);
            }
        } else {
            synOutCtx = synInCtx;
        }

        synOutCtx.setProperty(SynapseConstants.CONTINUATION_CALL, true);
        ContinuationStackManager.updateSeqContinuationState(synOutCtx, getMediatorPosition());

        try {
            // If no endpoints are defined, send where implicitly stated
            if (endpoint == null) {

                if (synLog.isTraceOrDebugEnabled()) {
                    StringBuffer sb = new StringBuffer();
                    sb.append("Calling ").append(synOutCtx.isResponse() ? "response" : "request")
                            .append(" message using implicit message properties..");
                    sb.append("\nCalling To: ").append(synOutCtx.getTo() != null ?
                            synOutCtx.getTo().getAddress() : "null");
                    sb.append("\nSOAPAction: ").append(synOutCtx.getWSAAction() != null ?
                            synOutCtx.getWSAAction() : "null");
                    synLog.traceOrDebug(sb.toString());
                }

                if (synLog.isTraceTraceEnabled()) {
                    synLog.traceTrace("Envelope : " + synOutCtx.getEnvelope());
                }
                synOutCtx.getEnvironment().send(null, synOutCtx);

            } else {
                endpoint.send(synOutCtx);
            }

            // check whether fault sequence is already invoked
            Object lastSequenceFaultHandler = getLastSequenceFaultHandler(synOutCtx);
            if (lastSequenceFaultHandler != null && faultHandlerBeforeInvocation != lastSequenceFaultHandler) {
                return false;
            }
        } catch (Exception e) {
            handleFault(synInCtx, e);
        }

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("End : Call mediator - Non Blocking Call");
        }

        if (outOnlyMessage) {
            // For out only invocations request flow should continue
            // otherwise flow should stop from here
            return true;
        }
        return false;
    }

    public Endpoint getEndpoint() {
        if (isWrappingEndpointCreated) {
            return null;
        }
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public void init(SynapseEnvironment synapseEnvironment) {
        this.synapseEnv = synapseEnvironment;

        if (endpoint != null) {
            endpoint.init(synapseEnvironment);
        }

        if (blocking) {
            try {
                configCtx = ConfigurationContextFactory.createConfigurationContextFromFileSystem(
                        clientRepository != null ? clientRepository : DEFAULT_CLIENT_REPO,
                        axis2xml != null ? axis2xml : DEFAULT_AXIS2_XML);
                blockingMsgSender = new BlockingMsgSender();
                blockingMsgSender.setConfigurationContext(configCtx);
                blockingMsgSender.init();

            } catch (AxisFault axisFault) {
                String msg = "Error while initializing the Call mediator";
                log.error(msg, axisFault);
                throw new SynapseException(msg, axisFault);
            }
        } else {
            synapseEnvironment.updateCallMediatorCount(true);
        }
    }

    public void destroy() {
        if (endpoint != null) {
            endpoint.destroy();
        }
        if (!blocking) {
            synapseEnv.updateCallMediatorCount(false);
        }
    }

    /**
     * Setting return blocking makes CallMediator access the message’s content in blocking mode when mediating messages
     * Fixes product-ei #1805, #3015
     */
    @Override
    public boolean isContentAware() {
        return blocking;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public void setBlocking(boolean blocking) {
        this.blocking = blocking;
    }

    public boolean getInitClientOptions() {
        return initClientOptions;
    }

    public void setInitClientOptions(boolean initClientOptions) {
        this.initClientOptions = initClientOptions;
    }

    public String getClientRepository() {
        return clientRepository;
    }

    public void setClientRepository(String clientRepository) {
        this.clientRepository = clientRepository;
    }

    public String getAxis2xml() {
        return axis2xml;
    }

    public void setAxis2xml(String axis2xml) {
        this.axis2xml = axis2xml;
    }

    /**
     * Handle the exception and set the appropriate properties to the message context.
     *
     * @param synCtx    Message Context
     * @param ex        Exception
     */
    private void handleFault(MessageContext synCtx, Exception ex) {
        synCtx.setProperty(SynapseConstants.SENDING_FAULT, Boolean.TRUE);
        AxisFault axisFault = null;

        if (ex instanceof SynapseException) {
            if (ex.getCause() != null && ex.getCause() instanceof AxisFault) {
                axisFault = (AxisFault) ex.getCause();
            }
        } else if (ex instanceof AxisFault) {
            axisFault = (AxisFault) ex;
        }

        int errorCode = SynapseConstants.NON_BLOCKING_CALL_OPERATION_FAILED;
        if (axisFault != null) {
            if (axisFault.getFaultCodeElement() != null && !"".equals(axisFault.getFaultCodeElement().getText())) {
                try {
                    errorCode = Integer.parseInt(axisFault.getFaultCodeElement().getText());
                } catch (NumberFormatException e) {
                    //Do Nothing
                }
            }
            synCtx.setProperty(SynapseConstants.ERROR_CODE, errorCode);

            if (axisFault.getCause() != null && axisFault.getCause().getCause() != null &&
                    axisFault.getCause().getCause() instanceof IOException) {
                //Set the error message for RabbitMQ nack when publishing
                synCtx.setProperty(SynapseConstants.ERROR_MESSAGE, axisFault.getCause().getCause().getMessage());
            } else if (axisFault.getMessage() != null) {
                synCtx.setProperty(SynapseConstants.ERROR_MESSAGE, axisFault.getMessage());
            } else {
                synCtx.setProperty(SynapseConstants.ERROR_MESSAGE, "Error while performing the call operation");
            }

            if (axisFault.getFaultDetailElement() != null) {
                if (axisFault.getFaultDetailElement().getFirstElement() != null) {
                    synCtx.setProperty(SynapseConstants.ERROR_DETAIL, axisFault.getFaultDetailElement()
                            .getFirstElement());
                } else {
                    synCtx.setProperty(SynapseConstants.ERROR_DETAIL, axisFault.getFaultDetailElement().getText());
                }
            }
        }
        synCtx.setProperty(SynapseConstants.ERROR_EXCEPTION, ex);
        throw new SynapseException("Error while performing the call operation", ex);
    }

    @Override
    public void setComponentStatisticsId(ArtifactHolder holder) {
        if (getAspectConfiguration() == null) {
            configure(new AspectConfiguration(getMediatorName()));
        }
        String cloneId = StatisticIdentityGenerator.getIdForComponent(getMediatorName(), ComponentType.MEDIATOR, holder);
        getAspectConfiguration().setUniqueId(cloneId);

        if (endpoint != null && !blocking) {
            if (endpoint instanceof IndirectEndpoint && !StringUtils.isEmpty(((IndirectEndpoint) endpoint).getKey()) &&
                    isDynamicEndpoint(((IndirectEndpoint) endpoint).getKey())) {
                Endpoint realEndpoint = ((IndirectEndpoint) endpoint).getRealEndpoint();
                realEndpoint.setComponentStatisticsId(holder);
                ((AbstractEndpoint) realEndpoint).getDefinition().getAspectConfiguration()
                        .setHashCode(holder.getHashCodeAsString());
            } else {
                endpoint.setComponentStatisticsId(holder);
            }
        }
        StatisticIdentityGenerator.reportingEndEvent(cloneId, ComponentType.MEDIATOR, holder);
    }

    /**
     * Method to check dynamic endpoints defined in config and governance registry.
     * @param key Endpoint Key.
     * @return whether the endpoint saved in the registry
     */
    private boolean isDynamicEndpoint(String key) {
        return key.startsWith("gov:") || key.startsWith("conf:");
    }

    public boolean isSourceAvailable() {
        return isSourceAvailable;
    }

    public void setSourceAvailable(boolean sourceAvailable) {
        isSourceAvailable = sourceAvailable;
    }

    public boolean isTargetAvailable() {
        return isTargetAvailable;
    }

    public void setTargetAvailable(boolean targetAvailable) {
        isTargetAvailable = targetAvailable;
    }

    public Source getSourceForOutboundPayload() {
        return sourceForOutboundPayload;
    }

    public void setSourceForOutboundPayload(Source sourceForOutboundPayload) {
        this.sourceForOutboundPayload = sourceForOutboundPayload;
    }

    public Target getTargetForInboundPayload() {
        return targetForInboundPayload;
    }

    public void setTargetForInboundPayload(Target targetForInboundPayload) {
        this.targetForInboundPayload = targetForInboundPayload;
    }

    public String getSourceMessageType() {
        return sourceMessageType;
    }

    public void setSourceMessageType(String sourceMessageType) {
        this.sourceMessageType = sourceMessageType;
    }

}
