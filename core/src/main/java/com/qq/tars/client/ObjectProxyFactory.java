/**
 * Tencent is pleased to support the open source community by making Tars available.
 * <p>
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 * <p>
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * https://opensource.org/licenses/BSD-3-Clause
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.qq.tars.client;

import com.qq.tars.client.rpc.loadbalance.DefaultLoadBalance;
import com.qq.tars.client.rpc.tars.TarsProtocolInvoker;
import com.qq.tars.client.support.ServantCacheManager;
import com.qq.tars.client.util.ClientLogger;
import com.qq.tars.client.util.ParseTools;
import com.qq.tars.common.util.StringUtils;
import com.qq.tars.protocol.annotation.Servant;
import com.qq.tars.protocol.annotation.ServantCodec;
import com.qq.tars.register.RegisterManager;
import com.qq.tars.rpc.common.LoadBalance;
import com.qq.tars.rpc.common.ProtocolInvoker;
import com.qq.tars.rpc.exc.ClientException;
import com.qq.tars.rpc.exc.CommunicatorConfigException;
import com.qq.tars.rpc.protocol.Codec;
import com.qq.tars.rpc.protocol.ServantProtocolFactory;
import com.qq.tars.rpc.protocol.tars.TarsCodec;

import java.lang.reflect.Constructor;

/**
 * 对象代理工厂
 */
class ObjectProxyFactory {

    private final Communicator communicator;

    public ObjectProxyFactory(Communicator communicator) {
        this.communicator = communicator;
    }

    /**
     * 拿到servant对象代理
     * @param api
     * @param objName
     * @param setDivision
     * @param servantProxyConfig
     * @param loadBalance
     * @param protocolInvoker
     * @param <T>
     * @return
     * @throws ClientException
     */
    public <T> ObjectProxy<T> getObjectProxy(Class<T> api, String objName, String setDivision, ServantProxyConfig servantProxyConfig,
                                             LoadBalance<T> loadBalance, ProtocolInvoker<T> protocolInvoker) throws ClientException {
        if (servantProxyConfig == null) {
            //创建默认的servantProxy
            servantProxyConfig = createServantProxyConfig(objName, setDivision);
        } else {
            //已经配置过
            servantProxyConfig.setCommunicatorId(communicator.getId());
            servantProxyConfig.setModuleName(communicator.getCommunicatorConfig().getModuleName(), communicator.getCommunicatorConfig().isEnableSet(), communicator.getCommunicatorConfig().getSetDivision());
            //这里配置locator，如果设置过则使用自己配置的，否则使用默认值
            servantProxyConfig.setLocator(communicator.getCommunicatorConfig().getLocator());
            if (StringUtils.isNotEmpty(setDivision)) {
                //分区
                servantProxyConfig.setSetDivision(setDivision);
            }
        }

        //更新servant的ip/port
        updateServantEndpoints(servantProxyConfig);

        //负载均衡器
        if (loadBalance == null) {
            //初始化默认负载均衡
            loadBalance = createLoadBalance(servantProxyConfig);
        }

        if (protocolInvoker == null) {
            //初始化protocolInvoker
            protocolInvoker = createProtocolInvoker(api, servantProxyConfig);
        }

        //对象代理
        return new ObjectProxy<T>(api, servantProxyConfig, loadBalance, protocolInvoker, communicator);
    }

    /**
     * 创建protocolInvoker
     * @param api
     * @param servantProxyConfig
     * @param <T>
     * @return
     * @throws ClientException
     */
    private <T> ProtocolInvoker<T> createProtocolInvoker(Class<T> api,
                                                         ServantProxyConfig servantProxyConfig) throws ClientException {
        ProtocolInvoker<T> protocolInvoker = null;
        //创建TarsCodec
        Codec codec = createCodec(api, servantProxyConfig);
        if (api.isAnnotationPresent(Servant.class)) {
            if (codec == null) {
                //默认TarsCodec
                codec = new TarsCodec(servantProxyConfig.getCharsetName());
            }
            //protobuff or tars
            servantProxyConfig.setProtocol(codec.getProtocol());

            //创建TarsProtocolInvoker
            protocolInvoker = new TarsProtocolInvoker<T>(api, servantProxyConfig, new ServantProtocolFactory(codec), communicator.getThreadPoolExecutor());
        } else {
            throw new ClientException(servantProxyConfig.getSimpleObjectName(), "unkonw protocol servant invoker", null);
        }
        return protocolInvoker;
    }

