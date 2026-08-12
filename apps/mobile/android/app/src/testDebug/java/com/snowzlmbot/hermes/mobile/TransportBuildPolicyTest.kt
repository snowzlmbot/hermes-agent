package com.snowzlmbot.hermes.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TransportBuildPolicyTest {
  @Test
  fun debugBuildExplicitlyAllowsDevelopmentCleartext() {
    assertTrue(BuildConfig.ALLOW_INSECURE_TRANSPORT)
    val flags = RuntimeEnvironment.getApplication().applicationInfo.flags
    assertTrue(flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
  }
}
