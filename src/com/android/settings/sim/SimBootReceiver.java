/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.settings.sim;

import com.android.settings.R;
import com.android.settings.Settings.SimSettingsActivity;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.res.Resources;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;

import com.android.settings.Utils;

import java.util.List;

public class SimBootReceiver extends BroadcastReceiver {
    private static final String TAG = "SimBootReceiver";
    private static final int SLOT_EMPTY = -1;
    private static final int NOTIFICATION_ID = 1;
    private static final String SHARED_PREFERENCES_NAME = "sim_state";
    private static final String SLOT_PREFIX = "sim_slot_";
    private static final int NOTIFICATION_ID_SIM_DISABLED = 2;

    private SharedPreferences mSharedPreferences = null;
    private TelephonyManager mTelephonyManager;
    private Context mContext;
    private SubscriptionManager mSubscriptionManager;

    @Override
    public void onReceive(Context context, Intent intent) {
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mContext = context;
        mSubscriptionManager = SubscriptionManager.from(mContext);
        mSharedPreferences = mContext.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);

        mSubscriptionManager.addOnSubscriptionsChangedListener(mSubscriptionListener);
        if(anySimDisabled()) {
            createSimDisabledNotification(mContext);
        }
    }

    private void detectChangeAndNotify() {
        final int numSlots = mTelephonyManager.getSimCount();
        final boolean isInProvisioning = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 0;
        boolean notificationSent = false;
        int numSIMsDetected = 0;
        int lastSIMSlotDetected = -1;

        // Do not create notifications on single SIM devices or when provisiong or airplane mode on
        if (numSlots < 2 || isInProvisioning || (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0)) {
            return;
        }

        for (int i = 0; i < TelephonyManager.getDefault().getPhoneCount(); i++) {
            if (TelephonyManager.getDefault().hasIccCard(i)) {
                numSIMsDetected++;
            }
        }

        List<SubscriptionInfo> sil = mSubscriptionManager.getActiveSubscriptionInfoList();
        // if numSIMsDetected is 2, sil.size may be 1 because of another subInfo hasn't been loaded yet,
        // then we should wait all subInfo is loaded(sil.size() == numSIMsDetected), then we can clear the
        // subinfo which no longer exist and show the notification if needed
        if (sil == null || sil.size() < 1) {
            return;
        } else if (sil.size() == numSIMsDetected) {
            // Cancel any previous notifications
            cancelNotification(mContext);
            // Clear defaults for any subscriptions which no longer exist
            mSubscriptionManager.clearDefaultsForInactiveSubIds();
            boolean dataSelected = SubscriptionManager.isUsableSubIdValue(
                    SubscriptionManager.getDefaultDataSubId());
            boolean smsSelected = SubscriptionManager.isUsableSubIdValue(
                    SubscriptionManager.getDefaultSmsSubId());
            // If data and sms defaults are selected, dont show notification (Calls default is optional)
            if (dataSelected && smsSelected) {
                return;
            }

        for (int i = 0; i < numSlots; i++) {
            final SubscriptionInfo sir = Utils.findRecordBySlotId(mContext, i);
            final String key = SLOT_PREFIX+i;
            final int lastSubId = getLastSubId(key);

            if (sir != null) {
                numSIMsDetected++;
                final int currentSubId = sir.getSubscriptionId();
                if (lastSubId != currentSubId) {
                    createNotification(mContext);
                    setLastSubId(key, currentSubId);
                    notificationSent = true;
                }
                lastSIMSlotDetected = i;
            } else if (lastSubId != SLOT_EMPTY) {
                createNotification(mContext);
                setLastSubId(key, SLOT_EMPTY);
                notificationSent = true;
            }
        }

        if (notificationSent) {
            Intent intent = new Intent(mContext, SimDialogActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (numSIMsDetected == 1) {
                intent.putExtra(SimDialogActivity.DIALOG_TYPE_KEY, SimDialogActivity.PREFERRED_PICK);
                intent.putExtra(SimDialogActivity.PREFERRED_SIM, lastSIMSlotDetected);
            } else {
                intent.putExtra(SimDialogActivity.DIALOG_TYPE_KEY, SimDialogActivity.DEFAULT_DATA_PICK);
            }
            mContext.startActivity(intent);
        }
    }
	}

    private int getLastSubId(String strSlotId) {
        return mSharedPreferences.getInt(strSlotId, SLOT_EMPTY);
    }

    private void setLastSubId(String strSlotId, int value) {
        Editor editor = mSharedPreferences.edit();
        editor.putInt(strSlotId, value);
        editor.commit();
    }

    private void createNotification(Context context){
        final Resources resources = context.getResources();

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_sim_card_alert_white_48dp)
                .setColor(resources.getColor(R.color.sim_noitification))
                .setContentTitle(resources.getString(R.string.sim_notification_title))
                .setContentText(resources.getString(R.string.sim_notification_summary));
        Intent resultIntent = new Intent(context, SimSettingsActivity.class);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                context,
                0,
                resultIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
                );
        builder.setContentIntent(resultPendingIntent);
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    public static void cancelNotification(Context context) {
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private final OnSubscriptionsChangedListener mSubscriptionListener =
            new OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            detectChangeAndNotify();
        }
    };
    private void createSimDisabledNotification(Context context) {
        final Resources resources = context.getResources();

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_sim_card_alert_white_48dp)
                .setColor(resources.getColor(R.color.sim_noitification))
                .setContentTitle(resources.getString(R.string.sim_slot_disabled))
                .setContentText(resources.getString(R.string.sim_notification_summary));
        Intent resultIntent = new Intent(context, SimSettingsActivity.class);
        resultIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent resultPendingIntent =
                PendingIntent.getActivity(
                context,
                0,
                resultIntent,
                PendingIntent.FLAG_CANCEL_CURRENT
                );
        builder.setContentIntent(resultPendingIntent);
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID_SIM_DISABLED, builder.build());
    }

    public static void cancelSimDisabledNotification(Context context) {
        NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID_SIM_DISABLED);
    }

    private boolean anySimDisabled() {
        for (int i = 0; i < mTelephonyManager.getSimCount(); i++) {
            if(mTelephonyManager.isSimOff(i)) {
                return true;
            }
        }
        return false;
    }
}
