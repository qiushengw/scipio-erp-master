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
package org.ofbiz.order.shoppinglist;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.RecordNotFoundException;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.order.shoppingcart.CartItemModifyException;
import org.ofbiz.order.shoppingcart.CartUpdate;
import org.ofbiz.order.shoppingcart.ItemNotFoundException;
import org.ofbiz.order.shoppingcart.ShoppingCart;
import org.ofbiz.order.shoppingcart.ShoppingCartItem;
import org.ofbiz.product.catalog.CatalogWorker;
import org.ofbiz.product.config.ProductConfigWorker;
import org.ofbiz.product.config.ProductConfigWrapper;
import org.ofbiz.product.store.ProductStoreWorker;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ModelService;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.webapp.event.EventUtil;
import org.ofbiz.webapp.website.WebSiteWorker;

/**
 * Shopping cart events.
 */
public class ShoppingListEvents {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String resource = "OrderUiLabels";
    public static final String resource_error = "OrderErrorUiLabels";
    public static final String PERSISTANT_LIST_NAME = "auto-save";

    public static String addBulkFromCart(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");

        String shoppingListId = request.getParameter("shoppingListId");
        String shoppingListTypeId = request.getParameter("shoppingListTypeId");
        String selectedCartItems[] = request.getParameterValues("selectedItem");
        String shoppingListAuthToken = getShoppingListAuthTokenForList(request, shoppingListId); // SCIPIO

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        if (UtilValidate.isEmpty(selectedCartItems)) {
            selectedCartItems = makeCartItemsArray(cart);
        }

        try {
            shoppingListId = addBulkFromCart(delegator, dispatcher, cart, userLogin, shoppingListId, shoppingListTypeId, selectedCartItems, true, true, shoppingListAuthToken);
        } catch (IllegalArgumentException e) {
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        request.setAttribute("shoppingListId", shoppingListId);
        return "success";
    }

    // SCIPIO: Added shoppingListAuthToken, needed for anon operations
    public static String addBulkFromCart(Delegator delegator, LocalDispatcher dispatcher, ShoppingCart cart, GenericValue userLogin, String shoppingListId, String shoppingListTypeId, String[] items, boolean allowPromo, boolean append, String shoppingListAuthToken) throws IllegalArgumentException {
        String errMsg = null;
        
        // SCIPIO (2019-03-07): Ensuring shoppingListId really exists, otherwise force creation. 
        // This fixes the bug where shoppingListId passed wasn't null but didn't exist in DB. Later on, failed to create ShoppingListItems.
        GenericValue shoppingList = null;
        try {
            shoppingList = delegator.findOne("ShoppingList", false, UtilMisc.toMap("shoppingListId", shoppingListId));
        } catch (GenericEntityException e) {
            Debug.logError(e, "Problems creating getting ShoppingList [" + shoppingListId + "]", module);
            errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.cannot_create_retrieve_shopping_list", cart.getLocale());
            throw new IllegalArgumentException(errMsg);
        }

        if (items == null || items.length == 0) {
            errMsg = UtilProperties.getMessage(resource_error, "shoppinglistevents.select_items_to_add_to_list", cart.getLocale());
            throw new IllegalArgumentException(errMsg);
        }

        // SCIPIO: fixed
        //if (UtilValidate.isEmpty(shoppingList)) {
        if (shoppingList == null) {
            // create a new shopping list
            Map<String, Object> newListResult = null;
            try {
                newListResult = dispatcher.runSync("createShoppingList", UtilMisc.<String, Object>toMap("userLogin", userLogin, "productStoreId", cart.getProductStoreId(), "partyId", cart.getOrderPartyId(), "shoppingListTypeId", shoppingListTypeId, "currencyUom", cart.getCurrency()));
            } catch (GenericServiceException e) {
                Debug.logError(e, "Problems creating new ShoppingList", module);
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.cannot_create_new_shopping_list", cart.getLocale());
                throw new IllegalArgumentException(errMsg);
            }

            // check for errors
            if (ServiceUtil.isError(newListResult)) {
                throw new IllegalArgumentException(ServiceUtil.getErrorMessage(newListResult));
            }

            // get the new list id
            if (newListResult != null) {
                shoppingListId = (String) newListResult.get("shoppingListId");
                shoppingListAuthToken = (String) newListResult.get("shoppingListAuthToken"); // SCIPIO
            }

            // if no list was created throw an error
            if (UtilValidate.isEmpty(shoppingListId)) {
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.shoppingListId_is_required_parameter", cart.getLocale());
                throw new IllegalArgumentException(errMsg);
            }
        } else if (!append) {
            // SCIPIO: Verify before making any modifications
            if (!ShoppingListWorker.checkShoppingListSecurity(dispatcher.getDispatchContext(), userLogin, "UPDATE", shoppingList, shoppingListAuthToken)) {
                throw new IllegalArgumentException(UtilProperties.getMessage("CommonErrorUiLabels", "CommonPermissionErrorTryAccountSupport", cart.getLocale()));
            }
            try {
                clearListInfo(delegator, shoppingListId);
            } catch (GenericEntityException e) {
                Debug.logError(e, module);
                throw new IllegalArgumentException("Could not clear current shopping list: " + e.toString());
            }
        }

        for (String item2 : items) {
            Integer cartIdInt = null;
            try {
                cartIdInt = Integer.valueOf(item2);
            } catch (Exception e) {
                Debug.logWarning(e, UtilProperties.getMessage(resource_error,"OrderIllegalCharacterInSelectedItemField", Debug.getLogLocale()), module); // SCIPIO: log locale
            }
            if (cartIdInt != null) {
                ShoppingCartItem item = cart.findCartItem(cartIdInt);
                if (allowPromo || !item.getIsPromo()) {
                    Debug.logInfo("Adding cart item to shopping list [" + shoppingListId + "], allowPromo=" + allowPromo + ", item.getIsPromo()=" + item.getIsPromo() + ", item.getProductId()=" + item.getProductId() + ", item.getQuantity()=" + item.getQuantity(), module);
                    Map<String, Object> serviceResult = null;
                    try {
                        Map<String, Object> ctx = UtilMisc.<String, Object>toMap("userLogin", userLogin, "shoppingListId", shoppingListId, "productId", item.getProductId(), "quantity", item.getQuantity());
                        ctx.put("reservStart", item.getReservStart());
                        ctx.put("reservLength", item.getReservLength());
                        ctx.put("reservPersons", item.getReservPersons());
                        if (item.getConfigWrapper() != null) {
                            ctx.put("configId", item.getConfigWrapper().getConfigId());
                        }
                        ctx.put("shoppingListAuthToken", shoppingListAuthToken); // SCIPIO
                        serviceResult = dispatcher.runSync("createShoppingListItem", ctx);
                    } catch (GenericServiceException e) {
                        Debug.logError(e, "Problems creating ShoppingList item entity", module);
                        errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.error_adding_item_to_shopping_list", cart.getLocale());
                        throw new IllegalArgumentException(errMsg);
                    }

                    // check for errors
                    if (ServiceUtil.isError(serviceResult)) {
                        throw new IllegalArgumentException(ServiceUtil.getErrorMessage(serviceResult));
                    }
                }
            }
        }

        // return the shoppinglist id
        return shoppingListId;
    }

    // SCIPIO: Original overload
    public static String addBulkFromCart(Delegator delegator, LocalDispatcher dispatcher, ShoppingCart cart, GenericValue userLogin, String shoppingListId, String shoppingListTypeId, String[] items, boolean allowPromo, boolean append) throws IllegalArgumentException {
        return addBulkFromCart(delegator, dispatcher, cart, userLogin, shoppingListId, shoppingListTypeId, items, allowPromo, append, null);
    }

    public static String addListToCart(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");

        String shoppingListId = request.getParameter("shoppingListId");
        String includeChild = request.getParameter("includeChild");
        String prodCatalogId =  CatalogWorker.getCurrentCatalogId(request);

        // SCIPIO: Security check to make sure we have access to the list
        String shoppingListAuthToken = getShoppingListAuthTokenForList(request, shoppingListId); // SCIPIO
        GenericValue userLogin = (GenericValue) request.getAttribute("userLogin");
        if (!ShoppingListWorker.checkShoppingListSecurity(dispatcher.getDispatchContext(), userLogin, "UPDATE", shoppingListId, shoppingListAuthToken)) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage("CommonErrorUiLabels", "CommonPermissionErrorTryAccountSupport", UtilHttp.getLocale(request)));
            return "error";
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        try {
            addListToCart(delegator, dispatcher, cart, prodCatalogId, shoppingListId, (includeChild != null), true, true);
        } catch (IllegalArgumentException e) {
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    public static String addListToCart(Delegator delegator, LocalDispatcher dispatcher, ShoppingCart cart, String prodCatalogId, String shoppingListId, boolean includeChild, boolean setAsListItem, boolean append) throws java.lang.IllegalArgumentException {
        String errMsg = null;

        // no list; no add
        if (shoppingListId == null) {
            errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.choose_shopping_list", cart.getLocale());
            throw new IllegalArgumentException(errMsg);
        }

        // get the shopping list
        GenericValue shoppingList = null;
        List<GenericValue> shoppingListItems = null;
        try {
            shoppingList = EntityQuery.use(delegator).from("ShoppingList").where("shoppingListId", shoppingListId).queryOne();
            if (shoppingList == null) {
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.error_getting_shopping_list_and_items", cart.getLocale());
                throw new RecordNotFoundException(errMsg); // SCIPIO: switched IllegalArgumentException to RecordNotFoundException
            }

            shoppingListItems = shoppingList.getRelated("ShoppingListItem", null, null, false);
            if (shoppingListItems == null) {
                shoppingListItems = new LinkedList<>();
            }

            // include all items of child lists if flagged to do so
            if (includeChild) {
                List<GenericValue> childShoppingLists = shoppingList.getRelated("ChildShoppingList", null, null, false);
                for (GenericValue v : childShoppingLists) {
                    List<GenericValue> items = v.getRelated("ShoppingListItem", null, null, false);
                    shoppingListItems.addAll(items);
                }
            }

        } catch (GenericEntityException e) {
            Debug.logError(e, "Problems getting ShoppingList and ShoppingListItem records", module);
            errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.error_getting_shopping_list_and_items", cart.getLocale());
            throw new IllegalArgumentException(errMsg);
        }

        // no items; not an error; just mention that nothing was added
        if (UtilValidate.isEmpty(shoppingListItems)) {
            errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.no_items_added", cart.getLocale());
            return errMsg;
        }

        // check if we are to clear the cart first
        if (!append) {
            cart.clear();
            // Prevent the system from creating a new shopping list every time the cart is restored for anonymous user.
            cart.setAutoSaveListId(shoppingListId);
        }

        // get the survey info for all the items
        Map<String, List<String>> shoppingListSurveyInfo = getItemSurveyInfos(shoppingListItems);

        // add the items
        StringBuilder eventMessage = new StringBuilder();
        for (GenericValue shoppingListItem : shoppingListItems) {
            String productId = shoppingListItem.getString("productId");
            BigDecimal quantity = shoppingListItem.getBigDecimal("quantity");
            Timestamp reservStart = shoppingListItem.getTimestamp("reservStart");
            BigDecimal reservLength = shoppingListItem.getBigDecimal("reservLength");
            BigDecimal reservPersons = shoppingListItem.getBigDecimal("reservPersons");
            String configId = shoppingListItem.getString("configId");
            try {
                String listId = shoppingListItem.getString("shoppingListId");
                String itemId = shoppingListItem.getString("shoppingListItemSeqId");

                Map<String, Object> attributes = new HashMap<>();
                // list items are noted in the shopping cart
                if (setAsListItem) {
                    attributes.put("shoppingListId", listId);
                    attributes.put("shoppingListItemSeqId", itemId);
                }

                // check if we have existing survey responses to append
                if (shoppingListSurveyInfo.containsKey(listId + "." + itemId) && UtilValidate.isNotEmpty(shoppingListSurveyInfo.get(listId + "." + itemId))) {
                    attributes.put("surveyResponses", shoppingListSurveyInfo.get(listId + "." + itemId));
                }

                ProductConfigWrapper configWrapper = null;
                if (UtilValidate.isNotEmpty(configId)) {
                    configWrapper = ProductConfigWorker.loadProductConfigWrapper(delegator, dispatcher, configId, productId, cart.getProductStoreId(), prodCatalogId, cart.getWebSiteId(), cart.getCurrency(), cart.getLocale(), cart.getAutoUserLogin());
                }
                // TODO: add code to check for survey response requirement

                // i cannot get the addOrDecrease function to accept a null reservStart field: i get a null pointer exception a null constant works....
                if (reservStart == null) {
                       cart.addOrIncreaseItem(productId, null, quantity, null, null, null, null, null, null, attributes, prodCatalogId, configWrapper, null, null, null, dispatcher);
                } else {
                    cart.addOrIncreaseItem(productId, null, quantity, reservStart, reservLength, reservPersons, null, null, null, null, null, attributes, prodCatalogId, configWrapper, null, null, null, dispatcher);
                }
                Map<String, Object> messageMap = UtilMisc.<String, Object>toMap("productId", productId);
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.added_product_to_cart", messageMap, cart.getLocale());
                eventMessage.append(errMsg).append("\n");
            } catch (CartItemModifyException e) {
                Debug.logWarning(e, UtilProperties.getMessage(resource_error,"OrderProblemsAddingItemFromListToCart", Debug.getLogLocale())); // SCIPIO: log locale
                Map<String, Object> messageMap = UtilMisc.<String, Object>toMap("productId", productId);
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.problem_adding_product_to_cart", messageMap, cart.getLocale());
                eventMessage.append(errMsg).append("\n");
            } catch (ItemNotFoundException e) {
                Debug.logWarning(e, UtilProperties.getMessage(resource_error,"OrderProductNotFound", Debug.getLogLocale())); // SCIPIO: log locale
                Map<String, Object> messageMap = UtilMisc.<String, Object>toMap("productId", productId);
                errMsg = UtilProperties.getMessage(resource_error,"shoppinglistevents.problem_adding_product_to_cart", messageMap, cart.getLocale());
                eventMessage.append(errMsg).append("\n");
            }
        }

        if (eventMessage.length() > 0) {
            return eventMessage.toString();
        }

        // all done
        return ""; // no message to return; will simply reply as success
    }

    public static String replaceShoppingListItem(HttpServletRequest request, HttpServletResponse response) {
        String quantityStr = request.getParameter("quantity");

        // just call the updateShoppingListItem service
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
        Locale locale = UtilHttp.getLocale(request);

        BigDecimal quantity = null;
        try {
            quantity = new BigDecimal(quantityStr);
        } catch (Exception e) {
            // do nothing, just won't pass to service if it is null
            //Debug.logError(e, module); // SCIPIO: 2018-10-09: Don't log this as exception
        }

        Map<String, Object> serviceInMap = new HashMap<String, Object>();
        serviceInMap.put("shoppingListId", request.getParameter("shoppingListId"));
        serviceInMap.put("shoppingListItemSeqId", request.getParameter("shoppingListItemSeqId"));
        serviceInMap.put("productId", request.getParameter("add_product_id"));
        serviceInMap.put("userLogin", userLogin);
        checkSetShoppingListAuthTokenForService(request, serviceInMap); // SCIPIO
        if (quantity != null) serviceInMap.put("quantity", quantity);
        Map<String, Object> result = null;
        try {
            result = dispatcher.runSync("updateShoppingListItem", serviceInMap);
        } catch (GenericServiceException e) {
            String errMsg = UtilProperties.getMessage(resource_error,"shoppingListEvents.error_calling_update", locale) + ": "  + e.toString();
            request.setAttribute("_ERROR_MESSAGE_", errMsg);
            String errorMsg = "Error calling the updateShoppingListItem in handleShoppingListItemVariant: " + e.toString();
            Debug.logError(e, errorMsg, module);
            return "error";
        }

        ServiceUtil.getMessages(request, result, "", "", "", "", "", "", "");
        if ("error".equals(result.get(ModelService.RESPONSE_MESSAGE))) {
            return "error";
        } else {
            return "success";
        }
    }

    /**
     * Finds or creates a specialized (auto-save) shopping list used to record shopping bag contents between user visits.
     * <p>
     * SCIPIO: WARNING: Do not pass partyId from unverified input without checking {@link ShoppingListWorker#checkShoppingListSecurity}.
     */
    public static String getAutoSaveListId(Delegator delegator, LocalDispatcher dispatcher, String partyId, GenericValue userLogin, String productStoreId) throws GenericEntityException, GenericServiceException {
        if (partyId == null && userLogin != null) {
            partyId = userLogin.getString("partyId");
        }

        String autoSaveListId = null;
        GenericValue list = null;
        // TODO: add sorting, just in case there are multiple...
        if (partyId != null) {
            Map<String, Object> findMap = UtilMisc.<String, Object>toMap("partyId", partyId, "productStoreId", productStoreId, "shoppingListTypeId", "SLT_SPEC_PURP", "listName", PERSISTANT_LIST_NAME);
            List<GenericValue> existingLists = EntityQuery.use(delegator).from("ShoppingList").where(findMap).queryList();
            Debug.logInfo("Finding existing auto-save shopping list with:\nfindMap: " + findMap + "\nlists: " + existingLists, module);

            if (UtilValidate.isNotEmpty(existingLists)) {
                list = EntityUtil.getFirst(existingLists);
                autoSaveListId = list.getString("shoppingListId");
            }
        }
        if (list == null && dispatcher != null) {
            Map<String, Object> listFields = UtilMisc.<String, Object>toMap("userLogin", userLogin, "productStoreId", productStoreId, "shoppingListTypeId", "SLT_SPEC_PURP", "listName", PERSISTANT_LIST_NAME);
            Map<String, Object> newListResult = dispatcher.runSync("createShoppingList", listFields);
            if (ServiceUtil.isError(newListResult)) {
                String errorMessage = ServiceUtil.getErrorMessage(newListResult);
                Debug.logError(errorMessage, module);
                return null;
            }
            if (newListResult != null) {
                autoSaveListId = (String) newListResult.get("shoppingListId");
            }
        }

        return autoSaveListId;
    }

    /**
     * Fills the specialized shopping list with the current shopping cart if one exists (if not leaves it alone)
     */
    public static void fillAutoSaveList(ShoppingCart cart, LocalDispatcher dispatcher) throws GeneralException {
        if (cart != null && dispatcher != null) {
            GenericValue userLogin = ShoppingListEvents.getCartUserLogin(cart);
            Delegator delegator = cart.getDelegator();
            String autoSaveListId = cart.getAutoSaveListId();
            if (autoSaveListId == null) {
                autoSaveListId = getAutoSaveListId(delegator, dispatcher, null, userLogin, cart.getProductStoreId());
                cart.setAutoSaveListId(autoSaveListId);
            }
            GenericValue shoppingList = EntityQuery.use(delegator).from("ShoppingList").where("shoppingListId", autoSaveListId).queryOne();
            Integer currentListSize = 0;
            if (UtilValidate.isNotEmpty(shoppingList)) {
                List<GenericValue> shoppingListItems = shoppingList.getRelated("ShoppingListItem", null, null, false);
                if (UtilValidate.isNotEmpty(shoppingListItems)) {
                    currentListSize = shoppingListItems.size();
                }
            }
            // SCIPIO: NOTE: It is usually WRONG to get the shoppingListAuthToken from ShoppingList, but in this case the shoppingListId only comes from internal or pre-verified sources
            // (because callers are expected to validate autoSaveListId before calling cart.setAutoSaveListId(autoSaveListId))
            String shoppingListAuthToken = shoppingList.getString("shoppingListAuthToken");

            try {
                String[] itemsArray = makeCartItemsArray(cart);
                if (itemsArray.length != 0) {
                    addBulkFromCart(delegator, dispatcher, cart, userLogin, autoSaveListId, null, itemsArray, false, false, shoppingListAuthToken);
                } else if (currentListSize != 0) {
                    clearListInfo(delegator, autoSaveListId);
                }
            } catch (IllegalArgumentException e) {
                throw new GeneralException(e.getMessage(), e);
            }
        }
    }

    /**
     * Saves the shopping cart to the specialized (auto-save) shopping list
     */
    public static String saveCartToAutoSaveList(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        try {
            fillAutoSaveList(cart, dispatcher);
        } catch (GeneralException e) {
            Debug.logError(e, "Error saving the cart to the auto-save list: " + e.toString(), module);
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * Restores the specialized (auto-save) shopping list back into the shopping cart
     */
    public static String restoreAutoSaveList(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        GenericValue productStore = ProductStoreWorker.getProductStore(request);

        if (!ProductStoreWorker.autoSaveCart(productStore)) {
            // if auto-save is disabled just return here
            return "success";
        }

        HttpSession session = request.getSession();
        
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        // safety check for missing required parameter.
        if (cart.getWebSiteId() == null) {
            cart.setWebSiteId(WebSiteWorker.getWebSiteId(request));
        }

        // locate the user's identity
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        if (userLogin == null) {
            userLogin = (GenericValue) session.getAttribute("autoUserLogin");
        }

        // find the list ID
        String autoSaveListId = cart.getAutoSaveListId();
        if (autoSaveListId == null) {
            try {
                autoSaveListId = getAutoSaveListId(delegator, dispatcher, null, userLogin, cart.getProductStoreId());
            } catch (GeneralException e) {
                Debug.logError(e, module);
            }
            cart.setAutoSaveListId(autoSaveListId);
        } else if (userLogin != null) {
            String existingAutoSaveListId = null;
            try {
                existingAutoSaveListId = getAutoSaveListId(delegator, dispatcher, null, userLogin, cart.getProductStoreId());
            } catch (GeneralException e) {
                Debug.logError(e, module);
            }
            if (existingAutoSaveListId != null) {
                if (!existingAutoSaveListId.equals(autoSaveListId)) {
                    // Replace with existing shopping list
                    cart.setAutoSaveListId(existingAutoSaveListId);
                    autoSaveListId = existingAutoSaveListId;
                    cart.setLastListRestore(null);
                } else {
                    // CASE: User first login and logout and then re-login again. This condition does not require a restore at all
                    // because at this point items in the cart and the items in the shopping list are same so just return.
                    return "success";
                }
            }
        }

        // check to see if we are okay to load this list
        java.sql.Timestamp lastLoad = cart.getLastListRestore();
        boolean okayToLoad = autoSaveListId == null ? false : (lastLoad == null ? true : false);
        if (!okayToLoad && lastLoad != null) {
            GenericValue shoppingList = null;
            try {
                shoppingList = EntityQuery.use(delegator).from("ShoppingList").where("shoppingListId", autoSaveListId).queryOne();
            } catch (GenericEntityException e) {
                Debug.logError(e, module);
            }
            if (shoppingList != null) {
                java.sql.Timestamp lastModified = shoppingList.getTimestamp("lastAdminModified");
                if (lastModified != null) {
                    if (lastModified.after(lastLoad)) {
                        okayToLoad = true;
                    }
                    if (cart.size() == 0 && lastModified.after(cart.getCartCreatedTime())) {
                        okayToLoad = true;
                    }
                }
            }
        }

        // load (restore) the list of we have determined it is okay to load
        if (okayToLoad) {
            String prodCatalogId = CatalogWorker.getCurrentCatalogId(request);
            try {
                addListToCart(delegator, dispatcher, cart, prodCatalogId, autoSaveListId, false, false, userLogin != null ? true : false);
                cart.setLastListRestore(UtilDateTime.nowTimestamp());
                cartUpdate.commit(cart); // SCIPIO
            } catch(RecordNotFoundException e) { // SCIPIO: log this as warning because it is a "normal" case when we receive old cookies
                Debug.logWarning("Auto-save shopping list not found for shoppingListId [" + autoSaveListId + "]; abandoning cart changes", module);
            } catch (IllegalArgumentException e) {
                Debug.logError(e, "Could not load auto-save shopping list to cart; abandoning cart changes", module); // SCIPIO: Added mesasge
            }
        } else {
            cartUpdate.commit(cart); // SCIPIO
        }

        }
        return "success";
    }

    /**
     * Remove all items from the given list.
     */
    public static int clearListInfo(Delegator delegator, String shoppingListId) throws GenericEntityException {
        // remove the survey responses first
        delegator.removeByAnd("ShoppingListItemSurvey", UtilMisc.toMap("shoppingListId", shoppingListId));

        // next remove the items
        return delegator.removeByAnd("ShoppingListItem", UtilMisc.toMap("shoppingListId", shoppingListId));
    }

    /**
     * Creates records for survey responses on survey items
     */
    public static int makeListItemSurveyResp(Delegator delegator, GenericValue item, List<String> surveyResps) throws GenericEntityException {
        if (UtilValidate.isNotEmpty(surveyResps)) {
            int count = 0;
            for (String responseId : surveyResps) {
                GenericValue listResp = delegator.makeValue("ShoppingListItemSurvey");
                listResp.set("shoppingListId", item.getString("shoppingListId"));
                listResp.set("shoppingListItemSeqId", item.getString("shoppingListItemSeqId"));
                listResp.set("surveyResponseId", responseId);
                delegator.create(listResp);
                count++;
            }
            return count;
        }
        return -1;
    }

    /**
     * Returns Map keyed on item sequence ID containing a list of survey response IDs
     */
    public static Map<String, List<String>> getItemSurveyInfos(List<GenericValue> items) {
        Map<String, List<String>> surveyInfos = new HashMap<>();
        if (UtilValidate.isNotEmpty(items)) {
            for (GenericValue item : items) {
                String listId = item.getString("shoppingListId");
                String itemId = item.getString("shoppingListItemSeqId");
                surveyInfos.put(listId + "." + itemId, getItemSurveyInfo(item));
            }
        }

        return surveyInfos;
    }

    /**
     * Returns a list of survey response IDs for a shopping list item
     */
    public static List<String> getItemSurveyInfo(GenericValue item) {
        List<String> responseIds = new LinkedList<>();
        List<GenericValue> surveyResp = null;
        try {
            surveyResp = item.getRelated("ShoppingListItemSurvey", null, null, false);
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }

        if (UtilValidate.isNotEmpty(surveyResp)) {
            for (GenericValue resp : surveyResp) {
                responseIds.add(resp.getString("surveyResponseId"));
            }
        }

        return responseIds;
    }

    private static GenericValue getCartUserLogin(ShoppingCart cart) {
        GenericValue ul = cart.getUserLogin();
        if (ul == null) {
            ul = cart.getAutoUserLogin();
        }
        return ul;
    }

    private static String[] makeCartItemsArray(ShoppingCart cart) {
        int len = cart.size();
        String[] arr = new String[len];
        for (int i = 0; i < len; i++) {
            arr[i] = Integer.toString(i);
        }
        return arr;
    }

    /**
     * Create the guest cookies for a shopping list
     */
    public static String createGuestShoppingListCookies(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession(true);
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        String guestShoppingUserName = getAutoSaveShoppingListCookieName(request); // SCIPIO
        String productStoreId = ProductStoreWorker.getProductStoreId(request);
        int cookieAge = (60 * 60 * 24 * 30);
        String autoSaveListId = null;
        String shoppingListAuthToken = null; // SCIPIO: shoppingListAuthToken for lists with no partyId
        Cookie[] cookies = request.getCookies();

        // check userLogin
        if (userLogin != null) {
            String partyId = userLogin.getString("partyId");
            if (UtilValidate.isEmpty(partyId)) {
                return "success";
            }
        }

        // find shopping list ID
        ShoppingListCookieInfo autoSaveCookieInfo = ShoppingListCookieInfo.fromCookie(request, guestShoppingUserName);
        // SCIPIO: We must do a security check here, because the autoSaveListId is just read out of the cart by other code
        if (autoSaveCookieInfo != null) {
            GenericValue shoppingList = null;
            try {
                // DEV NOTE: I have left out the .cache() call here, because this is only run on first-visit, but .cache(true) might be ok
                // since the ShoppingList.partyId and shoppingListAuthToken fields never get modified...
                shoppingList = delegator.from("ShoppingList").where("shoppingListId", autoSaveCookieInfo.getShoppingListId()).queryOne();
            } catch (GenericEntityException e) {
                Debug.logError("createGuestShoppingListCookies: Could not get ShoppingList '" + autoSaveCookieInfo.getShoppingListId() + "'", module);
            }
            if (shoppingList == null) {
                if (Debug.verboseOn()) {
                    Debug.logVerbose("createGuestShoppingListCookies: Cookies pointed to non-existent ShoppingList '" + autoSaveCookieInfo.getShoppingListId() + "'", module);
                }
            } else {
                if (ShoppingListWorker.checkShoppingListSecurity(dispatcher.getDispatchContext(), userLogin, "UPDATE", shoppingList, autoSaveCookieInfo.getAuthToken())) {
                    autoSaveListId = autoSaveCookieInfo.getShoppingListId();
                    shoppingListAuthToken = autoSaveCookieInfo.getAuthToken();
                } else {
                    Debug.logWarning("createGuestShoppingListCookies: Could not authenticate user '"
                            + (userLogin != null ? userLogin.getString("partyId") : "(anonymous)")
                            + "' to use ShoppingList '" + autoSaveCookieInfo.getShoppingListId() + "' from cookies", module);
                }
            }
        }

        // clear the auto-save info
        if (ProductStoreWorker.autoSaveCart(delegator, productStoreId)) {
            if (UtilValidate.isEmpty(autoSaveListId)) {
                try {
                    Map<String, Object> listFields = UtilMisc.<String, Object>toMap("userLogin", userLogin, "productStoreId", productStoreId, "shoppingListTypeId", "SLT_SPEC_PURP", "listName", PERSISTANT_LIST_NAME);
                    Map<String, Object> newListResult = dispatcher.runSync("createShoppingList", listFields);
                    if (ServiceUtil.isError(newListResult)) {
                        String errorMessage = ServiceUtil.getErrorMessage(newListResult);
                        Debug.logError(errorMessage, module);
                        return null;
                    }
                    if (newListResult != null) {
                        autoSaveListId = (String) newListResult.get("shoppingListId");
                        shoppingListAuthToken = (String) newListResult.get("shoppingListAuthToken"); // SCIPIO
                    }
                } catch (GeneralException e) {
                    Debug.logError(e, module);
                }
                // SCIPIO: include shoppingListAuthToken
                //Cookie guestShoppingListCookie = new Cookie(guestShoppingUserName, autoSaveListId);
                Cookie guestShoppingListCookie = new Cookie(guestShoppingUserName, makeShoppingListCookieValue(autoSaveListId, shoppingListAuthToken));
                guestShoppingListCookie.setMaxAge(cookieAge);
                guestShoppingListCookie.setPath("/");
                guestShoppingListCookie.setSecure(true);
                guestShoppingListCookie.setHttpOnly(true);
                response.addCookie(guestShoppingListCookie);
            }
        }
        if (UtilValidate.isNotEmpty(autoSaveListId)) {
            try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            ShoppingCart cart = cartUpdate.getCartForUpdate();

            cart.setAutoSaveListId(autoSaveListId);

            cartUpdate.commit(cart); // SCIPIO
            }
        }
        return "success";
    }

    private static String makeShoppingListCookieValue(String autoSaveListId, String shoppingListAuthToken) { // shoppingListId::shoppingListAuthToken
        if (autoSaveListId == null) {
            return null;
        }
        return autoSaveListId + (shoppingListAuthToken != null ? "::" + shoppingListAuthToken : "");
    }

    /**
     * Clear the guest cookies for a shopping list
     */
    public static String clearGuestShoppingListCookies(HttpServletRequest request, HttpServletResponse response) {
        Properties systemProps = System.getProperties();
        String guestShoppingUserName = "GuestShoppingListId_" + systemProps.getProperty("user.name").replace(" ", "_");
        Cookie guestShoppingListCookie = new Cookie(guestShoppingUserName, null);
        guestShoppingListCookie.setMaxAge(0);
        guestShoppingListCookie.setPath("/");
        response.addCookie(guestShoppingListCookie);
        return "success";
    }

    public static class ShoppingListCookieInfo { // SCIPIO
        private final String shoppingListId;
        private final String authToken;
        private final Cookie cookie; // may be null if not exist yet

        public ShoppingListCookieInfo(String shoppingListId, String authToken, Cookie cookie) {
            this.shoppingListId = shoppingListId;
            this.authToken = authToken;
            this.cookie = cookie;
        }

        private static ShoppingListCookieInfo fromStringRepr(String value, Cookie cookie) {
            if (value == null) {
                return null;
            }
            String[] parts = value.split("::", 2);
            if (parts.length >= 2 && UtilValidate.isNotEmpty(parts[0])) {
                return new ShoppingListCookieInfo(parts[0], UtilValidate.isNotEmpty(parts[1]) ? parts[1] : null, cookie);
            }
            return null;
        }

        public static ShoppingListCookieInfo fromStringRepr(String value) {
            return fromStringRepr(value, null);
        }

        public static ShoppingListCookieInfo fromCookie(Cookie cookie) {
            return fromStringRepr(cookie != null ? cookie.getValue() : null, cookie);
        }

        public static ShoppingListCookieInfo fromCookie(HttpServletRequest request, String cookieName) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie: cookies) {
                    if (cookie.getName().equals(cookieName)) {
                        return ShoppingListCookieInfo.fromCookie(cookie);
                    }
                }
            }
            return null;
        }

