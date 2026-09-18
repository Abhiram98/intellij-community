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

private val SAFE_DELETE_SOURCE_CONTENT: String = """
  public class SafeDeleteSource {
    public void unused() {
    }

    public static void used() {
    }

    public void caller() {
      used();
    }
  }
  """.trimIndent()

private val SAFE_DELETE_LOCAL_CONTENT: String = """
  public class SafeDeleteLocalSource {
    public void m() {
      int unusedLocal = 1;
    }
  }
  """.trimIndent()

class SafeDeleteToolsetTest : GeneralMcpToolsetTestBase() {
  val safeDeleteSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("SafeDeleteSource.java", SAFE_DELETE_SOURCE_CONTENT)
  val safeDeleteSourceJavaFile by safeDeleteSourceJavaFileFixture

  val safeDeleteLocalJavaFileFixture = sourceRootFixture.virtualFileFixture("SafeDeleteLocalSource.java", SAFE_DELETE_LOCAL_CONTENT)
  val safeDeleteLocalJavaFile by safeDeleteLocalJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // Usage discovery (ReferencesSearch, OverridingMethodsSearch, findSuperMethods) is index-backed.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun deletes_an_unused_method() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::safe_delete.name,
      safeDeleteArgs(symbolName = "unused"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("symbolKind")).isEqualTo("method")
      assertThat(readFileTextInMemory()).doesNotContain("unused")
    }
  }

  @Test
  fun deletes_an_unused_local_variable() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::safe_delete.name,
      safeDeleteArgs(symbolName = "unusedLocal", kind = "local", file = safeDeleteLocalJavaFile),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("symbolKind")).isEqualTo("local")
      assertThat(readFileTextInMemory(safeDeleteLocalJavaFile)).doesNotContain("unusedLocal")
    }
  }

  @Test
  fun aborts_when_a_usage_lives_in_code_that_is_not_deleted() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // `used` has a caller that is not deleted with it, so Safe Delete would normally ask; abort.
    testMcpTool(
      RefactoringToolset::safe_delete.name,
      safeDeleteArgs(symbolName = "used"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("aborted")
      // nothing was changed
      assertThat(readFileTextInMemory()).contains("used()")
    }
  }

  private fun safeDeleteArgs(
    symbolName: String,
    kind: String? = null,
    file: com.intellij.openapi.vfs.VirtualFile = safeDeleteSourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("symbolName", JsonPrimitive(symbolName))
      if (kind != null) put("kind", JsonPrimitive(kind))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile = safeDeleteSourceJavaFile): String {
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