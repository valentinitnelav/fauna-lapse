package com.faunalapse.app;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * "Screen off": switches the screen off and locks the phone at once, as the power button does, so
 * the phone need not be pressed where it stands (a press may move it out of focus). Android lets an
 * app do this only as a "device admin app", here with the single right to lock the screen, which
 * the user grants once in Android's own screen. The session goes on: it never needs the screen.
 * Android asks to remove the right before FaunaLapse can be uninstalled.
 */
public final class ScreenOff extends DeviceAdminReceiver {

    private static ComponentName admin(Context context) {
        return new ComponentName(context, ScreenOff.class);
    }

    private static DevicePolicyManager manager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean allowed(Context context) {
        return manager(context).isAdminActive(admin(context));
    }

    static void lock(Context context) {
        manager(context).lockNow();
    }

    /** Android's screen that asks the user for the right, with FaunaLapse's reason. */
    static Intent ask(Context context) {
        return new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.screen_off_reason));
    }

    /** Gives the right back (for example before uninstalling FaunaLapse). */
    static void remove(Context context) {
        manager(context).removeActiveAdmin(admin(context));
    }
}
