/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.rpc.client;

import com.alipay.sofa.rpc.bootstrap.ConsumerBootstrap;
import com.alipay.sofa.rpc.common.struct.OrderedComparator;
import com.alipay.sofa.rpc.common.utils.CommonUtils;
import com.alipay.sofa.rpc.common.utils.StringUtils;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.ext.ExtensionClass;
import com.alipay.sofa.rpc.ext.ExtensionLoader;
import com.alipay.sofa.rpc.ext.ExtensionLoaderFactory;
import com.alipay.sofa.rpc.ext.ExtensionLoaderListener;
import com.alipay.sofa.rpc.filter.AutoActive;
import com.alipay.sofa.rpc.log.Logger;
import com.alipay.sofa.rpc.log.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Chain of routers
 *
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class RouterChain {

    /**
     * LOGGER
     */
    private static final Logger                              LOGGER                = LoggerFactory
                                                                                       .getLogger(RouterChain.class);

    /**
     * ???????????????????????? {"alias":ExtensionClass}
     */
    private final static Map<String, ExtensionClass<Router>> PROVIDER_AUTO_ACTIVES = Collections
                                                                                       .synchronizedMap(new ConcurrentHashMap<String, ExtensionClass<Router>>());

    /**
     * ???????????????????????? {"alias":ExtensionClass}
     */
    private final static Map<String, ExtensionClass<Router>> CONSUMER_AUTO_ACTIVES = Collections
                                                                                       .synchronizedMap(new ConcurrentHashMap<String, ExtensionClass<Router>>());

    /**
     * ???????????????
     */
    private final static ExtensionLoader<Router>             EXTENSION_LOADER      = buildLoader();

    private static ExtensionLoader<Router> buildLoader() {
        ExtensionLoader<Router> extensionLoader = ExtensionLoaderFactory.getExtensionLoader(Router.class);
        extensionLoader.addListener(new ExtensionLoaderListener<Router>() {
            @Override
            public void onLoad(ExtensionClass<Router> extensionClass) {
                Class<? extends Router> implClass = extensionClass.getClazz();
                // ?????????????????????????????????
                AutoActive autoActive = implClass.getAnnotation(AutoActive.class);
                if (autoActive != null) {
                    String alias = extensionClass.getAlias();
                    if (autoActive.providerSide()) {
                        PROVIDER_AUTO_ACTIVES.put(alias, extensionClass);
                    }
                    if (autoActive.consumerSide()) {
                        CONSUMER_AUTO_ACTIVES.put(alias, extensionClass);
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Extension of interface " + Router.class + ", " + implClass + "(" + alias +
                            ") will auto active");
                    }
                }
            }
        });
        return extensionLoader;
    }

    /**
     * ?????????
     */
    private final List<Router> routers;

    public RouterChain(List<Router> actualRouters, ConsumerBootstrap consumerBootstrap) {
        this.routers = new ArrayList<Router>();
        if (CommonUtils.isNotEmpty(actualRouters)) {
            for (Router router : actualRouters) {
                if (router.needToLoad(consumerBootstrap)) {
                    router.init(consumerBootstrap);
                    routers.add(router);
                }
            }
        }
    }

    /**
     * ??????Provider
     *
     * @param request       ??????????????????????????????????????????????????????????????????????????????
     * @param providerInfos providers???<b>????????????</b>?????????Provider?????????
     * @return ?????????????????????Provider??????
     */
    public List<ProviderInfo> route(SofaRequest request, List<ProviderInfo> providerInfos) {
        for (Router router : routers) {
            providerInfos = router.route(request, providerInfos);
        }
        return providerInfos;
    }

    /**
     * ??????Router???
     *
     * @param consumerBootstrap ????????????????????????
     * @return ?????????
     */
    public static RouterChain buildConsumerChain(ConsumerBootstrap consumerBootstrap) {
        ConsumerConfig<?> consumerConfig = consumerBootstrap.getConsumerConfig();
        List<Router> customRouters = consumerConfig.getRouterRef() == null ? new ArrayList<Router>()
            : new CopyOnWriteArrayList<Router>(consumerConfig.getRouterRef());
        // ??????????????????????????????
        HashSet<String> excludes = parseExcludeRouter(customRouters);

        // ???????????????????????????????????????????????????router???????????????
        List<ExtensionClass<Router>> extensionRouters = new ArrayList<ExtensionClass<Router>>();
        List<String> routerAliases = consumerConfig.getRouter();
        if (CommonUtils.isNotEmpty(routerAliases)) {
            for (String routerAlias : routerAliases) {
                if (startsWithExcludePrefix(routerAlias)) { // ????????????????????????
                    excludes.add(routerAlias.substring(1));
                } else {
                    extensionRouters.add(EXTENSION_LOADER.getExtensionClass(routerAlias));
                }
            }
        }
        // ?????????????????????router
        if (!excludes.contains(StringUtils.ALL) && !excludes.contains(StringUtils.DEFAULT)) { // ??????-*???-default?????????????????????
            for (Map.Entry<String, ExtensionClass<Router>> entry : CONSUMER_AUTO_ACTIVES.entrySet()) {
                if (!excludes.contains(entry.getKey())) {
                    extensionRouters.add(entry.getValue());
                }
            }
        }
        excludes = null; // ????????????
        // ???order??????????????????
        if (extensionRouters.size() > 1) {
            Collections.sort(extensionRouters, new OrderedComparator<ExtensionClass>());
        }
        List<Router> actualRouters = new ArrayList<Router>();
        for (ExtensionClass<Router> extensionRouter : extensionRouters) {
            Router actualRoute = extensionRouter.getExtInstance();
            actualRouters.add(actualRoute);
        }
        // ???????????????????????????
        actualRouters.addAll(customRouters);
        return new RouterChain(actualRouters, consumerBootstrap);
    }

    /**
     * ???????????????????????????????????????
     *
     * @param customRouters ?????????Router
     * @return ????????????
     */
    private static HashSet<String> parseExcludeRouter(List<Router> customRouters) {
        HashSet<String> excludeKeys = new HashSet<String>();
        if (CommonUtils.isNotEmpty(customRouters)) {
            for (Router router : customRouters) {
                if (router instanceof ExcludeRouter) {
                    // ??????????????????????????????
                    ExcludeRouter excludeRouter = (ExcludeRouter) router;
                    String excludeName = excludeRouter.getExcludeName();
                    if (StringUtils.isNotEmpty(excludeName)) {
                        String excludeRouterName = startsWithExcludePrefix(excludeName) ? excludeName.substring(1)
                            : excludeName;
                        if (StringUtils.isNotEmpty(excludeRouterName)) {
                            excludeKeys.add(excludeRouterName);
                        }
                    }
                    customRouters.remove(router);
                }
            }
        }
        if (!excludeKeys.isEmpty()) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Find exclude routers: {}", excludeKeys);
            }
        }
        return excludeKeys;
    }

    private static boolean startsWithExcludePrefix(String excludeName) {
        char c = excludeName.charAt(0);
        return c == '-' || c == '!';
    }
}
