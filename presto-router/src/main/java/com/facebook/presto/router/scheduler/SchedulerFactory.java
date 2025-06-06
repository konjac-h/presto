/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.router.scheduler;

import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.router.Scheduler;

import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static java.util.Objects.requireNonNull;

public class SchedulerFactory
{
    private final SchedulerType schedulerType;
    private final CustomSchedulerManager schedulerManager;

    public SchedulerFactory(SchedulerType schedulerType, CustomSchedulerManager schedulerManager)
    {
        this.schedulerType = requireNonNull(schedulerType, "schedulerType is null");
        this.schedulerManager = requireNonNull(schedulerManager, "schedulerManager is null");
    }

    public Scheduler create()
    {
        switch (schedulerType) {
            case RANDOM_CHOICE:
                return new RandomChoiceScheduler();
            case WEIGHTED_RANDOM_CHOICE:
                return new WeightedRandomChoiceScheduler();
            case USER_HASH:
                return new UserHashScheduler();
            case ROUND_ROBIN:
                return new RoundRobinScheduler();
            case WEIGHTED_ROUND_ROBIN:
                return new WeightedRoundRobinScheduler();
            case CUSTOM_PLUGIN_SCHEDULER:
                schedulerManager.loadScheduler();
                return schedulerManager.getScheduler();
        }
        throw new PrestoException(NOT_SUPPORTED, "Unsupported router scheduler type " + schedulerType);
    }
}
