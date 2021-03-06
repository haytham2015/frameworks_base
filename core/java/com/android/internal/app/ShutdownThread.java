/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2011-2012 Code Aurora Forum. All rights reserved.
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

 
package com.android.internal.app;

import java.io.File;
import java.io.IOException;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Handler;
import android.os.Power;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.os.storage.IMountService;
import android.os.storage.IMountShutdownObserver;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;

import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.ITelephonyMSim;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.qrd.plugin.feature_query.FeatureQuery;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;

public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int MAX_NUM_PHONE_STATE_READS = 16;
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10*1000;
    private static final int MAX_SHUTDOWN_WAIT_TIME = 20*1000;

    // length of vibration before shutting down
    private static final int SHUTDOWN_VIBRATE_MS = 500;
    
    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    
    private static boolean mReboot;
    private static String mRebootReason;

    // Provides shutdown assurance in case the system_server is killed
    public static final String SHUTDOWN_ACTION_PROPERTY = "sys.shutdown.requested";
    public static final String SHUTDOWN_RUNNING_PROPERTY = "sys.shutdown.running";
    public static final String RADIO_SHUTDOWN_PROPERTY = "sys.radio.shutdown";

    private static final String SYSFS_MSM_EFS_SYNC_COMPLETE = "/sys/devices/platform/rs300000a7.65536/sync_sts";
    private static final String SYSFS_MDM_EFS_SYNC_COMPLETE = "/sys/devices/platform/rs300100a7.65536/sync_sts";

    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();
    
    private final Object mActionDoneSync = new Object();
    private boolean mActionDone;
    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mCpuWakeLock;
    private PowerManager.WakeLock mScreenWakeLock;
    private Handler mHandler;

    private static final String USER_BOOTANIMATION_FILE = "/data/local/shutdownanimation.zip";
    private static final String SYSTEM_BOOTANIMATION_FILE = "/system/media/shutdownanimation.zip";
    private static final String SYSTEM_ENCRYPTED_BOOTANIMATION_FILE = "/system/media/shutdownanimation-encrypted.zip";

    private static final String MUSIC_SHUTDOWN_FILE = "/system/media/shutdown.wav";
    private boolean isShutdownMusicPlaying = false;

    private ShutdownThread() {
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void shutdown(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        final int longPressBehavior = context.getResources().getInteger(
                        com.android.internal.R.integer.config_longPressOnPowerBehavior);
        final int resourceId = longPressBehavior == 2
                ? com.android.internal.R.string.shutdown_confirm_question
                : com.android.internal.R.string.shutdown_confirm;

        Log.d(TAG, "Notifying thread to start shutdown longPressBehavior=" + longPressBehavior);

        if (confirm) {
            final CloseDialogReceiver closer = new CloseDialogReceiver(context);
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(com.android.internal.R.string.power_off)
                    .setMessage(resourceId)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            beginShutdownSequence(context);
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.no, null)
                    .create();
            closer.dialog = dialog;
            dialog.setOnDismissListener(closer);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            dialog.show();
        } else {
            beginShutdownSequence(context);
        }
    }

    private static class CloseDialogReceiver extends BroadcastReceiver
            implements DialogInterface.OnDismissListener {
        private Context mContext;
        public Dialog dialog;

        CloseDialogReceiver(Context context) {
            mContext = context;
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            dialog.cancel();
        }

        public void onDismiss(DialogInterface unused) {
            mContext.unregisterReceiver(this);
        }
    }

    /**
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     *
     * @param context Context used to display the shutdown progress dialog.
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     * @param confirm true if user confirmation is needed before shutting down.
     */
    public static void reboot(final Context context, String reason, boolean confirm) {
        mReboot = true;
        mRebootReason = reason;
        shutdown(context, confirm);
    }

    
    private static String getShutdownMusicFilePath() {    
        final String[] fileName = {"/data/qrd_theme/boot/shutdown.wav",			
                "/system/media/shutdown.wav"};    
        File checkFile = null;  
        int i = 0;
        for( ; i < fileName.length; i ++) {
            checkFile = new File(fileName[i]);
            if (checkFile.exists())
                break;           
        }
        if (i >= fileName.length)
            return null;
        return fileName[i];   
    }
    
    private static void lockDevice() {
        SystemProperties.set(SHUTDOWN_RUNNING_PROPERTY, "true");
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager
                .getService(Context.WINDOW_SERVICE));
        try {
            wm.updateRotation(false);
        } catch (RemoteException e) {
            Log.w(TAG, "boot animation can not lock device!");
        }
    }
    
    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            if (sIsStarted) {
                Log.d(TAG, "Shutdown sequence already running, returning.");
                return;
            }
            sIsStarted = true;
        }

        if (FeatureQuery.FEATURE_BOOT_ANIMATION && checkAnimationFileExist()) {
            lockDevice();
            showShutdownAnimation();
            // playShutdownMusic(MUSIC_SHUTDOWN_FILE);
            String shutDownFile = getShutdownMusicFilePath();
            if (shutDownFile != null && !isSilentMode())
                sInstance.playShutdownMusic(shutDownFile);
        } else {
            // throw up an indeterminate system dialog to indicate radio is
            // shutting down.
            ProgressDialog pd = new ProgressDialog(context);
            pd.setTitle(context.getText(com.android.internal.R.string.power_off));
            pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
            pd.setIndeterminate(true);
            pd.setCancelable(false);
            pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);

            pd.show();
        }

        sInstance.mContext = context;
        sInstance.mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);

        // make sure we never fall asleep again
        sInstance.mCpuWakeLock = null;
        try {
            sInstance.mCpuWakeLock = sInstance.mPowerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, TAG + "-cpu");
            sInstance.mCpuWakeLock.setReferenceCounted(false);
            sInstance.mCpuWakeLock.acquire();
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to acquire wake lock", e);
            sInstance.mCpuWakeLock = null;
        }

        // also make sure the screen stays on for better user experience
        sInstance.mScreenWakeLock = null;
        if (sInstance.mPowerManager.isScreenOn()) {
            try {
                sInstance.mScreenWakeLock = sInstance.mPowerManager.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK, TAG + "-screen");
                sInstance.mScreenWakeLock.setReferenceCounted(false);
                sInstance.mScreenWakeLock.acquire();
            } catch (SecurityException e) {
                Log.w(TAG, "No permission to acquire wake lock", e);
                sInstance.mScreenWakeLock = null;
            }
        }

        // start the thread that initiates shutdown
        sInstance.mHandler = new Handler() {
        };
        sInstance.start();
    }

    void actionDone() {
        synchronized (mActionDoneSync) {
            mActionDone = true;
            mActionDoneSync.notifyAll();
        }
    }

    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        boolean bluetoothOff;
        boolean radioOff;
        boolean msmEfsSyncDone = false, mdmEfsSyncDone = false;

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                actionDone();
            }
        };

        /*
         * Write a system property in case the system_server reboots before we
         * get to the actual hardware restart. If that happens, we'll retry at
         * the beginning of the SystemServer startup.
         */
        {
            String reason = (mReboot ? "1" : "0") + (mRebootReason != null ? mRebootReason : "");
            SystemProperties.set(SHUTDOWN_ACTION_PROPERTY, reason);
        }

        Log.i(TAG, "wait for shutdown music");
        final long endTimeForMusic = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (isShutdownMusicPlaying) {
                long delay = endTimeForMusic - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "play shutdown music timeout!");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
            if (!isShutdownMusicPlaying) {
                Log.i(TAG, "play shutdown music complete.");
            }
        }

        Log.i(TAG, "Sending shutdown broadcast...");
        
        // First send the high-level shut down broadcast.
        mActionDone = false;
        mContext.sendOrderedBroadcast(new Intent(Intent.ACTION_SHUTDOWN), null,
                br, mHandler, 0, null, null);
        
        final long endTime = SystemClock.elapsedRealtime() + MAX_BROADCAST_TIME;
        synchronized (mActionDoneSync) {
            while (!mActionDone) {
                long delay = endTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }
        
        Log.i(TAG, "Shutting down activity manager...");
        
        final IActivityManager am =
            ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }

        final ITelephony phone =
                ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        final IBluetooth bluetooth =
                IBluetooth.Stub.asInterface(ServiceManager.checkService(
                        BluetoothAdapter.BLUETOOTH_SERVICE));

        final IMountService mount =
                IMountService.Stub.asInterface(
                        ServiceManager.checkService("mount"));
        
        try {
            bluetoothOff = bluetooth == null ||
                           bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
            if (!bluetoothOff) {
                Log.w(TAG, "Disabling Bluetooth...");
                bluetooth.disable(false);  // disable but don't persist new state
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
            bluetoothOff = true;
        }

        try {
            radioOff = true;
            if (TelephonyManager.getDefault().isMultiSimEnabled()) {
                final ITelephonyMSim mphone = ITelephonyMSim.Stub.asInterface(
                        ServiceManager.checkService("phone_msim"));
                if (mphone != null) {
                    //radio off indication should be sent for both subscriptions in case of DSDS.
                    for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++) {
                        radioOff = radioOff && !mphone.isRadioOn(i);
                        if (mphone.isRadioOn(i)) {
                            Log.w(TAG, "Turning off radio on Subscription :" + i);
                            mphone.setRadio(false, i);
                        }
                    }
                }
            } else {
                radioOff = phone == null || !phone.isRadioOn();
                if (!radioOff) {
                    Log.w(TAG, "Turning off radio...");
                    phone.setRadio(false);
                }
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during radio shutdown", ex);
            radioOff = true;
        }

        SystemProperties.set(RADIO_SHUTDOWN_PROPERTY, "true");
        Log.i(TAG, "Waiting for radio file system sync to complete ...");

        // Wait a max of 8 seconds
        for (int i = 0; i < MAX_NUM_PHONE_STATE_READS; i++) {
            if (!msmEfsSyncDone) {
                try {
                    FileInputStream fis = new FileInputStream(SYSFS_MSM_EFS_SYNC_COMPLETE);
                    int result = fis.read();
                    fis.close();
                    if (result == '1')
                                        {
                        msmEfsSyncDone = true;
                                        }
                } catch (Exception ex) {
                    Log.e(TAG, "Exception during msmEfsSyncDone", ex);
                    msmEfsSyncDone = true;
                }
            }
            if (!mdmEfsSyncDone) {
                try {
                    FileInputStream fis = new FileInputStream(SYSFS_MDM_EFS_SYNC_COMPLETE);
                    int result = fis.read();
                    fis.close();
                    if (result == '1')
                                        {
                        mdmEfsSyncDone = true;
                                        }
                } catch (Exception ex) {
                    Log.e(TAG, "Exception during mdmEfsSyncDone", ex);
                    mdmEfsSyncDone = true;
                }
            }
            if (msmEfsSyncDone && mdmEfsSyncDone) {
                Log.i(TAG, "Radio file system sync complete.");
                break;
            }
            Log.i(TAG, "Radio file system sync incomplete - retry.");
            SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
        }

        Log.i(TAG, "Waiting for Bluetooth and Radio...");
        
        // Wait a max of 32 seconds for clean shutdown
        for (int i = 0; i < MAX_NUM_PHONE_STATE_READS; i++) {
            if (!bluetoothOff) {
                try {
                    bluetoothOff =
                            bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }
            }
            if (!radioOff) {
                try {
                    if (TelephonyManager.getDefault().isMultiSimEnabled()) {
                        radioOff = true;
                        final ITelephonyMSim mphone = ITelephonyMSim.Stub.asInterface(
                                ServiceManager.checkService("phone_msim"));
                        for (int j = 0; j < TelephonyManager.getDefault().getPhoneCount(); j++) {
                            radioOff = radioOff && !mphone.isRadioOn(j);
                        }
                    } else {
                        radioOff = !phone.isRadioOn();
                    }
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }
            }
            if (radioOff && bluetoothOff) {
                Log.i(TAG, "Radio and Bluetooth shutdown complete.");
                break;
            }
            SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
        }

        // Shutdown MountService to ensure media is in a safe state
        IMountShutdownObserver observer = new IMountShutdownObserver.Stub() {
            public void onShutDownComplete(int statusCode) throws RemoteException {
                Log.w(TAG, "Result code " + statusCode + " from MountService.shutdown");
                actionDone();
            }
        };

        Log.i(TAG, "Shutting down MountService");
        // Set initial variables and time out time.
        mActionDone = false;
        final long endShutTime = SystemClock.elapsedRealtime() + MAX_SHUTDOWN_WAIT_TIME;
        synchronized (mActionDoneSync) {
            try {
                if (mount != null) {
                    mount.shutdown(observer);
                } else {
                    Log.w(TAG, "MountService unavailable for shutdown");
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception during MountService shutdown", e);
            }
            while (!mActionDone) {
                long delay = endShutTime - SystemClock.elapsedRealtime();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown wait timed out");
                    break;
                }
                try {
                    mActionDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }

        rebootOrShutdown(mReboot, mRebootReason);
    }

    /**
     * Do not call this directly. Use {@link #reboot(Context, String, boolean)}
     * or {@link #shutdown(Context, boolean)} instead.
     *
     * @param reboot true to reboot or false to shutdown
     * @param reason reason for reboot
     */
    public static void rebootOrShutdown(boolean reboot, String reason) {
        if (reboot) {
            Log.i(TAG, "Rebooting, reason: " + reason);
            try {
                Power.reboot(reason);
            } catch (Exception e) {
                Log.e(TAG, "Reboot failed, will attempt shutdown instead", e);
            }
        } else if (SHUTDOWN_VIBRATE_MS > 0) {
            // vibrate before shutting down
            Vibrator vibrator = new Vibrator();
            try {
                vibrator.vibrate(SHUTDOWN_VIBRATE_MS);
            } catch (Exception e) {
                // Failure to vibrate shouldn't interrupt shutdown.  Just log it.
                Log.w(TAG, "Failed to vibrate during shutdown.", e);
            }

            // vibrator is asynchronous so we need to wait to avoid shutting down too soon.
            try {
                Thread.sleep(SHUTDOWN_VIBRATE_MS);
            } catch (InterruptedException unused) {
            }
        }

        // Shutdown power
        Log.i(TAG, "Performing low-level shutdown...");
        Power.shutdown();
    }

    private static boolean checkAnimationFileExist() {
        if (new File(USER_BOOTANIMATION_FILE).exists()
                || new File(SYSTEM_BOOTANIMATION_FILE).exists()
                || new File(SYSTEM_ENCRYPTED_BOOTANIMATION_FILE).exists())
            return true;
        else
            return false;
    }
    
    private static boolean isSilentMode() {
        String mode = SystemProperties.get("persist.sys.silent");
        return mode != null && mode.equals("1");
    }

    private static void showShutdownAnimation() {
        SystemProperties.set("ctl.start", "bootanim");
    }

    private void playShutdownMusic(String path) {
        MediaPlayer mediaPlayer = new MediaPlayer();
        try
        {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isShutdownMusicPlaying = true;
            mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    synchronized (mActionDoneSync) {
                        isShutdownMusicPlaying = false;
                        mActionDoneSync.notifyAll();
                    }
                }
            });
        } catch (IOException e) {
            Log.d(TAG, "play shutdown music error:" + e);
        }
    }
}
