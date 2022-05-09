/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.client.naming.core;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.client.naming.cache.ServiceInfoHolder;
import com.alibaba.nacos.client.naming.event.InstancesChangeNotifier;
import com.alibaba.nacos.client.naming.remote.NamingClientProxy;
import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.alibaba.nacos.client.naming.utils.UtilAndComs;
import com.alibaba.nacos.common.executor.NameThreadFactory;
import com.alibaba.nacos.common.lifecycle.Closeable;
import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.common.utils.ThreadUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.alibaba.nacos.client.utils.LogUtils.NAMING_LOGGER;

/**
 * <ul>服务信息更新服务/ </ul>
 * Service information update service.
 *
 * @author xiweng.yy
 */
public class ServiceInfoUpdateService implements Closeable {
    
    private static final long DEFAULT_DELAY = 1000L;
    
    private static final int DEFAULT_UPDATE_CACHE_TIME_MULTIPLE = 6;
    
    private final Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();
    
    private final ServiceInfoHolder serviceInfoHolder;
    
    private final ScheduledExecutorService executor;
    
    private final NamingClientProxy namingClientProxy;
    
    private final InstancesChangeNotifier changeNotifier;
    
    public ServiceInfoUpdateService(Properties properties, ServiceInfoHolder serviceInfoHolder,
            NamingClientProxy namingClientProxy, InstancesChangeNotifier changeNotifier) {
        this.executor = new ScheduledThreadPoolExecutor(initPollingThreadCount(properties),
                new NameThreadFactory("com.alibaba.nacos.client.naming.updater"));
        this.serviceInfoHolder = serviceInfoHolder;
        this.namingClientProxy = namingClientProxy;
        this.changeNotifier = changeNotifier;
    }
    
    private int initPollingThreadCount(Properties properties) {
        if (properties == null) {
            return UtilAndComs.DEFAULT_POLLING_THREAD_COUNT;
        }
        return ConvertUtils.toInt(properties.getProperty(PropertyKeyConst.NAMING_POLLING_THREAD_COUNT),
                UtilAndComs.DEFAULT_POLLING_THREAD_COUNT);
    }
    
    /**
     * Schedule update if absent.
     *
     * @param serviceName service name
     * @param groupName   group name
     * @param clusters    clusters
     */
    public void scheduleUpdateIfAbsent(String serviceName, String groupName, String clusters) {
        //serviceKey:clusterName@@groupName@@serviceName
        String serviceKey = ServiceInfo.getKey(NamingUtils.getGroupedName(serviceName, groupName), clusters);
        //futureMap有serviceKey对应的值，则结束该代码块儿
        if (futureMap.get(serviceKey) != null) {
            return;
        }
        synchronized (futureMap) {
            //双重检查，避免在等待锁的过程中，被放入
            if (futureMap.get(serviceKey) != null) {
                return;
            }
            //放入一个新的任务
            ScheduledFuture<?> future = addTask(new UpdateTask(serviceName, groupName, clusters));
            futureMap.put(serviceKey, future);
        }
    }

