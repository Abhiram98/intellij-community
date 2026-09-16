// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.GeneralMcpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.MoveMethodToolset
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

private const val TARGET_CLASS = "MoveTarget"

private val SOURCE_CONTENT: String = """
  public class MoveSource {
    private final MoveTarget target = new MoveTarget();

    public static String staticMoveMe() {
      return "static";
    }

    public String instanceMoveMe() {
      return target.greet();
    }

    public String caller() {
      return staticMoveMe() + instanceMoveMe();
    }
  }

  class ClashingSource {
    public static String greet() {
      return "static";
    }
  }
  """.trimIndent()

private val TARGET_CONTENT: String = """
  public class MoveTarget {
    public String greet() {
      return "hi";
    }
  }
  """.trimIndent()

class MoveMethodToolsetTest : GeneralMcpToolsetTestBase() {
  val moveSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("MoveSource.java", SOURCE_CONTENT)
  val moveTargetJavaFileFixture = sourceRootFixture.virtualFileFixture("MoveTarget.java", TARGET_CONTENT)
  val moveSourceJavaFile by moveSourceJavaFileFixture
  val moveTargetJavaFile by moveTargetJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // The tool performs index-backed lookups (findClass, findSuperMethods, findUsages) and throws
    // IndexNotReadyException if the project is still in dumb mode. Fixtures realized during a test
    // (writing MoveSource.java/MoveTarget.java) schedule indexing that must be flushed before the call.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun moves_static_method_between_classes() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveMethodToolset::move_method.name,
      moveMethodArgs(methodName = "staticMoveMe"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.bool("isStatic")).isTrue()
      assertThat(json.int("usagesUpdated")).isGreaterThanOrEqualTo(1)
      assertThat(result.textContent.text).contains("\"src/MoveSource.java\"")
      assertThat(result.textContent.text).contains("\"src/MoveTarget.java\"")
    }

    // the method is gone from its old location
    testMcpTool(
      MoveMethodToolset::move_method.name,
      moveMethodArgs(methodName = "staticMoveMe"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Couldn't find method 'staticMoveMe'")
    }
  }

  @Test
  fun moves_instance_method_onto_target_typed_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveMethodToolset::move_method.name,
      moveMethodArgs(methodName = "instanceMoveMe"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.bool("isStatic")).isFalse()
      assertThat(json.string("targetClass")).isEqualTo(TARGET_CLASS)
      assertThat(json.int("usagesUpdated")).isGreaterThanOrEqualTo(1)
    }

    testMcpTool(
      MoveMethodToolset::move_method.name,
      moveMethodArgs(methodName = "instanceMoveMe"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Couldn't find method 'instanceMoveMe'")
    }
  }

  @Test
  fun reports_conflicts_when_target_class_already_has_the_method() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      MoveMethodToolset::move_method.name,
      moveMethodArgs(methodName = "greet"),
    ) { result ->
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
      MoveMethodToolset::move_method.name,
      moveMethodArgs(targetClass = "pkg.Nonexistent"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("Target class 'pkg.Nonexistent' not found")
    }
  }

  private fun moveMethodArgs(
    methodName: String = "staticMoveMe",
    targetClass: String = TARGET_CLASS,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(moveSourceJavaFile.path))
      put("methodName", JsonPrimitive(methodName))
      put("targetClass", JsonPrimitive(targetClass))
    }
    waitForSmartModeAfterFixtureRealization()
    return args
  }

  private fun waitForSmartModeAfterFixtureRealization() {
    // Realizing moveSourceJavaFile (above) writes MoveSource.java/MoveTarget.java into the indexable source
    // root. The tool's index-backed queries (findClass, findSuperMethods, findUsages) throw
    // IndexNotReadyException while those edits are still being indexed, so flush dumb mode before the call.
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