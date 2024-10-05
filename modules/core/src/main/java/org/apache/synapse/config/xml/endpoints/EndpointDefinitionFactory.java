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

package org.apache.synapse.config.xml.endpoints;

import org.apache.axiom.om.OMAttribute;
import org.apache.axiom.om.OMElement;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseException;
import org.apache.synapse.aspects.AspectConfiguration;
import org.apache.synapse.config.xml.XMLConfigConstants;
import org.apache.synapse.endpoints.EndpointDefinition;
import org.apache.synapse.util.xpath.SynapseXPath;
import org.jaxen.JaxenException;

import javax.xml.namespace.QName;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

public class EndpointDefinitionFactory implements DefinitionFactory{
    public static final Log log = LogFactory.getLog(EndpointDefinitionFactory.class);

    /**
     * Extracts the QoS information from the XML which represents a WSDL/Address/Default endpoints
     *
     * @param elem XML which represents the endpoint with QoS information
     * @return the created endpoint definition
     */
    public EndpointDefinition createDefinition(OMElement elem) {
        EndpointDefinition definition = new EndpointDefinition();

        OMAttribute optimize
                = elem.getAttribute(new QName(XMLConfigConstants.NULL_NAMESPACE, "optimize"));
        OMAttribute encoding
                = elem.getAttribute(new QName(XMLConfigConstants.NULL_NAMESPACE, "encoding"));

/*
        OMAttribute trace = elem.getAttribute(new QName(
                XMLConfigConstants.NULL_NAMESPACE, XMLConfigConstants.TRACE_ATTRIB_NAME));
        if (trace != null && trace.getAttributeValue() != null) {
            String traceValue = trace.getAttributeValue();
            if (XMLConfigConstants.TRACE_ENABLE.equals(traceValue)) {
                AspectConfiguration aspectConfiguration = definition.getAspectConfiguration();
                if (aspectConfiguration == null) {
                    aspectConfiguration = new AspectConfiguration(null);
                }
                aspectConfiguration.enableTracing();
                definition.configure(aspectConfiguration);
            }
        }
*/

        if (optimize != null && optimize.getAttributeValue().length() > 0) {
            String method = optimize.getAttributeValue().trim();
            if ("mtom".equalsIgnoreCase(method)) {
                definition.setUseMTOM(true);
            } else if ("swa".equalsIgnoreCase(method)) {
                definition.setUseSwa(true);
            }
        }

        if (encoding != null && encoding.getAttributeValue() != null) {
            definition.setCharSetEncoding(encoding.getAttributeValue());
        }

        OMElement wsAddr = elem.getFirstChildWithName(
                new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "enableAddressing"));
        if (wsAddr != null) {

            definition.setAddressingOn(true);

            OMAttribute version = wsAddr.getAttribute(new QName("version"));
            if (version != null && version.getAttributeValue() != null) {
                String versionValue = version.getAttributeValue().trim().toLowerCase();
                if (SynapseConstants.ADDRESSING_VERSION_FINAL.equals(versionValue) ||
                        SynapseConstants.ADDRESSING_VERSION_SUBMISSION.equals(versionValue)) {
                    definition.setAddressingVersion(version.getAttributeValue());
                } else {
                    handleException("Unknown value for the addressing version. Possible values " +
                            "for the addressing version are 'final' and 'submission' only.");
                }
            }

            String useSepList = wsAddr.getAttributeValue(new QName("separateListener"));
            if (useSepList != null) {
                if ("true".equals(useSepList.trim().toLowerCase())) {
                    definition.setUseSeparateListener(true);
                }
            }
        }

