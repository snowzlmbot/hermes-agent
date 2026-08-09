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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.snowzlmbot.hermes.mobile.R
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
        Text(
          selected?.let { stringResource(R.string.model_selected, it.id, it.providerName) }
            ?: if (isLoading) stringResource(R.string.models_loading) else stringResource(R.string.model_select)
        )
      }
      DropdownMenu(
        expanded = modelExpanded,
        onDismissRequest = { modelExpanded = false },
      ) {
        models.forEach { option ->
          DropdownMenuItem(
            text = { Text(stringResource(R.string.model_option, option.id, option.providerName)) },
            onClick = {
              modelExpanded = false
              onSelectModel(option)
            },
          )
        }
        DropdownMenuItem(
          text = { Text(stringResource(R.string.models_refresh)) },
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
          Text(stringResource(R.string.reasoning_selected, reasoningEffort.ifBlank { stringResource(R.string.reasoning_default) }))
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