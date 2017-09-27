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

import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.SparseArray;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.*;
import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.AbstractDeviceManager;
import org.radarcns.android.device.DeviceManager;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.key.MeasurementKey;
import org.radarcns.phone.*;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static android.os.BatteryManager.*;

public class WearSensorManager extends AbstractDeviceManager<WearSensorService, WearState> implements DeviceManager, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
    private static final Logger logger = LoggerFactory.getLogger(WearSensorManager.class);
    private static final int TYPE_BATTERY_STATUS = -1;
    private static final SparseArray<BatteryStatus> BATTERY_TYPES = new SparseArray<>(5);

    static {
        BATTERY_TYPES.append(BATTERY_STATUS_UNKNOWN, BatteryStatus.UNKNOWN);
        BATTERY_TYPES.append(BATTERY_STATUS_CHARGING, BatteryStatus.CHARGING);
        BATTERY_TYPES.append(BATTERY_STATUS_DISCHARGING, BatteryStatus.DISCHARGING);
        BATTERY_TYPES.append(BATTERY_STATUS_NOT_CHARGING, BatteryStatus.NOT_CHARGING);
        BATTERY_TYPES.append(BATTERY_STATUS_FULL, BatteryStatus.FULL);
    }

    private final DataCache<MeasurementKey, PhoneAcceleration> accelerationTable;
    private final DataCache<MeasurementKey, PhoneLight> lightTable;
    private final DataCache<MeasurementKey, PhoneStepCount> stepCountTable;
    private final DataCache<MeasurementKey, PhoneGyroscope> gyroscopeTable;
    private final DataCache<MeasurementKey, PhoneMagneticField> magneticFieldTable;
    private final AvroTopic<MeasurementKey, PhoneBatteryLevel> batteryTopic;

    private final AtomicInteger lastStepCount = new AtomicInteger(-1);
    private GoogleApiClient googleApiClient;
    private ExecutorService sender;

    public WearSensorManager(WearSensorService context, TableDataHandler dataHandler, String groupId, String sourceId) {
        super(context, new WearState(), dataHandler, groupId, sourceId);
        WearSensorTopics topics = WearSensorTopics.getInstance();
        this.accelerationTable = dataHandler.getCache(topics.getAccelerationTopic());
        this.lightTable = dataHandler.getCache(topics.getLightTopic());
        this.stepCountTable = dataHandler.getCache(topics.getStepCountTopic());
        this.gyroscopeTable = dataHandler.getCache(topics.getGyroscopeTopic());
        this.magneticFieldTable = dataHandler.getCache(topics.getMagneticFieldTopic());
        this.batteryTopic = topics.getBatteryLevelTopic();

        setName("Wear");

        googleApiClient = new GoogleApiClient.Builder(getService())
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
    }

    @Override
    public void start(@NonNull final Set<String> acceptableIds) {
        sender = Executors.newSingleThreadExecutor();
        googleApiClient.connect();

        updateStatus(DeviceStatusListener.Status.CONNECTING);
    }

    @Override
    public void close() throws IOException {
        Wearable.DataApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
        sender.shutdown();
        super.close();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        updateStatus(DeviceStatusListener.Status.CONNECTING);
        Wearable.DataApi.addListener(googleApiClient, this);
        logger.info("Connected to Google API");
    }

    @Override
    public void onConnectionSuspended(int cause) {
        logger.warn("Connection to Google suspended");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        logger.error("Error connecting to Google API: {}", connectionResult.getErrorMessage());
        updateStatus(DeviceStatusListener.Status.DISCONNECTED);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        if (getState().getStatus() != DeviceStatusListener.Status.CONNECTED) {
            updateStatus(DeviceStatusListener.Status.CONNECTED);
        }
        for (final DataEvent event : dataEvents) {
            switch (event.getType()) {
                case DataEvent.TYPE_DELETED:
                    logger.info("Asset deleted {}", event.getDataItem().getUri());
                    break;
                case DataEvent.TYPE_CHANGED: {
                    logger.info("Received data: {}", event.getDataItem().getUri());
                    String path = event.getDataItem().getUri().getPath();
                    if (path.startsWith("/radar-sensor-data/")) {
                        sender.submit(new Runnable() {
                            @Override
                            public void run() {
                                processDataEvent(event);
                            }
                        });
                    }
                    break;
                }
            }
        }
    }

    private void processDataEvent(DataEvent event) {
        DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
        Asset asset = dataMapItem.getDataMap().getAsset("data");
        try (InputStream assetInputStream = Wearable.DataApi.getFdForAsset(googleApiClient, asset).await().getInputStream();
             DataInputStream in = new DataInputStream(assetInputStream)) {
            while (in.available() > 0) {
                int sensorType = in.readInt();
                int accuracy = in.readInt();
                double timestamp = in.readDouble();
                int valuesCount = in.readInt();
                float[] values = new float[valuesCount];
                for (int i = 0; i < valuesCount; i++) {
                    values[i] = in.readFloat();
                }

                switch (sensorType) {
                    case TYPE_BATTERY_STATUS:
                        processBattery(timestamp, values);
                        break;
                    case Sensor.TYPE_ACCELEROMETER:
                        processAcceleration(timestamp, values);
                        break;
                    case Sensor.TYPE_MAGNETIC_FIELD:
                        processMagneticField(timestamp, values);
                        break;
                    case Sensor.TYPE_GYROSCOPE:
                        processGyroscope(timestamp, values);
                        break;
                    case Sensor.TYPE_LIGHT:
                        processLight(timestamp, values);
                        break;
                    case Sensor.TYPE_STEP_COUNTER:
                        processStep(timestamp, values);
                        break;
                    default:
                        logger.debug("Phone registered unknown sensor change: '{}'", sensorType);
                }
            }
        } catch (IOException e) {
            logger.error("Error reading a data asset", e);
            return;
        }
        Wearable.DataApi.deleteDataItems(googleApiClient, event.getDataItem().getUri());
    }

    private void processAcceleration(double timestamp, float[] values) {
        // x,y,z are in m/s2
        float x = values[0] / SensorManager.GRAVITY_EARTH;
        float y = values[1] / SensorManager.GRAVITY_EARTH;
        float z = values[2] / SensorManager.GRAVITY_EARTH;
        getState().setAcceleration(x, y, z);

        double timeReceived = System.currentTimeMillis() / 1_000d;

        send(accelerationTable, new PhoneAcceleration(timestamp, timeReceived, x, y, z));
    }

    private void processLight(double timestamp, float[] values) {
        float lightValue = values[0];
        getState().setLight(lightValue);

        double timeReceived = System.currentTimeMillis() / 1_000d;

        send(lightTable, new PhoneLight(timestamp, timeReceived, lightValue));
    }

    private void processGyroscope(double timestamp, float[] values) {
        // Not normalized axis of rotation in rad/s
        float axisX = values[0];
        float axisY = values[1];
        float axisZ = values[2];

        double timeReceived = System.currentTimeMillis() / 1_000d;
        send(gyroscopeTable, new PhoneGyroscope(timestamp, timeReceived, axisX, axisY, axisZ));
    }

    private void processMagneticField(double timestamp, float[] values) {
        // Magnetic field in microTesla
        float axisX = values[0];
        float axisY = values[1];
        float axisZ = values[2];

        double timeReceived = System.currentTimeMillis() / 1_000d;

        send(magneticFieldTable, new PhoneMagneticField(timestamp, timeReceived, axisX, axisY, axisZ));
    }

    private void processStep(double timestamp, float[] values) {
        // Number of step since listening or since reboot
        int stepCount = (int) values[0];

        double timeReceived = System.currentTimeMillis() / 1_000d;

        // Send how many steps have been taken since the last time this function was triggered
        // Note: normally processStep() is called for every new step and the stepsSinceLastUpdate is 1
        int stepsSinceLastUpdate;


        if (lastStepCount.compareAndSet(-1, stepCount)) {
            stepsSinceLastUpdate = 1;
        } else {
            stepsSinceLastUpdate = stepCount - lastStepCount.getAndSet(stepCount);
        }

        send(stepCountTable, new PhoneStepCount(timestamp, timeReceived, stepsSinceLastUpdate));

        logger.info("Steps taken: {}", stepsSinceLastUpdate);
    }

    private void processBattery(double time, float[] values) {
        float batteryPct = values[0];

        boolean isPlugged = values[1] > 0;
        int status = (int) values[2];
        BatteryStatus batteryStatus = BATTERY_TYPES.get(status, BatteryStatus.UNKNOWN);

        getState().setBatteryLevel(batteryPct);

        double timeReceived = System.currentTimeMillis() / 1000.0;

        trySend(batteryTopic, 0L, new PhoneBatteryLevel(time, timeReceived, batteryPct, isPlugged, batteryStatus));
    }
}
