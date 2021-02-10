/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.lease;

import com.netflix.eureka.registry.AbstractInstanceRegistry;

/**
 * Describes a time-based availability of a {@link T}. Purpose is to avoid
 * accumulation of instances in {@link AbstractInstanceRegistry} as result of ungraceful
 * shutdowns that is not uncommon in AWS environments.
 *
 * If a lease elapses without renewals, it will eventually expire consequently
 * marking the associated {@link T} for immediate eviction - this is similar to
 * an explicit cancellation except that there is no communication between the
 * {@link T} and {@link LeaseManager}.
 *
 * @author Karthik Ranganathan, Greg Kim
 */
public class Lease<T> {

    enum Action {
        Register, Cancel, Renew
    };

    public static final int DEFAULT_DURATION_IN_SECS = 90;

    private T holder;
    private long evictionTimestamp;
    private long registrationTimestamp;
    private long serviceUpTimestamp;
    // Make it volatile so that the expiration task would see this quicker
    private volatile long lastUpdateTimestamp;
    private long duration;

    public Lease(T r, int durationInSecs) {
        holder = r;
        registrationTimestamp = System.currentTimeMillis();
        lastUpdateTimestamp = registrationTimestamp;
        duration = (durationInSecs * 1000);

    }

    /**
     * Renew the lease, use renewal duration if it was specified by the
     * associated {@link T} during registration, otherwise default duration is
     * {@link #DEFAULT_DURATION_IN_SECS}.
     */
    public void renew() {
        //每次人家服务发送一次心跳，保持心跳，其实靠的就是刷新这个lastUpdateTimestamp
        //类似于是最近一次活跃的时间
        //比如说上一次心跳的时间，是19:55:00+90s=19:56:30
        lastUpdateTimestamp = System.currentTimeMillis() + duration; //duration默认是90秒

    }

    /**
     * Cancels the lease by updating the eviction time.
     */
    public void cancel() {
        if (evictionTimestamp <= 0) {
            evictionTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Mark the service as up. This will only take affect the first time called,
     * subsequent calls will be ignored.
     */
    public void serviceUp() {
        if (serviceUpTimestamp == 0) {
            serviceUpTimestamp = System.currentTimeMillis();
        }
    }

    /**
     * Set the leases service UP timestamp.
     */
    public void setServiceUpTimestamp(long serviceUpTimestamp) {
        this.serviceUpTimestamp = serviceUpTimestamp;
    }

    /**
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     */
    public boolean isExpired() {
        return isExpired(0l);
    }

    /**
     * Checks if the lease of a given {@link com.netflix.appinfo.InstanceInfo} has expired or not.
     *
     * Note that due to renew() doing the 'wrong" thing and setting lastUpdateTimestamp to +duration more than
     * what it should be, the expiry will actually be 2 * duration. This is a minor bug and should only affect
     * instances that ungracefully shutdown. Due to possible wide ranging impact to existing usage, this will
     * not be fixed.
     *
     * @param additionalLeaseMs any additional lease time to add to the lease evaluation in ms.
     */
    public boolean isExpired(long additionalLeaseMs) {
        //当前时间是否大于了上一次心跳的时间，再加上90s，再加上additionalLeaseMs(compensationsTimes，补偿时间，92s)
        //20:02:32
        //20:02:32>19:56:30+90s+92s=19:58:00+92s=19:59:32

        //不看那个补偿时间，假如说，当前时间比上次心跳的时间差了超过90s，说明，90s之内，都没有更新过心跳
        //就说明那个服务实例在90s秒内没有更新过心跳
        //此时就任务那个服务实例可能已经宕机了

        //19:55:00 -> 如果到了19:56:30,没有心跳，就该认为服务实例故障了
        //19:56:00 -> 如果到了19:56:30,没有心跳，还不会认为服务实例宕机了
        //19:58:01 -> 19:56:30+90s=19:58:00+92s=19:59:32
        //你差不多至少要到20:00:00，才能认为这个服务实例过期了

        //你需要等待2个90s之后，都没有心跳，才会认为这个服务实例挂掉了，才会下线，90s,180s,3分钟没有心跳
        //才会认为这个服务实例宕机了，下线了
        //如果你没有看过源码，你可能就会犯错了，你出面试，人家问你，多长时间没有心跳，会认为服务实例挂了，90s
        //源码层面是有bug的，90*2=180s,才会服务实例下线
        //失效多级缓存，30s才能同步，服务30s才会重新抓取增量注册表

        //一个服务实例挂掉了之后，可能要过几分钟，四五分钟，才能让其他的服务感知到


        return (evictionTimestamp > 0 || System.currentTimeMillis() > (lastUpdateTimestamp + duration + additionalLeaseMs));
    }

    /**
     * Gets the milliseconds since epoch when the lease was registered.
     *
     * @return the milliseconds since epoch when the lease was registered.
     */
    public long getRegistrationTimestamp() {
        return registrationTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was last renewed.
     * Note that the value returned here is actually not the last lease renewal time but the renewal + duration.
     *
     * @return the milliseconds since epoch when the lease was last renewed.
     */
    public long getLastRenewalTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the lease was evicted.
     *
     * @return the milliseconds since epoch when the lease was evicted.
     */
    public long getEvictionTimestamp() {
        return evictionTimestamp;
    }

    /**
     * Gets the milliseconds since epoch when the service for the lease was marked as up.
     *
     * @return the milliseconds since epoch when the service for the lease was marked as up.
     */
    public long getServiceUpTimestamp() {
        return serviceUpTimestamp;
    }

    /**
     * Returns the holder of the lease.
     */
    public T getHolder() {
        return holder;
    }

}
