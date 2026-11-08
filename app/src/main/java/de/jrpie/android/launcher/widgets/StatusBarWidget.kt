package de.jrpie.android.launcher.widgets

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import de.jrpie.android.launcher.ui.widgets.StatusBarView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("widget:statusbar")
class StatusBarWidget(
    override var id: Int,
    override var position: WidgetPosition,
    override val panelId: Int,
    override var allowInteraction: Boolean = true
) : Widget() {

    override fun createView(activity: Activity): View {
        return StatusBarView(activity, null, id, panelId)
    }

    override fun findView(views: Sequence<View>): StatusBarView? {
        return views.mapNotNull { it as? StatusBarView }.firstOrNull { it.appWidgetId == id }
    }

    override fun getPreview(context: Context): Drawable? {
        return null
    }

    override fun getIcon(context: Context): Drawable? {
        return null
    }

    override fun isConfigurable(context: Context): Boolean {
        return false
    }

    override fun isReconfigurable(context: Context): Boolean {
        return false
    }

    override fun configure(activity: Activity, requestCode: Int) {}
}
