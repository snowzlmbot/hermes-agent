package com.snowzlmbot.hermes.mobile.platform

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalNotificationServiceTest {
  private val context: Application
    get() = ApplicationProvider.getApplicationContext()
  private val route = StoredSessionRoute("stored-safe", "a".repeat(64))

  @Test
  fun channelCreationIsIdempotent() {
    val manager = context.getSystemService(NotificationManager::class.java)
    val service = LocalNotificationService(context, manager, { false }, { true })

    service.initializeChannel()
    service.initializeChannel()

    assertEquals(1, manager.notificationChannels.count { it.id == HermesNotificationContract.CHANNEL_ID })
  }

  @Test
  fun android13WithoutPermissionDoesNotThrowOrPublish() {
    val published = mutableListOf<Pair<Int, Notification>>()
    val service = service(permission = false, published = published)

    service.post(NotificationKind.COMPLETION, route)

    assertTrue(published.isEmpty())
  }

  @Test
  fun foregroundSuppressesEverySupportedNotification() {
    val published = mutableListOf<Pair<Int, Notification>>()
    val service = service(foreground = true, published = published)

    NotificationKind.entries.forEach { service.post(it, route) }

    assertTrue(published.isEmpty())
  }

  @Test
  fun backgroundPublishesRouteWithoutSensitivePayload() {
    val published = mutableListOf<Pair<Int, Notification>>()
    val service = service(published = published)

    service.post(NotificationKind.INPUT, route)

    assertEquals(1, published.size)
    assertTrue(published.single().second.contentIntent != null)
  }

  @Test
  fun invalidRouteIsSilentlyRejected() {
    val published = mutableListOf<Pair<Int, Notification>>()
    val service = service(published = published)

    service.post(NotificationKind.APPROVAL, StoredSessionRoute("stored", "not-a-hash"))

    assertTrue(published.isEmpty())
  }

  @Test
  @Config(sdk = [32])
  fun preAndroid13DoesNotRequireRuntimeNotificationPermission() {
    assertTrue(NotificationPermissionPolicy.canPost(context))
  }

  private fun service(
    foreground: Boolean = false,
    permission: Boolean = true,
    published: MutableList<Pair<Int, Notification>>,
  ): LocalNotificationService {
    val manager = context.getSystemService(NotificationManager::class.java)
    return LocalNotificationService(
      context = context,
      notificationManager = manager,
      appInForeground = { foreground },
      permissionChecker = { permission },
      notificationSink = { id, notification -> published += id to notification },
    )
  }
}
