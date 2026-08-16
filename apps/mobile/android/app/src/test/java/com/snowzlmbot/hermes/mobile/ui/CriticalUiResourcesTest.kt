package com.snowzlmbot.hermes.mobile.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class CriticalUiResourcesTest {
  @Test
  fun criticalUiStringsExistInDefaultAndChineseResources() {
    val defaultStrings = readStrings("values/strings.xml")
    val chineseStrings = readStrings("values-zh-rCN/strings.xml")

    assertTrue("Missing default strings: ${requiredKeys - defaultStrings.keys}", defaultStrings.keys.containsAll(requiredKeys))
    assertTrue("Missing Chinese strings: ${requiredKeys - chineseStrings.keys}", chineseStrings.keys.containsAll(requiredKeys))
    val untranslated = requiredKeys.filter { defaultStrings.getValue(it) == chineseStrings.getValue(it) }
    assertTrue("Untranslated critical strings: $untranslated", untranslated.isEmpty())
  }

  @Test
  fun localizedCriticalStringsPreserveFormatArguments() {
    val defaultStrings = readStrings("values/strings.xml")
    val chineseStrings = readStrings("values-zh-rCN/strings.xml")

    requiredKeys.forEach { key ->
      assertEquals("Format arguments differ for $key", formatArguments(defaultStrings.getValue(key)), formatArguments(chineseStrings.getValue(key)))
    }
  }

  private fun readStrings(relativePath: String): Map<String, String> {
    val file = listOf(
      File("app/src/main/res/$relativePath"),
      File("src/main/res/$relativePath"),
    ).firstOrNull(File::isFile) ?: error("Could not find $relativePath")
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    return (0 until document.getElementsByTagName("string").length)
      .map { document.getElementsByTagName("string").item(it) as Element }
      .associate { it.getAttribute("name") to it.textContent }
  }

  private fun formatArguments(value: String): List<String> =
    formatArgument.findAll(value).map { it.value }.toList()

  private companion object {
    val formatArgument = Regex("%\\d+\\\$[a-zA-Z]")

    val requiredKeys = setOf(
      "loading_connecting",
      "onboarding_connect_title",
      "gateway_address_label",
      "session_token_label",
      "allow_cleartext_gateway",
      "action_connect",
      "chat_new_conversation",
      "sessions_title",
      "reconnect_content_description",
      "connection_settings_title",
      "conversation_preparing",
      "settings_forget_description",
      "settings_forget_action",
      "session_new_content_description",
      "session_search",
      "action_clear",
      "session_view_active",
      "session_view_archived",
      "session_empty_view",
      "session_empty_search",
      "session_pinned_content_description",
      "session_actions_content_description",
      "session_action_restore",
      "session_action_unpin",
      "session_action_pin",
      "session_action_rename",
      "session_action_archive",
      "session_action_delete",
      "session_delete_title",
      "session_delete_message",
      "session_rename_title",
      "session_title_label",
      "action_save",
      "approval_required",
      "secret_required",
      "secret_input_label",
      "secret_send_securely",
      "sudo_required",
      "sudo_input_label",
      "sudo_authenticate",
      "composer_attach",
      "composer_stop_recording",
      "composer_record_voice",
      "composer_transcribing_voice",
      "composer_message_placeholder",
      "action_stop",
      "action_send",
      "attachment_dialog_title",
      "attachment_dialog_message",
      "attachment_choose_file",
      "error_microphone_permission_required",
    )
  }
}
