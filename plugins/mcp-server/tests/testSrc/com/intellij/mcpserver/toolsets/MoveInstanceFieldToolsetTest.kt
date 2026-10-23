// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.GeneralMcpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.MoveInstanceFieldToolset
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

private const val TARGET_CLASS = "FieldHolder"

// FieldSource holds a FieldHolder link ('holder'), so 'value' can be moved onto it.
// WithoutLink has no FieldHolder field, so a move from it must fail.
private val SOURCE_CONTENT: String = """
  public class InstanceFieldSource {
    public FieldHolder holder = new FieldHolder();
    public int value = 5;
    public static int STATIC_VALUE = 7;

    public int readInternally() {
      return value;
    }
  }

  class WithoutLink {
    public int orphan = 9;
  }
  """.trimIndent()

private val TARGET_CONTENT: String = """
  public class FieldHolder {
    public int existing = 0;
  }
  """.trimIndent()

private val CALLER_CONTENT: String = """
  public class InstanceFieldCaller {
    public int readExternally() {
      InstanceFieldSource s = new InstanceFieldSource();
      return s.value;
    }
  }
  """.trimIndent()

class MoveInstanceFieldToolsetTest : GeneralMcpToolsetTestBase() {
  val sourceFileFixture = sourceRootFixture.virtualFileFixture("InstanceFieldSource.java", SOURCE_CONTENT)
  val targetFileFixture = sourceRootFixture.virtualFileFixture("FieldHolder.java", TARGET_CONTENT)
  val callerFileFixture = sourceRootFixture.virtualFileFixture("InstanceFieldCaller.java", CALLER_CONTENT)
  val sourceFile by sourceFileFixture
  val targetFile by targetFileFixture
  val callerFile by callerFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun moves_instance_field_through_receiver() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveInstanceFieldToolset::move_instance_field.name,
      instanceFieldArgs(fieldName = "value"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("receiverName")).isEqualTo("holder")
      assertThat(json.int("usagesUpdated")).isGreaterThanOrEqualTo(2)
      assertThat(result.textContent.text).contains("\"src/InstanceFieldSource.java\"")
      assertThat(result.textContent.text).contains("\"src/FieldHolder.java\"")
    }

    // the field is gone from its old location
    testMcpTool(
      MoveInstanceFieldToolset::move_instance_field.name,
      instanceFieldArgs(fieldName = "value"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Couldn't find field 'value'")
    }
  }

  @Test
  fun rejects_static_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveInstanceFieldToolset::move_instance_field.name,
      instanceFieldArgs(fieldName = "STATIC_VALUE"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(result.textContent.text).contains("is static")
      assertThat(result.textContent.text).contains("move_static_field")
    }
  }

  @Test
  fun reports_missing_receiver() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // WithoutLink.orphan -> FieldHolder, but WithoutLink has no FieldHolder-typed field.
    testMcpTool(
      MoveInstanceFieldToolset::move_instance_field.name,
      instanceFieldArgs(fieldName = "orphan"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("No field of type")
    }
  }

  @Test
  fun reports_conflict_when_target_already_has_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // FieldHolder already declares 'existing'; ClashSource has a FieldHolder link plus its own
    // 'existing' field, so moving it onto FieldHolder is a name clash in the target.
    testMcpTool(
      MoveInstanceFieldToolset::move_instance_field.name,
      instanceFieldArgs(fieldName = "existing", pathOverride = clashSourceFile.path),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(result.textContent.text).contains("already exists")
    }
  }

  val clashSourceFileFixture = sourceRootFixture.virtualFileFixture(
    "ClashSource.java",
    """
    public class ClashSource {
      public FieldHolder holder = new FieldHolder();
      public int existing = 3;
    }
    """.trimIndent(),
  )
  val clashSourceFile by clashSourceFileFixture

  private fun instanceFieldArgs(
    fieldName: String = "value",
    targetClass: String = TARGET_CLASS,
    pathOverride: String? = null,
    receiverName: String? = null,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(pathOverride ?: sourceFile.path))
      put("fieldName", JsonPrimitive(fieldName))
      put("targetClass", JsonPrimitive(targetClass))
      if (receiverName != null) put("receiverName", JsonPrimitive(receiverName))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun parseResult(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.content.toInt()

  private fun JsonObject.bool(name: String) = this[name]!!.jsonPrimitive.content.toBooleanStrict()

  private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}
