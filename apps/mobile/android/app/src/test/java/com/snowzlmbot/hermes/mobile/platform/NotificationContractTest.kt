package com.snowzlmbot.hermes.mobile.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.snowzlmbot.hermes.mobile.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationContractTest {
  private val context: Application
    get() = ApplicationProvider.getApplicationContext()
  private val route = StoredSessionRoute("stored-safe", "a".repeat(64))

  @Test
  fun profileScopeIsLowercaseSha256() {
    val first = NotificationRouteMetadata.profileScope("profile-a|https://gateway.example/")
    val second = NotificationRouteMetadata.profileScope("profile-b|https://gateway.example/")
    assertEquals(64, first.length)
    assertTrue(Regex("[0-9a-f]{64}").matches(first))
    assertFalse(first == second)
  }

  @Test
  fun openIntentContainsExactlyStoredIdAndScope() {
    val intent = NotificationContentFactory.openIntent(context, route)
    assertEquals(context.packageName, intent.component?.packageName)
    assertEquals(MainActivity::class.java.name, intent.component?.className)
    assertEquals(HermesNotificationContract.OPEN_STORED_SESSION_ACTION, intent.action)
    assertEquals(route.storedSessionId, intent.getStringExtra(HermesNotificationContract.STORED_SESSION_ID_EXTRA))
    assertEquals(route.profileScope, intent.getStringExtra(HermesNotificationContract.PROFILE_SCOPE_EXTRA))
    assertEquals(
      setOf(HermesNotificationContract.STORED_SESSION_ID_EXTRA, HermesNotificationContract.PROFILE_SCOPE_EXTRA),
      intent.extras?.keySet(),
    )
  }

  @Test
  fun notificationIdsDifferAcrossRoutesAndKinds() {
    val other = StoredSessionRoute("stored-two", route.profileScope)
    assertFalse(NotificationIds.forSignal(NotificationKind.COMPLETION, route) == NotificationIds.forSignal(NotificationKind.COMPLETION, other))
    assertFalse(NotificationIds.forSignal(NotificationKind.COMPLETION, route) == NotificationIds.forSignal(NotificationKind.APPROVAL, route))
  }

  @Test
  fun consumesAndClearsRouteExactlyOnce() {
    val source = NotificationContentFactory.openIntent(context, route)
    val consumed = mutableListOf<StoredSessionRoute>()
    NotificationIntentConsumer.consume(source) { consumed += it }
    NotificationIntentConsumer.consume(source) { consumed += it }
    assertEquals(listOf(route), consumed)
    assertNull(source.getStringExtra(HermesNotificationContract.STORED_SESSION_ID_EXTRA))
    assertNull(source.getStringExtra(HermesNotificationContract.PROFILE_SCOPE_EXTRA))
  }

  @Test
  fun rejectsMissingScopeAndExtraMetadata() {
    val missing = NotificationContentFactory.openIntent(context, route)
      .removeExtra(HermesNotificationContract.PROFILE_SCOPE_EXTRA)
    val extra = NotificationContentFactory.openIntent(context, route).putExtra("token", "forbidden")
    val consumed = mutableListOf<StoredSessionRoute>()
    NotificationIntentConsumer.consume(missing) { consumed += it }
    NotificationIntentConsumer.consume(extra) { consumed += it }
    assertTrue(consumed.isEmpty())
  }
}