        public String getShoppingListId() { return shoppingListId; }
        public String getAuthToken() { return authToken; }
        public Cookie getCookie() { return cookie; }
    }

    public static String  getAutoSaveShoppingListCookieName(HttpServletRequest request) { // SCIPIO
        return "GuestShoppingListId_" + System.getProperties().getProperty("user.name").replace(" ", "_");
    }

    public static ShoppingListCookieInfo getAutoSaveShoppingListCookieInfo(HttpServletRequest request) { // SCIPIO
        return ShoppingListCookieInfo.fromCookie(request, getAutoSaveShoppingListCookieName(request));
    }

    public static String getShoppingListAuthTokenForList(HttpServletRequest request, String shoppingListId) { // SCIPIO
        if (UtilValidate.isEmpty(shoppingListId)) {
            return null;
        }
        String authToken = (String) request.getAttribute("shoppingListAuthToken");
        if (authToken == null) {
            authToken = request.getParameter("shoppingListAuthToken");
        }
        if (UtilValidate.isNotEmpty(authToken)) {
            return authToken;
        } else {
            authToken = null;
        }
        ShoppingListCookieInfo cookieInfo = getAutoSaveShoppingListCookieInfo(request);
        if (cookieInfo != null && shoppingListId.equals(cookieInfo.getShoppingListId()) && UtilValidate.isNotEmpty(cookieInfo.getAuthToken())) {
            authToken = cookieInfo.getAuthToken();
        }
        return authToken;
    }

    public static void checkSetShoppingListAuthTokenForService(HttpServletRequest request, Map<String, Object> servCtx) { // SCIPIO
        if (!servCtx.containsKey("shoppingListAuthToken")) {
            String authToken = getShoppingListAuthTokenForList(request, (String) servCtx.get("shoppingListId"));
            if (authToken != null) {
                servCtx.put("shoppingListAuthToken", authToken);
            }
        }
    }

    public static String updateShoppingList(HttpServletRequest request, HttpServletResponse response) throws GenericServiceException { // SCIPIO
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "updateShoppingList");
        checkSetShoppingListAuthTokenForService(request, servCtx);
        return EventUtil.runServiceAsEvent(request, response, "updateShoppingList", servCtx);
    }

    public static String createShoppingListFromOrder(HttpServletRequest request, HttpServletResponse response) throws GenericServiceException { // SCIPIO
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "makeShoppingListFromOrder");
        checkSetShoppingListAuthTokenForService(request, servCtx);
        return EventUtil.runServiceAsEvent(request, response, "makeShoppingListFromOrder", servCtx);
    }

    public static String addItemToShoppingList(HttpServletRequest request, HttpServletResponse response) throws GenericServiceException { // SCIPIO
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "createShoppingListItem");
        checkSetShoppingListAuthTokenForService(request, servCtx);
        return EventUtil.runServiceAsEvent(request, response, "createShoppingListItem", servCtx);
    }

    public static String updateShoppingListItem(HttpServletRequest request, HttpServletResponse response) throws GenericServiceException { // SCIPIO
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "updateShoppingListItem");
        checkSetShoppingListAuthTokenForService(request, servCtx);
        return EventUtil.runServiceAsEvent(request, response, "updateShoppingListItem", servCtx);
    }

    public static String removeFromShoppingList(HttpServletRequest request, HttpServletResponse response) throws GenericServiceException { // SCIPIO
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "removeShoppingListItem");
        checkSetShoppingListAuthTokenForService(request, servCtx);
        return EventUtil.runServiceAsEvent(request, response, "removeShoppingListItem", servCtx);
    }
}
