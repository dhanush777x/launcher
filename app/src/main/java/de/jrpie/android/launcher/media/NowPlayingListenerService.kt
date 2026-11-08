package de.jrpie.android.launcher.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Required by [android.media.session.MediaSessionManager] to observe active media sessions.
 * The service itself does not process notifications; it only serves as the component the
 * system grants media session access to.
 */
class NowPlayingListenerService : NotificationListenerService() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, NowPlayingListenerService::class.java)

        fun hasAccess(context: Context): Boolean {
            val component = componentName(context)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager?.isNotificationListenerAccessGranted(component) == true
            } else {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    "enabled_notification_listeners"
                )
                enabled?.split(':')?.any {
                    ComponentName.unflattenFromString(it) == component
                } == true
            }
        }

        /**
         * Opens the system screen where notification access can be granted. On API 21 the
         * dedicated screen does not exist, so fall back to the general settings screen.
         */
        fun settingsIntent() = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                ACTION_NOTIFICATION_LISTENER_SETTINGS
            } else {
                Settings.ACTION_SETTINGS
            }
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private const val ACTION_NOTIFICATION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    }
}
