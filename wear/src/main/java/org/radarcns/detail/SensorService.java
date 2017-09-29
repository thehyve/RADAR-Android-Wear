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
import android.os.Handler;
import android.os.IBinder;
import android.os.StatFs;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static android.os.BatteryManager.BATTERY_STATUS_UNKNOWN;
import static android.os.Environment.*;

public class SensorService extends Service implements SensorEventListener {
    private static final String TAG = SensorService.class.getSimpleName();
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int MAX_FILES = 200;
    private static final int MIN_FREE_SPACE = 100 * 1024 * 1024;
    private static final int TYPE_BATTERY_STATUS = -1;

    private File dataDirectory;
    private volatile File fileToWrite;
    private DataOutputStream outputStream;

    private Handler handler;
    private ExecutorService executor;

    private BroadcastReceiver batteryLevelReceiver;
    private Toast lastInfoToast;

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler();

        executor = Executors.newSingleThreadExecutor();

        executor.submit(() -> tryToConnectAndRun((googleApiClient, node) -> {
            if (node != null) {
                handler.post(this::init);
            } else {
                handler.post(() -> closeApp("Couldn't connect to RADAR phone app. Try again later."));
            }
        }));
    }

    private void init() {
        makeForegroundService();
        setupDataDirectory();
        executor.submit(this::sendFiles);
        subscribeToSensorUpdates();
    }

    private void closeApp(String message) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent(this, MainWearActivity.class)
                        .setAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                        .putExtra("message", message));
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
        StringBuilder builder = new StringBuilder("Found sensors:\n");
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            long interval = updateInterval(sensor);
            if (interval > 0) {
                sensorManager.registerListener(this, sensor, (int) TimeUnit.SECONDS.toMicros(interval));
                builder.append(sensor.getName()).append('\n');
            }
        }
        info(builder.toString());

        batteryLevelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                    processBatteryStatus(intent);
                }
            }
        };

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
        write(event.sensor.getType(), eventTimestampToSecondsUTC(event.timestamp), event.values);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void write(int type, double timestamp, float... values) {
        try {
            if (outputStream == null) {
                if (fileToWrite == null) {
                    fileToWrite = new File(dataDirectory, System.currentTimeMillis() + ".radar");
                }

                outputStream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(fileToWrite)));
            }

            outputStream.writeInt(type);
            outputStream.writeDouble(timestamp);
            outputStream.writeInt(values.length);
            for (float x : values) {
                outputStream.writeFloat(x);
            }

            int hash = Arrays.deepHashCode(new Object[]{type, timestamp, values.length, values});
            outputStream.write(hash);

            outputStream.flush(); // after every message

            if (fileToWrite.length() >= BUFFER_SIZE) {
                closeActiveFile();
                executor.submit(this::sendFiles);
            }

        } catch (IOException e) {
            error("Error saving sensor events", e);
        }
    }


    private void sendFiles() {
        File[] files = dataDirectory.listFiles((dir, name) -> name.endsWith(".radar"));

        if (files.length == 0) {
            return;
        }

        tryToConnectAndRun((googleApiClient, node) -> {
            if (node == null) {
                if (files.length >= MAX_FILES || availableSpace(dataDirectory) < MIN_FREE_SPACE) {
                    stopSelf();
                    closeApp("Closing the app to prevent data storage overflow");
                }
                return;
            }

            try {
                info("Sending data");
                Arrays.sort(files);

                for (File file : files) {
                    if (file == fileToWrite) {
                        continue;
                    }
                    byte[] bytes = new byte[(int) file.length()];

                    try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
                        f.readFully(bytes);
                    }
                    Asset asset = Asset.createFromBytes(bytes);
                    PutDataMapRequest dataMap = PutDataMapRequest.create("/radar-sensor-data/" + file.getName());
                    dataMap.getDataMap().putAsset("data", asset);

                    PutDataRequest request = dataMap.asPutDataRequest();
                    DataApi.DataItemResult result = Wearable.DataApi.putDataItem(googleApiClient, request).await();

                    if (result.getStatus().isSuccess()) {
                        if (!file.delete()) {
                            error("Error deleting backup files. Please contact the support team.", null);
                        }
                    } else {
                        error("Error sending data. " + result.getStatus().getStatusMessage(), null);
                    }
                }

                info("Finished sending data");
            } catch (IOException e) {
                error("Error sending data", e);
            }
        });
    }

    private void tryToConnectAndRun(RadarAction action) {
        info("Connecting to Google Play");

        GoogleApiClient googleApiClient = new GoogleApiClient.Builder(this).addApi(Wearable.API).build();

        try {
            ConnectionResult connectionResult = googleApiClient.blockingConnect();
            Node node = null;

            if (!connectionResult.isSuccess()) {
                error(withInfo("Cannot connect to Google Play", connectionResult.getErrorMessage()), null);
            } else {
                info("Connected to Google Play. Connecting to a RADAR device");

                CapabilityApi.GetCapabilityResult capabilityResult =
                        Wearable.CapabilityApi.getCapability(googleApiClient, "radar_phone", CapabilityApi.FILTER_ALL).await();

                if (!capabilityResult.getStatus().isSuccess()) {
                    error(withInfo("Cannot find a RADAR device", capabilityResult.getStatus().getStatusMessage()), null);
                } else if (capabilityResult.getCapability().getNodes().isEmpty()) {
                    error("Cannot find a RADAR device", null);
                } else {
                    info("Found a RADAR device");
                    node = capabilityResult.getCapability().getNodes().iterator().next();
                }
            }

            action.apply(googleApiClient, node);
        } finally {
            if (googleApiClient.isConnected()) {
                googleApiClient.disconnect();
            }
        }
    }

    @Override
    public void onDestroy() {
        executor.shutdown();

        ((SensorManager) getSystemService(SENSOR_SERVICE)).unregisterListener(this);
        if (batteryLevelReceiver != null) {
            unregisterReceiver(batteryLevelReceiver);
        }

        try {
            closeActiveFile();
        } catch (IOException e) {
            error("Error closing output file", e);
        }

        try {
            executor.awaitTermination(4, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            error("Error stopping the service", null);
        }

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
            handler.post(() -> {
                lastInfoToast = Toast.makeText(this, message, Toast.LENGTH_SHORT);
                lastInfoToast.show();

            });
        }
        Log.i(TAG, message);
    }

    private void error(String message, Throwable e) {
        handler.post(() -> {
            if (lastInfoToast != null) {
                lastInfoToast.cancel();
                lastInfoToast = null;
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
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

        write(TYPE_BATTERY_STATUS, System.currentTimeMillis() / 1000.0, batteryPct, isPlugged, status);
    }

    private static String withInfo(String message, String info) {
        return (info == null) ? message : message + ": " + info;
    }

    // Poor man's BiConsumer
    @FunctionalInterface
    private interface RadarAction {
        void apply(GoogleApiClient googleApiClient, Node node);
    }
}