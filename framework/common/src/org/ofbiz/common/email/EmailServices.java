/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.common.email;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.SendFailedException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.xml.transform.stream.StreamSource;

import org.apache.fop.apps.Fop;
import org.apache.fop.apps.MimeConstants;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.HttpClient;
import org.ofbiz.base.util.HttpClientException;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.collections.MapStack;
import org.ofbiz.base.util.collections.RenderMapStack;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.mail.MimeMessageWrapper;
import org.ofbiz.webapp.view.ApacheFopWorker;
import org.ofbiz.widget.renderer.ScreenRenderer;
import org.ofbiz.widget.renderer.ScreenStringRenderer;
import org.ofbiz.widget.renderer.macro.MacroScreenRenderer;

import com.sun.mail.smtp.SMTPAddressFailedException;

/**
 * Email Services
 */
public class EmailServices {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String resource = "CommonUiLabels";

    /**
     * Basic JavaMail Service
     *@param ctx The DispatchContext that this service is operating in
     *@param context Map containing the input parameters
     *@return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> sendMail(DispatchContext ctx, Map<String, ? extends Object> context) {
        Delegator delegator = ctx.getDelegator();
        String communicationEventId = (String) context.get("communicationEventId");
        String orderId = (String) context.get("orderId");
        Locale locale = (Locale) context.get("locale");
        if (communicationEventId != null) {
            Debug.logInfo("SendMail Running, for communicationEventId : " + communicationEventId, module);
        }
        Map<String, Object> results = ServiceUtil.returnSuccess();
        String subject = (String) context.get("subject");
        subject = FlexibleStringExpander.expandString(subject, context);

        String partyId = (String) context.get("partyId");
        String body = (String) context.get("body");
        List<Map<String, Object>> bodyParts = UtilGenerics.checkList(context.get("bodyParts"));
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        results.put("communicationEventId", communicationEventId);
        results.put("partyId", partyId);
        results.put("subject", subject);

        if (UtilValidate.isNotEmpty(orderId)) {
            results.put("orderId", orderId);
        }
        if (UtilValidate.isNotEmpty(body)) {
            body = FlexibleStringExpander.expandString(body, context);
            results.put("body", body);
        }
        if (UtilValidate.isNotEmpty(bodyParts)) {
            results.put("bodyParts", bodyParts);
        }
        results.put("userLogin", userLogin);

        String sendTo = (String) context.get("sendTo");
        String sendCc = (String) context.get("sendCc");
        String sendBcc = (String) context.get("sendBcc");
        // SCIPIO: 03-31-2020: Checking if replyTo is present.
        List<String> replyTo = null;
        if (context.containsKey("replyTo")) {
            replyTo = (List<String>) context.get("replyTo");
        }

        // check to see if we should redirect all mail for testing
        String redirectAddress = EntityUtilProperties.getPropertyValue("general", "mail.notifications.redirectTo", delegator);
        if (UtilValidate.isNotEmpty(redirectAddress)) {
            StringBuilder sb = new StringBuilder();
            sb.append(" [To: ").append(sendTo);
            if (UtilValidate.isNotEmpty(sendCc)) {
                sb.append(", Cc: ").append(sendCc);
            }
            if (UtilValidate.isNotEmpty(sendBcc)) {
                sb.append(", Bcc: ").append(sendBcc);
            }
            sb.append("]");
            subject += sb.toString();
            sendTo = redirectAddress;
            sendCc = null;
            sendBcc = null;
            if (subject.length() > 255) {
                subject = subject.substring(0, 255);
            }
        }

        String sendFrom = (String) context.get("sendFrom");
        String sendType = (String) context.get("sendType");
        String port = (String) context.get("port");
        String socketFactoryClass = (String) context.get("socketFactoryClass");
        String socketFactoryPort  = (String) context.get("socketFactoryPort");
        String socketFactoryFallback  = (String) context.get("socketFactoryFallback");
        String sendVia = (String) context.get("sendVia");
        String authUser = (String) context.get("authUser");
        String authPass = (String) context.get("authPass");
        String messageId = (String) context.get("messageId");
        String contentType = (String) context.get("contentType");
        Boolean sendPartial = (Boolean) context.get("sendPartial");
        Boolean isStartTLSEnabled = (Boolean) context.get("startTLSEnabled");
        // SCIPIO: 03-31-2020: Flag used to check if custom headers are allowed
        Boolean isCustomHeadersAllowed = (Boolean) context.get("allowCustomHeaders");
        // SCIPIO: 11-19-2020: Added "send as" which serves as an alias for "From" field
        String sendAs = (String) context.get("sendAs");

        boolean useSmtpAuth = false;

        // define some default
        if (sendType == null || sendType.equals("mail.smtp.host")) {
            sendType = "mail.smtp.host";
            if (UtilValidate.isEmpty(sendVia)) {
                sendVia = EntityUtilProperties.getPropertyValue("general", "mail.smtp.relay.host", "localhost", delegator);
            }
            if (UtilValidate.isEmpty(authUser)) {
                authUser = EntityUtilProperties.getPropertyValue("general", "mail.smtp.auth.user", delegator);
            }
            if (UtilValidate.isEmpty(authPass)) {
                authPass = EntityUtilProperties.getPropertyValue("general", "mail.smtp.auth.password", delegator);
            }
            if (UtilValidate.isNotEmpty(authUser)) {
                useSmtpAuth = true;
            }
            if (UtilValidate.isEmpty(port)) {
                port = EntityUtilProperties.getPropertyValue("general", "mail.smtp.port", delegator);
            }
            if (UtilValidate.isEmpty(socketFactoryPort)) {
                socketFactoryPort = EntityUtilProperties.getPropertyValue("general", "mail.smtp.socketFactory.port", delegator);
            }
            if (UtilValidate.isEmpty(socketFactoryClass)) {
                socketFactoryClass = EntityUtilProperties.getPropertyValue("general", "mail.smtp.socketFactory.class", delegator);
            }
            if (UtilValidate.isEmpty(socketFactoryFallback)) {
                socketFactoryFallback = EntityUtilProperties.getPropertyValue("general", "mail.smtp.socketFactory.fallback", "false", delegator);
            }
            if (sendPartial == null) {
                sendPartial = EntityUtilProperties.propertyValueEqualsIgnoreCase("general", "mail.smtp.sendpartial", "true", delegator) ? true : false;
            }
            if (isStartTLSEnabled == null) {
                isStartTLSEnabled = EntityUtilProperties.propertyValueEqualsIgnoreCase("general", "mail.smtp.starttls.enable", "true", delegator);
            }
        } else if (sendVia == null) {
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendMissingParameterSendVia", locale));
        }

        if (contentType == null) {
            contentType = "text/html";
        }

        if (UtilValidate.isNotEmpty(bodyParts)) {
            contentType = "multipart/mixed";
        }
        results.put("contentType", contentType);

        Session session;
        MimeMessage mail;
        try {
            Properties props = System.getProperties();
            props.put(sendType, sendVia);
            if (UtilValidate.isNotEmpty(port)) {
                props.put("mail.smtp.port", port);
            }
            if (UtilValidate.isNotEmpty(socketFactoryPort)) {
                props.put("mail.smtp.socketFactory.port", socketFactoryPort);
            }
            if (UtilValidate.isNotEmpty(socketFactoryClass)) {
                props.put("mail.smtp.socketFactory.class", socketFactoryClass);
                Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider()); // SCIPIO: 2018-08-30: TODO: REVIEW: can remove now?
            }
            if (UtilValidate.isNotEmpty(socketFactoryFallback)) {
                props.put("mail.smtp.socketFactory.fallback", socketFactoryFallback);
            }
            if (useSmtpAuth) {
                props.put("mail.smtp.auth", "true");
            }
            if (sendPartial != null) {
                props.put("mail.smtp.sendpartial", sendPartial ? "true" : "false");
            }
            if (isStartTLSEnabled) {
                props.put("mail.smtp.starttls.enable", "true");
            }

            session = Session.getInstance(props);
            boolean debug = EntityUtilProperties.propertyValueEqualsIgnoreCase("general", "mail.debug.on", "Y", delegator);
            session.setDebug(debug);

            mail = new MimeMessage(session);
            if (messageId != null) {
                mail.setHeader("In-Reply-To", messageId);
                mail.setHeader("References", messageId);
            }

            // SCIPIO: 03-31-2020: Added replyTo header.
            if (replyTo != null) {
                InternetAddress[] internetAddresses = new InternetAddress[replyTo.size()];
                for (int i = 0; i < replyTo.size() ; i++) {
                    internetAddresses[i] = new InternetAddress(replyTo.get(i));
                }
                mail.setReplyTo(internetAddresses);
            }

            InternetAddress from = new InternetAddress(sendFrom);
            if (UtilValidate.isNotEmpty(sendAs)) {
                try {
                    from.setPersonal(FlexibleStringExpander.expandString(sendAs, context), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    Debug.logError(e, "MessagingException when adding send as (alias) [" + sendAs + "] for from [" + sendFrom + "]", module);
                }
            }
            mail.setFrom(from);
            mail.setSubject(subject, "UTF-8");
            mail.setHeader("X-Mailer", "SCIPIO ERP");
            mail.setSentDate(new Date());
            mail.addRecipients(Message.RecipientType.TO, sendTo);

            // SCIPIO: 03-31-2020: Added custom headers support.
            // TODO: Implement a proper validation mechanism
            if (isCustomHeadersAllowed && context.containsKey("customHeaders")) {
                Map<String, Object> customHeaders = (Map<String, Object>) context.get("customHeaders");
                for (String customHeaderName : customHeaders.keySet()) {
                    Object customHeaderValue = customHeaders.get(customHeaderName);
                    if (customHeaderValue instanceof String) {
                        mail.setHeader(customHeaderName, (String) customHeaderValue);
                    }
                }
            }

            if (UtilValidate.isNotEmpty(sendCc)) {
                mail.addRecipients(Message.RecipientType.CC, sendCc);
            }
            if (UtilValidate.isNotEmpty(sendBcc)) {
                mail.addRecipients(Message.RecipientType.BCC, sendBcc);
            }

            if (UtilValidate.isNotEmpty(bodyParts)) {
                // check for multipart message (with attachments)
                // BodyParts contain a list of Maps items containing content(String) and type(String) of the attachement
                MimeMultipart mp = new MimeMultipart();
                Debug.logInfo(bodyParts.size() + " multiparts found",module);
                for (Map<String, Object> bodyPart: bodyParts) {
                    Object bodyPartContent = bodyPart.get("content");
                    MimeBodyPart mbp = new MimeBodyPart();

                    if (bodyPartContent instanceof String) {
                        Debug.logInfo("part of type: " + bodyPart.get("type") + " and size: " + bodyPart.get("content").toString().length() , module);
                        mbp.setText((String) bodyPartContent, "UTF-8", ((String) bodyPart.get("type")).substring(5));
                    } else if (bodyPartContent instanceof byte[]) {
                        ByteArrayDataSource bads = new ByteArrayDataSource((byte[]) bodyPartContent, (String) bodyPart.get("type"));
                        Debug.logInfo("part of type: " + bodyPart.get("type") + " and size: " + ((byte[]) bodyPartContent).length , module);
                        mbp.setDataHandler(new DataHandler(bads));
                    } else if (bodyPartContent instanceof DataHandler) {
                        mbp.setDataHandler((DataHandler) bodyPartContent);
                    } else {
                        mbp.setDataHandler(new DataHandler(bodyPartContent, (String) bodyPart.get("type")));
                    }

                    String fileName = (String) bodyPart.get("filename");
                    if (fileName != null) {
                        mbp.setFileName(fileName);
                    }
                    mp.addBodyPart(mbp);
                }
                mail.setContent(mp);
                mail.saveChanges();
            } else {
                // create the singelpart message
                if (contentType.startsWith("text")) {
                    mail.setText(body, "UTF-8", contentType.substring(5));
                } else {
                    mail.setContent(body, contentType);
                }
                mail.saveChanges();
            }
        } catch (MessagingException e) {
            Debug.logError(e, "MessagingException when creating message to [" + sendTo + "] from [" + sendFrom + "] cc [" + sendCc + "] bcc [" + sendBcc + "] subject [" + subject + "]", module);
            Debug.logError("Email message that could not be created to [" + sendTo + "] had context: " + context, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendMessagingException", UtilMisc.toMap("sendTo", sendTo, "sendFrom", sendFrom, "sendCc", sendCc, "sendBcc", sendBcc, "subject", subject), locale));
        }

        // check to see if sending mail is enabled
        String mailEnabled = EntityUtilProperties.getPropertyValue("general", "mail.notifications.enabled", "N", delegator);
        if (!"Y".equalsIgnoreCase(mailEnabled)) {
            // no error; just return as if we already processed
            Debug.logImportant("Mail notifications disabled in general.properties; mail with subject [" + subject + "] not sent to addressee [" + sendTo + "]", module);
            if (Debug.verboseOn()) Debug.logVerbose("What would have been sent, the addressee: " + sendTo + " subject: " + subject + " context: " + context, module);
            results.put("messageWrapper", new MimeMessageWrapper(session, mail));
            return results;
        }

        Transport trans = null;
        try {
            trans = session.getTransport("smtp");
            if (!useSmtpAuth) {
                trans.connect();
            } else {
                trans.connect(sendVia, authUser, authPass);
            }
            trans.sendMessage(mail, mail.getAllRecipients());
            results.put("messageWrapper", new MimeMessageWrapper(session, mail));
            results.put("messageId", mail.getMessageID());
            trans.close();
        } catch (SendFailedException e) {
            // message code prefix may be used by calling services to determine the cause of the failure
            Debug.logError(e, "[ADDRERR] Address error when sending message to [" + sendTo + "] from [" + sendFrom + "] cc [" + sendCc + "] bcc [" + sendBcc + "] subject [" + subject + "]", module);
            List<SMTPAddressFailedException> failedAddresses = new LinkedList<>();
            Exception nestedException = null;
            while ((nestedException = e.getNextException()) != null && nestedException instanceof MessagingException) {
                if (nestedException instanceof SMTPAddressFailedException) {
                    SMTPAddressFailedException safe = (SMTPAddressFailedException) nestedException;
                    Debug.logError("Failed to send message to [" + safe.getAddress() + "], return code [" + safe.getReturnCode() + "], return message [" + safe.getMessage() + "]", module);
                    failedAddresses.add(safe);
                    break;
                }
            }
            Boolean sendFailureNotification = (Boolean) context.get("sendFailureNotification");
            if (sendFailureNotification == null || sendFailureNotification) {
                sendFailureNotification(ctx, context, mail, failedAddresses);
                results.put("messageWrapper", new MimeMessageWrapper(session, mail));
                try {
                    results.put("messageId", mail.getMessageID());
                    trans.close();
                } catch (MessagingException e1) {
                    Debug.logError(e1, module);
                }
            } else {
                return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendAddressError", UtilMisc.toMap("sendTo", sendTo, "sendFrom", sendFrom, "sendCc", sendCc, "sendBcc", sendBcc, "subject", subject), locale));
            }
        } catch (MessagingException e) {
            // message code prefix may be used by calling services to determine the cause of the failure
            // SCIPIO: Merged second error (why?)
            Debug.logError(e, "[CON] Connection error when sending message to [" + sendTo + "] from [" + sendFrom + "] cc [" + sendCc + "] bcc [" + sendBcc + "] subject [" + subject + "] context " + context, module);
            //Debug.logError("Email message that could not be sent to [" + sendTo + "] had context: " + context, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendConnectionError", UtilMisc.toMap("sendTo", sendTo, "sendFrom", sendFrom, "sendCc", sendCc, "sendBcc", sendBcc, "subject", subject), locale));
        }
        return results;
    }

    /**
     * JavaMail Service that gets body content from a URL
     *@param ctx The DispatchContext that this service is operating in
     *@param rcontext Map containing the input parameters
     *@return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> sendMailFromUrl(DispatchContext ctx, Map<String, ? extends Object> rcontext) {
        // pretty simple, get the content and then call the sendMail method below
        Map<String, Object> sendMailContext = UtilMisc.makeMapWritable(rcontext);
        String bodyUrl = (String) sendMailContext.remove("bodyUrl");
        Map<String, Object> bodyUrlParameters = UtilGenerics.checkMap(sendMailContext.remove("bodyUrlParameters"));
        Locale locale = (Locale) rcontext.get("locale");
        LocalDispatcher dispatcher = ctx.getDispatcher();

        URL url = null;

        try {
            url = new URL(bodyUrl);
        } catch (MalformedURLException e) {
            Debug.logWarning(e, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendMalformedUrl", UtilMisc.toMap("bodyUrl", bodyUrl, "errorString", e.toString()), locale));
        }

        HttpClient httpClient = new HttpClient(url, bodyUrlParameters);
        String body = null;

        try {
            body = httpClient.post();
        } catch (HttpClientException e) {
            Debug.logWarning(e, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendGettingError", UtilMisc.toMap("errorString", e.toString()), locale));
        }

        sendMailContext.put("body", body);
        Map<String, Object> sendMailResult;
        try {
            sendMailResult = dispatcher.runSync("sendMail", sendMailContext);
        } catch (GenericServiceException e) {
            Debug.logError(e, module);
            return ServiceUtil.returnError(e.getMessage());
        }

        // just return the same result; it contains all necessary information
        return sendMailResult;
    }

    /**
     * JavaMail Service that gets body content from a Screen Widget
     * defined in the product store record and if available as attachment also.
     *@param dctx The DispatchContext that this service is operating in
     *@param rServiceContext Map containing the input parameters
     *@return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> sendMailFromScreen(DispatchContext dctx, Map<String, ? extends Object> rServiceContext) {
        Map<String, Object> serviceContext = UtilMisc.makeMapWritable(rServiceContext);
        LocalDispatcher dispatcher = dctx.getDispatcher();
        serviceContext.remove("autoInferParams"); // SCIPIO: 2019-02-04: This can't be passed to sendMail
        String webSiteId = (String) serviceContext.remove("webSiteId");
        String bodyText = (String) serviceContext.remove("bodyText");
        String bodyScreenUri = (String) serviceContext.remove("bodyScreenUri");
        String xslfoAttachScreenLocationParam = (String) serviceContext.remove("xslfoAttachScreenLocation");
        String attachmentNameParam = (String) serviceContext.remove("attachmentName");
        List<String> xslfoAttachScreenLocationListParam = UtilGenerics.checkList(serviceContext.remove("xslfoAttachScreenLocationList"));
        List<String> attachmentNameListParam = UtilGenerics.checkList(serviceContext.remove("attachmentNameList"));

        List<String> xslfoAttachScreenLocationList = new LinkedList<>();
        List<String> attachmentNameList = new LinkedList<>();
        if (UtilValidate.isNotEmpty(xslfoAttachScreenLocationParam)) {
            xslfoAttachScreenLocationList.add(xslfoAttachScreenLocationParam);
        }
        if (UtilValidate.isNotEmpty(attachmentNameParam)) {
            attachmentNameList.add(attachmentNameParam);
        }
        if (UtilValidate.isNotEmpty(xslfoAttachScreenLocationListParam)) {
            xslfoAttachScreenLocationList.addAll(xslfoAttachScreenLocationListParam);
        }
        if (UtilValidate.isNotEmpty(attachmentNameListParam)) {
            attachmentNameList.addAll(attachmentNameListParam);
        }

        Locale locale = (Locale) serviceContext.get("locale");
        Map<String, Object> bodyParameters = UtilGenerics.checkMap(serviceContext.remove("bodyParameters"));
        //populate with service context
        bodyParameters.put("emailCtx",serviceContext);
        if (bodyParameters == null) {
            bodyParameters = MapStack.create();
        }
        if (!bodyParameters.containsKey("locale")) {
            bodyParameters.put("locale", locale);
        } else {
            locale = (Locale) bodyParameters.get("locale");
        }
        String partyId = (String) serviceContext.get("partyId");
        if (partyId == null) {
            partyId = (String) bodyParameters.get("partyId");
        }
        String orderId = (String) bodyParameters.get("orderId");
        String custRequestId = (String) bodyParameters.get("custRequestId");

        bodyParameters.put("communicationEventId", serviceContext.get("communicationEventId"));
        // SCIPIO: Use new, better method
        //NotificationServices.setBaseUrl(dctx.getDelegator(), webSiteId, bodyParameters);
        NotificationServices.checkSetWebSiteFields(dctx.getDelegator(), webSiteId, bodyParameters);
        String contentType = (String) serviceContext.remove("contentType");

        // SCIPIO: For debugging and development reasons, note whether a webSiteId is present,
        // and also whether bodyParameters was outfitted with a productStoreId.
        // TODO?: In future, switch this to verbose when confidence is higher there are no more issues left from this.
        if (Debug.infoOn()) {
            GenericValue paramsUserLogin = (GenericValue) bodyParameters.get("userLogin");
            Debug.logInfo("sendMailFromScreen:"
                    + " [sendTo=" + serviceContext.get("sendTo")
                    + ", partyId=" + partyId
                    + ", bodyScreenUri=" + bodyScreenUri
                    + ", webSiteId=" + webSiteId
                    + ", orderId=" + orderId
                    + ", bodyParameters.webSiteId=" + bodyParameters.get("webSiteId")
                    + ", bodyParameters.productStoreId=" + bodyParameters.get("productStoreId")
                    + ", bodyParameters.orderId=" + bodyParameters.get("orderId")
                    + ", locale=" + locale
                    + ", bodyParameters.userLogin(Id)=" + (paramsUserLogin != null ? paramsUserLogin.get("userLoginId") : null)
                    + ", bodyParameters.partyId=" + bodyParameters.get("partyId")
                    + ", serviceContext.partyId=" + serviceContext.get("partyId")
                    + "]", module);
        }

        StringWriter bodyWriter = new StringWriter();

        MapStack<String> screenContext = RenderMapStack.createRenderContext(); // SCIPIO: Dedicated context class: MapStack.create();
        screenContext.put("locale", locale);

        ScreenStringRenderer screenStringRenderer = null;
        try {
            screenStringRenderer = new MacroScreenRenderer(EntityUtilProperties.getPropertyValue("widget", "screenemail.name", EntityUtilProperties.getPropertyValue("widget", "screen.name", dctx.getDelegator()), dctx.getDelegator()),
                    EntityUtilProperties.getPropertyValue("widget", "screenemail.screenrenderer", EntityUtilProperties.getPropertyValue("widget", "screen.screenrenderer", dctx.getDelegator()), dctx.getDelegator())); // SCIPIO: now using screenemail properties + fallback if missing
        } catch (Exception e) { // SCIPIO: Changed (TemplateException | IOException e) to Exception (for robustness)
            Debug.logError(e, "Error rendering screen for email: " + e.toString(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendRenderingScreenEmailError", UtilMisc.toMap("errorString", e.toString()), locale));
        }
        ScreenRenderer screens = ScreenRenderer.makeWithEnvAwareFetching(bodyWriter, screenContext, screenStringRenderer); // SCIPIO: env-aware
        screens.populateContextForService(dctx, bodyParameters);
        screenContext.putAll(bodyParameters);

        if (bodyScreenUri != null) {
            try {
                screens.render(bodyScreenUri);
            // SCIPIO: let's be more conservative
            //} catch (GeneralException | IOException | SAXException | ParserConfigurationException e) {
            } catch(Exception e) {
                Debug.logError(e, "Error rendering screen for email: " + e.toString(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendRenderingScreenEmailError", UtilMisc.toMap("errorString", e.toString()), locale));
            }
        }

        boolean isMultiPart = false;

        // check if attachment screen location passed in
        if (UtilValidate.isNotEmpty(xslfoAttachScreenLocationList)) {
            List<Map<String, ? extends Object>> bodyParts = new LinkedList<Map<String, ? extends Object>>();
            if (bodyText != null) {
                bodyText = FlexibleStringExpander.expandString(bodyText, screenContext,  locale);
                bodyParts.add(UtilMisc.<String, Object>toMap("content", bodyText, "type", UtilValidate.isNotEmpty(contentType) ? contentType : "text/html"));
            } else {
                bodyParts.add(UtilMisc.<String, Object>toMap("content", bodyWriter.toString(), "type", UtilValidate.isNotEmpty(contentType) ? contentType : "text/html"));
            }

            for (int i = 0; i < xslfoAttachScreenLocationList.size(); i++) {
                String xslfoAttachScreenLocation = xslfoAttachScreenLocationList.get(i);
                String attachmentName = "Details.pdf";
                if (UtilValidate.isNotEmpty(attachmentNameList) && attachmentNameList.size() >= i) {
                    attachmentName = attachmentNameList.get(i);
                }
                isMultiPart = true;
                // start processing fo pdf attachment
                try {
                    Writer writer = new StringWriter();
                    MapStack<String> screenContextAtt = RenderMapStack.createRenderContext(); // SCIPIO: Dedicated context class: MapStack.create();
                    // substitute the freemarker variables...
                    ScreenStringRenderer foScreenStringRenderer = new MacroScreenRenderer(EntityUtilProperties.getPropertyValue("widget", "screenfop.name", dctx.getDelegator()),
                            EntityUtilProperties.getPropertyValue("widget", "screenfop.screenrenderer", dctx.getDelegator()));
                    ScreenRenderer screensAtt = ScreenRenderer.makeWithEnvAwareFetching(writer, screenContextAtt, foScreenStringRenderer); // SCIPIO: env-aware + fixed a bug where screenContext was passed instead of screenContextAtt
                    screensAtt.populateContextForService(dctx, bodyParameters);
                    screenContextAtt.putAll(bodyParameters);
                    screensAtt.render(xslfoAttachScreenLocation);

                    /*
                    try { // save generated fo file for debugging
                        String buf = writer.toString();
                        java.io.FileWriter fw = new java.io.FileWriter(new java.io.File("/tmp/file1.xml"));
                        fw.write(buf.toString());
                        fw.close();
                    } catch (IOException e) {
                        Debug.logError(e, "Couldn't save xsl-fo xml debug file: " + e.toString(), module);
                    }*/


