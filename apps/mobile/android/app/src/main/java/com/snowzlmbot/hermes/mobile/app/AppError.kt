package com.snowzlmbot.hermes.mobile.app

import com.snowzlmbot.hermes.mobile.R
import com.snowzlmbot.hermes.mobile.core.EndpointValidationException
import com.snowzlmbot.hermes.mobile.core.InsecureEndpointException

internal enum class AppError(val messageRes: Int) {
  TOKEN_REQUIRED(R.string.error_gateway_token_required),
  CLEARTEXT_DISABLED(R.string.error_cleartext_disabled),
  GATEWAY_ADDRESS_INVALID(R.string.error_gateway_address_invalid),
  CONNECTION_SAVE_FAILED(R.string.error_connection_save),
  CONNECTION_FAILED(R.string.error_gateway_connect),
  OAUTH_OPTIONS_FAILED(R.string.error_oauth_options),
  OAUTH_IN_PROGRESS(R.string.error_oauth_finish_current),
  OAUTH_OPTIONS_REQUIRED(R.string.error_oauth_load_first),
  OAUTH_OPTIONS_STALE(R.string.error_oauth_reload_address),
  OAUTH_BROWSER_OPEN_FAILED(R.string.error_oauth_open),
  OAUTH_START_FAILED(R.string.error_oauth_start),
  OAUTH_NO_ACTIVE_FLOW(R.string.error_oauth_missing_pending),
  OAUTH_COMPLETE_FAILED(R.string.error_oauth_complete),
  OAUTH_BROWSER_UNAVAILABLE(R.string.error_oauth_browser_missing),
  ATTACHMENT_FAILED(R.string.error_attachment_upload),
  ;

  companion object {
    fun forConnection(error: Throwable): AppError = when (error) {
      is EndpointValidationException -> GATEWAY_ADDRESS_INVALID
      is InsecureEndpointException -> CLEARTEXT_DISABLED
      else -> CONNECTION_SAVE_FAILED
    }

    fun forOAuthDiscovery(error: Throwable): AppError = when (error) {
      is EndpointValidationException -> GATEWAY_ADDRESS_INVALID
      is InsecureEndpointException -> CLEARTEXT_DISABLED
      else -> OAUTH_OPTIONS_FAILED
    }

    fun forOAuthStart(error: Throwable): AppError = when (error) {
      is EndpointValidationException -> GATEWAY_ADDRESS_INVALID
      is InsecureEndpointException -> CLEARTEXT_DISABLED
      else -> OAUTH_START_FAILED
    }
  }
}
