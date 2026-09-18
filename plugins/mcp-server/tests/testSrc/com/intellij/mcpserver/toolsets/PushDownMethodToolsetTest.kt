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

private val PUSH_DOWN_BASE_CONTENT: String = """
  public class PushDownBase {
    public String greet() {
      return "hello";
    }

    public static String stat() {
      return "s";
    }
  }
  """.trimIndent()

private val PUSH_DOWN_SUB_CONTENT: String = """
  public class PushDownSub extends PushDownBase {
  }
  """.trimIndent()

private val FINAL_BASE_CONTENT: String = """
  public final class FinalBase {
    public String greet() {
      return "hi";
    }
  }
  """.trimIndent()

private val FINAL_SUB_CONTENT: String = """
  public class FinalSub extends FinalBase {
  }
  """.trimIndent()

class PushDownMethodToolsetTest : GeneralMcpToolsetTestBase() {
  val pushDownBaseJavaFileFixture = sourceRootFixture.virtualFileFixture("PushDownBase.java", PUSH_DOWN_BASE_CONTENT)
  val pushDownBaseJavaFile by pushDownBaseJavaFileFixture

  val pushDownSubJavaFileFixture = sourceRootFixture.virtualFileFixture("PushDownSub.java", PUSH_DOWN_SUB_CONTENT)
  val pushDownSubJavaFile by pushDownSubJavaFileFixture

  val finalBaseJavaFileFixture = sourceRootFixture.virtualFileFixture("FinalBase.java", FINAL_BASE_CONTENT)
  val finalBaseJavaFile by finalBaseJavaFileFixture

  val finalSubJavaFileFixture = sourceRootFixture.virtualFileFixture("FinalSub.java", FINAL_SUB_CONTENT)
  val finalSubJavaFile by finalSubJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // findClass / ClassInheritorsSearch / ReferencesSearch are all index-backed.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun pushes_a_method_down_into_all_direct_subclasses_and_keeps_an_abstract_super() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::push_down_method.name,
      pushDownMethodArgs(methodName = "greet"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.bool("abstractedInSuper")).isTrue()
      assertThat(json.string("toClasses")).contains("PushDownSub")

      // the subclass receives a real implementation, the superclass keeps an abstract declaration
      assertThat(readFileTextInMemory(pushDownSubJavaFile)).contains("return \"hello\";")
      assertThat(readFileTextInMemory(pushDownBaseJavaFile)).contains("public abstract String greet();")
    }
  }

  @Test
  fun refuses_to_push_a_static_method_to_all_subclasses() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::push_down_method.name,
      pushDownMethodArgs(methodName = "stat"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("static members cannot be kept abstract")
    }
  }

  @Test
  fun aborts_for_a_final_superclass() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::push_down_method.name,
      pushDownMethodArgs(methodName = "greet", file = finalBaseJavaFile),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("final")
    }
  }

  private fun pushDownMethodArgs(
    methodName: String,
    file: com.intellij.openapi.vfs.VirtualFile = pushDownBaseJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("methodName", JsonPrimitive(methodName))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile): String {
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