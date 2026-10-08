package app.relay.spike

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.PowerManager

/**
 * Experiment 0.4b: does registering the Mac as a "companion device" make
 * Android exempt us from battery optimisation? We check
 * PowerManager.isIgnoringBatteryOptimizations before and after.
 */
object CompanionHelper {
    const val REQUEST_ASSOCIATE = 42

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.packageManager.hasSystemFeature("android.software.companion_device_setup")

    fun associationCount(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return 0
        return cdm.myAssociations.size
    }

    fun isBatteryExempt(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Shows the system picker of nearby Bluetooth devices; choose your Mac. Android 13+ only. */
    fun associate(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            SpikeLog.log("CDM: this experiment needs Android 13+")
            return
        }
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)
            .build()
        SpikeLog.log("CDM: battery exempt BEFORE association = ${isBatteryExempt(activity)}")
        cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                activity.startIntentSenderForResult(intentSender, REQUEST_ASSOCIATE, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                SpikeLog.log("CDM: associated with \"${associationInfo.displayName}\"")
                SpikeLog.log("CDM: battery exempt AFTER association = ${isBatteryExempt(activity)}")
                // A companion app may ask the system to show the notification-access screen.
                @Suppress("DEPRECATION")
                runCatching {
                    cdm.requestNotificationAccess(
                        ComponentName(activity, RelayNotificationListener::class.java),
                    )
                }.onFailure { SpikeLog.log("CDM: requestNotificationAccess failed: ${it.message}") }
            }

            override fun onFailure(error: CharSequence?) {
                SpikeLog.log("CDM: association failed: $error")
            }
        })
    }
}
