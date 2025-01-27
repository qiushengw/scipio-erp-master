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
package org.ofbiz.base.component;

import java.io.File;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.ofbiz.base.container.ContainerConfig;
import org.ofbiz.base.container.ContainerConfig.Container;
import org.ofbiz.base.container.ContainerException;
import org.ofbiz.base.location.FlexibleLocation;
import org.ofbiz.base.util.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * An object that models the <code>&lt;ofbiz-component&gt;</code> element.
 *
 * @see <code>ofbiz-component.xsd</code>
 */
public final class ComponentConfig {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String SCIPIO_COMPONENT_XML_FILENAME = "scipio-component.xml"; // SCIPIO
    public static final String SCIPIO_THEME_XML_FILENAME = "scipio-theme.xml"; // SCIPIO
    public static final String OFBIZ_COMPONENT_XML_FILENAME = "ofbiz-component.xml";
    /* Note: These Maps are not UtilCache instances because there is no strategy or implementation for reloading components.
     * Also, we are using LinkedHashMap to maintain insertion order - which client code depends on. This means
     * we will need to use synchronization code because there is no concurrent implementation of LinkedHashMap.
     */
    // SCIPIO: 2018-10-18: componentConfigCache is now an immutable cache and we replace it with copies, to avoid blocking reads
    // TODO: REVIEW: For some of these variables the volatile keyword may not strictly be necessary, but playing it safe for now.
    //private static final ComponentConfigCache componentConfigCache = new ComponentConfigCache();
    private static volatile ComponentConfigCache componentConfigCache = new ComponentConfigCache();
    // SCIPIO: 2018-10-18: new disabled component cache; these are NOT placed in componentConfigCache!
    private static volatile ComponentConfigCache disabledComponentConfigCache = new ComponentConfigCache();
    private static final Object componentConfigCacheSyncObj = new Object();
    // SCIPIO: 2018-10-18: fix bad sync patterns for this using unmodifiable map
    //private static final Map<String, List<WebappInfo>> serverWebApps = new LinkedHashMap<>();
    private static volatile Map<String, List<WebappInfo>> serverWebApps = Collections.unmodifiableMap(new LinkedHashMap<>());
    private static final Object serverWebAppsSyncObj = new Object();
    /**
     * Maps component::webapp name of the old webapp to the new webapp.
     *
     * <p>SCIPIO: Added.</p>
     */
    private static volatile Map<String, WebappInfo> overriddenWebappNameMap = Collections.emptyMap();
    private static final Object overriddenWebappsSyncObj = new Object();

    public static Boolean componentExists(String componentName) {
        Assert.notEmpty("componentName", componentName);
        return componentConfigCache.fromGlobalName(componentName) != null;
    }

    public static List<ClasspathInfo> getAllClasspathInfos() {
        return getAllClasspathInfos(null);
    }

