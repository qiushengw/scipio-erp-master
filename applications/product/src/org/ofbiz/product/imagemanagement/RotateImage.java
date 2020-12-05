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
package org.ofbiz.product.imagemanagement;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.string.FlexibleStringExpander;
import org.ofbiz.common.image.ImageTransform;
import org.ofbiz.common.image.ImageType.ImageTypeInfo;
import org.ofbiz.common.image.storer.ImageStorers;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;


public class RotateImage {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String resourceError = "ProductErrorUiLabels";
    public static final String resource = "ProductFUiLabels";

    public static Map<String, Object> imageRotate(DispatchContext dctx, Map<String, ? extends Object> context)
            throws IOException {
        Locale locale = (Locale)context.get("locale");
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        String nameOfThumb = FlexibleStringExpander.expandString(EntityUtilProperties.getPropertyValue("catalog", "image.management.nameofthumbnail", delegator), context);

        String productId = (String) context.get("productId");
        String imageName = (String) context.get("imageName");
        String angle = (String) context.get("angle");

        String imageProfile = (String) context.get("imageProfile"); // SCIPIO
        if (UtilValidate.isEmpty(imageProfile)) {
            imageProfile = "IMAGE_PRODUCT";
        }
        Map<String, Object> imageWriteOptions = UtilGenerics.cast(context.get("imageWriteOptions"));

        if (UtilValidate.isNotEmpty(imageName)) {
            Map<String, Object> contentCtx = new HashMap<>();
            contentCtx.put("contentTypeId", "DOCUMENT");
            contentCtx.put("userLogin", userLogin);
            Map<String, Object> contentResult;
            try {
                contentResult = dispatcher.runSync("createContent", contentCtx);
                if (ServiceUtil.isError(contentResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(contentResult));
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, module);
                return ServiceUtil.returnError(e.getMessage());
            }

            Map<String, Object> contentThumb = new HashMap<>();
            contentThumb.put("contentTypeId", "DOCUMENT");
            contentThumb.put("userLogin", userLogin);
            Map<String, Object> contentThumbResult;
            try {
                contentThumbResult = dispatcher.runSync("createContent", contentThumb);
                if (ServiceUtil.isError(contentThumbResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(contentThumbResult));
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, module);
                return ServiceUtil.returnError(e.getMessage());
            }

            String contentIdThumb = (String) contentThumbResult.get("contentId");
            String contentId = (String) contentResult.get("contentId");
            String filenameToUse = (String) contentResult.get("contentId") + ".jpg";
            String filenameTouseThumb = (String) contentResult.get("contentId") + nameOfThumb + ".jpg";

            String imageServerPath = FlexibleStringExpander.expandString(EntityUtilProperties.getPropertyValue("catalog", "image.management.path", delegator), context);
            String imageServerUrl = FlexibleStringExpander.expandString(EntityUtilProperties.getPropertyValue("catalog", "image.management.url", delegator), context);
            BufferedImage bufImg = ImageIO.read(new File(imageServerPath + "/" + productId + "/" + imageName));
            if (bufImg == null) { // SCIPIO: may be null
                return ServiceUtil.returnError(UtilProperties.getMessage("CommonErrorUiLabels", "ImageTransform.unable_to_read_image", locale));
            }

            // SCIPIO: obsolete
//            int bufImgType;
//            if (BufferedImage.TYPE_CUSTOM == bufImg.getType()) {
//                bufImgType = BufferedImage.TYPE_INT_ARGB_PRE;
//            } else {
//                bufImgType = bufImg.getType();
//            }

            int w = bufImg.getWidth(null);
            int h = bufImg.getHeight(null);
            // SCIPIO: fixed for indexed images
            //BufferedImage bufNewImg = new BufferedImage(w, h, bufImgType);
            BufferedImage bufNewImg = ImageTransform.createBufferedImage(ImageTypeInfo.from(bufImg), w, h);
            Graphics2D g = bufNewImg.createGraphics();
            g.rotate(Math.toRadians(Double.parseDouble(angle)), w / 2.0, h / 2.0);
            g.drawImage(bufImg,0,0,null);
            g.dispose();

            String mimeType = imageName.substring(imageName.lastIndexOf('.') + 1);
            ImageStorers.write(bufNewImg, mimeType, new File(imageServerPath + "/" + productId + "/" + filenameToUse),
                    imageProfile, imageWriteOptions, delegator); // SCIPIO: ImageIO->ImageStorers

            double imgHeight = bufNewImg.getHeight();
            double imgWidth = bufNewImg.getWidth();

            Map<String, Object> resultResize = ImageManagementServices.resizeImageThumbnail(bufNewImg, imgHeight, imgWidth);
            ImageStorers.write((RenderedImage) resultResize.get("bufferedImage"), mimeType, new File(imageServerPath + "/" + productId + "/" + filenameTouseThumb),
                    imageProfile, imageWriteOptions, delegator); // SCIPIO: ImageIO->ImageStorers

            String imageUrlResource = imageServerUrl + "/" + productId + "/" + filenameToUse;
            String imageUrlThumb = imageServerUrl + "/" + productId + "/" + filenameTouseThumb;

            ImageManagementServices.createContentAndDataResource(dctx, userLogin, filenameToUse, imageUrlResource, contentId, "image/jpeg");
            ImageManagementServices.createContentAndDataResource(dctx, userLogin, filenameTouseThumb, imageUrlThumb, contentIdThumb, "image/jpeg");

            Map<String, Object> createContentAssocMap = new HashMap<>();
            createContentAssocMap.put("contentAssocTypeId", "IMAGE_THUMBNAIL");
            createContentAssocMap.put("contentId", contentId);
            createContentAssocMap.put("contentIdTo", contentIdThumb);
            createContentAssocMap.put("userLogin", userLogin);
            createContentAssocMap.put("mapKey", "100");
            try {
                Map<String, Object> serviceResult = dispatcher.runSync("createContentAssoc", createContentAssocMap);
                if (ServiceUtil.isError(serviceResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(serviceResult));
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, module);
                return ServiceUtil.returnError(e.getMessage());
            }

            Map<String, Object> productContentCtx = new HashMap<>();
            productContentCtx.put("productId", productId);
            productContentCtx.put("productContentTypeId", "IMAGE");
            productContentCtx.put("fromDate", UtilDateTime.nowTimestamp());
            productContentCtx.put("userLogin", userLogin);
            productContentCtx.put("contentId", contentId);
            productContentCtx.put("statusId", "IM_PENDING");
            try {
                Map<String, Object> serviceResult = dispatcher.runSync("createProductContent", productContentCtx);
                if (ServiceUtil.isError(serviceResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(serviceResult));
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, module);
                return ServiceUtil.returnError(e.getMessage());
            }

            Map<String, Object> contentApprovalCtx = new HashMap<>();
            contentApprovalCtx.put("contentId", contentId);
            contentApprovalCtx.put("userLogin", userLogin);
            try {
                Map<String, Object> serviceResult = dispatcher.runSync("createImageContentApproval", contentApprovalCtx);
                if (ServiceUtil.isError(serviceResult)) {
                    return ServiceUtil.returnError(ServiceUtil.getErrorMessage(serviceResult));
                }
            } catch (GenericServiceException e) {
                Debug.logError(e, module);
                return ServiceUtil.returnError(e.getMessage());
            }
        } else {
            String errMsg = UtilProperties.getMessage(resourceError, "ProductPleaseSelectImage", locale);
            Debug.logFatal(errMsg, module);
            return ServiceUtil.returnError(errMsg);
        }
        String successMsg = UtilProperties.getMessage(resource, "ProductRotatedImageSuccessfully", locale);
        Map<String, Object> result = ServiceUtil.returnSuccess(successMsg);
        return result;
    }
}
