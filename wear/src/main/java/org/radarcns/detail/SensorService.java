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

package org.radarcns.detail;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.*;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.StatFs;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.Environment.*;

public class SensorService extends Service implements SensorEventListener {
    private static final String TAG = SensorService.class.getSimpleName();
    private static final int BUFFER_SIZE = 100 * 1024;
    private static final int TYPE_BATTERY_STATUS = -1;

    private GoogleApiClient googleApiClient;
    private File dataDirectory;
    private File fileToWrite;
    private DataOutputStream outputStream;

    private final BroadcastReceiver batteryLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                processBatteryStatus(intent);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        setupDataDirectory();

        googleApiClient = new GoogleApiClient.Builder(this)
                        .addApi(Wearable.API)
                        .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                            @Override
                            public void onConnected(@Nullable Bundle bundle) {
                                info("Connected to Google Play");
                                whenConnectedToRadar(() -> {
                                    makeForegroundService();
                                    transferFiles(); // From the previous run
                                    subscribeToSensorUpdates();
                                });
                            }

                            @Override
                            public void onConnectionSuspended(int cause) {
                                String msg;
                                switch (cause) {
                                    case GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED:
                                        msg = "Service disconnected";
                                        break;
                                    case GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST:
                                        msg = "Network lost";
                                        break;
                                    default:
                                        msg = "(" + cause + ")";
                                        break;
                                }
                                error("Connection to Google Play suspended: " + msg, null);
                            }
                        })
                        .build();

        info("Connecting to Google Play services");
        googleApiClient.connect();
    }

    private void setupDataDirectory() {
        SharedPreferences preferences = getSharedPreferences(SensorService.class.getName(), Context.MODE_PRIVATE);
        if (preferences.contains("data_directory")) {
            dataDirectory = new File(preferences.getString("data_directory", null));
        }
        if (dataDirectory == null || !dataDirectory.exists()) {
            dataDirectory =
                    (isExternalStorageWritable() && availableSpace(getExternalStorageDirectory()) > availableSpace(getDataDirectory()))
                            ? getExternalFilesDir("RADAR")
                            : getFilesDir();
            preferences
                    .edit()
                    .putString("data_directory", dataDirectory.getPath())
                    .apply();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private void subscribeToSensorUpdates() {
        info("Discovering sensors");
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            long interval = updateInterval(sensor);
            if (interval > 0) {
                sensorManager.registerListener(this, sensor, (int) TimeUnit.SECONDS.toMicros(interval));
                info("Found " + sensor.getName());
            }
        }

        processBatteryStatus(registerReceiver(batteryLevelReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
    }

    private void makeForegroundService() {
        startForeground(1,
                new Notification.Builder(this)
                        .setContentTitle("RADAR")
                        .setContentText("Open RADAR app")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainWearActivity.class), 0))
                        .build());
    }

    // TODO: Make configurable
    private long updateInterval(Sensor sensor) {
        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_LIGHT:
                return 200;
            case Sensor.TYPE_STEP_COUNTER:
                return 20_000;
            default:
                return 0;
        }
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        write(event.sensor.getType(), event.accuracy, eventTimestampToSecondsUTC(event.timestamp), event.values);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void write(int type, int accuracy, double timestamp, float... values) {
        try {
            if (outputStream == null) {
                if (fileToWrite == null) {
                    fileToWrite = new File(dataDirectory, System.currentTimeMillis() + ".radar");
                }

                outputStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileToWrite)));
            }

            outputStream.writeInt(type);
            outputStream.writeInt(accuracy);
            outputStream.writeDouble(timestamp);
            outputStream.writeInt(values.length);
            for (float x : values) {
                outputStream.writeFloat(x);
            }

            outputStream.flush();

            if (fileToWrite.length() >= BUFFER_SIZE) {
                closeActiveFile();
                whenConnectedToRadar(this::transferFiles);
            }

        } catch (IOException e) {
            error("Error saving sensor events", e);
        }
    }


    private void whenConnectedToRadar(Runnable action) {
        info("Connecting to a RADAR device");
        if (googleApiClient == null || !googleApiClient.isConnected()) {
            return; // Stopping the service
        }
        Wearable.CapabilityApi.getCapability(googleApiClient, "radar_phone", CapabilityApi.FILTER_ALL).setResultCallback(result -> {
            if (result.getStatus().isSuccess() && !result.getCapability().getNodes().isEmpty()) {
                info("Connected to a RADAR device " + result.getCapability().getNodes().iterator().next().getDisplayName());
                action.run();
            } else {
                if (result.getStatus().isSuccess()) {
                    error("Cannot find a RADAR device", null);
                } else {
                    error("Cannot find a RADAR device: " + result.getStatus().getStatusMessage(), null);
                }
                CapabilityApi.CapabilityListener listener = new CapabilityApi.CapabilityListener() {
                    @Override
                    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
                        if (!capabilityInfo.getNodes().isEmpty()) {
                            info("Connected to a RADAR device " + capabilityInfo.getNodes().iterator().next().getDisplayName());
                            Wearable.CapabilityApi.removeCapabilityListener(googleApiClient, this, "radar_phone");
                            action.run();
                        }
                    }
                };
                Wearable.CapabilityApi.addCapabilityListener(googleApiClient, listener, "radar_phone");
            }
        }, 10, TimeUnit.SECONDS);
    }


    private void transferFiles() {
        long start = System.currentTimeMillis();

        File[] files = dataDirectory.listFiles((dir, name) -> name.endsWith(".radar"));

        if (files.length == 0) {
            return;
        }

        info("Sending data");
        Arrays.sort(files);

        try {
            for (File file : files) {
                if (file == fileToWrite) {
                    continue;
                }

                try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
                    byte[] bytes = new byte[(int) f.length()];
                    f.readFully(bytes);
                    Asset asset = Asset.createFromBytes(bytes);
                    PutDataMapRequest dataMap = PutDataMapRequest.create("/radar-sensor-data/" + file.getName());
                    dataMap.getDataMap().putAsset("data", asset);

                    PutDataRequest request = dataMap.asPutDataRequest();
                    Wearable.DataApi.putDataItem(googleApiClient, request)
                            .setResultCallback(result -> {
                                if (result.getStatus().isSuccess()) {
                                    boolean isLast = file == files[files.length - 1];
                                    if (isLast) {
                                        info("Finished sending data");

                                        long stop = System.currentTimeMillis();
                                        Log.i(TAG, "File transfer took " + (stop - start) + " ms");
                                    }
                                    file.delete();
                                } else {
                                    error("Error sending data. " + result.getStatus().getStatusMessage(), null);
                                }
                            });
                }
            }
        } catch (IOException e) {
            error("Error sending data", e);
        }

        long stop = System.currentTimeMillis();
        Log.i(TAG, "Time spent in the UI thread: " + (stop - start) + " ms");
    }

    @Override
    public void onDestroy() {
        ((SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(this);

        try {
            closeActiveFile();
        } catch (IOException e) {
            error("Error closing output file", e);
        }

        unregisterReceiver(batteryLevelReceiver);

        googleApiClient.disconnect();

        super.onDestroy();
    }

    private void closeActiveFile() throws IOException {
        if (outputStream != null) {
            outputStream.close();
            outputStream = null;
            fileToWrite = null;
        }
    }

    private static boolean isExternalStorageWritable() {
        return MEDIA_MOUNTED.equals(getExternalStorageState());
    }

    private static long availableSpace(File file) {
        StatFs stat = new StatFs(file.getPath());
        long blockSize = stat.getBlockSizeLong();
        long availableBlocks = stat.getAvailableBlocksLong();
        return availableBlocks * blockSize;
    }

    private void info(String message) {
        if (MainWearActivity.isActive()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
        Log.i(TAG, message);
    }

    private void error(String message, Throwable e) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (e != null) {
            Log.e(TAG, message, e);
        } else {
            Log.e(TAG, message);
        }
    }

    private static double eventTimestampToSecondsUTC(long eventTimestampNanos) {
        double currentSeconds = System.currentTimeMillis() / 1_000d;
        double secondsSinceEvent = (System.nanoTime() - eventTimestampNanos) / 1_000_000_000d;

        return currentSeconds - secondsSinceEvent;
    }

    private void processBatteryStatus(Intent intent) {
        if (intent == null) {
            return;
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        float batteryPct = level / (float) scale;

        int isPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BATTERY_STATUS_UNKNOWN);

        write(TYPE_BATTERY_STATUS, 0, System.currentTimeMillis() / 1000.0, batteryPct, isPlugged, status);
    }
}