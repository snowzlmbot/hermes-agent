package com.snowzlmbot.hermes.mobile.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.snowzlmbot.hermes.mobile.MainActivity
import com.snowzlmbot.hermes.mobile.R

internal enum class NotificationKind(val wireValue: String) {
  COMPLETION("completion"),
  APPROVAL("approval"),
  INPUT("input"),
}

internal data class ChatNotificationSignal(
  val kind: NotificationKind,
  val storedSessionId: String,
)

internal object HermesNotificationContract {
  const val CHANNEL_ID = "hermes-session-events"
  const val OPEN_STORED_SESSION_ACTION = "com.snowzlmbot.hermes.mobile.OPEN_STORED_SESSION"
  const val STORED_SESSION_ID_EXTRA = "stored_session_id"
  const val PROFILE_SCOPE_EXTRA = "profile_scope"
}

internal object NotificationRouteMetadata {
  private val scopePattern = Regex("[0-9a-f]{64}")

  fun profileScope(sessionSelectionScope: String): String =
    NotificationProfileScope.fromSelectionScope(sessionSelectionScope)

  fun isValid(route: StoredSessionRoute): Boolean =
    route.storedSessionId.isNotBlank() &&
      route.storedSessionId == route.storedSessionId.trim() &&
      route.storedSessionId.toByteArray(Charsets.UTF_8).size <= 1_024 &&
      scopePattern.matches(route.profileScope)
}

internal object NotificationPermissionPolicy {
  fun canPost(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
}

internal object NotificationIds {
  fun forSignal(kind: NotificationKind, route: StoredSessionRoute): Int =
    "${kind.wireValue}|${route.profileScope}|${route.storedSessionId}".hashCode()
}

internal object NotificationContentFactory {
  fun openIntent(context: Context, route: StoredSessionRoute): Intent = Intent(context, MainActivity::class.java)
    .setAction(HermesNotificationContract.OPEN_STORED_SESSION_ACTION)
    .putExtra(HermesNotificationContract.STORED_SESSION_ID_EXTRA, route.storedSessionId)
    .putExtra(HermesNotificationContract.PROFILE_SCOPE_EXTRA, route.profileScope)

  fun pendingIntent(context: Context, kind: NotificationKind, route: StoredSessionRoute): PendingIntent =
    PendingIntent.getActivity(
      context,
      NotificationIds.forSignal(kind, route),
      openIntent(context, route),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal object NotificationIntentConsumer {
  private val allowedKeys = setOf(
    HermesNotificationContract.STORED_SESSION_ID_EXTRA,
    HermesNotificationContract.PROFILE_SCOPE_EXTRA,
  )

  fun consume(intent: Intent, handler: (StoredSessionRoute) -> Unit) {
    if (intent.action != HermesNotificationContract.OPEN_STORED_SESSION_ACTION) return
    val keys = intent.extras?.keySet().orEmpty()
    val route = StoredSessionRoute(
      storedSessionId = intent.getStringExtra(HermesNotificationContract.STORED_SESSION_ID_EXTRA).orEmpty(),
      profileScope = intent.getStringExtra(HermesNotificationContract.PROFILE_SCOPE_EXTRA).orEmpty(),
    )
    intent.action = null
    intent.replaceExtras(Bundle())
    if (keys == allowedKeys && NotificationRouteMetadata.isValid(route)) handler(route)
  }
}

internal class LocalNotificationService(
  private val context: Context,
  private val notificationManager: NotificationManager =
    context.getSystemService(NotificationManager::class.java),
  private val appInForeground: () -> Boolean = {
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
  },
  private val permissionChecker: () -> Boolean = { NotificationPermissionPolicy.canPost(context) },
  private val notificationSink: (Int, Notification) -> Unit = notificationManager::notify,
) {
  fun initializeChannel() {
    notificationManager.createNotificationChannel(
      NotificationChannel(
        HermesNotificationContract.CHANNEL_ID,
        context.getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = context.getString(R.string.notification_channel_description)
      },
    )
  }

  fun post(kind: NotificationKind, route: StoredSessionRoute) {
    if (appInForeground() || !permissionChecker() || !NotificationRouteMetadata.isValid(route)) return
    initializeChannel()
    val body = when (kind) {
      NotificationKind.COMPLETION -> R.string.notification_completion_body
      NotificationKind.APPROVAL -> R.string.notification_approval_body
      NotificationKind.INPUT -> R.string.notification_input_body
    }
    val notification = NotificationCompat.Builder(context, HermesNotificationContract.CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(context.getString(body))
      .setAutoCancel(true)
      .setContentIntent(NotificationContentFactory.pendingIntent(context, kind, route))
      .build()
    notificationSink(NotificationIds.forSignal(kind, route), notification)
  }
}
