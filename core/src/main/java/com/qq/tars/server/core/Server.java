/**
 * Tencent is pleased to support the open source community by making Tars available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.qq.tars.server.core;

import java.io.IOException;
import java.util.Map.Entry;

import com.qq.tars.client.Communicator;
import com.qq.tars.client.CommunicatorConfig;
import com.qq.tars.client.CommunicatorFactory;
import com.qq.tars.common.util.BeanAccessor;
import com.qq.tars.common.util.StringUtils;
import com.qq.tars.net.core.SessionManager;
import com.qq.tars.register.RegisterManager;
import com.qq.tars.server.common.ServerLogger;
import com.qq.tars.server.config.ConfigurationManager;
import com.qq.tars.server.config.ServantAdapterConfig;
import com.qq.tars.server.config.ServerConfig;
import com.qq.tars.server.ha.ConnectionSessionListener;
import com.qq.tars.support.log.LogConfCacheMngr;
import com.qq.tars.support.log.LoggerFactory;
import com.qq.tars.support.om.OmConstants;
import com.qq.tars.support.om.OmServiceMngr;

public class Server {

    private AppContext appContext = null;
    private ServerConfig serverConfig;

    public Server(ServerConfig config) {
        this.serverConfig = config;
    }

    /**
     * 启动server服务
     * @param appContext
     */
    public void startUp(AppContext appContext) {
        try {

//            initCommunicator();
//
//            configLogger();
//
//            startManagerService();

            //启动app上线文
            startAppContext(appContext);

            //启动session会话管理，剔除超时的会话[默认120秒]
            startSessionManager();

            //注册hook，结束时关闭reactor
            registerServerHook();

            System.out.println("[SERVER] server is ready...");
        } catch (Throwable ex) {
            System.out.println("[SERVER] failed to start server...");
            ex.printStackTrace();
            System.out.close();
            System.err.close();
            System.exit(-1);
        }
    }

    private void startAppContext(AppContext appContext) throws Exception {
        AppContextManager.getInstance().setAppContext(appContext);
        this.appContext = appContext;
        appContext.init();
    }

    /**
     * 开始服务管理
     */
    public static void startManagerService() {
        OmServiceMngr.getInstance().initAndStartOmService();
    }

    /**
     * 初始化server communicator
     * 设置bean
     */
    public static void initCommunicator() {
        CommunicatorConfig config = ConfigurationManager.getInstance().getServerConfig().getCommunicatorConfig();
        Communicator communicator = CommunicatorFactory.getInstance().getCommunicator(config);
        BeanAccessor.setBeanValue(CommunicatorFactory.getInstance(), "communicator", communicator);
    }

    public static void configLogger() {
        String objName = ConfigurationManager.getInstance().getServerConfig().getLog();
        String appName = ConfigurationManager.getInstance().getServerConfig().getApplication();
        String serviceName = ConfigurationManager.getInstance().getServerConfig().getServerName();

        String defaultLevel = ConfigurationManager.getInstance().getServerConfig().getLogLevel();
        String defaultRoot = ConfigurationManager.getInstance().getServerConfig().getLogPath();
        String dataPath = ConfigurationManager.getInstance().getServerConfig().getDataPath();

        LoggerFactory.config(CommunicatorFactory.getInstance().getCommunicator()
                , objName, appName, serviceName, defaultLevel, defaultRoot);

        LogConfCacheMngr.getInstance().init(dataPath);
        if (StringUtils.isNotEmpty(LogConfCacheMngr.getInstance().getLevel())) {
            LoggerFactory.setDefaultLoggerLevel(LogConfCacheMngr.getInstance().getLevel());
        }

        ServerLogger.init();
    }

    /**
     * 加载服务器配置
     */
    public static void loadServerConfig() {
        try {
            //解决TAF配置文件,初始化服务器配置
            ConfigurationManager.getInstance().init();

            ServerConfig cfg = ConfigurationManager.getInstance().getServerConfig();
            ServerLogger.initNamiCoreLog(cfg.getLogPath(), cfg.getLogLevel());
            //设置UDP buffersize
            System.setProperty("com.qq.nami.server.udp.bufferSize", String.valueOf(cfg.getUdpBufferSize()));
            //设置根目录
            System.setProperty("server.root", cfg.getBasePath());
        } catch (Throwable ex) {
            ex.printStackTrace(System.err);
            System.err.println("The exception occured at load server config");
            System.exit(2);
        }
    }

    private void startSessionManager() throws IOException {
        SessionManager sessionManager = SessionManager.getSessionManager();
        sessionManager.setTimeout(serverConfig.getSessionTimeOut());
        sessionManager.setCheckInterval(serverConfig.getSessionCheckInterval());

        int connCount = 0;
        for (Entry<String, ServantAdapterConfig> adapterConfigEntry : ConfigurationManager.getInstance().getServerConfig().getServantAdapterConfMap().entrySet()) {
            if (OmConstants.AdminServant.equals(adapterConfigEntry.getKey())) {
                continue;
            }
            connCount += adapterConfigEntry.getValue().getMaxConns();
        }
        ConnectionSessionListener sessionListener = new ConnectionSessionListener(connCount);
        sessionManager.addSessionListener(sessionListener);

        sessionManager.start();
    }

    private void registerServerHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                try {
                    if (appContext != null) {
                        appContext.stop();
                    }
                } catch (Exception ex) {
                    System.err.println("The exception occured at stopping server...");
                }
            }
        });
    }
}
