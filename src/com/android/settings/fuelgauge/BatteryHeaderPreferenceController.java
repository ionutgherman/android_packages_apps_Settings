/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settings.fuelgauge;

import static com.android.settings.fuelgauge.BatteryBroadcastReceiver.BatteryUpdateType.BATTERY_NOT_PRESENT;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceScreen;

import com.android.settings.core.BasePreferenceController;
import com.android.settings.core.PreferenceControllerMixin;
import com.android.settings.R;
import com.android.settingslib.Utils;
import com.android.settingslib.widget.UsageProgressBarPreference;

// LINT.IfChange
/** Controller that update the battery header view */
public class BatteryHeaderPreferenceController extends BasePreferenceController
        implements PreferenceControllerMixin, LifecycleEventObserver {
    private static final String TAG = "BatteryHeaderPreferenceController";
    private static final int BATTERY_MAX_LEVEL = 100;

    @Nullable @VisibleForTesting BatteryBroadcastReceiver mBatteryBroadcastReceiver;
    @Nullable @VisibleForTesting UsageProgressBarPreference mBatteryUsageProgressBarPreference;

    public BatteryHeaderPreferenceController(Context context, String key) {
        super(context, key);
    }

    @Override
    public void onStateChanged(@NonNull LifecycleOwner lifecycleOwner,
            @NonNull Lifecycle.Event event) {
        switch (event) {
            case ON_CREATE:
                mBatteryBroadcastReceiver = new BatteryBroadcastReceiver(mContext);
                mBatteryBroadcastReceiver.setBatteryChangedListener(
                        type -> {
                            if (type != BATTERY_NOT_PRESENT) {
                                quickUpdateHeaderPreference();
                            }
                        });
                break;
            case ON_START:
                if (mBatteryBroadcastReceiver != null) {
                    mBatteryBroadcastReceiver.register();
                }
                break;
            case ON_STOP:
                if (mBatteryBroadcastReceiver != null) {
                    mBatteryBroadcastReceiver.unRegister();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mBatteryUsageProgressBarPreference = screen.findPreference(getPreferenceKey());
        // Hide the bottom summary from the progress bar.
        mBatteryUsageProgressBarPreference.setBottomSummary("");

        if (com.android.settings.Utils.isBatteryPresent(mContext)) {
            quickUpdateHeaderPreference();
        } else {
            mBatteryUsageProgressBarPreference.setVisible(false);
        }
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE_UNSEARCHABLE;
    }

    private CharSequence generateLabel(BatteryInfo info) {
        if (info.pluggedStatus == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            final CharSequence wirelessChargingLabel =
                    mBatterySettingsFeatureProvider.getWirelessChargingLabel(mContext, info);
            if (wirelessChargingLabel != null) {
                return wirelessChargingLabel;
            }
        }

        if (Utils.containsIncompatibleChargers(mContext, TAG)) {
            return mContext.getString(
                    com.android.settingslib.R.string.battery_info_status_not_charging);
        } else if (BatteryUtils.isBatteryDefenderOn(info)) {
            return mContext.getString(
                    com.android.settingslib.R.string.battery_info_status_charging_on_hold);
        } else if (info.remainingLabel == null
                || info.batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING) {
            // Present status only if no remaining time or status anomalous
            return info.statusLabel;
        } else if (info.statusLabel != null && !info.discharging) {
            // Charging state
            if (com.android.settingslib.fuelgauge.BatteryUtils.isChargingStringV2Enabled()) {
                return info.isFastCharging
                        ? mContext.getString(
                                R.string.battery_state_and_duration,
                                info.statusLabel,
                                info.remainingLabel)
                        : info.remainingLabel;
            }
            return mContext.getString(
                    R.string.battery_state_and_duration, info.statusLabel, info.remainingLabel);
        } else if (mPowerManager.isPowerSaveMode()) {
            // Power save mode is on
            final String powerSaverOn =
                    mContext.getString(R.string.battery_tip_early_heads_up_done_title);
            return mContext.getString(
                    R.string.battery_state_and_duration, powerSaverOn, info.remainingLabel);
        } else if (mBatteryTip != null && mBatteryTip.getType() == BatteryTip.TipType.LOW_BATTERY) {
            // Low battery state
            final String lowBattery = mContext.getString(R.string.low_battery_summary);
            return mContext.getString(
                    R.string.battery_state_and_duration, lowBattery, info.remainingLabel);
        } else {
            // Discharging state
            return info.remainingLabel;
        }
    }

    public void updateHeaderPreference(BatteryInfo info) {
        if (!mBatteryStatusFeatureProvider.triggerBatteryStatusUpdate(this, info)) {
            mBatteryUsageProgressBarPref.setBottomSummary(generateLabel(info));
        }

        mBatteryUsageProgressBarPref.setUsageSummary(
                formatBatteryPercentageText(info.batteryLevel));
        mBatteryUsageProgressBarPref.setPercent(info.batteryLevel, BATTERY_MAX_LEVEL);
    }

    /** Callback which receives text for the summary line. */
    public void updateBatteryStatus(String label, BatteryInfo info) {
        final CharSequence summary = label != null ? label : generateLabel(info);
        mBatteryUsageProgressBarPref.setBottomSummary(summary);
        Log.d(TAG, "updateBatteryStatus: " + label + " summary: " + summary);
    }

    public void quickUpdateHeaderPreference() {
        if (mBatteryUsageProgressBarPreference == null) {
            return;
        }

        Intent batteryBroadcast =
                com.android.settingslib.fuelgauge.BatteryUtils.getBatteryIntent(mContext);
        final int batteryLevel = Utils.getBatteryLevel(batteryBroadcast);
        final boolean discharging =
                batteryBroadcast.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) == 0;
        final int chargeCounterUah =
                batteryBroadcast.getIntExtra(BatteryManager.EXTRA_CHARGE_COUNTER, -1);

        mBatteryUsageProgressBarPreference.setUsageSummary(
                formatBatteryPercentageText(batteryLevel));
        mBatteryUsageProgressBarPreference.setPercent(batteryLevel, BATTERY_MAX_LEVEL);

        if (chargeCounterUah > 0) {
            int chargeCounter = chargeCounterUah / 1_000;
            mBatteryUsageProgressBarPreference.setTotalSummary(
                    formatBatteryChargeCounterText(chargeCounter));
        }
    }

    private CharSequence formatBatteryPercentageText(int batteryLevel) {
        return com.android.settings.Utils.formatPercentage(batteryLevel);
    }

    private CharSequence formatBatteryChargeCounterText(int chargeCounter) {
        return mContext.getString(R.string.battery_charge_counter_summary, chargeCounter);
    }
}
// LINT.ThenChange(BatteryHeaderPreference.kt)
