// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("TestFunctionName")

package com.intellij.mcpserver.toolsets

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.GeneralMcpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.ExtractMethodToolset
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

private val EXTRACT_SOURCE_CONTENT: String = """
  public class ExtractSource {
    public int compute(int base) {
      int doubled = base * 2;
      int incremented = doubled + 1;
      return incremented;
    }
  }
  """.trimIndent()

private val MULTI_OUTPUT_SOURCE_CONTENT: String = """
  public class MultiOutputSource {
    public void run() {
      int a = 1;
      int b = 2;
      System.out.println(a + b);
    }
  }
  """.trimIndent()

class ExtractMethodToolsetTest : GeneralMcpToolsetTestBase() {
  val extractSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("ExtractSource.java", EXTRACT_SOURCE_CONTENT)
  val extractSourceJavaFile by extractSourceJavaFileFixture

  val multiOutputJavaFileFixture = sourceRootFixture.virtualFileFixture("MultiOutputSource.java", MULTI_OUTPUT_SOURCE_CONTENT)
  val multiOutputJavaFile by multiOutputJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // The extraction analyzes the fragment against the enclosing classes and suggests conflict-free method
    // names, which are index-backed queries that throw IndexNotReadyException while the project is in dumb
    // mode. Fixtures realized during a test schedule indexing that must be flushed before the call.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun extracts_statements_into_a_new_method() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(
        startLine = 3,
        endLine = 4,
        methodName = "computeValue",
      ),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("methodName")).isEqualTo("computeValue")
      assertThat(json.bool("isStatic")).isFalse()
      assertThat(json.string("returnType")).isEqualTo("int")
      assertThat(json.string("extractedCode")).contains("private int computeValue(int base)")

      val fileText = readFileTextInMemory()
      // the selected statements are replaced by a call site (the fragment's output variable
      // `incremented` is assigned from the call), and the extracted method is placed after the
      // containing method
      assertThat(fileText).contains("int incremented = computeValue(base);")
      assertThat(fileText).contains("private int computeValue(int base)")

      assertThat(result.textContent.text).contains("\"src/ExtractSource.java\"")
    }
  }

  @Test
  fun suggests_a_method_name_when_omitted() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(startLine = 3, endLine = 4),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      val methodName = json.string("methodName")
      assertThat(methodName).isNotBlank()
      assertThat(readFileTextInMemory()).contains(methodName)
    }
  }

  @Test
  fun extracts_an_expression_selected_by_columns() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // Line 4 is `      int incremented = doubled + 1;`: `doubled + 1` spans columns 23..33.
    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(
        startLine = 4,
        startColumn = 23,
        endLine = 4,
        endColumn = 33,
        methodName = "computeValue",
      ),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("methodName")).isEqualTo("computeValue")
      assertThat(readFileTextInMemory()).contains("computeValue(doubled)")
    }
  }

  @Test
  fun reports_failure_for_multiple_output_variables() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // `int a = 1; int b = 2;` both live on after the fragment (line 5 reads both), so the fragment has
    // two output variables and cannot be extracted as a method returning a single value.
    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(
        startLine = 3,
        endLine = 4,
        methodName = "extractBoth",
        file = multiOutputJavaFile,
      ),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("Cannot extract")
    }

    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(
        startLine = 3,
        endLine = 4,
        methodName = "extractBoth",
        file = multiOutputJavaFile,
      ),
    ) { result ->
      // nothing was changed, the fragment is still intact
      val fileText = readFileTextInMemory(file = multiOutputJavaFile)
      // nothing was changed, the fragment is still intact
      assertThat(fileText).contains("int a = 1;")
      assertThat(fileText).contains("int b = 2;")
    }
  }

  @Test
  fun rejects_an_invalid_method_name() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(startLine = 3, endLine = 4, methodName = "not valid"),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("not a valid Java identifier")
    }
  }

  @Test
  fun rejects_an_out_of_bounds_range() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      ExtractMethodToolset::extract_method.name,
      extractMethodArgs(startLine = 999, endLine = 1000),
    ) { result ->
      assertThat(result.isError).isTrue()
      assertThat(result.textContent.text).contains("startLine is out of bounds")
    }
  }

  private fun extractMethodArgs(
    startLine: Int,
    endLine: Int,
    startColumn: Int? = null,
    endColumn: Int? = null,
    methodName: String? = null,
    file: com.intellij.openapi.vfs.VirtualFile = extractSourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("startLine", JsonPrimitive(startLine))
      put("endLine", JsonPrimitive(endLine))
      if (startColumn != null) put("startColumn", JsonPrimitive(startColumn))
      if (endColumn != null) put("endColumn", JsonPrimitive(endColumn))
      if (methodName != null) put("methodName", JsonPrimitive(methodName))
    }
    waitForSmartModeAfterFixtureRealization()
    return args
  }

  private fun waitForSmartModeAfterFixtureRealization() {
    DumbService.getInstance(project).waitForSmartMode()
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile = extractSourceJavaFile): String {
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