    public static List<ClasspathInfo> getAllClasspathInfos(String componentName) {
        List<ClasspathInfo> classpaths = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                classpaths.addAll(cc.getClasspathInfos());
            }
        }
        return classpaths;
    }

    /**
     * Gets all ENABLED components (enabled="true" or implicit).
     *
     * <p>SCIPIO: NOTE: This EXCLUDES components marked enabled="false" and always has, but was poorly
     * named historically. This is the same as {@link #getEnabledComponents()}. There is inconsistency with
     * {@link #getComponentConfig}.</p>
     */
    public static Collection<ComponentConfig> getAllComponents() {
        return componentConfigCache.values();
    }

    /**
     * Get all components resources directly under "dir" folders, typically config/ and dtd/ (non-recursive).
     *
     * <p>SCIPIO: 2.1.0: Added for global label namespace support.</p>
     */
    public static List<URL> getAllComponentsRootResourceFileURLs(java.io.FilenameFilter fnFilter) {
        if (fnFilter == null) {
            fnFilter = FileUtil.TRUE_FILENAME_FILTER;
        }
        List<URL> resourceURLs = new ArrayList<>();
        for(ComponentConfig cc : ComponentConfig.getAllComponents()) {
            for (ComponentConfig.ClasspathInfo ci : cc.getClasspathInfos()) {
                if ("dir".equals(ci.getType())) {
                    File configDir = new File(cc.getRootLocation(), ci.location);
                    File[] files = configDir.listFiles(fnFilter);
                    if (files != null && files.length > 0) {
                        for(File file : files) {
                            if (file.isFile()) {
                                try {
                                    resourceURLs.add(file.toURI().toURL());
                                } catch (MalformedURLException e) {
                                    Debug.logError(e, module);
                                }
                            }
                        }
                    }
                }
            }
        }
        return resourceURLs;
    }

    /**
     * Gets all ENABLED components (enabled="true" or implicit).
     *
     * <p>NOTE: This is the same as {@link #getAllComponents()}, added for clarity.</p>
     *
     * <p>SCIPIO: Added 2018-10-18.</p>
     */
    public static Collection<ComponentConfig> getEnabledComponents() {
        return componentConfigCache.values();
    }

    /**
     * Gets all DISABLED components (enabled="false").
     *
     * <p>SCIPIO: Added 2018-10-18.</p>
     */
    public static Collection<ComponentConfig> getDisabledComponents() {
        return disabledComponentConfigCache.values();
    }

    public static List<ContainerConfig.Container> getAllContainers() {
        return getAllContainers(null);
    }

    public static List<ContainerConfig.Container> getAllContainers(String componentName) {
        List<ContainerConfig.Container> containers = new ArrayList<ContainerConfig.Container>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                containers.addAll(cc.getContainers());
            }
        }
        return containers;
    }

    public static List<EntityResourceInfo> getAllEntityResourceInfos(String type) {
        return getAllEntityResourceInfos(type, null);
    }

    public static List<EntityResourceInfo> getAllEntityResourceInfos(String type, String componentName) {
        List<EntityResourceInfo> entityInfos = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                List<EntityResourceInfo> ccEntityInfoList = cc.getEntityResourceInfos();
                if (UtilValidate.isEmpty(type)) {
                    entityInfos.addAll(ccEntityInfoList);
                } else {
                    for (EntityResourceInfo entityResourceInfo : ccEntityInfoList) {
                        if (type.equals(entityResourceInfo.type)) {
                            entityInfos.add(entityResourceInfo);
                        }
                    }
                }
            }
        }
        return entityInfos;
    }

    public static List<KeystoreInfo> getAllKeystoreInfos() {
        return getAllKeystoreInfos(null);
    }

    public static List<KeystoreInfo> getAllKeystoreInfos(String componentName) {
        List<KeystoreInfo> keystoreInfos = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                keystoreInfos.addAll(cc.getKeystoreInfos());
            }
        }
        return keystoreInfos;
    }

    public static List<ServiceResourceInfo> getAllServiceResourceInfos(String type) {
        return getAllServiceResourceInfos(type, null);
    }

    public static List<ServiceResourceInfo> getAllServiceResourceInfos(String type, String componentName) {
        List<ServiceResourceInfo> serviceInfos = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                List<ServiceResourceInfo> ccServiceInfoList = cc.getServiceResourceInfos();
                if (UtilValidate.isEmpty(type)) {
                    serviceInfos.addAll(ccServiceInfoList);
                } else {
                    for (ServiceResourceInfo serviceResourceInfo : ccServiceInfoList) {
                        if (type.equals(serviceResourceInfo.type)) {
                            serviceInfos.add(serviceResourceInfo);
                        }
                    }
                }
            }
        }
        return serviceInfos;
    }

    public static List<TestSuiteInfo> getAllTestSuiteInfos() {
        return getAllTestSuiteInfos(null);
    }

    public static List<TestSuiteInfo> getAllTestSuiteInfos(String componentName) {
        List<TestSuiteInfo> testSuiteInfos = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                testSuiteInfos.addAll(cc.getTestSuiteInfos());
            }
        }
        return testSuiteInfos;
    }

    public static List<WebappInfo> getAllWebappResourceInfos() {
        return getAllWebappResourceInfos(null);
    }

    public static List<WebappInfo> getAllWebappResourceInfos(String componentName) {
        List<WebappInfo> webappInfos = new ArrayList<>();
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                webappInfos.addAll(cc.getWebappInfos());
            }
        }
        return webappInfos;
    }

    /**
     * Returns webapp infos by context root (mount-point).
     *
     * <p>SCIPIO: Added.</p>
     */
    private static Map<String, List<WebappInfo>> getAllWebappResourceInfosByContextRoot(Collection<ComponentConfig> componentList, String componentName) {
        Map<String, List<WebappInfo>> contextRootWebappInfos = new HashMap<>();
        for (ComponentConfig cc : componentList) {
            if (componentName == null || componentName.equals(cc.getComponentName())) {
                for(WebappInfo webappInfo : cc.getWebappInfos()) {
                    List<WebappInfo> webappInfoList = contextRootWebappInfos.get(webappInfo.getContextRoot());
                    if (webappInfoList == null) {
                        webappInfoList = new ArrayList<>();
                        contextRootWebappInfos.put(webappInfo.getContextRoot(), webappInfoList);
                    }
                    webappInfoList.add(webappInfo);
                }
            }
        }
        return contextRootWebappInfos;
    }

    public static List<WebappInfo> getAppBarWebInfos(String serverName) {
        return ComponentConfig.getAppBarWebInfos(serverName, null, null);
    }

    public static List<WebappInfo> getAppBarWebInfos(String serverName, Comparator<? super String> comp, String menuName) {
        String serverWebAppsKey = serverName + menuName;
        List<WebappInfo> webInfos = null;
        //synchronized (serverWebApps) { // SCIPIO: 2018-10-18: use immutable cache copies instead
        webInfos = serverWebApps.get(serverWebAppsKey);
        //}
        if (webInfos == null) {
            synchronized (serverWebAppsSyncObj) { // SCIPIO: 2018-10-18: only sync on writes, ok with immutable cache
                webInfos = serverWebApps.get(serverWebAppsKey);
                if (webInfos == null) {
                    Map<String, WebappInfo> tm = null;
                    // use a TreeMap to sort the components alpha by title
                    if (comp != null) {
                        tm = new TreeMap<>(comp);
                    } else {
                        tm = new TreeMap<>();
                    }
                    for (ComponentConfig cc : getAllComponents()) {
                        for (WebappInfo wInfo : cc.getWebappInfos()) {
                            String key = UtilValidate.isNotEmpty(wInfo.position) ? wInfo.position : wInfo.title;
                            if (serverName.equals(wInfo.server) && wInfo.getAppBarDisplay()) {
                                if (UtilValidate.isNotEmpty(menuName)) {
                                    if (menuName.equals(wInfo.menuName)) {
                                        tm.put(key, wInfo);
                                    }
                                } else {
                                    tm.put(key, wInfo);
                                }
                            }
                        }
                    }
                    webInfos = new ArrayList<>(tm.size());
                    webInfos.addAll(tm.values());
                    webInfos = Collections.unmodifiableList(webInfos);
                    // SCIPIO: 2018-10-18: make unmodifiable cache copy
                    //synchronized (serverWebApps) {
                    //    // We are only preventing concurrent modification, we are not guaranteeing a singleton.
                    //    serverWebApps.put(serverWebAppsKey, webInfos);
                    //}
                    Map<String, List<WebappInfo>> newServerWebApps = new LinkedHashMap<>(serverWebApps);
                    newServerWebApps.put(serverWebAppsKey, webInfos);
                    serverWebApps = Collections.unmodifiableMap(newServerWebApps);
                }
            }
        }
        return webInfos;
    }

    public static List<WebappInfo> getAppBarWebInfos(String serverName, String menuName) {
        return getAppBarWebInfos(serverName, null, menuName);
    }

    /**
     * Returns the component by global name.
     *
     * <p>SCIPIO: NOTE: Per legacy behavior, this returns even disabled components (unlike {@link #getAllComponents()}).</p>
     */
    public static ComponentConfig getComponentConfig(String globalName) throws ComponentException {
        // TODO: we need to look up the rootLocation from the container config, or this will blow up
        return getComponentConfig(globalName, null);
    }

    /**
     * Returns the component by global name.
     *
     * <p>SCIPIO: NOTE: Per legacy behavior, this returns even disabled components (unlike {@link #getAllComponents()}).</p>
     */
    public static ComponentConfig getComponentConfig(String globalName, String rootLocation) throws ComponentException {
        return getComponentConfig(globalName, rootLocation, false);
    }

    /**
     * Returns the component by global name.
     *
     * <p>SCIPIO: NOTE: Per legacy behavior, this returns even disabled components (unlike {@link #getAllComponents()}).</p>
     */
    public static ComponentConfig getComponentConfig(String globalName, String rootLocation, boolean existingOnly) throws ComponentException {
        ComponentConfig componentConfig = checkComponentConfig(globalName, rootLocation); // SCIPIO: 2018-10-18: delegated
        if (componentConfig != null) {
            return componentConfig;
        } else if (existingOnly) {
            if (globalName != null) {
                throw new ComponentException.ComponentNotFoundException("No component found named: " + globalName);
            } else if (rootLocation != null) {
                throw new ComponentException.ComponentNotFoundException("No component found named: " + rootLocation);
            } else {
                throw new ComponentException.ComponentNotFoundException("No component found, none specified", new NullPointerException());
            }
        } else if (rootLocation == null) {
            // Do we really need to do this?
            // SCIPIO: 2017-08-03: The answer to above is yes.
            // SCIPIO: 2017-08-03: Now using better exception below to identify specifically when component not found
            // so that caller can handle gracefully or simply skip.
            //throw new ComponentException("No component found named : " + globalName);
            if (globalName != null) {
                throw new ComponentException.ComponentNotFoundException("No component found named: " + globalName);
            } else {
                throw new ComponentException.ComponentNotFoundException("No component found, none specified", new NullPointerException());
            }
        }

        // SCIPIO: 2018-10-18: Use an immutable config cache now, with sync (mostly) only on writes
        // IN ADDITION, use a sync block here, as this ensures that the ComponentConfig instances are unique globally,
        // which may prevent unexpected problems
        synchronized(componentConfigCacheSyncObj) {
            componentConfig = checkComponentConfig(globalName, rootLocation);
            if (componentConfig != null) {
                return componentConfig;
            }

            componentConfig = new ComponentConfig(globalName, rootLocation);
            if (componentConfig.enabled()) {
                //componentConfigCache.put(componentConfig);
                componentConfigCache = componentConfigCache.copyCacheAndPut(componentConfig);
                if (Debug.verboseOn()) {
                    Debug.logVerbose("Registered new component in cache: " + componentConfig + " (total: " + componentConfigCache.size() + ")", module);
                }
            } else {
                // SCIPIO: 2018-10-18: New disabled component cache
                disabledComponentConfigCache = disabledComponentConfigCache.copyCacheAndPut(componentConfig);
                if (Debug.verboseOn()) {
                    Debug.logVerbose("Registered new disabled component in cache: " + componentConfig + " (total disabled: " + disabledComponentConfigCache.size() + ")", module);
                }
            }
            return componentConfig;
        }
    }

    /**
     * Returns the component by root location.
     *
     * <p>NOTE: Per legacy behavior, this returns even disabled components (unlike {@link #getAllComponents()}).</p>
     *
     * <p>SCIPIO: 3.0.0: Added explicit helper.</p>
     */
    public static ComponentConfig getComponentConfigByLocation(String rootLocation) throws ComponentException {
        return getComponentConfig(null, rootLocation);
    }

    private static ComponentConfig checkComponentConfig(String globalName, String rootLocation) { // SCIPIO: 2018-10-18: refactored from method above
        ComponentConfig componentConfig = checkComponentConfig(componentConfigCache, globalName, rootLocation);
        if (componentConfig != null) {
            return componentConfig;
        }
        componentConfig = checkComponentConfig(disabledComponentConfigCache, globalName, rootLocation);
        if (componentConfig != null) {
            return componentConfig;
        }
        return null;
    }

    /**
     * Returns the component from the given cache using global name, root location or a resource path/URL from which
     * root location can be extracted.
     *
     * <p>SCIPIO: 2018-10-18: Refactored from {@link #checkComponentConfig(String, String)}.</p>
     */
    private static ComponentConfig checkComponentConfig(ComponentConfigCache componentConfigCache, String globalName,
                                                        String rootLocation) {
        // Original stock code checks
        ComponentConfig componentConfig;
        if (globalName != null && !globalName.isEmpty()) {
            componentConfig = componentConfigCache.fromGlobalName(globalName);
            if (componentConfig != null) {
                return componentConfig;
            }
        }
        if (rootLocation != null && !rootLocation.isEmpty()) {
            componentConfig = componentConfigCache.fromRootLocation(rootLocation);
            if (componentConfig != null) {
                return componentConfig;
            }
        }
        return null;
    }

    /**
     * Returns the given component config IF it is enabled, otherwise throws exception.
     *
     * <p>SCIPIO: 2018-10-29: Added.</p>
     */
    public static ComponentConfig getEnabledComponentConfig(String globalName) throws ComponentException {
        ComponentConfig componentConfig = getEnabledComponentConfigOrNull(globalName);
        if (componentConfig == null) {
            throw new ComponentException.ComponentNotFoundException("No enabled component found named: " + globalName);
        }
        return componentConfig;
    }

    /**
     * Returns the given component config IF it is enabled, otherwise throws exception.
     *
     * <p>SCIPIO: 3.0.0: Added.</p>
     */
    public static ComponentConfig getEnabledComponentConfigByLocation(String rootLocation) throws ComponentException {
        ComponentConfig componentConfig = getEnabledComponentConfigByLocationOrNull(rootLocation);
        if (componentConfig == null) {
            throw new ComponentException.ComponentNotFoundException("No enabled component found for location: " + rootLocation);
        }
        return componentConfig;
    }

    /**
     * Returns the given component config IF it is enabled, otherwise null (SCIPIO).
     *
     * <p>SCIPIO: 2018-10-29: Added.</p>
     */
    public static ComponentConfig getEnabledComponentConfigOrNull(String globalName) {
        return componentConfigCache.fromGlobalName(globalName);
    }

    /**
     * Returns the given component config IF it is enabled, otherwise null (SCIPIO).
     *
     * <p>SCIPIO: 3.0.0: Added explicit helper.</p>
     */
    public static ComponentConfig getEnabledComponentConfigByLocationOrNull(String rootLocation) {
        return componentConfigCache.fromRootLocation(rootLocation);
    }

    /**
     * Returns true if the named component is present and enabled (SCIPIO).
     *
     * <p>SCIPIO: 2018-10-29: Added.</p>
     */
    public static boolean isComponentEnabled(String globalName) {
        return (componentConfigCache.fromGlobalName(globalName) != null);
    }

    /**
     * Returns true if the named component is present in the filesystem,
     * without guarantee it is enabled.
     *
     * <p>SCIPIO: Added 2018-10-29.</p>
     *
     * @see #isComponentEnabled(String)
     */
    public static boolean isComponentPresent(String globalName) {
        return (componentConfigCache.fromGlobalName(globalName) != null) 
                || (disabledComponentConfigCache.fromGlobalName(globalName) != null);
    }

    /**
     * Special method for initialization only to set the global component order.
     *
     * <p>Public only by force - intended ONLY for use from {@link org.ofbiz.base.container.ComponentContainer}.</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static void clearStoreComponents(List<ComponentConfig> componentList) {
        synchronized(componentConfigCacheSyncObj) {
            // NOTE: 2018-10-18: Due to the stock logic in getComponentConfig(String, String), we must separate
            // enabled from disabled components here, otherwise getAllComponents() will return disabled components
            List<ComponentConfig> enabledComponentList = new ArrayList<>(componentList.size());
            List<ComponentConfig> disabledComponentList = new ArrayList<>(componentList.size());
            for(ComponentConfig component : componentList) {
                if (component.enabled()) {
                    enabledComponentList.add(component);
                } else {
                    disabledComponentList.add(component);
                }
            }
            componentConfigCache = new ComponentConfigCache(enabledComponentList);
            disabledComponentConfigCache = new ComponentConfigCache(disabledComponentList);
            Debug.logInfo("Setting global component cache: " + enabledComponentList.size() 
                    + " enabled components, " + disabledComponentList.size() + " disabled components", module);
        }
    }

    public static String getFullLocation(String componentName, String resourceLoaderName, String location) throws ComponentException {
        ComponentConfig cc = getComponentConfig(componentName, null);
        return cc.getFullLocation(resourceLoaderName, location);
    }

    public static KeystoreInfo getKeystoreInfo(String componentName, String keystoreName) {
        for (ComponentConfig cc : getAllComponents()) {
            if (componentName != null && componentName.equals(cc.getComponentName())) {
                for (KeystoreInfo ks : cc.getKeystoreInfos()) {
                    if (keystoreName != null && keystoreName.equals(ks.getName())) {
                        return ks;
                    }
                }
            }
        }
        return null;
    }

    public static String getRootLocation(String componentName) throws ComponentException {
        ComponentConfig cc = getComponentConfig(componentName);
        return cc.getRootLocation();
    }

    public static InputStream getStream(String componentName, String resourceLoaderName, String location) throws ComponentException {
        ComponentConfig cc = getComponentConfig(componentName);
        return cc.getStream(resourceLoaderName, location);
    }

    public static URL getURL(String componentName, String resourceLoaderName, String location) throws ComponentException {
        ComponentConfig cc = getComponentConfig(componentName);
        return cc.getURL(resourceLoaderName, location);
    }

    /**
     * Server context root webapp cache.
     *
     * <p>SCIPIO: Added.</p>
     */
    private static Map<String, Map<String, WebappInfo>> serverContextRootWebappInfoCache;

    private static Map<String, Map<String, WebappInfo>> getServerContextRootWebappInfoMap() {
        if (serverContextRootWebappInfoCache != null) {
            return serverContextRootWebappInfoCache;
        }
        Map<String, Map<String, WebappInfo>> map = new HashMap<>();
        for(WebappInfo webappInfo : getAllWebappResourceInfos()) {
            Map<String, WebappInfo> contextRootMap = map.get(webappInfo.getServer());
            if (contextRootMap == null) {
                contextRootMap = new HashMap<>();
                map.put(webappInfo.getServer(), contextRootMap);
            }
            if (contextRootMap.containsKey(webappInfo.getContextRoot())) {
               Debug.logWarning("Scipio: There are two webapps registered in system for contextRoot '"
                        + webappInfo.getContextRoot() + "' for the same server (" + webappInfo.getServer()
                        + "); ocasionally code can have problems with this (due to varying lookup orders by contextRoot)", module);
            }
            contextRootMap.put(webappInfo.getContextRoot(), webappInfo);
        }
        map = Collections.unmodifiableMap(map);
        serverContextRootWebappInfoCache = map;
        return map;
    }

    private static Map<String, WebappInfo> contextRootWebappInfoCache;

    private static Map<String, WebappInfo> getContextRootWebappInfoMap() {
        if (contextRootWebappInfoCache != null) {
            return contextRootWebappInfoCache;
        }
        Map<String, WebappInfo> map = new HashMap<>();
        for(WebappInfo webappInfo : getAllWebappResourceInfos()) {
            if (map.containsKey(webappInfo.getContextRoot())) {
                WebappInfo otherInfo = map.get(webappInfo.getContextRoot());
                if (otherInfo.getServer().equals(webappInfo.getServer())) {
                    Debug.logWarning("Scipio: There are two webapps registered in system for contextRoot '"
                            + webappInfo.getContextRoot() + "' for the same server (" + webappInfo.getServer()
                            + "); ocasionally code can have problems with this (due to varying lookup orders by contextRoot)", module);
                } else {
                    Debug.logWarning("Scipio: There are two webapps registered in system for contextRoot '"
                            + webappInfo.getContextRoot() + "' but under different servers; some legacy code"
                            + " may experience problems under this configuration (even if servers are different)", module);
                }
            }
            map.put(webappInfo.getContextRoot(), webappInfo);
        }
        map = Collections.unmodifiableMap(map);
        contextRootWebappInfoCache = map;
        return map;
    }

    /**
     * Gets a resolved map of the effective WebappInfo for each given context root (contextPath),
     * with optional server filter (usually recommended).
     *
     * <p>SCIPIO: NOTE: This must only be called post-loading due to caching - do not call during loading!</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static Map<String, WebappInfo> getWebappInfosByContextRoot(String serverName) {
        if (serverName != null) {
            return getServerContextRootWebappInfoMap().get(serverName);
        } else {
            return getContextRootWebappInfoMap();
        }
    }

    /**
     * Gets webapp info by context root, with optional server filter (usually recommended).
     *
     * <p>SCIPIO: NOTE: This must only be called post-loading due to caching - do not call during loading!</p>
     *
     * <p>SCIPIO: 2018-08-08: Modified to support a cache.</p>
     */
    public static WebappInfo getWebappInfoByContextRoot(String serverName, String contextRoot) {
        if (serverName != null) {
            Map<String, WebappInfo> contextRootMap = getServerContextRootWebappInfoMap().get(serverName);
            return (contextRootMap != null) ? contextRootMap.get(contextRoot) : null;
        } else {
            return getContextRootWebappInfoMap().get(contextRoot);
        }
    }

    /**
     * Gets webapp info by server name and context root.
     *
     * <p>SCIPIO: NOTE: This must only be called post-loading due to caching - do not call during loading!</p>
     *
     * <p>SCIPIO: 2018-08-08: Modified to support a cache.</p>
     */
    public static WebappInfo getWebAppInfo(String serverName, String contextRoot) {
        return getWebappInfoByContextRoot(serverName, contextRoot);
        /* old stock implementation
        if (serverName == null || contextRoot == null) {
            return null;
        }
        ComponentConfig.WebappInfo info = null;
        for (ComponentConfig cc : getAllComponents()) {
            for (WebappInfo wInfo : cc.getWebappInfos()) {
                if (serverName.equals(wInfo.server) && contextRoot.equals(wInfo.getContextRoot())) {
                    info = wInfo;
                }
            }
        }
        return info;
        */
    }

    /**
     * Returns the exact WebappInfo by name (no overrides).
     *
     * <p>NOTE: Not for use during initialization.</p>
     *
     * <p>TODO: Use caching.</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static WebappInfo getWebappInfoByName(String componentName, String webappName) throws IllegalArgumentException {
        WebappInfo webappInfo = null;
        // get the last entry (should override previous ones)
        for(ComponentConfig cc : ComponentConfig.getAllComponents()) {
            for (WebappInfo wInfo : cc.getWebappInfos()) {
                if (cc.getGlobalName().equals(componentName) && wInfo.getName().equals(webappName)) {
                    webappInfo = wInfo;
                }
            }
        }
        return webappInfo;
    }

    /**
     * Maps the component::webapp name of an overridden webapp to the new webapp.
     *
     * <p>Only returns non-null if the webapp was actually overridden.</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static WebappInfo getOverriddenWebappInfoByName(String componentName, String webappName) {
        return overriddenWebappNameMap.get(componentName+"::"+webappName);
    }

    /**
     * Maps the component::webapp name of an overridden webapp to the new webapp, otherwise the webapp itself, or null.
     *
     * <p>SCIPIO: Added.</p>
     */
    public static WebappInfo getEffectiveWebappInfoByName(String componentName, String webappName) {
        WebappInfo webappInfo = getOverriddenWebappInfoByName(componentName, webappName);
        return (webappInfo != null) ? webappInfo : getWebappInfoByName(componentName, webappName);
    }

    public static boolean isFileResourceLoader(String componentName, String resourceLoaderName) throws ComponentException {
        ComponentConfig cc = getComponentConfig(componentName);
        return cc.isFileResourceLoader(resourceLoaderName);
    }

    // ========== ComponentConfig instance ==========

    private final String globalName;
    private final String rootLocation;
    private final String componentName;
    private final boolean enabled;
    private final Map<String, ResourceLoaderInfo> resourceLoaderInfos;
    private final List<ClasspathInfo> classpathInfos;
    private final List<EntityResourceInfo> entityResourceInfos;
    private final List<ServiceResourceInfo> serviceResourceInfos;
    private final List<TestSuiteInfo> testSuiteInfos;
    private final List<KeystoreInfo> keystoreInfos;
    private final List<WebappInfo> webappInfos;
    private final List<ContainerConfig.Container> containers;
    /**
     * The components this one depends on.
     *
     * <p>This is the implementation of the "depends-on" element (existed in stock ofbiz xsd, but not implemented).
     * Does not contain duplicates.</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    private final List<String> componentDependencies;
    private final List<ClasspathSpecialInfo> classpathSpecialInfos; // SCIPIO: added 2018-06-18

    private ComponentConfig(String globalName, String rootLocation) throws ComponentException {
        if (!rootLocation.endsWith("/")) {
            rootLocation = rootLocation + "/";
        }
        this.rootLocation = rootLocation.replace('\\', '/');
        File rootLocationDir = new File(rootLocation);
        if (!rootLocationDir.exists()) {
            throw new ComponentException("The component root location does not exist: " + rootLocation);
        }
        if (!rootLocationDir.isDirectory()) {
            throw new ComponentException("The component root location is not a directory: " + rootLocation);
        }
        // SCIPIO: refactored xmlUrl
        URL xmlUrl = getComponentFileUrl(rootLocation);
        if (xmlUrl == null) {
            throw new ComponentException("Could not find the " + SCIPIO_COMPONENT_XML_FILENAME + " configuration file in the component root location: " + rootLocation); // SCIPIO: switched name
        }
        Document ofbizComponentDocument = null;
        try {
            ofbizComponentDocument = UtilXml.readXmlDocument(xmlUrl, true);
        } catch (Exception e) {
            throw new ComponentException("Error reading the component config file: " + xmlUrl, e);
        }
        Element ofbizComponentElement = ofbizComponentDocument.getDocumentElement();
        this.componentName = ofbizComponentElement.getAttribute("name");
        this.enabled = "true".equalsIgnoreCase(ofbizComponentElement.getAttribute("enabled"));
        if (UtilValidate.isEmpty(globalName)) {
            this.globalName = this.componentName;
        } else {
            this.globalName = globalName;
        }
        // resource-loader - resourceLoaderInfos
        List<? extends Element> childElements = UtilXml.childElementList(ofbizComponentElement, "resource-loader");
        if (!childElements.isEmpty()) {
            Map<String, ResourceLoaderInfo> resourceLoaderInfos = new LinkedHashMap<>();
            for (Element curElement : childElements) {
                ResourceLoaderInfo resourceLoaderInfo = new ResourceLoaderInfo(curElement);
                resourceLoaderInfos.put(resourceLoaderInfo.name, resourceLoaderInfo);
            }
            this.resourceLoaderInfos = Collections.unmodifiableMap(resourceLoaderInfos);
        } else {
            this.resourceLoaderInfos = Collections.emptyMap();
        }
        // classpath - classpathInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "classpath");
        if (!childElements.isEmpty()) {
            List<ClasspathInfo> classpathInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                ClasspathInfo classpathInfo = new ClasspathInfo(this, curElement);
                classpathInfos.add(classpathInfo);
            }
            this.classpathInfos = Collections.unmodifiableList(classpathInfos);
        } else {
            this.classpathInfos = Collections.emptyList();
        }
        // entity-resource - entityResourceInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "entity-resource");
        if (!childElements.isEmpty()) {
            List<EntityResourceInfo> entityResourceInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                EntityResourceInfo entityResourceInfo = new EntityResourceInfo(this, curElement);
                entityResourceInfos.add(entityResourceInfo);
            }
            this.entityResourceInfos = Collections.unmodifiableList(entityResourceInfos);
        } else {
            this.entityResourceInfos = Collections.emptyList();
        }
        // service-resource - serviceResourceInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "service-resource");
        if (!childElements.isEmpty()) {
            List<ServiceResourceInfo> serviceResourceInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                ServiceResourceInfo serviceResourceInfo = new ServiceResourceInfo(this, curElement);
                serviceResourceInfos.add(serviceResourceInfo);
            }
            this.serviceResourceInfos = Collections.unmodifiableList(serviceResourceInfos);
        } else {
            this.serviceResourceInfos = Collections.emptyList();
        }
        // test-suite - serviceResourceInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "test-suite");
        if (!childElements.isEmpty()) {
            List<TestSuiteInfo> testSuiteInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                TestSuiteInfo testSuiteInfo = new TestSuiteInfo(this, curElement);
                testSuiteInfos.add(testSuiteInfo);
            }
            this.testSuiteInfos = Collections.unmodifiableList(testSuiteInfos);
        } else {
            this.testSuiteInfos = Collections.emptyList();
        }
        // keystore - (cert/trust store infos)
        childElements = UtilXml.childElementList(ofbizComponentElement, "keystore");
        if (!childElements.isEmpty()) {
            List<KeystoreInfo> keystoreInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                KeystoreInfo keystoreInfo = new KeystoreInfo(this, curElement);
                keystoreInfos.add(keystoreInfo);
            }
            this.keystoreInfos = Collections.unmodifiableList(keystoreInfos);
        } else {
            this.keystoreInfos = Collections.emptyList();
        }
        // webapp - webappInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "webapp");
        if (!childElements.isEmpty()) {
            List<WebappInfo> webappInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                // SCIPIO: 2018-05-22: webapp disable tag for easier toggling webapps
                if ("false".equals(curElement.getAttribute("enabled"))) {
                    Debug.logInfo("Webapp '" + curElement.getAttribute("name")
                        + "' of component '" + componentName + "' is disabled; ignoring definition", module);
                    continue;
                }
                WebappInfo webappInfo = new WebappInfo(this, curElement);
                webappInfos.add(webappInfo);
            }
            this.webappInfos = Collections.unmodifiableList(webappInfos);
        } else {
            this.webappInfos = Collections.emptyList();
        }
        // containers
        try {
            Collection<Container> containers = ContainerConfig.getContainers(xmlUrl);
            if (!containers.isEmpty()) {
                this.containers = Collections.unmodifiableList(new ArrayList<ContainerConfig.Container>(containers));
            } else {
                this.containers = Collections.emptyList();
            }
        } catch (ContainerException ce) {
            throw new ComponentException("Error reading containers for component: " + this.globalName, ce);
        }
        // SCIPIO: component dependencies
        childElements = UtilXml.childElementList(ofbizComponentElement, "depends-on");
        if (!childElements.isEmpty()) {
            Collection<String> componentDependencies = new LinkedHashSet<>();
            for (Element curElement : childElements) {
                String depName = curElement.getAttribute("component-name");
                if (depName.equals(this.componentName)) {
                    Debug.logWarning("Scipio: component '" + this.componentName + "' has dependency on itself", module);
                    continue;
                }
                if (componentDependencies.contains(depName)) {
                    Debug.logWarning("Scipio: component '" + this.componentName + "' has duplicate dependency on component '" + depName + "'", module);
                    continue;
                }
                componentDependencies.add(depName);
            }
            this.componentDependencies = Collections.unmodifiableList(new ArrayList<String>(componentDependencies));
        } else {
            this.componentDependencies = Collections.emptyList();
        }
        // classpath - classpathInfos
        childElements = UtilXml.childElementList(ofbizComponentElement, "classpath-special");
        if (!childElements.isEmpty()) {
            List<ClasspathSpecialInfo> classpathInfos = new ArrayList<>(childElements.size());
            for (Element curElement : childElements) {
                ClasspathSpecialInfo classpathInfo = new ClasspathSpecialInfo(this, curElement);
                classpathInfos.add(classpathInfo);
            }
            this.classpathSpecialInfos = Collections.unmodifiableList(classpathInfos);
        } else {
            this.classpathSpecialInfos = Collections.emptyList();
        }
        if (Debug.verboseOn()) {
            Debug.logVerbose("Read component config : [" + rootLocation + "]", module);
        }
    }

    /**
     * Copy constructor, with extra optional overrides such as explicit webappInfos.
     *
     * <p>SCIPIO: Added.</p>
     */
    private ComponentConfig(ComponentConfig other, List<WebappInfo> webappInfos) {
        this.globalName = other.globalName;
        this.rootLocation = other.rootLocation;
        this.componentName = other.componentName;
        this.enabled = other.enabled;
        this.resourceLoaderInfos = other.resourceLoaderInfos;
        this.classpathInfos = other.classpathInfos;
        this.entityResourceInfos = other.entityResourceInfos;
        this.serviceResourceInfos = other.serviceResourceInfos;
        this.testSuiteInfos = other.testSuiteInfos;
        this.keystoreInfos = other.keystoreInfos;
        if (webappInfos != null) {
            List<WebappInfo> newWebappInfos = new ArrayList<>(webappInfos.size());
            for(WebappInfo webappInfo : webappInfos) {
                if (webappInfo.componentConfig != this) {
                    // NOTE: IMPORTANT: We must duplicate all the webapps if the backreference to ComponentConfig has changed!
                    newWebappInfos.add(new WebappInfo(webappInfo, this));
                } else {
                    newWebappInfos.add(webappInfo);
                }
            }
            this.webappInfos = Collections.unmodifiableList(newWebappInfos);
        } else {
            this.webappInfos = other.webappInfos;
        }
        this.containers = other.containers;
        this.componentDependencies = other.componentDependencies;
        this.classpathSpecialInfos = other.classpathSpecialInfos;
    }

    /**
     * Returns the scipio-component.xml, scipio-theme.xml or ofbiz-component.xml file for the given component directory,
     * or null if not found.
     *
     * <p>DEV NOTE: Duplicated to: <code>org.ofbiz.base.start.Config#getComponentFile</code>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static File getComponentFile(File componentDir) {
        File file = new File(componentDir, SCIPIO_COMPONENT_XML_FILENAME);
        if (file.exists() && file.isFile()) {
            return file;
        }
        file = new File(componentDir, SCIPIO_THEME_XML_FILENAME);
        if (file.exists() && file.isFile()) {
            return file;
        }
        file = new File(componentDir, OFBIZ_COMPONENT_XML_FILENAME);
        if (file.exists() && file.isFile()) {
            return file;
        }
        return null;
    }

    /**
     * Returns the scipio-component.xml, scipio-theme.xml or ofbiz-component.xml file for the given component directory,
     * or null if not found.
     *
     * <p>SCIPIO: Added.</p>
     */
    public static File getComponentFile(String componentDir) { // SCIPIO: DEV NOTE: DUPLICATED AT: org.ofbiz.base.start.Config#getComponentFile
        File file = FileUtil.getFile(componentDir + "/" + SCIPIO_COMPONENT_XML_FILENAME);
        if (file != null && file.exists()) {
            return file;
        }
        file = FileUtil.getFile(componentDir + "/" + SCIPIO_THEME_XML_FILENAME);
        if (file != null && file.exists()) {
            return file;
        }
        file = FileUtil.getFile(componentDir + "/" + OFBIZ_COMPONENT_XML_FILENAME);
        if (file != null && file.exists()) {
            return file;
        }
        return null;
    }

    /**
     * Returns the scipio-component.xml, scipio-theme.xml or ofbiz-component.xml file URL for the given component directory,
     * or null if not found.
     *
     * <p>NOTE: There is no way to determine the URL reliably unless the file exists, but otherwise scipio-component.xml would
     * be considered the "default".</p>
     *
     * <p>SCIPIO: Added.</p>
     */
    public static URL getComponentFileUrl(String componentDir) {
        String xmlFilename = componentDir + "/" + SCIPIO_COMPONENT_XML_FILENAME;
        URL xmlUrl = UtilURL.fromFilename(xmlFilename);
        if (xmlUrl == null) {
            xmlFilename = componentDir + "/" + SCIPIO_THEME_XML_FILENAME;
            xmlUrl = UtilURL.fromFilename(xmlFilename);
            if (xmlUrl == null) {
                xmlFilename = componentDir + "/" + OFBIZ_COMPONENT_XML_FILENAME;
                xmlUrl = UtilURL.fromFilename(xmlFilename);
            }
        }
        return xmlUrl;
    }

    public boolean enabled() {
        return this.enabled;
    }

    public List<ClasspathInfo> getClasspathInfos() {
        return this.classpathInfos;
    }

    public String getComponentName() {
        return this.componentName;
    }

    public List<ContainerConfig.Container> getContainers() {
        return this.containers;
    }

    public List<EntityResourceInfo> getEntityResourceInfos() {
        return this.entityResourceInfos;
    }

    public String getFullLocation(String resourceLoaderName, String location) throws ComponentException {
        ResourceLoaderInfo resourceLoaderInfo = resourceLoaderInfos.get(resourceLoaderName);
        if (resourceLoaderInfo == null) {
            throw new ComponentException("Could not find resource-loader named: " + resourceLoaderName);
        }
        StringBuilder buf = new StringBuilder();
        // pre-pend component root location if this is a type component resource-loader
        if ("component".equals(resourceLoaderInfo.type)) {
            buf.append(rootLocation);
        }

        if (UtilValidate.isNotEmpty(resourceLoaderInfo.prependEnv)) {
            String propValue = System.getProperty(resourceLoaderInfo.prependEnv);
            if (propValue == null) {
                String errMsg = "The Java environment (-Dxxx=yyy) variable with name " + resourceLoaderInfo.prependEnv + " is not set, cannot load resource.";
                Debug.logError(errMsg, module);
                throw new IllegalArgumentException(errMsg);
            }
            buf.append(propValue);
        }
        if (UtilValidate.isNotEmpty(resourceLoaderInfo.prefix)) {
            buf.append(resourceLoaderInfo.prefix);
        }
        buf.append(location);
        return buf.toString();
    }

    public String getGlobalName() {
        return this.globalName;
    }

    public List<KeystoreInfo> getKeystoreInfos() {
        return this.keystoreInfos;
    }

    public Map<String, ResourceLoaderInfo> getResourceLoaderInfos() {
        return this.resourceLoaderInfos;
    }

    /**
     * Returns absolute path to component, INCLUDING trailing slash.
     *
     * <p>SCIPIO: NOTE: Includes trailing slash.</p>
     */
    public String getRootLocation() {
        return this.rootLocation;
    }

    public List<ServiceResourceInfo> getServiceResourceInfos() {
        return this.serviceResourceInfos;
    }

    public InputStream getStream(String resourceLoaderName, String location) throws ComponentException {
        URL url = getURL(resourceLoaderName, location);
        try {
            return url.openStream();
        } catch (java.io.IOException e) {
            throw new ComponentException("Error opening resource at location [" + url.toExternalForm() + "]", e);
        }
    }

    public List<TestSuiteInfo> getTestSuiteInfos() {
        return this.testSuiteInfos;
    }

    public URL getURL(String resourceLoaderName, String location) throws ComponentException {
        ResourceLoaderInfo resourceLoaderInfo = resourceLoaderInfos.get(resourceLoaderName);
        if (resourceLoaderInfo == null) {
            throw new ComponentException("Could not find resource-loader named: " + resourceLoaderName);
        }
        if ("component".equals(resourceLoaderInfo.type) || "file".equals(resourceLoaderInfo.type)) {
            String fullLocation = getFullLocation(resourceLoaderName, location);
            URL fileUrl = UtilURL.fromFilename(fullLocation);
            if (fileUrl == null) {
                throw new ComponentException("File Resource not found: " + fullLocation);
            }
            return fileUrl;
        } else if ("classpath".equals(resourceLoaderInfo.type)) {
            String fullLocation = getFullLocation(resourceLoaderName, location);
            URL url = UtilURL.fromResource(fullLocation);
            if (url == null) {
                throw new ComponentException("Classpath Resource not found: " + fullLocation);
            }
            return url;
        } else if ("url".equals(resourceLoaderInfo.type)) {
            String fullLocation = getFullLocation(resourceLoaderName, location);
            URL url = null;
            try {
                url = FlexibleLocation.resolveLocation(location);
            } catch (java.net.MalformedURLException e) {
                throw new ComponentException("Error with malformed URL while trying to load URL resource at location [" + fullLocation + "]", e);
            }
            if (url == null) {
                throw new ComponentException("URL Resource not found: " + fullLocation);
            }
            return url;
        } else {
            throw new ComponentException("The resource-loader type is not recognized: " + resourceLoaderInfo.type);
        }
    }

    public List<WebappInfo> getWebappInfos() {
        return this.webappInfos;
    }

    /**
     * Returns the names of components this component depends on.
     *
     * <p>SCIPIO: Added.</p>
     */
    public List<String> getComponentDependencies() {
        return componentDependencies;
    }

    /**
     * Returns the special libraries.
     *
     * <p>SCIPIO: Added.</p>
     */
    public List<ClasspathSpecialInfo> getClasspathSpecialInfos() {
        return classpathSpecialInfos;
    }

    public boolean isFileResource(ResourceInfo resourceInfo) throws ComponentException {
        return isFileResourceLoader(resourceInfo.loader);
    }

    public boolean isFileResourceLoader(String resourceLoaderName) throws ComponentException {
        ResourceLoaderInfo resourceLoaderInfo = resourceLoaderInfos.get(resourceLoaderName);
        if (resourceLoaderInfo == null) {
            throw new ComponentException("Could not find resource-loader named: " + resourceLoaderName);
        }
        return "file".equals(resourceLoaderInfo.type) || "component".equals(resourceLoaderInfo.type);
    }

    @Override
    public String toString() { // SCIPIO: Added 2018-09-25
        return "[componentName=" + globalName + ", rootLocation=" + rootLocation + "]";
    }

    /**
     * Returns the component names for a list of configs.
     *
     * <p>SCIPIO: Added.</p>
     */
    public static List<String> getComponentNames(Collection<ComponentConfig> componentList) {
        List<String> names = new ArrayList<>(componentList.size());
        for(ComponentConfig config : componentList) {
            names.add(config.getComponentName());
        }
        return names;
    }

    /**
     * Creates a (order-preserving) map of component names to configs from a list of configs.
     *
     * <p>SCIPIO: Added.</p>
     */
    public static Map<String, ComponentConfig> makeComponentNameMap(Collection<ComponentConfig> componentList) {
        Map<String, ComponentConfig> map = new LinkedHashMap<>();
        for(ComponentConfig config : componentList) {
            map.put(config.getComponentName(), config);
        }
        return map;
    }

    /**
     * Reads special JAR locations for given purpose.
     *
     * <p>Only consults global entries (not those constrained to specific webapps).</p>
     *
     * <p>SCIPIO: Added 2018-06-18.</p>
     */
    public static List<File> readClasspathSpecialJarLocations(String purpose) {
        List<File> jarLocations = new ArrayList<>();
        for(ComponentConfig cc : ComponentConfig.getAllComponents()) {
            readClasspathSpecialJarLocations(jarLocations, cc, purpose, null);
        }
        return jarLocations;
    }

    /**
     * Reads special JAR locations for given purpose for given component.
     *
     * <p>SCIPIO: Added 2018-06-18.</p>
     */
    public static List<File> readClasspathSpecialJarLocations(ComponentConfig componentConfig, String purpose) {
        return readClasspathSpecialJarLocations(componentConfig, purpose, null);
    }

    /**
     * Reads special JAR locations for given purpose for given component, with optional webapp filter.
     *
     * <p>NOTE: If no webapp filter is given, returns only locations that are not restricted to specific component
     * webapps.</p>
     *
     * <p>SCIPIO: Added 2018-06-18.</p>
     */
    public static List<File> readClasspathSpecialJarLocations(ComponentConfig componentConfig, String purpose, String webappName) {
        List<File> jarLocations = new ArrayList<>();
        readClasspathSpecialJarLocations(jarLocations, componentConfig, purpose, webappName);
        return jarLocations;
    }

    /**
     * Reads special JAR locations for given purpose for given component,
     * with optional webapp filter.
     *
     * <p>NOTE: If no webapp filter is given, returns only locations that are not restricted to specific component
     * webapps.</p>
     *
     * <p>SCIPIO: Added 2018-06-18.</p>
     */
    private static void readClasspathSpecialJarLocations(List<File> jarLocations, ComponentConfig componentConfig, String purpose, String webappName) {
        String configRoot = componentConfig.getRootLocation();
        configRoot = configRoot.replace('\\', '/');
        for(ComponentConfig.ClasspathSpecialInfo info : componentConfig.getClasspathSpecialInfos()) {
            if (purpose != null && !info.getPurposes().contains(purpose)) {
                continue;
            }
            if (webappName != null) {
                if (!info.getWebappNames().contains(webappName)) {
                    continue;
                }
            } else {
                if (info.isWebappSpecific()) {
                    continue;
                }
            }
            String type = info.getType();
            if (type == null || !("jar".equals(type) || "dir".equals(type))) {
                continue;
            }
            String location = info.getLocation().replace('\\', '/');
            if (location.startsWith("/")) {
                location = location.substring(1);
            }
            String dirLoc = location;
            if (dirLoc.endsWith("/*")) {
                // strip off the slash splat
                dirLoc = location.substring(0, location.length() - 2);
            }
            String fileNameSeparator = ("\\".equals(File.separator) ? "\\" + File.separator : File.separator);
            dirLoc = dirLoc.replaceAll("/+|\\\\+", fileNameSeparator);
            File path = new File(configRoot, dirLoc);
            if (path.exists()) {
                if (path.isDirectory()) {
                    File[] listFiles = path.listFiles();
                    if (listFiles != null) {
                        for (File file : listFiles) {
                            String fileName = file.getName().toLowerCase();
                            if (fileName.endsWith(".jar")) {
                                jarLocations.add(file);
                            }
                        }
                    }
                } else {
                    jarLocations.add(path);
                }
            } else if (!info.isOptional()) {
                Debug.logError("Non-optional classpath-special entry location for component '" 
                        + componentConfig.getGlobalName() + "' references non-existent path: "
                        + path, module);
            }
        }
    }

    /**
     * Post-processes the global list of loaded components after all
     * <code>scipio-component.xml</code> files have been read (hook/callback) (new 2017-01-19).
     *
     * <p>SCIPIO: Added.</p>
     */
    public static List<ComponentConfig> postProcessComponentConfigs(List<ComponentConfig> componentList) {
        componentList = (postProcessWebappInfos(componentList));
        return componentList;
    }

    /**
     * Post-processes the webapp infos of component list.
     *
     * <p>SCIPIO: Added.</p>
     */
    private static List<ComponentConfig> postProcessWebappInfos(List<ComponentConfig> componentList) {
        // Find all duplicate mount-points
        Map<String, ComponentConfig> modifiedComponentsByName = new HashMap<>();
        for(Map.Entry<String, List<WebappInfo>> entry : getAllWebappResourceInfosByContextRoot(componentList, null).entrySet()) {
            // FIXME?: this part is not the most efficient and in certain cases may create needless copies
            List<WebappInfo> webappInfoList = entry.getValue();
            if (webappInfoList != null && webappInfoList.size() >= 2) {
                WebappInfo lastWebappInfo = webappInfoList.get(webappInfoList.size() - 1);
                // Handle special override-modes for all webapps before the last one
                for(WebappInfo webappInfo : webappInfoList.subList(0, webappInfoList.size() - 1)) {
                    // SCIPIO: 2018-08-13: we should only do these modifications if both are on the same serverId,
                    // because technically it should be valid to have same apps on different servers
                    if (lastWebappInfo.getServer().equals(webappInfo.getServer())) {
                        if ("remove-overridden-webapp".equals(lastWebappInfo.getOverrideMode())) {
                            Debug.logInfo("ofbiz-component: Applying remove-overridden-webapp requested by webapp " + lastWebappInfo.componentConfig.getComponentName()
                                + "#" + lastWebappInfo.getName() + " and removing previous webapp matching matching server and mount-point (" + lastWebappInfo.mountPoint + "): "
                                + webappInfo.componentConfig.getComponentName() + "#" + webappInfo.getName(), module);
                            ComponentConfig prevCc = webappInfo.componentConfig;
                            // NOTE: if the CC has multiple webapps, it may already have been updated,
                            // so fetch it out of the map
                            if (modifiedComponentsByName.containsKey(prevCc.getComponentName())) {
                                prevCc = modifiedComponentsByName.get(prevCc.getComponentName());
                            }
                            List<WebappInfo> prevWebappInfoList = prevCc.getWebappInfos();
                            List<WebappInfo> newWebappInfoList = new ArrayList<>(prevWebappInfoList.size());
                            WebappInfo overriddenWebappInfo = null; // SCIPIO
                            // removed this webapp from the list
                            for(WebappInfo prevWebappInfo : prevWebappInfoList) {
                                if (prevWebappInfo != webappInfo && !prevWebappInfo.getName().equals(webappInfo.getName())) {
                                    newWebappInfoList.add(prevWebappInfo);
                                } else {
                                    overriddenWebappInfo = prevWebappInfo;
                                }
                            }
                            ComponentConfig newCc = new ComponentConfig(prevCc, newWebappInfoList);
                            modifiedComponentsByName.put(newCc.getComponentName(), newCc);
                            if (overriddenWebappInfo != null) {
                                synchronized(overriddenWebappsSyncObj) {
                                    Map<String, WebappInfo> overriddenWebapps = new HashMap<>(overriddenWebappNameMap);
                                    overriddenWebapps.put(prevCc.getComponentName()+"::"+overriddenWebappInfo.getName(), webappInfo);
                                    overriddenWebappNameMap = Collections.unmodifiableMap(overriddenWebapps);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (modifiedComponentsByName.size() == 0) { // optimization
            return componentList;
        }

        List<ComponentConfig> resultList = new ArrayList<>(componentList.size());
        for(ComponentConfig cc : componentList) {
            if (modifiedComponentsByName.containsKey(cc.getComponentName())) {
                resultList.add(modifiedComponentsByName.get(cc.getComponentName()));
            } else {
                resultList.add(cc);
            }
        }

        return resultList;
    }

    /**
     * For an absolute or relative-to-project-root path, URL, or URI to a file under a component, returns the component.
     *
     * <p>SCIPIO: 3.0.0: Added for annotations support.</p>
     */
    public static ComponentConfig getComponentConfigFromResource(Object location) {
        if (location instanceof String && ((String) location).startsWith("component://")) {
            return getComponentConfigByComponentUrl((String) location);
        }
        String path = UtilURL.getAbsolutePath(location);
        if (path == null) {
            return null;
        }
        // For this check, always append a / even if it's a file or already there so the startsWith is sure to work if equal to root location
        String checkPath = path + "/";
        for (ComponentConfig cc : componentConfigCache.componentConfigList) {
            if (checkPath.startsWith(cc.getRootLocation())) { // NOTE: rootLocation ends with / so this works as-is
                return cc;
            }
        }
        for (ComponentConfig cc : disabledComponentConfigCache.componentConfigList) {
            if (checkPath.startsWith(cc.getRootLocation())) { // NOTE: rootLocation ends with / so this works as-is
                return cc;
            }
        }
        //throw new ComponentException.ComponentNotFoundException("Could not find component by path for location: " + location);
        return null;
    }

    /**
     * For a component:// URL to a file under a component, returns the component.
     *
     * <p>SCIPIO: 3.0.0: Added for annotations support.</p>
     */
    public static ComponentConfig getComponentConfigByComponentUrl(String componentUrl) {
        String componentName = getComponentNameFromComponentUrl(componentUrl);
        try {
            return getComponentConfig(componentName);
        } catch (ComponentException e) {
            return null;
        }
    }

    /**
     * For a component:// URL, return the component name.
     *
     * <p>SCIPIO: 3.0.0: Added for annotations support.</p>
     */
    public static String getComponentNameFromComponentUrl(String componentUrl) {
        if (!"component://".startsWith(componentUrl)) {
            throw new IllegalArgumentException("Not a component:// URL [" + componentUrl + "]");
        }
        int nextSlash = componentUrl.indexOf('/', "component://".length());
        if (nextSlash <= 0) {
            throw new IllegalArgumentException("Invalid component:// URL [" + componentUrl + "]");
        }
        String componentName = componentUrl.substring("component://".length(), nextSlash);
        return (!componentName.isEmpty()) ? componentName : null;
    }

    /**
     * For an absolute or relative-to-project-root path to a file under a webapp, returns the webapp, or optionally the
     * default webapp if the resource is outside the webapp path.
     *
     * <p>SCIPIO: 3.0.0: Added for annotations support.</p>
     */
    public static WebappInfo getWebappInfoFromResource(Object location, boolean useDefault) {
        if (location instanceof String && ((String) location).startsWith("component://")) {
            return getWebappInfoFromResourceByComponentUrl((String) location, useDefault);
        }
        String path = UtilURL.getAbsolutePath(location);
        if (path == null) {
            return null;
        }
        // For this check, always append a / even if it's a file or already there so the startsWith is sure to work if equal to root location
        String checkPath = path + "/";
        ComponentConfig component = null;
        for (ComponentConfig cc : componentConfigCache.componentConfigList) {
            if (checkPath.startsWith(cc.getRootLocation())) { // NOTE: rootLocation ends with / so this works as-is
                component = cc;
                break;
            }
        }
        if (component == null) {
            for (ComponentConfig cc : disabledComponentConfigCache.componentConfigList) {
                if (checkPath.startsWith(cc.getRootLocation())) { // NOTE: rootLocation ends with / so this works as-is
                    component = cc;
                    break;
                }
            }
            if (component == null) {
                return null;
            }
        }
        if (path.length() > component.getRootLocation().length()) {
            String relPath = path.substring(component.getRootLocation().length());
            String checkRelPath = relPath + "/";
            for (WebappInfo wi : component.getWebappInfos()) {
                if (checkRelPath.startsWith(wi.getRelativeLocation() + "/")) {
                    return wi;
                }
            }
        }
        if (useDefault) {
            return component.getWebappInfos().get(0);
        } else {
            return null;
        }
    }

    /**
     * For a component:// URL to a file under a webapp, returns the webapp, or optionally the default webapp
     * if the resource is outside the webapp path.
     *
     * <p>SCIPIO: 3.0.0: Added for annotations support.</p>
     */
    public static WebappInfo getWebappInfoFromResourceByComponentUrl(String componentUrl, boolean useDefault) {
        if (!"component://".startsWith(componentUrl)) {
            throw new IllegalArgumentException("Not a component:// URL [" +
                    componentUrl + "]");
        }
        int nextSlash = componentUrl.indexOf('/', "component://".length());
        if (nextSlash <= 0) {
            throw new IllegalArgumentException("Invalid component:// URL [" +
                    componentUrl + "]");
        }
        String componentName = componentUrl.substring("component://".length(), nextSlash);
        if (UtilValidate.isEmpty(componentName)) {
            throw new IllegalArgumentException("Invalid component name in URL [" +  componentUrl + "]");
        }
        ComponentConfig cc = null;
        try {
            cc = getComponentConfig(componentName);
        } catch (ComponentException e) {
            return null;
        }
        if (UtilValidate.isEmpty(cc.getWebappInfos())) {
            return null;
        }
        String relPath = componentUrl.substring(nextSlash + 1);
        for (WebappInfo wi : cc.getWebappInfos()) {
            if (relPath.equals(wi.location) || relPath.startsWith(wi.location + "/")) {
                return wi;
            }
        }
        if (useDefault) {
            return cc.getWebappInfos().get(0);
        } else {
            return null;
        }
    }

    /**
     * An object that models the <code>&lt;classpath&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static class ClasspathInfo { // SCIPIO: removed "final"
        public final ComponentConfig componentConfig;
        public final String type;
        public final String location;

        ClasspathInfo(ComponentConfig componentConfig, Element element) { // SCIPIO: removed "private"
            this.componentConfig = componentConfig;
            this.type = element.getAttribute("type");
            this.location = element.getAttribute("location");
        }

        public ComponentConfig getComponentConfig() {
            return componentConfig;
        }

        public String getType() {
            return type;
        }

        public String getLocation() {
            return location;
        }
    }

    /**
     * An object that models the <code>&lt;classpath-special&gt;</code> element.
     *
     * <p>SCIPIO: Added.</p>
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static class ClasspathSpecialInfo extends ClasspathInfo {
        private final Set<String> purposes;
        private final Set<String> webappNames;
        private final boolean optional;

        ClasspathSpecialInfo(ComponentConfig componentConfig, Element element) {
            super(componentConfig, element);
            this.purposes = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(element.getAttribute("purpose").split(","))));
            List<? extends Element> webappElements = UtilXml.childElementList(element, "webapp");
            Set<String> webappNames = new HashSet<>();
            for(Element webappElement : webappElements) {
                String webappName = webappElement.getAttribute("name");
                if (UtilValidate.isNotEmpty(webappName)) {
                    webappNames.add(webappName);
                }
            }
            this.webappNames = (webappNames.size() > 0) ? Collections.unmodifiableSet(webappNames) : null;
            this.optional = "true".equals(element.getAttribute("optional"));
        }

        public boolean isWebappSpecific() {
            return (webappNames != null);
        }

        public Set<String> getPurposes() {
            return purposes;
        }

        public Set<String> getWebappNames() {
            return webappNames;
        }

        public boolean isOptional() {
            return optional;
        }
    }

    /**
     * ComponentConfig instances need to be looked up by their global name and root location,
     * so this class encapsulates the Maps and synchronization code required to do that.
     *
     * <p>SCIPIO: 2018-10-18: Rewritten for thread-safe design with non-blocking reads (previous stock class was
     * completely thread-unsafe) where each change creates a new instance.</p>
     */
    private static final class ComponentConfigCache {
        // Key is the global name.
        private final Map<String, ComponentConfig> componentConfigs;
        // Root location mapped to global name.
        private final Map<String, String> componentLocations;
        // SCIPIO: Separate list of configs for the values() call;
        private final List<ComponentConfig> componentConfigList;

        ComponentConfigCache(ComponentConfigCache other, Collection<? extends ComponentConfig> newConfigs) {
            Map<String, ComponentConfig> componentConfigs;
            Map<String, String> componentLocations;
            if (other != null) {
                componentConfigs = new LinkedHashMap<>(other.componentConfigs);
                componentLocations = new HashMap<>(other.componentLocations);
            } else {
                componentConfigs = new LinkedHashMap<>();
                componentLocations = new HashMap<>();
            }
            if (newConfigs != null) {
                for(ComponentConfig config : newConfigs) {
                    String globalName = config.getGlobalName();
                    String rootLocation = config.getRootLocation();
                    componentLocations.put(rootLocation, globalName);
                    componentConfigs.put(globalName, config);
                }
            }
            this.componentConfigs = componentConfigs;
            this.componentLocations = componentLocations;
            this.componentConfigList = Collections.unmodifiableList(new ArrayList<>(componentConfigs.values()));
        }

        ComponentConfigCache(Collection<? extends ComponentConfig> newConfigs) {
            this(null, newConfigs);
        }

        ComponentConfigCache() {
            this(null, (Collection<? extends ComponentConfig>) null);
        }

        /**
         * Returns cloned instance with the given config added.
         *
         * <p>SCIPIO: Added.</p>
         */
        ComponentConfigCache copyCacheAndPut(ComponentConfig config) {
            return new ComponentConfigCache(this, (UtilMisc.toList(config)));
        }

        /**
         * Returns cloned instance with the given configs added.
         *
         * <p>SCIPIO: Added.</p>
         */
        ComponentConfigCache copyCacheAndPutAll(Collection<? extends ComponentConfig> configList) {
            return new ComponentConfigCache(this, configList);
        }

        ComponentConfig fromGlobalName(String globalName) {
            return componentConfigs.get(globalName);
        }

        ComponentConfig fromRootLocation(String rootLocation) {
            // SCIPIO: 3.0.0: Since ComponentConfig constructor converts this.rootLocation to forward slashes and this
            // is the format in the cache, we must also do it here, as well as ensure trailing slash
            if (!rootLocation.endsWith("/")) {
                rootLocation = rootLocation + "/";
            }
            rootLocation = rootLocation.replace('\\', '/');

            String globalName = componentLocations.get(rootLocation);
            if (globalName == null) {
                return null;
            }
            return componentConfigs.get(globalName);
        }

        Collection<ComponentConfig> values() {
            return componentConfigList;
        }
        
        int size() {
            return componentConfigList.size();
        }
    }

    /**
     * An object that models the <code>&lt;entity-resource&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class EntityResourceInfo extends ResourceInfo {
        public final String type;
        public final String readerName;

        private EntityResourceInfo(ComponentConfig componentConfig, Element element) {
            super(componentConfig, element);
            this.type = element.getAttribute("type");
            this.readerName = element.getAttribute("reader-name");
        }
    }

    /**
     * An object that models the <code>&lt;keystore&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class KeystoreInfo extends ResourceInfo {
        private final String name;
        private final String type;
        private final String password;
        private final boolean isCertStore;
        private final boolean isTrustStore;

        private KeystoreInfo(ComponentConfig componentConfig, Element element) {
            super(componentConfig, element);
            this.name = element.getAttribute("name");
            this.type = element.getAttribute("type");
            this.password = element.getAttribute("password");
            this.isCertStore = "true".equalsIgnoreCase(element.getAttribute("is-certstore"));
            this.isTrustStore = "true".equalsIgnoreCase(element.getAttribute("is-truststore"));
        }

        public KeyStore getKeyStore() {
            ComponentResourceHandler rh = this.createResourceHandler();
            try {
                return KeyStoreUtil.getStore(rh.getURL(), this.getPassword(), this.getType());
            } catch (Exception e) {
                Debug.logWarning(e, module);
            }
            return null;
        }

        public String getName() {
            return name;
        }

        public String getPassword() {
            return password;
        }

        public String getType() {
            return type;
        }

        public boolean isCertStore() {
            return isCertStore;
        }

        public boolean isTrustStore() {
            return isTrustStore;
        }
    }

    public static abstract class ResourceInfo {
        private final ComponentConfig componentConfig;
        private final String loader;
        private final String location;

        protected ResourceInfo(ComponentConfig componentConfig, Element element) {
            this.componentConfig = componentConfig;
            this.loader = element.getAttribute("loader");
            this.location = element.getAttribute("location");
        }

        public ComponentResourceHandler createResourceHandler() {
            return new ComponentResourceHandler(componentConfig.getGlobalName(), loader, location);
        }

        public ComponentConfig getComponentConfig() {
            return componentConfig;
        }

        public String getLocation() {
            return location;
        }
    }

    /**
     * An object that models the <code>&lt;resource-loader&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class ResourceLoaderInfo {
        public final String name;
        public final String type;
        public final String prependEnv;
        public final String prefix;

        private ResourceLoaderInfo(Element element) {
            this.name = element.getAttribute("name");
            this.type = element.getAttribute("type");
            this.prependEnv = element.getAttribute("prepend-env");
            this.prefix = element.getAttribute("prefix");
        }
    }

    /**
     * An object that models the <code>&lt;service-resource&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class ServiceResourceInfo extends ResourceInfo {
        public final String type;

        private ServiceResourceInfo(ComponentConfig componentConfig, Element element) {
            super(componentConfig, element);
            this.type = element.getAttribute("type");
        }
    }

    /**
     * An object that models the <code>&lt;test-suite&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class TestSuiteInfo extends ResourceInfo {
        public TestSuiteInfo(ComponentConfig componentConfig, Element element) {
            super(componentConfig, element);
        }
    }

    /**
     * An object that models the <code>&lt;webapp&gt;</code> element.
     *
     * @see <code>ofbiz-component.xsd</code>
     */
    public static final class WebappInfo {
        // FIXME: These fields should be private - since we have accessors - but
        //  client code accesses the fields directly.
        public final ComponentConfig componentConfig;
        public final List<String> virtualHosts;
        public final Map<String, String> initParameters;
        public final String name;
        public final String title;
        public final String description;
        public final String menuName;
        public final String server;
        public final String mountPoint;
        public final String contextRoot;
        public final String location;
        public final String[] basePermission;
        public final String position;
        public final boolean privileged;
        // CatalinaContainer modifies this field.
        private volatile boolean appBarDisplay;
        private final String accessPermission;
        /**
         * Override mode, to handle duplicate mount-points.
         *
         * <p>SCIPIO: 2017-01-19: Added.</p>
         */
        private final String overrideMode;

        private WebappInfo(ComponentConfig componentConfig, Element element) {
            this.componentConfig = componentConfig;
            this.name = element.getAttribute("name");
            String title = element.getAttribute("title");
            if (title.isEmpty()) {
                // default title is name w/ upper-cased first letter
                title = Character.toUpperCase(name.charAt(0)) + name.substring(1).toLowerCase();
            }
            this.title = title;
            String description = element.getAttribute("description");
            if (description.isEmpty()) {
                description = this.title;
            }
            this.description = description;
            this.server = element.getAttribute("server");
            String mountPoint = element.getAttribute("mount-point");
            // check the mount point and make sure it is properly formatted
            if (!mountPoint.isEmpty()) {
                if (!mountPoint.startsWith("/")) {
                    mountPoint = "/" + mountPoint;
                }
                if (!mountPoint.endsWith("/*")) {
                    if (!mountPoint.endsWith("/")) {
                        mountPoint = mountPoint + "/";
                    }
                    mountPoint = mountPoint + "*";
                }
            }
            this.mountPoint = mountPoint;
            if (this.mountPoint.endsWith("/*")) {
                this.contextRoot = this.mountPoint.substring(0, this.mountPoint.length() - 2);
            } else {
                this.contextRoot = this.mountPoint;
            }
            // SCIPIO: 3.0.0: Ensure no trailing slash
            String location = element.getAttribute("location");
            if (location.length() > 0 && !"/".equals(location) && location.endsWith("/")) {
                location = location.substring(0, location.length() - 1);
            }
            this.location = location;
            this.appBarDisplay = !"false".equals(element.getAttribute("app-bar-display"));
            this.privileged = !"false".equals(element.getAttribute("privileged"));
            this.accessPermission = element.getAttribute("access-permission");
            String basePermStr = element.getAttribute("base-permission");
            if (!basePermStr.isEmpty()) {
                this.basePermission = basePermStr.split(",");
            } else {
                // default base permission is NONE
                this.basePermission = new String[] { "NONE" };
            }
            // trim the permissions (remove spaces)
            for (int i = 0; i < this.basePermission.length; i++) {
                this.basePermission[i] = this.basePermission[i].trim();
                if (this.basePermission[i].indexOf('_') != -1) {
                    this.basePermission[i] = this.basePermission[i].substring(0, this.basePermission[i].indexOf('_'));
                }
            }
            String menuNameStr = element.getAttribute("menu-name");
            if (UtilValidate.isNotEmpty(menuNameStr)) {
                this.menuName = menuNameStr;
            } else {
                this.menuName = "main";
            }
            this.position = element.getAttribute("position");
            // load the virtual hosts
            List<? extends Element> virtHostList = UtilXml.childElementList(element, "virtual-host");
            if (!virtHostList.isEmpty()) {
                List<String> virtualHosts = new ArrayList<>(virtHostList.size());
                for (Element e : virtHostList) {
                    virtualHosts.add(e.getAttribute("host-name"));
                }
                this.virtualHosts = Collections.unmodifiableList(virtualHosts);
            } else {
                this.virtualHosts = Collections.emptyList();
            }
            // load the init parameters
            List<? extends Element> initParamList = UtilXml.childElementList(element, "init-param");
            if (!initParamList.isEmpty()) {
                Map<String, String> initParameters = new LinkedHashMap<>();
                for (Element e : initParamList) {
                    initParameters.put(e.getAttribute("name"), e.getAttribute("value"));
                }
                this.initParameters = Collections.unmodifiableMap(initParameters);
            } else {
                this.initParameters = Collections.emptyMap();
            }
            // SCIPIO: new
            this.overrideMode = element.getAttribute("override-mode");
        }

        /**
         * Copy constructor, with optional parent/backreference override for component config.
         *
         * <p>SCIPIO: Added.</p>
         */
        private WebappInfo(WebappInfo other, ComponentConfig componentConfig) {
            this.componentConfig = (componentConfig != null) ? componentConfig : other.componentConfig;
            this.virtualHosts = other.virtualHosts;
            this.initParameters = other.initParameters;
            this.name = other.name;
            this.title = other.title;
            this.description = other.description;
            this.menuName = other.menuName;
            this.server = other.server;
            this.mountPoint = other.mountPoint;
            this.contextRoot = other.contextRoot;
            this.location = other.location;
            this.basePermission = other.basePermission;
            this.position = other.position;
            this.privileged = other.privileged;
            this.appBarDisplay = other.appBarDisplay;
            this.accessPermission = other.accessPermission;
            this.overrideMode = other.overrideMode;
        }

        public ComponentConfig getComponentConfig() {
            return componentConfig;
        }

        public synchronized boolean getAppBarDisplay() {
            return this.appBarDisplay;
        }

        public String getAccessPermission() {
            return this.accessPermission;
        }

        public String[] getBasePermission() {
            return this.basePermission.clone();
        }

        public String getContextRoot() {
            return contextRoot;
        }

        public String getDescription() {
            return description;
        }

        public Map<String, String> getInitParameters() {
            return initParameters;
        }

        /**
         * Returns absolute webapp path including home and component root location, without trailing slash.
         *
         * <p>SCIPIO: NOTE: For as-is relative location, use {@link #getRelativeLocation()}</p>.
         */
        public String getLocation() {
            return componentConfig.getRootLocation() + location;
        }

        /**
         * Returns webapp path relative to component, without trailing slash.
         *
         * <p>SCIPIO: 3.0.0: Added.</p>
         */
        public String getRelativeLocation() {
            return location;
        }

        /**
         * Returns true if location was set (usually containing WEB-INF).
         *
         * <p>SCIPIO: 3.0.0: Added.</p>
         */
        public boolean hasLocation() {
            return !location.isEmpty();
        }
        public String getName() {
            return name;
        }

        public String getMountPoint() {
            return mountPoint;
        }

        public String getTitle() {
            return title;
        }

        public List<String> getVirtualHosts() {
            return virtualHosts;
        }

        public String getServer() { // SCIPIO: added 2018-08-10, missing in stock
            return server;
        }

        public String getOverrideMode() { // SCIPIO: new
            return overrideMode;
        }

        public synchronized void setAppBarDisplay(boolean appBarDisplay) {
            this.appBarDisplay = appBarDisplay;
        }

        @Override
        public String toString() { // SCIPIO: Added 2018-09-25
            return "[webappName=" + name 
                    + ", componentName=" + componentConfig.getGlobalName() 
                    + ", contextRoot=" + contextRoot
                    + ", server=" + server
                    + "]";
        }
    }
}
