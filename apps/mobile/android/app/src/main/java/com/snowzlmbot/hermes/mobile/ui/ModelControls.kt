package com.snowzlmbot.hermes.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.snowzlmbot.hermes.mobile.core.ModelCatalog
import com.snowzlmbot.hermes.mobile.core.ModelOption

private val reasoningEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

@Composable
internal fun ModelControls(
  catalog: ModelCatalog,
  currentModel: String,
  currentProvider: String,
  reasoningEffort: String,
  isLoading: Boolean,
  onRefresh: () -> Unit,
  onSelectModel: (ModelOption) -> Unit,
  onSetReasoningEffort: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val models = catalog.providers.flatMap { it.models }
  val selected = models.firstOrNull { it.providerId == currentProvider && it.id == currentModel }
  var modelExpanded by remember { mutableStateOf(false) }
  var reasoningExpanded by remember { mutableStateOf(false) }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(Modifier.fillMaxWidth()) {
      OutlinedButton(
        onClick = { modelExpanded = true },
        modifier = Modifier.fillMaxWidth().testTag("model-picker"),
      ) {
        Text(selected?.let { "Model: ${it.id}" } ?: if (isLoading) "Loading models..." else "Select model")
      }
      DropdownMenu(
        expanded = modelExpanded,
        onDismissRequest = { modelExpanded = false },
      ) {
        models.forEach { option ->
          DropdownMenuItem(
            text = { Text(option.id) },
            onClick = {
              modelExpanded = false
              onSelectModel(option)
            },
          )
        }
        DropdownMenuItem(
          text = { Text("Refresh models") },
          onClick = {
            modelExpanded = false
            onRefresh()
          },
        )
      }
    }

    if (selected?.supportsReasoning == true) {
      Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
          onClick = { reasoningExpanded = true },
          modifier = Modifier.fillMaxWidth().testTag("reasoning-picker"),
        ) {
          Text("Reasoning: ${reasoningEffort.ifBlank { "medium" }}")
        }
        DropdownMenu(
          expanded = reasoningExpanded,
          onDismissRequest = { reasoningExpanded = false },
        ) {
          reasoningEfforts.forEach { effort ->
            DropdownMenuItem(
              text = { Text(effort) },
              onClick = {
                reasoningExpanded = false
                onSetReasoningEffort(effort)
              },
            )
          }
        }
      }
    }
  }
}