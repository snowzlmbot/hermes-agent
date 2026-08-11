package com.snowzlmbot.hermes.mobile.core

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidProfileStoreTest {
  private val context: Application
    get() = ApplicationProvider.getApplicationContext()

  @Before
  fun resetPreferences() {
    context.getSharedPreferences("hermes.mobile.connection", Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun generatedProfileIdIsStableAcrossLoads() = runTest {
    val prefs = context.getSharedPreferences("hermes.mobile.connection", Context.MODE_PRIVATE)
    prefs.edit().clear().putString("address", "https://gateway.example/").putString("auth_mode", "TOKEN").commit()
    val first = AndroidProfileStore(context).load()
    val second = AndroidProfileStore(context).load()
    assertNotNull(first)
    assertEquals(first?.id, second?.id)
  }

  @Test
  fun savedProfileIdRoundTrips() = runTest {
    val profile = GatewayProfile("https://gateway.example/", GatewayAuthMode.TOKEN, false, id = "profile-a")
    AndroidProfileStore(context).save(profile)
    assertEquals(profile, AndroidProfileStore(context).load())
  }

  @After
  fun clearPreferences() {
    context.getSharedPreferences("hermes.mobile.connection", Context.MODE_PRIVATE).edit().clear().commit()
  }
}