    private <T> LoadBalance<T> createLoadBalance(ServantProxyConfig servantProxyConfig) {
        return new DefaultLoadBalance<T>(servantProxyConfig);
    }

    private <T> Codec createCodec(Class<T> api, ServantProxyConfig servantProxyConfig) throws ClientException {
        Codec codec = null;
        ServantCodec servantCodec = api.getAnnotation(ServantCodec.class);
        if (servantCodec != null) {
            Class<? extends Codec> codecClass = servantCodec.codec();
            if (codecClass != null) {
                Constructor<? extends Codec> constructor;
                try {
                    constructor = codecClass.getConstructor(new Class[]{String.class});
                    codec = constructor.newInstance(servantProxyConfig.getCharsetName());
                } catch (Exception e) {
                    throw new ClientException(servantProxyConfig.getSimpleObjectName(), "error occurred on create codec, codec=" + codecClass.getName(), e);
                }
            }
        }
        return codec;
    }

    private ServantProxyConfig createServantProxyConfig(String objName, String setDivision) throws CommunicatorConfigException {
        CommunicatorConfig communicatorConfig = communicator.getCommunicatorConfig();
        ServantProxyConfig cfg = new ServantProxyConfig(communicator.getId(), communicatorConfig.getLocator(), objName);
        cfg.setAsyncTimeout(communicatorConfig.getAsyncInvokeTimeout());
        cfg.setSyncTimeout(communicatorConfig.getSyncInvokeTimeout());

        if (StringUtils.isNotEmpty(setDivision)) {
            cfg.setSetDivision(setDivision);
        }

        cfg.setModuleName(communicatorConfig.getModuleName(), communicatorConfig.isEnableSet(), communicatorConfig.getSetDivision());
        cfg.setStat(communicatorConfig.getStat());
        cfg.setCharsetName(communicatorConfig.getCharsetName());
        cfg.setConnections(communicatorConfig.getConnections());
        return cfg;
    }

    /**
     * 更新servant服务的endpoints
     * @param cfg
     */
    private void updateServantEndpoints(ServantProxyConfig cfg) {
        CommunicatorConfig communicatorConfig = communicator.getCommunicatorConfig();

        String endpoints = null;
        if (!ParseTools.hasServerNode(cfg.getObjectName()) //没有配置server node
                && !cfg.isDirectConnection()  //非直连
                && !communicatorConfig.getLocator().startsWith(cfg.getSimpleObjectName())) {
            try {
                /** 从主控拉取server node */
                if (RegisterManager.getInstance().getHandler() != null) {
                    endpoints = ParseTools.parse(RegisterManager.getInstance().getHandler().query(cfg.getSimpleObjectName()),
                            cfg.getSimpleObjectName());
                } else {
                    //拿到服务端node
                    endpoints = communicator.getQueryHelper().getServerNodes(cfg);
                }
                if (StringUtils.isEmpty(endpoints)) {
                    throw new CommunicatorConfigException(cfg.getSimpleObjectName(), "servant node is empty on get by registry! communicator id=" + communicator.getId());
                }
                //缓存到本地
                ServantCacheManager.getInstance().save(communicator.getId(), cfg.getSimpleObjectName(), endpoints, communicatorConfig.getDataPath());
            } catch (CommunicatorConfigException e) {
                /** 如果失败，从本地绶存文件中拉取 */
                endpoints = ServantCacheManager.getInstance().get(communicator.getId(), cfg.getSimpleObjectName(), communicatorConfig.getDataPath());
                ClientLogger.getLogger().error(cfg.getSimpleObjectName() + " error occurred on get by registry, use by local cache=" + endpoints + "|" + e.getLocalizedMessage(), e);
            }

            if (StringUtils.isEmpty(endpoints)) {
                throw new CommunicatorConfigException(cfg.getSimpleObjectName(), "error occurred on create proxy, servant endpoint is empty! locator =" + communicatorConfig.getLocator() + "|communicator id=" + communicator.getId());
            }
            cfg.setObjectName(endpoints);
        }

        if (StringUtils.isEmpty(cfg.getObjectName())) {
            throw new CommunicatorConfigException(cfg.getSimpleObjectName(), "error occurred on create proxy, servant endpoint is empty!");
        }
    }
}
