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
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.banyandb;

import org.apache.skywalking.oap.server.core.CoreModule;
import org.apache.skywalking.oap.server.core.config.ConfigService;
import org.apache.skywalking.oap.server.core.storage.IBatchDAO;
import org.apache.skywalking.oap.server.core.storage.StorageBuilderFactory;
import org.apache.skywalking.oap.server.core.storage.StorageDAO;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.core.storage.cache.INetworkAddressAliasDAO;
import org.apache.skywalking.oap.server.core.storage.management.UITemplateManagementDAO;
import org.apache.skywalking.oap.server.core.storage.profile.IProfileTaskLogQueryDAO;
import org.apache.skywalking.oap.server.core.storage.profile.IProfileTaskQueryDAO;
import org.apache.skywalking.oap.server.core.storage.profile.IProfileThreadSnapshotQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.IAlarmQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.IBrowserLogQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.IEventQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.ILogQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.IMetadataQueryDAO;
import org.apache.skywalking.oap.server.core.storage.query.ITraceQueryDAO;
import org.apache.skywalking.oap.server.library.module.ModuleConfig;
import org.apache.skywalking.oap.server.library.module.ModuleDefine;
import org.apache.skywalking.oap.server.library.module.ModuleProvider;
import org.apache.skywalking.oap.server.library.module.ModuleStartException;
import org.apache.skywalking.oap.server.library.module.ServiceNotProvidedException;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBAlarmQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBBatchDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBBrowserLogQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBEventQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBLogQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBMetadataQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBNetworkAddressAliasDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBProfileTaskLogQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBProfileTaskQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBProfileThreadSnapshotQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBStorageDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBTraceQueryDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.BanyanDBUITemplateManagementDAO;
import org.apache.skywalking.oap.server.telemetry.TelemetryModule;
import org.apache.skywalking.oap.server.telemetry.api.HealthCheckMetrics;
import org.apache.skywalking.oap.server.telemetry.api.MetricsCreator;
import org.apache.skywalking.oap.server.telemetry.api.MetricsTag;

public class BanyanDBStorageProvider extends ModuleProvider {
    private BanyanDBStorageConfig config;
    private BanyanDBStorageClient client;

    public BanyanDBStorageProvider() {
        this.config = new BanyanDBStorageConfig();
    }

    @Override
    public String name() {
        return "banyandb";
    }

    @Override
    public Class<? extends ModuleDefine> module() {
        return StorageModule.class;
    }

    @Override
    public ModuleConfig createConfigBeanIfAbsent() {
        return config;
    }

    @Override
    public void prepare() throws ServiceNotProvidedException, ModuleStartException {
        this.registerServiceImplementation(StorageBuilderFactory.class, new BanyanDBStorageBuilderFactory());

        this.client = new BanyanDBStorageClient(config.getHost(), config.getPort(), config.getGroup());

        this.registerServiceImplementation(IBatchDAO.class, new BanyanDBBatchDAO(client, config.getMaxBulkSize(), config.getFlushInterval(), config.getConcurrentWriteThreads()));
        this.registerServiceImplementation(StorageDAO.class, new BanyanDBStorageDAO(client));

        this.registerServiceImplementation(INetworkAddressAliasDAO.class, new BanyanDBNetworkAddressAliasDAO(client));

        this.registerServiceImplementation(ITraceQueryDAO.class, new BanyanDBTraceQueryDAO(client));
        this.registerServiceImplementation(IBrowserLogQueryDAO.class, new BanyanDBBrowserLogQueryDAO(client));
        this.registerServiceImplementation(IMetadataQueryDAO.class, new BanyanDBMetadataQueryDAO(client));
        this.registerServiceImplementation(IAlarmQueryDAO.class, new BanyanDBAlarmQueryDAO(client));
        this.registerServiceImplementation(ILogQueryDAO.class, new BanyanDBLogQueryDAO(client));

        this.registerServiceImplementation(IProfileTaskQueryDAO.class, new BanyanDBProfileTaskQueryDAO(client));
        this.registerServiceImplementation(IProfileTaskLogQueryDAO.class, new BanyanDBProfileTaskLogQueryDAO(client, this.config.getFetchTaskLogMaxSize()));
        this.registerServiceImplementation(
                IProfileThreadSnapshotQueryDAO.class, new BanyanDBProfileThreadSnapshotQueryDAO(client));
        this.registerServiceImplementation(UITemplateManagementDAO.class, new BanyanDBUITemplateManagementDAO(client));

        this.registerServiceImplementation(IEventQueryDAO.class, new BanyanDBEventQueryDAO(client));

    }

    @Override
    public void start() throws ServiceNotProvidedException, ModuleStartException {
        final ConfigService configService = getManager().find(CoreModule.NAME)
                .provider()
                .getService(ConfigService.class);

        MetricsCreator metricCreator = getManager().find(TelemetryModule.NAME)
                .provider()
                .getService(MetricsCreator.class);
        HealthCheckMetrics healthChecker = metricCreator.createHealthCheckerGauge(
                "storage_banyandb", MetricsTag.EMPTY_KEY, MetricsTag.EMPTY_VALUE);
        this.client.registerChecker(healthChecker);
        try {
            this.client.connect();
        } catch (Exception e) {
            throw new ModuleStartException(e.getMessage(), e);
        }
    }

    @Override
    public void notifyAfterCompleted() throws ServiceNotProvidedException, ModuleStartException {

    }

    @Override
    public String[] requiredModules() {
        return new String[]{CoreModule.NAME};
    }
}
