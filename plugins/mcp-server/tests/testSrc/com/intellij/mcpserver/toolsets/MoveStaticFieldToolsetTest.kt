// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.GeneralMcpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.MoveStaticFieldToolset
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.junit5.fixture.virtualFileFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TARGET_CLASS = "FieldTarget"

private val SOURCE_CONTENT: String = """
  public class FieldSource {
    public static int STATIC_MOVE_ME = 42;
    public static int DUP = 1;
    public int instanceMoveMe = 7;

    public static int caller() {
      return STATIC_MOVE_ME;
    }
  }
  """.trimIndent()

private val TARGET_CONTENT: String = """
  public class FieldTarget {
    public static int DUP = 99;
  }
  """.trimIndent()

class MoveStaticFieldToolsetTest : GeneralMcpToolsetTestBase() {
  val moveSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("FieldSource.java", SOURCE_CONTENT)
  val moveTargetJavaFileFixture = sourceRootFixture.virtualFileFixture("FieldTarget.java", TARGET_CONTENT)
  val moveSourceJavaFile by moveSourceJavaFileFixture
  val moveTargetJavaFile by moveTargetJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // The tool performs index-backed lookups (findClass, findUsages) and throws IndexNotReadyException
    // if the project is still in dumb mode. Flush indexing scheduled by the realized fixtures first.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun moves_static_field_between_classes() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveStaticFieldToolset::move_static_field.name,
      moveStaticFieldArgs(fieldName = "STATIC_MOVE_ME"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.int("usagesUpdated")).isGreaterThanOrEqualTo(1)
      assertThat(result.textContent.text).contains("\"src/FieldSource.java\"")
      assertThat(result.textContent.text).contains("\"src/FieldTarget.java\"")
    }

    // the field is gone from its old location
    testMcpTool(
      MoveStaticFieldToolset::move_static_field.name,
      moveStaticFieldArgs(fieldName = "STATIC_MOVE_ME"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Couldn't find field 'STATIC_MOVE_ME'")
    }
  }

  @Test
  fun rejects_instance_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveStaticFieldToolset::move_static_field.name,
      moveStaticFieldArgs(fieldName = "instanceMoveMe"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(result.textContent.text).contains("is not static")
      assertThat(result.textContent.text).contains("move_instance_field")
    }
  }

  @Test
  fun reports_conflicts_when_target_class_already_has_the_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveStaticFieldToolset::move_static_field.name,
      moveStaticFieldArgs(fieldName = "DUP"),
    ) { result ->
      // DUP exists in both FieldSource and FieldTarget -> name clash in the target class.
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(result.textContent.text).contains("already exists")
    }
  }

  @Test
  fun reports_error_for_unknown_target_class() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveStaticFieldToolset::move_static_field.name,
      moveStaticFieldArgs(targetClass = "pkg.Nonexistent"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Target class 'pkg.Nonexistent' not found")
    }
  }

  private fun moveStaticFieldArgs(
    fieldName: String = "STATIC_MOVE_ME",
    targetClass: String = TARGET_CLASS,
    pathOverride: String? = null,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(pathOverride ?: moveSourceJavaFile.path))
      put("fieldName", JsonPrimitive(fieldName))
      put("targetClass", JsonPrimitive(targetClass))
    }
    waitForSmartModeAfterFixtureRealization()
    return args
  }

  private fun waitForSmartModeAfterFixtureRealization() {
    // Realizing the fixtures writes the Java files into the indexable source root. The tool's
    // index-backed queries throw IndexNotReadyException while those edits are still being indexed,
    // so flush dumb mode before the call.
    DumbService.getInstance(project).waitForSmartMode()
  }

  private fun parseResult(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.content.toInt()

  private fun JsonObject.bool(name: String) = this[name]!!.jsonPrimitive.content.toBooleanStrict()

  private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}
