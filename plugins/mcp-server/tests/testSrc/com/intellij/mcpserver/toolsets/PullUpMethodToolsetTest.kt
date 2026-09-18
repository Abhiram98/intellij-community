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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private val PULL_UP_BASE_CONTENT: String = """
  public class PullUpBase {
  }
  """.trimIndent()

private val PULL_UP_SOURCE_CONTENT: String = """
  public class PullUpSource extends PullUpBase {
    public String greet() {
      return "hello";
    }

    public void caller() {
      System.out.println(greet());
    }
  }
  """.trimIndent()

// The conflict-abort fixture gets its OWN base class: PullUpConflictSource must not extend
// PullUpBase, otherwise making `greet` abstract there would (correctly) conflict because this
// second non-abstract inheritor does not implement it.
private val PULL_UP_CONFLICT_BASE_CONTENT: String = """
  public class PullUpConflictBase {
  }
  """.trimIndent()

private val PULL_UP_CONFLICT_SOURCE_CONTENT: String = """
  public class PullUpConflictSource extends PullUpConflictBase {
    private int factor = 2;

    public int compute() {
      return factor * 3;
    }
  }
  """.trimIndent()

class PullUpMethodToolsetTest : GeneralMcpToolsetTestBase() {
  val pullUpBaseJavaFileFixture = sourceRootFixture.virtualFileFixture("PullUpBase.java", PULL_UP_BASE_CONTENT)
  val pullUpBaseJavaFile by pullUpBaseJavaFileFixture

  val pullUpSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("PullUpSource.java", PULL_UP_SOURCE_CONTENT)
  val pullUpSourceJavaFile by pullUpSourceJavaFileFixture

  val pullUpConflictBaseJavaFileFixture = sourceRootFixture.virtualFileFixture("PullUpConflictBase.java", PULL_UP_CONFLICT_BASE_CONTENT)
  val pullUpConflictBaseJavaFile by pullUpConflictBaseJavaFileFixture

  val pullUpConflictSourceJavaFileFixture = sourceRootFixture.virtualFileFixture("PullUpConflictSource.java", PULL_UP_CONFLICT_SOURCE_CONTENT)
  val pullUpConflictSourceJavaFile by pullUpConflictSourceJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // findClass / findMethodsInFile / the conflict analysis and usage search are all index-backed.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun pulls_a_method_up_into_the_auto_detected_superclass() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::pull_up_method.name,
      pullUpMethodArgs(methodName = "greet"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("toClass")).isEqualTo("PullUpBase")
      assertThat(json.array("membersMoved")).contains("greet")

      // the member is moved, not copied: the base class now hosts the implementation
      assertThat(readFileTextInMemory(pullUpBaseJavaFile)).contains("public String greet()")
      assertThat(readFileTextInMemory(pullUpSourceJavaFile)).doesNotContain("return \"hello\";")
    }
  }

  @Test
  fun pulls_a_method_up_as_abstract_keeping_the_implementation() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::pull_up_method.name,
      pullUpMethodArgs(methodName = "greet", makeAbstract = true),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("toClass")).isEqualTo("PullUpBase")

      val baseText = readFileTextInMemory(pullUpBaseJavaFile)
      assertThat(baseText).contains("abstract")
      assertThat(baseText).contains("public abstract String greet();")
      // the concrete implementation stays in the subclass (now overriding the abstract one)
      assertThat(readFileTextInMemory(pullUpSourceJavaFile)).contains("return \"hello\";")
    }
  }

  @Test
  fun aborts_returning_conflicts_when_the_method_uses_class_members_not_pulled_up() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::pull_up_method.name,
      pullUpMethodArgs(methodName = "compute", file = pullUpConflictSourceJavaFile),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("conflict")
    }
  }

  private fun pullUpMethodArgs(
    methodName: String,
    makeAbstract: Boolean = false,
    file: com.intellij.openapi.vfs.VirtualFile = pullUpSourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("methodName", JsonPrimitive(methodName))
      if (makeAbstract) put("makeAbstract", JsonPrimitive(makeAbstract))
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

  private fun JsonObject.array(name: String): List<String> =
    this[name]!!.jsonArray.map { it.jsonPrimitive.content }

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}