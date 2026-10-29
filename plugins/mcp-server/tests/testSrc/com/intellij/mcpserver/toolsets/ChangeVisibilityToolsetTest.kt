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

// The member holder. `widenMe` is private, `narrowMe` is public and used cross-class below.
private val VISIBILITY_SOURCE_CONTENT: String = """
  public class VisibilitySource {
    private void widenMe() {
    }

    public int narrowMe = 1;

    public void internalCaller() {
      widenMe();
    }
  }
  """.trimIndent()

// A separate class, same package, that reads the public field cross-class. Narrowing the field to
// private would make this reference inaccessible, so the tool must report a conflict.
private val VISIBILITY_CONSUMER_CONTENT: String = """
  public class VisibilityConsumer {
    public int read(VisibilitySource source) {
      return source.narrowMe;
    }
  }
  """.trimIndent()

class ChangeVisibilityToolsetTest : GeneralMcpToolsetTestBase() {
  val visibilitySourceJavaFileFixture = sourceRootFixture.virtualFileFixture("VisibilitySource.java", VISIBILITY_SOURCE_CONTENT)
  val visibilitySourceJavaFile by visibilitySourceJavaFileFixture

  val visibilityConsumerJavaFileFixture = sourceRootFixture.virtualFileFixture("VisibilityConsumer.java", VISIBILITY_CONSUMER_CONTENT)
  val visibilityConsumerJavaFile by visibilityConsumerJavaFileFixture

  @BeforeEach
  fun waitForProjectIndexes() {
    // Force both fixtures to materialise on disk BEFORE flushing the index queue, so the cross-class
    // reference from VisibilityConsumer is indexed and found by ReferencesSearch in the tool.
    visibilitySourceJavaFile
    visibilityConsumerJavaFile
    // Reference search and the hierarchy analysis are index-backed.
    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun widens_a_private_method_to_public() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::change_visibility.name,
      visibilityArgs(symbolName = "widenMe", visibility = "public"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("symbolKind")).isEqualTo("method")
      assertThat(json.string("oldVisibility")).isEqualTo("private")
      assertThat(json.string("newVisibility")).isEqualTo("public")
      assertThat(readFileTextInMemory()).contains("public void widenMe")
    }
  }

  @Test
  fun reports_a_conflict_when_narrowing_a_cross_class_field() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    // Reference the consumer so the fixture materialises it before the search runs.
    visibilityConsumerJavaFile

    testMcpTool(
      RefactoringToolset::change_visibility.name,
      visibilityArgs(symbolName = "narrowMe", visibility = "private", kind = "field"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isFalse()
      assertThat(json.string("message")).contains("would break the code")
      // The cross-class reference was actually analysed.
      assertThat(json.int("referencesAnalysed")).isGreaterThanOrEqualTo(1)
      // Nothing changed: the field is still public.
      assertThat(readFileTextInMemory()).contains("public int narrowMe")
    }
  }

  @Test
  fun reports_no_change_when_visibility_is_already_the_requested_one() = runBlocking(Dispatchers.Default) {
    assumeTrue(isJavaPluginInstalled(), "Java plugin is required for this test")

    testMcpTool(
      RefactoringToolset::change_visibility.name,
      visibilityArgs(symbolName = "narrowMe", visibility = "public", kind = "field"),
    ) { result ->
      assertThat(result.isError).isFalse()
      val json = parseResult(result.textContent.text)
      assertThat(json.bool("success")).isTrue()
      assertThat(json.string("message")).contains("already has")
    }
  }

  private fun visibilityArgs(
    symbolName: String,
    visibility: String,
    kind: String? = null,
    file: com.intellij.openapi.vfs.VirtualFile = visibilitySourceJavaFile,
  ): JsonObject {
    val args = buildJsonObject {
      put("pathInProject", JsonPrimitive(file.path))
      put("symbolName", JsonPrimitive(symbolName))
      put("visibility", JsonPrimitive(visibility))
      if (kind != null) put("kind", JsonPrimitive(kind))
    }
    DumbService.getInstance(project).waitForSmartMode()
    return args
  }

  private fun readFileTextInMemory(file: com.intellij.openapi.vfs.VirtualFile = visibilitySourceJavaFile): String {
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

  private fun JsonObject.int(name: String) = this[name]!!.jsonPrimitive.content.toInt()

  private fun isJavaPluginInstalled(): Boolean {
    return PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.java"))
  }
}
