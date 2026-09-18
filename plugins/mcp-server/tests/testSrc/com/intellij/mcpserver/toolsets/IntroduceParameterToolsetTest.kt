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

private val INTRODUCE_SOURCE_CONTENT: String = """
  public class IntroduceParameterSource {
    public int compute(int base) {
      int doubled = base * 2;
      return doubled;
    }

    public int caller(int base) {
      return compute(base);
    }
  }
  """.trimIndent()

private val PARAMETER_SOURCE_CONTENT: String = """
  public class ParameterSource {
    public int compute(int doubled) {
      return doubled;
    }
  }
  """.trimIndent()

class IntroduceParameterToolsetTest : GeneralMcpToolsetTestBase() {
  val introduceSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("IntroduceParameterSource.java", INTRODUCE_SOURCE_CONTENT)
  val introduceSourceJavaFile by introduceSourceJavaFileFixture

  val parameterSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("ParameterSource.java", PARAMETER_SOURCE_CONTENT)
  val parameterSourceJavaFile by parameterSourceJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // The tool's super-method analysis, occurrence search and the processor's internal findUsages are
    // index-backed and throw IndexNotReadyException while the project is in dumb mode.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun introduces_a_local_variable_as_a_parameter() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::introduce_parameter.name,
      introduceParameterArgs(variableName = "doubled"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("parameterName")).isEqualTo("doubled")
      assertThat(json.string("parameterType")).isEqualTo("int")
      assertThat(json.string("methodName")).isEqualTo("compute")
      assertThat(json.string("methodSignature")).contains("compute(int base, int doubled)")

      // the local declaration is removed from the method and the call site passes the initializer
      val fileText = readFileTextInMemory()
      assertThat(fileText).doesNotContain("int doubled = base * 2;")
      assertThat(fileText).contains("return compute(base, base * 2);")
    }
  }

  @Test
  fun reports_a_parameter_as_not_introducible() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::introduce_parameter.name,
      introduceParameterArgs(variableName = "doubled", file = parameterSourceJavaFile),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("is a method parameter")
    }
  }

  @Test
  fun errors_for_an_unknown_variable() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::introduce_parameter.name,
      introduceParameterArgs(variableName = "nope"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("Couldn't find a local variable named 'nope'")
    }
  }

  private fun introduceParameterArgs(
    variableName: String,
    file: com.intellij.openapi.vfs.VirtualFile = introduceSourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("variableName", JsonPrimitive(variableName))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile = introduceSourceJavaFile): String {
    return runBlocking {
      withContext(Dispatchers.EDT) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        PsiDocumentManager.getInstance(project).getPsiFile(document)!!.text
      }
    }
  }

  private fun parseResult(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun JsonObject.bool(name: String) = this[name]!!.jsonPrimitive.content.toBooleanStrict()

  private fun JsonObject.string(name: String) = this[name]!!.jsonPrimitive.content

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}