package com.snowzlmbot.hermes.mobile.feature

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidStoredSessionSelectionStoreTest {
  @Test
  fun storesSelectionsByGatewayProfileAndClearsOnlyTheTarget() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val first = AndroidStoredSessionSelectionStore(context, "https://first.example")
    val second = AndroidStoredSessionSelectionStore(context, "https://second.example")

    first.save("stored-first")
    second.save("stored-second")

    assertEquals("stored-first", first.load())
    assertEquals("stored-second", second.load())
    first.clear()
    assertNull(first.load())
    assertEquals("stored-second", second.load())
  }
}
