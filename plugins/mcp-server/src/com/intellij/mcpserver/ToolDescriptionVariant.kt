// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mcpserver

/**
 * Verbosity preset for the agent-facing descriptions of MCP tools.
 *
 * [BASELINE] serves the descriptions as the toolsets declare them. The other presets replace
 * individual descriptions and parameter descriptions with the overrides of the matching JSON
 * definition file; tools without an entry keep the baseline text.
 */
enum class ToolDescriptionVariant {
  BASELINE,
  TERSE,
  MEDIUM,
  VERBOSE,
  ;

  /** The identifier used in the variant file and in the JSON definition file names. */
  val id: String
    get() = name.lowercase()

  /** The [com.intellij.mcpserver.McpServerBundle] key of the human-readable name. */
  val displayNameKey: String
    get() = "tool.description.variant.$id"

  companion object {
    fun fromId(id: String): ToolDescriptionVariant? = entries.find { it.id == id }
  }
}
