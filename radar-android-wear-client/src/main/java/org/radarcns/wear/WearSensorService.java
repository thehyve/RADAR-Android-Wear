/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.wear;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceService;
import org.radarcns.key.MeasurementKey;
import org.radarcns.topic.AvroTopic;

import java.util.Arrays;
import java.util.List;

import static org.radarcns.android.RadarConfiguration.SOURCE_ID_KEY;

/**
 * A service that manages the phone sensor manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
public class WearSensorService extends DeviceService {
    private String sourceId;

    @Override
    protected WearSensorManager createDeviceManager() {
        return new WearSensorManager(this, getDataHandler(), getUserId(), getSourceId());
    }

    @Override
    protected BaseDeviceState getDefaultState() {
        return new WearState();
    }

    @Override
    protected WearSensorTopics getTopics() {
        return WearSensorTopics.getInstance();
    }

    @Override
    protected List<AvroTopic<MeasurementKey, ? extends SpecificRecord>> getCachedTopics() {
        return Arrays.<AvroTopic<MeasurementKey, ? extends SpecificRecord>>asList(
                 getTopics().getAccelerationTopic()
                ,getTopics().getLightTopic()
                ,getTopics().getGyroscopeTopic()
                ,getTopics().getMagneticFieldTopic()
                ,getTopics().getStepCountTopic()
        );
    }

    public String getSourceId() {
        if (sourceId == null) {
            sourceId = RadarConfiguration.getOrSetUUID(getApplicationContext(), SOURCE_ID_KEY);
        }
        return sourceId;
    }
}
