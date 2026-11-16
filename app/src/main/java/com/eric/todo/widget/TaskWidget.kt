package com.eric.todo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.eric.todo.MainActivity
import com.eric.todo.R
import com.eric.todo.data.BUSINESS
import com.eric.todo.data.PERSONAL
import com.eric.todo.data.Settings
import com.eric.todo.data.Task
import com.eric.todo.data.TaskStore

/**
 * Home-screen list. Resize it to fill the whole screen.
 * Tap BUSINESS / PERSONAL to switch, tap a task to check it off, Undo brings it back.
 */
class TaskWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.eric.todo.WIDGET_TOGGLE"
        const val ACTION_SWITCH = "com.eric.todo.WIDGET_SWITCH"
        const val ACTION_UNDO = "com.eric.todo.WIDGET_UNDO"
        const val EXTRA_ID = "task_id"
        const val EXTRA_LIST = "list"
        private const val PREFS = "widget"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TaskWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, views(context, it)) }
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun lastDone(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_done", null)

        private fun setLastDone(context: Context, id: String?) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_done", id).apply()

        private fun views(context: Context, widgetId: Int): RemoteViews {
            val settings = Settings.get(context)
            val list = settings.widgetList(widgetId)
            val all = TaskStore.all(context)
            val businessCount = all.count { it.list == BUSINESS && !it.done }
            val personalCount = all.count { it.list == PERSONAL && !it.done }
            val open = all.count { it.list == list && !it.done }

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            fun switchTo(target: String, code: Int) = PendingIntent.getBroadcast(
                context, widgetId * 10 + code,
                Intent(context, TaskWidget::class.java)
                    .setAction(ACTION_SWITCH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(EXTRA_LIST, target),
                flags,
            )
            val openApp = PendingIntent.getActivity(
                context, widgetId * 10 + 3,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_LIST, list)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            )
            val undo = PendingIntent.getBroadcast(
                context, widgetId * 10 + 4,
                Intent(context, TaskWidget::class.java).setAction(ACTION_UNDO),
                flags,
            )
            val toggle = PendingIntent.getBroadcast(
                context, widgetId * 10 + 5,
                Intent(context, TaskWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,   // filled in per row
            )
            val adapter = Intent(context, TaskWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }

            val undoVisible = lastDone(context)?.let { id -> all.any { it.id == id && it.done } } == true

            return RemoteViews(context.packageName, R.layout.widget_tasks).apply {
                setTextViewText(R.id.tab_business, "BUSINESS $businessCount")
                setTextViewText(R.id.tab_personal, "PERSONAL $personalCount")
                val bizOn = list == BUSINESS
                setInt(R.id.tab_business, "setBackgroundResource", if (bizOn) R.drawable.tab_business_on else R.drawable.tab_off)
                setInt(R.id.tab_personal, "setBackgroundResource", if (!bizOn) R.drawable.tab_personal_on else R.drawable.tab_off)
                setTextColor(R.id.tab_business, if (bizOn) Color.BLACK else Color.WHITE)
                setTextColor(R.id.tab_personal, if (!bizOn) Color.BLACK else Color.WHITE)
                setOnClickPendingIntent(R.id.tab_business, switchTo(BUSINESS, 1))
                setOnClickPendingIntent(R.id.tab_personal, switchTo(PERSONAL, 2))
                setOnClickPendingIntent(R.id.widget_add, openApp)
                setOnClickPendingIntent(R.id.widget_empty, openApp)
                setOnClickPendingIntent(R.id.widget_undo, undo)
                setViewVisibility(R.id.widget_undo, if (undoVisible) View.VISIBLE else View.GONE)

                @Suppress("DEPRECATION")
                setRemoteAdapter(R.id.widget_list, adapter)
                setEmptyView(R.id.widget_list, R.id.widget_empty)
                setPendingIntentTemplate(R.id.widget_list, toggle)
                setTextViewText(R.id.widget_empty, "Nothing on your ${list.lowercase()} list.\nTap + to add.")
                setViewVisibility(R.id.widget_empty, if (open == 0) View.VISIBLE else View.GONE)
            }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { manager.updateAppWidget(it, views(context, it)) }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> intent.getStringExtra(EXTRA_ID)?.let {
                setLastDone(context, it)
                TaskStore.setDone(context, it, true)     // refreshes the widget
            }
            ACTION_UNDO -> lastDone(context)?.let {
                setLastDone(context, null)
                TaskStore.setDone(context, it, false)
            }
            ACTION_SWITCH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val list = intent.getStringExtra(EXTRA_LIST) ?: return
                if (id != -1) {
                    Settings.get(context).setWidgetList(id, list)
                    val manager = AppWidgetManager.getInstance(context)
                    manager.updateAppWidget(id, views(context, id))
                    @Suppress("DEPRECATION")
                    manager.notifyAppWidgetViewDataChanged(intArrayOf(id), R.id.widget_list)
                }
            }
        }
    }
}

class TaskWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))

    private class Factory(private val context: Context, private val widgetId: Int) : RemoteViewsFactory {
        private var items: List<Task> = emptyList()
        private var textSize = 22f

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            val settings = Settings.get(context)
            items = TaskStore.open(context, settings.widgetList(widgetId))
            textSize = (settings.values.value.textSize - 2).coerceIn(18, 30).toFloat()
        }
        override fun onDestroy() = Unit
        override fun getCount() = items.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true

        override fun getViewAt(position: Int): RemoteViews {
            val item = items.getOrNull(position)
                ?: return RemoteViews(context.packageName, R.layout.widget_task_item)
            return RemoteViews(context.packageName, R.layout.widget_task_item).apply {
                setTextViewText(R.id.item_text, item.text)
                setTextViewTextSize(R.id.item_text, TypedValue.COMPLEX_UNIT_SP, textSize)
                setImageViewResource(R.id.item_check, if (item.list == PERSONAL) R.drawable.ic_circle_personal else R.drawable.ic_circle_business)
                setTextViewText(R.id.item_count, "×${item.count}")
                setViewVisibility(R.id.item_count, if (item.count > 1) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.item_urgent, if (item.urgent) View.VISIBLE else View.GONE)
                setOnClickFillInIntent(R.id.item_row, Intent().putExtra(TaskWidget.EXTRA_ID, item.id))
            }
        }
    }
}
