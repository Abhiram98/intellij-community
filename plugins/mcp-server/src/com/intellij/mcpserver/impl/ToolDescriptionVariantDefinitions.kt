// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mcpserver.impl

import com.intellij.mcpserver.ToolDescriptionVariant
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.PathManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val logger = logger<ToolDescriptionVariantDefinitionsLoader>()

/**
 * The agent-facing description overrides of one [ToolDescriptionVariant].
 *
 * The map is keyed by tool name; tools without an entry keep the baseline text. [params] holds
 * parameter description overrides keyed by parameter name.
 */
@Serializable
internal data class ToolDescriptionVariantDefinitions(
  val tools: Map<String, ToolDescriptionOverride> = emptyMap(),
)

@Serializable
internal data class ToolDescriptionOverride(
  val description: String? = null,
  val params: Map<String, String> = emptyMap(),
)

/**
 * Loads the definitions of a variant. [ToolDescriptionVariant.BASELINE] always loads to `null`.
 *
 * A file in the IDE config directory (`mcpServer/descriptions/<id>.json`) overrides the definitions
 * bundled with the plugin (`toolDescriptionVariants/<id>.json`), so an experiment can restate a
 * variant without a rebuild.
 */
internal object ToolDescriptionVariantDefinitionsLoader {
  private const val CONFIG_DIRECTORY: String = "mcpServer/descriptions"
  private const val RESOURCE_DIRECTORY: String = "/toolDescriptionVariants"

  private val json = Json { ignoreUnknownKeys = true }

  fun load(variant: ToolDescriptionVariant): ToolDescriptionVariantDefinitions? {
    if (variant == ToolDescriptionVariant.BASELINE) return null
    val configText = readConfigFileText(variant)
    val text = configText ?: readResourceText(variant) ?: return null
    return try {
      json.decodeFromString<ToolDescriptionVariantDefinitions>(text)
    }
    catch (e: Exception) {
      logger.warn("Cannot parse the tool description definitions of variant '$variant'", e)
      null
    }
  }

  private fun readConfigFileText(variant: ToolDescriptionVariant): String? {
    val file: Path = PathManager.getConfigDir().resolve(CONFIG_DIRECTORY).resolve("${variant.id}.json")
    return try {
      file.takeIf { it.isRegularFile() }?.readText()
    }
    catch (e: Exception) {
      logger.warn("Cannot read the tool description definitions file $file", e)
      null
    }
  }

  private fun readResourceText(variant: ToolDescriptionVariant): String? {
    val resource = "$RESOURCE_DIRECTORY/${variant.id}.json"
    return ToolDescriptionVariantDefinitionsLoader::class.java.getResourceAsStream(resource)
      ?.readBytes()?.decodeToString()
  }
}