    /**
     * 添加一个新的定时任务
     * @param task  要添加的新的任务
     * @return
     */
    private synchronized ScheduledFuture<?> addTask(UpdateTask task) {
        return executor.schedule(task, DEFAULT_DELAY, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Stop to schedule update if contain task.
     *
     * @param serviceName service name
     * @param groupName   group name
     * @param clusters    clusters
     */
    public void stopUpdateIfContain(String serviceName, String groupName, String clusters) {
        String serviceKey = ServiceInfo.getKey(NamingUtils.getGroupedName(serviceName, groupName), clusters);
        if (!futureMap.containsKey(serviceKey)) {
            return;
        }
        synchronized (futureMap) {
            if (!futureMap.containsKey(serviceKey)) {
                return;
            }
            futureMap.remove(serviceKey);
        }
    }
    
    @Override
    public void shutdown() throws NacosException {
        String className = this.getClass().getName();
        NAMING_LOGGER.info("{} do shutdown begin", className);
        ThreadUtils.shutdownThreadPool(executor, NAMING_LOGGER);
        NAMING_LOGGER.info("{} do shutdown stop", className);
    }
    
    public class UpdateTask implements Runnable {
        
        long lastRefTime = Long.MAX_VALUE;
        
        private boolean isCancel;
        
        private final String serviceName;
        
        private final String groupName;
        
        private final String clusters;
        
        private final String groupedServiceName;
        
        private final String serviceKey;
        
        /**
         * the fail situation. 1:can't connect to server 2:serviceInfo's hosts is empty
         */
        private int failCount = 0;
        
        public UpdateTask(String serviceName, String groupName, String clusters) {
            this.serviceName = serviceName;
            this.groupName = groupName;
            this.clusters = clusters;
            this.groupedServiceName = NamingUtils.getGroupedName(serviceName, groupName);
            this.serviceKey = ServiceInfo.getKey(groupedServiceName, clusters);
        }
        
        @Override
        public void run() {
            long delayTime = DEFAULT_DELAY;
            
            try {
                //没有订阅过，则取消该任务的执行
                if (!changeNotifier.isSubscribed(groupName, serviceName, clusters) && !futureMap.containsKey(
                        serviceKey)) {
                    NAMING_LOGGER.info("update task is stopped, service:{}, clusters:{}", groupedServiceName, clusters);
                    isCancel = true;
                    return;
                }
                
                ServiceInfo serviceObj = serviceInfoHolder.getServiceInfoMap().get(serviceKey);
                if (serviceObj == null) {
                    //如果serviceObj  为空，则重新查询，并更新到 serviceInfoHolder中
                    serviceObj = namingClientProxy.queryInstancesOfService(serviceName, groupName, clusters, 0, false);
                    serviceInfoHolder.processServiceInfo(serviceObj);
                    lastRefTime = serviceObj.getLastRefTime();
                    return;
                }
                // 过期服务，服务的最新更新时间小于等于缓存刷新（最后一次拉取数据的时间）时间，从注册中心重新查询
                if (serviceObj.getLastRefTime() <= lastRefTime) {
                    //没有更新，则查询并更新
                    serviceObj = namingClientProxy.queryInstancesOfService(serviceName, groupName, clusters, 0, false);
                    serviceInfoHolder.processServiceInfo(serviceObj);
                }
                //变更最后引用时间
                lastRefTime = serviceObj.getLastRefTime();
                if (CollectionUtils.isEmpty(serviceObj.getHosts())) {
                    incFailCount();
                    return;
                }
                // TODO multiple time can be configured.
                //cacheMillis:1000L,1000L*6,6秒
                delayTime = serviceObj.getCacheMillis() * DEFAULT_UPDATE_CACHE_TIME_MULTIPLE;
                resetFailCount();
            } catch (Throwable e) {
                incFailCount();
                NAMING_LOGGER.warn("[NA] failed to update serviceName: {}", groupedServiceName, e);
            } finally {
                if (!isCancel) {
                    //最后设置任务下次开始执行的时间。最多延迟6W毫秒/60秒/1分钟
                    // 下次调度刷新时间，下次执行的时间与failCount有关，failCount=0，则下次调度时间为6秒，最长为1分钟
                    // 即当无异常情况下缓存实例的刷新时间是6秒
                    executor.schedule(this, Math.min(delayTime << failCount, DEFAULT_DELAY * 60),
                            TimeUnit.MILLISECONDS);
                }
            }
        }

        /**
         * 失败计数递增
         */
        private void incFailCount() {
            int limit = 6;
            if (failCount == limit) {
                return;
            }
            failCount++;
        }

        /**
         * 重置失败计数为0；
         */
        private void resetFailCount() {
            failCount = 0;
        }
    }
}