                    // create the input stream for the generation
                    StreamSource src = new StreamSource(new StringReader(writer.toString()));

                    // create the output stream for the generation
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    Fop fop = ApacheFopWorker.createFopInstance(baos, MimeConstants.MIME_PDF);
                    ApacheFopWorker.transform(src, null, fop);

                    // and generate the PDF
                    baos.flush();
                    baos.close();

                    // store in the list of maps for sendmail....
                    bodyParts.add(UtilMisc.<String, Object> toMap("content", baos.toByteArray(), "type", "application/pdf", "filename",
                            attachmentName));
                } catch (Exception e) {
                    Debug.logError(e, "Error rendering PDF attachment for email: " + e.toString(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendRenderingScreenPdfError",
                            UtilMisc.toMap("errorString", e.toString()), locale));
                }

                serviceContext.put("bodyParts", bodyParts);
            }
        } else {
            isMultiPart = false;
            // store body and type for single part message in the context.
            if (bodyText != null) {
                bodyText = FlexibleStringExpander.expandString(bodyText, screenContext,  locale);
                serviceContext.put("body", bodyText);
            } else {
                serviceContext.put("body", bodyWriter.toString());
            }

            // Only override the default contentType in case of plaintext, since other contentTypes may be multipart
            //    and would require specific handling.
            if (contentType != null && contentType.equalsIgnoreCase("text/plain")) {
                serviceContext.put("contentType", "text/plain");
            } else {
                serviceContext.put("contentType", "text/html");
            }
        }

        // also expand the subject at this point, just in case it has the FlexibleStringExpander syntax in it...
        String subject = (String) serviceContext.remove("subject");
        subject = FlexibleStringExpander.expandString(subject, screenContext, locale);
        if (Debug.infoOn()) {
            Debug.logInfo("Expanded email subject to: " + subject, module);
        }
        serviceContext.put("subject", subject);
        serviceContext.put("partyId", partyId);
        if (UtilValidate.isNotEmpty(orderId)) {
            serviceContext.put("orderId", orderId);
        }
        if (UtilValidate.isNotEmpty(custRequestId)) {
            serviceContext.put("custRequestId", custRequestId);
        }

        if (Debug.verboseOn()) {
            Debug.logVerbose("sendMailFromScreen sendMail context: " + serviceContext, module);
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        Map<String, Object> sendMailResult;
        Boolean hideInLog = (Boolean) serviceContext.get("hideInLog");
        try {
            if (!Boolean.TRUE.equals(hideInLog)) {
                if (isMultiPart) {
                    sendMailResult = dispatcher.runSync("sendMailMultiPart", serviceContext);
                } else {
                    sendMailResult = dispatcher.runSync("sendMail", serviceContext);
                }
            } else {
                if (isMultiPart) {
                    sendMailResult = dispatcher.runSync("sendMailMultiPartHiddenInLog", serviceContext);
                } else {
                    sendMailResult = dispatcher.runSync("sendMailHiddenInLog", serviceContext);
                }
            }
        } catch (Exception e) {
            Debug.logError(e, "Error send email:" + e.toString(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(resource, "CommonEmailSendError", UtilMisc.toMap("errorString", e.toString()), locale));
        }
        if (ServiceUtil.isError(sendMailResult)) {
            return ServiceUtil.returnError(ServiceUtil.getErrorMessage(sendMailResult));
        }

        result.put("messageWrapper", sendMailResult.get("messageWrapper"));
        result.put("body", bodyWriter.toString());
        result.put("subject", subject);
        result.put("communicationEventId", sendMailResult.get("communicationEventId"));
        if (UtilValidate.isNotEmpty(orderId)) {
            result.put("orderId", orderId);
        }
        if (UtilValidate.isNotEmpty(custRequestId)) {
            result.put("custRequestId", custRequestId);
        }
        return result;
    }

    /**
     * JavaMail Service same than sendMailFromScreen but with hidden result in log.
     * To prevent having not encoded passwords shown in log
     *@param dctx The DispatchContext that this service is operating in
     *@param rServiceContext Map containing the input parameters
     *@return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> sendMailHiddenInLogFromScreen(DispatchContext dctx, Map<String, ? extends Object> rServiceContext) {
        Map<String, Object> serviceContext = UtilMisc.makeMapWritable(rServiceContext);
        serviceContext.put("hideInLog", true);
        return sendMailFromScreen(dctx, serviceContext);
    }

    public static void sendFailureNotification(DispatchContext dctx, Map<String, ? extends Object> context, MimeMessage message, List<SMTPAddressFailedException> failures) {
        Locale locale = (Locale) context.get("locale");
        Map<String, Object> newContext = new LinkedHashMap<>();
        newContext.put("userLogin", context.get("userLogin"));
        newContext.put("sendFailureNotification", false);
        newContext.put("sendFrom", context.get("sendFrom"));
        newContext.put("sendTo", context.get("sendFrom"));
        newContext.put("subject", UtilProperties.getMessage(resource, "CommonEmailSendUndeliveredMail", locale));
        StringBuilder sb = new StringBuilder();
        sb.append(UtilProperties.getMessage(resource, "CommonEmailDeliveryFailed", locale));
        sb.append("/n/n");
        for (SMTPAddressFailedException failure : failures) {
            sb.append(failure.getAddress());
            sb.append(": ");
            sb.append(failure.getMessage());
            sb.append("/n/n");
        }
        sb.append(UtilProperties.getMessage(resource, "CommonEmailDeliveryOriginalMessage", locale));
        sb.append("/n/n");
        List<Map<String, Object>> bodyParts = new LinkedList<>();
        bodyParts.add(UtilMisc.<String, Object>toMap("content", sb.toString(), "type", "text/plain"));
        try {
            bodyParts.add(UtilMisc.<String, Object>toMap("content", message.getDataHandler()));
        } catch (MessagingException e) {
            Debug.logError(e, module);
        }
        newContext.put("bodyParts", bodyParts);
        try {
            dctx.getDispatcher().runSync("sendMailMultiPart", newContext);
        } catch (GenericServiceException e) {
            Debug.logError(e, module);
        }
    }

    /** class to create a file in memory required for sending as an attachment */
    public static class StringDataSource implements DataSource {
        private String contentType;
        private ByteArrayOutputStream contentArray;

        public StringDataSource(String content, String contentType) throws IOException {
            this.contentType = contentType;
            contentArray = new ByteArrayOutputStream();
            contentArray.write(content.getBytes(StandardCharsets.ISO_8859_1)); // SCIPIO: StandardCharsets (instead of "iso-8859-1")
            contentArray.flush();
            contentArray.close();
        }

        public String getContentType() {
            return contentType == null ? "application/octet-stream" : contentType;
        }

        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(contentArray.toByteArray());
        }

        public String getName() {
            return "stringDatasource";
        }

        public OutputStream getOutputStream() throws IOException {
            throw new IOException("Cannot write to this read-only resource");
        }
    }

    /** class to create a file in memory required for sending as an attachment */
    public static class ByteArrayDataSource implements DataSource {
        private String contentType;
        private byte[] contentArray;

        public ByteArrayDataSource(byte[] content, String contentType) {
            this.contentType = contentType;
            this.contentArray = content.clone();
        }

        public String getContentType() {
            return contentType == null ? "application/octet-stream" : contentType;
        }

        public InputStream getInputStream() {
            return new ByteArrayInputStream(contentArray);
        }

        public String getName() {
            return "ByteArrayDataSource";
        }

        public OutputStream getOutputStream() throws IOException {
            throw new IOException("Cannot write to this read-only resource");
        }
    }
}
