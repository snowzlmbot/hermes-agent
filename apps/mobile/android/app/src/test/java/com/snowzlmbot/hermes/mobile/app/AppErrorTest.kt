package com.snowzlmbot.hermes.mobile.app

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.snowzlmbot.hermes.mobile.R
import com.snowzlmbot.hermes.mobile.core.EndpointValidationException
import com.snowzlmbot.hermes.mobile.core.InsecureEndpointException
import com.snowzlmbot.hermes.mobile.core.NativeAuthException
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppErrorTest {
  @Test
  fun mapsConnectionValidationWithoutExposingExceptionText() {
    assertEquals(
      R.string.error_gateway_address_invalid,
      AppError.forConnection(EndpointValidationException("private-host.example")).messageRes,
    )
    assertEquals(
      R.string.error_cleartext_disabled,
      AppError.forConnection(InsecureEndpointException("private-network-detail")).messageRes,
    )
    assertEquals(
      R.string.error_connection_save,
      AppError.forConnection(IllegalStateException("secret backend detail")).messageRes,
    )
  }

  @Test
  fun mapsOAuthFailuresToStableResourceIds() {
    assertEquals(
      R.string.error_gateway_address_invalid,
      AppError.forOAuthDiscovery(EndpointValidationException("invalid endpoint detail")).messageRes,
    )
    assertEquals(
      R.string.error_cleartext_disabled,
      AppError.forOAuthDiscovery(InsecureEndpointException("transport detail")).messageRes,
    )
    assertEquals(
      R.string.error_oauth_options,
      AppError.forOAuthDiscovery(NativeAuthException("gateway provider detail")).messageRes,
    )
    assertEquals(
      R.string.error_oauth_start,
      AppError.forOAuthStart(NativeAuthException("provider detail")).messageRes,
    )
  }

  @Test
  fun resolvesTypedErrorsThroughEnglishAndChineseResources() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val english = context.createConfigurationContext(
      Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
    )
    val chinese = context.createConfigurationContext(
      Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
    )

    assertEquals("Enter a gateway token.", english.getString(AppError.TOKEN_REQUIRED.messageRes))
    assertEquals("请输入网关令牌。", chinese.getString(AppError.TOKEN_REQUIRED.messageRes))
    assertEquals(
      "Could not load OAuth sign-in options.",
      english.getString(AppError.OAUTH_OPTIONS_FAILED.messageRes),
    )
    assertEquals(
      "无法加载 OAuth 登录方式。",
      chinese.getString(AppError.OAUTH_OPTIONS_FAILED.messageRes),
    )
  }
}
