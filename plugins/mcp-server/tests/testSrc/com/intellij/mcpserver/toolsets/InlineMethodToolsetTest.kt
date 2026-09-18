// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.GeneralMcpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.RefactoringToolset
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.junit5.fixture.virtualFileFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

private val INLINE_SOURCE_CONTENT: String = """
  public class InlineSource {
    public int twice(int n) {
      return n * 2;
    }

    public void caller() {
      System.out.println(twice(21));
    }

    public int unused() {
      return 7;
    }
  }
  """.trimIndent()

private val RECURSIVE_SOURCE_CONTENT: String = """
  public class RecursiveSource {
    public int fact(int n) {
      return n <= 1 ? 1 : n * fact(n - 1);
    }
  }
  """.trimIndent()

class InlineMethodToolsetTest : GeneralMcpToolsetTestBase() {
  val inlineSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("InlineSource.java", INLINE_SOURCE_CONTENT)
  val inlineSourceJavaFile by inlineSourceJavaFileFixture

  val recursiveSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("RecursiveSource.java", RECURSIVE_SOURCE_CONTENT)
  val recursiveSourceJavaFile by recursiveSourceJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // The call-site discovery and the processor's internal findUsages are index-backed searches.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun inlines_all_usages_and_deletes_the_method() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::inline_method.name,
      inlineMethodArgs(methodName = "twice"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.bool("methodDeleted")).isTrue()
      assertThat(json.int("usagesInlined")).isGreaterThanOrEqualTo(1)

      val fileText = readFileTextInMemory()
      assertThat(fileText).doesNotContain("twice")
      assertThat(fileText).contains("System.out.println(21 * 2);")
    }
  }

  @Test
  fun aborts_for_recursive_methods() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::inline_method.name,
      inlineMethodArgs(methodName = "fact", file = recursiveSourceJavaFile),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("recursive")
    }
  }

  @Test
  fun inline_this_only_requires_a_call_site() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::inline_method.name,
      inlineMethodArgs(methodName = "unused", inlineThisOnly = true),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("no call sites")
    }
  }

  private fun inlineMethodArgs(
    methodName: String,
    inlineThisOnly: Boolean = false,
    file: com.intellij.openapi.vfs.VirtualFile = inlineSourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("methodName", JsonPrimitive(methodName))
      if (inlineThisOnly) put("inlineThisOnly", JsonPrimitive(inlineThisOnly))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile = inlineSourceJavaFile): String {
    return runBlocking {
      withContext(Dispatchers.EDT) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        PsiDocumentManager.getInstance(project).getPsiFile(document)!!.text
      }
    }
  }

  private fun parseResult(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.content.toInt()

  private fun JsonObject.bool(name: String) = this[name]!!.jsonPrimitive.content.toBooleanStrict()

  private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}