        OMElement wsSec = elem.getFirstChildWithName(
                new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "enableSec"));
        if (wsSec != null) {

            definition.setSecurityOn(true);

            OMAttribute policyKey      = wsSec.getAttribute(
                    new QName(XMLConfigConstants.NULL_NAMESPACE, "policy"));
            OMAttribute inboundPolicyKey  = wsSec.getAttribute(
                    new QName(XMLConfigConstants.NULL_NAMESPACE, "inboundPolicy"));
            OMAttribute outboundPolicyKey = wsSec.getAttribute(
                    new QName(XMLConfigConstants.NULL_NAMESPACE, "outboundPolicy"));

            //Checks whether the specified policy is dynamic or static and modify the endpoint definition accordingly
            if (policyKey != null && policyKey.getAttributeValue() != null) {
                String p = policyKey.getAttributeValue();
                Pattern pattern = Pattern.compile("\\{.*\\}");
                if (pattern.matcher(p).matches()) {
                    try {
                        p = p.trim().substring(1, p.length() - 1);
                        SynapseXPath xpath = null;
                        xpath = new SynapseXPath(p);
                        definition.setDynamicPolicy(xpath);
                        definition.setWsSecPolicyKey(p);
                    } catch (JaxenException e) {
                        handleException("Couldn't assign dynamic endpoint policy as Synapse expression");
                    }
                } else {
                    String wsSecPolicy = p.trim();
                    definition.setWsSecPolicyKey(wsSecPolicy);
                }
            } else {
                if (inboundPolicyKey != null && inboundPolicyKey.getAttributeValue() != null) {
                    definition.setInboundWsSecPolicyKey(inboundPolicyKey.getAttributeValue());
                }
                if (outboundPolicyKey != null && outboundPolicyKey.getAttributeValue() != null) {
                    definition.setOutboundWsSecPolicyKey(outboundPolicyKey.getAttributeValue());
                }
            }
        }

        // set the timeout configuration
        OMElement timeout = elem.getFirstChildWithName(
                new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "timeout"));
        if (timeout != null) {
            OMElement duration = timeout.getFirstChildWithName(
                    new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "duration"));

            if (duration != null) {
                String d = duration.getText();
                if (d != null) {
                    try {
                        Pattern pattern = Pattern.compile("\\{.*\\}");
                        if (pattern.matcher(d).matches()) {
                            d = d.trim().substring(1, d.length() - 1);
                            SynapseXPath xpath = new SynapseXPath(d);
                            definition.setDynamicTimeoutExpression(xpath);
                        } else {
                            long timeoutMilliSeconds = Long.parseLong(d.trim());
                            definition.setTimeoutDuration(timeoutMilliSeconds);
                        }
                    } catch (NumberFormatException e) {
                        handleException("Endpoint timeout duration expected as a " +
                                "number but was not a number");
                    } catch (JaxenException e) {
                        handleException("Couldn't assign dynamic endpoint timeout as Synapse expression");
                    }
                }
            }

            OMElement action = timeout.getFirstChildWithName(
                    new QName(XMLConfigConstants.SYNAPSE_NAMESPACE, "responseAction"));
            if (action != null && action.getText() != null) {
                String actionString = action.getText().trim();
                if (isExpression(actionString)) {
                    try {
                        String expressionStr = actionString.substring(1, actionString.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicTimeoutAction(expressionXPath);
                    } catch (JaxenException e) {
                        handleException("Couldn't assign dynamic timeout action as Synapse expression");
                    }
                } else {
                    if ("discard".equalsIgnoreCase(actionString)) {
                        definition.setTimeoutAction(SynapseConstants.DISCARD);
                    } else if ("fault".equalsIgnoreCase(actionString)) {
                        definition.setTimeoutAction(SynapseConstants.DISCARD_AND_FAULT);
                    } else {
                        handleException("Invalid timeout action, action : "
                                + actionString + " is not supported");
                    }
                }
            }
        }

        OMElement markAsTimedOut = elem.getFirstChildWithName(new QName(
            SynapseConstants.SYNAPSE_NAMESPACE,
            XMLConfigConstants.MARK_FOR_SUSPENSION));

        if (markAsTimedOut != null) {

            OMElement timeoutCodes = markAsTimedOut.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.ERROR_CODES));
            if (timeoutCodes != null && timeoutCodes.getText() != null) {
                StringTokenizer st = new StringTokenizer(timeoutCodes.getText().trim(), ", ");
                while (st.hasMoreTokens()) {
                    String s = st.nextToken();
                    try {
                        definition.addTimeoutErrorCode(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        handleException("The timeout error codes should be specified " +
                            "as valid numbers separated by commas : " + timeoutCodes.getText(), e);
                    }
                }
            }

            OMElement retriesBeforeSuspend = markAsTimedOut.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.RETRIES_BEFORE_SUSPENSION));
            if (retriesBeforeSuspend != null && retriesBeforeSuspend.getText() != null) {
                try {
                    String trimmedRetriesBeforeSuspend = retriesBeforeSuspend.getText().trim();
                    if (isExpression(trimmedRetriesBeforeSuspend)) {
                        String expressionStr = trimmedRetriesBeforeSuspend.substring(1, trimmedRetriesBeforeSuspend.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicRetriesOnTimeoutBeforeSuspend(expressionXPath);
                    } else {
                        definition.setRetriesOnTimeoutBeforeSuspend(
                                Integer.parseInt(trimmedRetriesBeforeSuspend));
                    }
                } catch (NumberFormatException e) {
                    handleException("The retries before suspend [for timeouts] should be " +
                        "specified as a valid number : " + retriesBeforeSuspend.getText(), e);
                } catch (JaxenException e) {
                    handleException("Couldn't assign dynamic retries before suspend [for timeouts] as Synapse expression");
                }
            }

            OMElement retryDelay = markAsTimedOut.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.RETRY_DELAY));
            if (retryDelay != null && retryDelay.getText() != null) {
                try {
                    String trimmedRetryDelay = retryDelay.getText().trim();
                    if (isExpression(trimmedRetryDelay)) {
                        String expressionStr = trimmedRetryDelay.substring(1, trimmedRetryDelay.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicRetryDurationOnTimeout(expressionXPath);
                    } else {
                        definition.setRetryDurationOnTimeout(
                                Integer.parseInt(trimmedRetryDelay));
                    }
                } catch (NumberFormatException e) {
                    handleException("The retry delay for timeouts should be specified " +
                        "as a valid number : " + retryDelay.getText(), e);
                } catch (JaxenException e) {
                    handleException("Couldn't assign dynamic retry delay for timeouts as Synapse expression");
                }
            }
        }

        // support backwards compatibility with Synapse 1.2 - for suspendDurationOnFailure
        OMElement suspendDurationOnFailure = elem.getFirstChildWithName(new QName(
            SynapseConstants.SYNAPSE_NAMESPACE, "suspendDurationOnFailure"));
        if (suspendDurationOnFailure != null && suspendDurationOnFailure.getText() != null) {

            log.warn("Configuration uses deprecated style for endpoint 'suspendDurationOnFailure'");
            try {
                definition.setInitialSuspendDuration(
                        1000 * Long.parseLong(suspendDurationOnFailure.getText().trim()));
                definition.setSuspendProgressionFactor((float) 1.0);
            } catch (NumberFormatException e) {
                handleException("The initial suspend duration should be specified " +
                    "as a valid number : " + suspendDurationOnFailure.getText(), e);
            }
        }

        OMElement suspendOnFailure = elem.getFirstChildWithName(new QName(
            SynapseConstants.SYNAPSE_NAMESPACE,
            XMLConfigConstants.SUSPEND_ON_FAILURE));

        if (suspendOnFailure != null) {

            OMElement suspendCodes = suspendOnFailure.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.ERROR_CODES));
            if (suspendCodes != null && suspendCodes.getText() != null) {

                StringTokenizer st = new StringTokenizer(suspendCodes.getText().trim(), ", ");
                while (st.hasMoreTokens()) {
                    String s = st.nextToken();
                    try {
                        definition.addSuspendErrorCode(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        handleException("The suspend error codes should be specified " +
                            "as valid numbers separated by commas : " + suspendCodes.getText(), e);
                    }
                }
            }

            OMElement initialDuration = suspendOnFailure.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.SUSPEND_INITIAL_DURATION));
            if (initialDuration != null && initialDuration.getText() != null) {
                try {
                    String initialDurationTrimmed = initialDuration.getText().trim();
                    if (isExpression(initialDurationTrimmed)) {
                        String expressionStr = initialDurationTrimmed.substring(1, initialDurationTrimmed.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicInitialSuspendDuration(expressionXPath);
                    } else {
                        definition.setInitialSuspendDuration(
                                Integer.parseInt(initialDurationTrimmed));
                    }
                } catch (NumberFormatException e) {
                    handleException("The initial suspend duration should be specified " +
                        "as a valid number : " + initialDuration.getText(), e);
                } catch (JaxenException e) {
                    handleException("Couldn't assign dynamic initial suspend duration as Synapse expression");
                }
            }

            OMElement progressionFactor = suspendOnFailure.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.SUSPEND_PROGRESSION_FACTOR));
            if (progressionFactor != null && progressionFactor.getText() != null) {
                try {
                    String trimmedProgressionFactor = progressionFactor.getText().trim();
                    if (isExpression(trimmedProgressionFactor)) {
                        String expressionStr = trimmedProgressionFactor.substring(1, trimmedProgressionFactor.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicSuspendProgressionFactor(expressionXPath);
                    } else {
                        definition.setSuspendProgressionFactor(
                                Float.parseFloat(trimmedProgressionFactor));
                    }
                } catch (NumberFormatException e) {
                    handleException("The suspend duration progression factor should be specified " +
                        "as a valid float : " + progressionFactor.getText(), e);
                } catch (JaxenException e) {
                    handleException("Couldn't assign dynamic suspend duration progression factor as Synapse expression");
                }
            }

            OMElement maximumDuration = suspendOnFailure.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE,
                XMLConfigConstants.SUSPEND_MAXIMUM_DURATION));
            if (maximumDuration != null && maximumDuration.getText() != null) {
                try {
                    String trimmedMaximumDuration = maximumDuration.getText().trim();
                    if (isExpression(trimmedMaximumDuration)) {
                        String expressionStr = trimmedMaximumDuration.substring(1, trimmedMaximumDuration.length() - 1);
                        SynapseXPath expressionXPath = new SynapseXPath(expressionStr);
                        definition.setDynamicSuspendMaximumDuration(expressionXPath);
                    } else {
                        definition.setSuspendMaximumDuration(
                                Long.parseLong(maximumDuration.getText().trim()));
                    }
                } catch (NumberFormatException e) {
                    handleException("The maximum suspend duration should be specified " +
                        "as a valid number : " + maximumDuration.getText(), e);
                } catch (JaxenException e) {
                    handleException("Couldn't assign dynamic maximum suspend duration as Synapse expression");
                }
            }
        }

        OMElement retryConfig = elem.getFirstChildWithName(new QName(
            SynapseConstants.SYNAPSE_NAMESPACE, XMLConfigConstants.RETRY_CONFIG));

        if (retryConfig != null) {

            OMElement retryDisabledErrorCodes = retryConfig.getFirstChildWithName(new QName(
                SynapseConstants.SYNAPSE_NAMESPACE, "disabledErrorCodes"));
            if (retryDisabledErrorCodes != null && retryDisabledErrorCodes.getText() != null) {

                StringTokenizer st = new StringTokenizer(
                        retryDisabledErrorCodes.getText().trim(), ", ");
                while (st.hasMoreTokens()) {
                    String s = st.nextToken();
                    try {
                        definition.addRetryDisabledErrorCode(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        handleException("The suspend error codes should be specified as valid " +
                                "numbers separated by commas : "
                                + retryDisabledErrorCodes.getText(), e);
                    }
                }
            }

            OMElement retryEnabledErrorCodes = retryConfig.getFirstChildWithName(new QName(
                    SynapseConstants.SYNAPSE_NAMESPACE, "enabledErrorCodes"));
            if (retryEnabledErrorCodes != null && retryEnabledErrorCodes.getText() != null) {

                StringTokenizer st = new StringTokenizer(
                        retryEnabledErrorCodes.getText().trim(), ", ");
                while (st.hasMoreTokens()) {
                    String s = st.nextToken();
                    try {
                        definition.addRetryEnabledErrorCode(Integer.parseInt(s));
                    } catch (NumberFormatException e) {
                        handleException("The suspend error codes should be specified as valid " +
                                "numbers separated by commas : "
                                + retryEnabledErrorCodes.getText(), e);
                    }
                }
            }



        }

        return definition;
    }

    private boolean isExpression(String expressionStr) {
        Pattern pattern = Pattern.compile("\\{.*\\}}");
        if (pattern.matcher(expressionStr).matches()) {
            return true;
        }
        return false;
    }

    protected static void handleException(String msg) {
        log.error(msg);
        throw new SynapseException(msg);
    }

    protected static void handleException(String msg, Exception e) {
        log.error(msg, e);
        throw new SynapseException(msg, e);
    }
}
