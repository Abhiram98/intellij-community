// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mcpserver.toolsets.general

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.mcpserver.McpServerBundle
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.extractMethod.newImpl.ExtractException
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.ExtractedElements
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.util.DocumentUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

class ExtractMethodToolset : McpToolset {
  override fun displayName(): String = McpServerBundle.message("toolset.display.name.extractMethod")

  override fun displayDescription(toolName: String): String? = McpServerBundle.message("tool.description.$toolName")

  @McpTool
  @McpDescription("""
    Extracts the code fragment at the given line/column range of a Java file into a new method,
    replacing the selection with a call site (IntelliJ "Extract Method" refactoring).

    The range uses 1-based line and column coordinates, matching the numbered output of `read_file`
    and the coordinates accepted by `get_symbol_info`. `endColumn` is inclusive; when omitted,
    the selection extends to the end of `endLine`.

    Requires:
        - pathInProject: The relative path to the Java file from the project root
        - startLine: 1-based line number of the first selected line
        - endLine: 1-based line number of the last selected line

    Optional:
        - startColumn: 1-based column of the first selected character (default: 1)
        - endColumn: 1-based column of the last selected character, inclusive (default: end of line)
        - methodName: Name for the extracted method. If omitted, a safe name is suggested automatically.
        - targetClass: Fully-qualified name of the class the method should be placed in. Defaults to
          the innermost enclosing class of the selection.

    Returns a structured result with `success`, the extracted method's name, signature and code, and
    the list of changed files. When the fragment cannot be extracted (e.g. multiple output variables,
    broken control flow), nothing is changed and `success` is `false` with the reason in `message`.
  """)
  suspend fun extract_method(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("1-based line number of the first selected line")
    startLine: Int,
    @McpDescription("1-based column number of the first selected character (default: 1)")
    startColumn: Int = 1,
    @McpDescription("1-based line number of the last selected line")
    endLine: Int,
    @McpDescription("1-based column number of the last selected character, inclusive (default: end of line)")
    endColumn: Int? = null,
    @McpDescription("Name for the extracted method; a safe name is suggested automatically if omitted")
    methodName: String? = null,
    @McpDescription("Fully-qualified name of the class to place the method in; defaults to the innermost enclosing class")
    targetClass: String? = null,
  ): ExtractMethodResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.extracting.method", pathInProject, startLine, endLine))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(pathInProject)
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
                      ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
                      ?: mcpFail("File not found: $pathInProject")

    val document = readAction {
      FileDocumentManager.getInstance().getDocument(virtualFile)
    } ?: mcpFail("Cannot read file: $pathInProject")

    val psiFile = readAction {
      PsiDocumentManager.getInstance(project).getPsiFile(document)
    } ?: mcpFail("Couldn't get PSI file for: $pathInProject")

    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java code can be extracted, but '$pathInProject' is not a Java file")
    }

    val range = readAction { buildSelectionRange(document, startLine, startColumn, endLine, endColumn) }

    val elements = readAction { ExtractSelector().suggestElementsToExtract(psiFile, range) }
    if (elements.isEmpty()) {
      return ExtractMethodResult(
        success = false,
        message = "Nothing to extract in $pathInProject at lines $startLine-$endLine: " +
                  "the selection must cover a set of statements, a code block, or an expression.",
      )
    }

    val options = try {
      readAction { ExtractMethodPipeline.findAllOptionsToExtract(elements) }
    }
    catch (e: ExtractException) {
      return ExtractMethodResult(
        success = false,
        message = "Cannot extract the selection in $pathInProject: ${e.message}",
      )
    }
    if (options.isEmpty()) {
      return ExtractMethodResult(
        success = false,
        message = "Cannot extract the selection in $pathInProject: no suitable target class found.",
      )
    }

    val selected = selectExtractOption(options, targetClass)
    if (selected == null) {
      val candidates = options.joinToString { (it.targetClass.qualifiedName ?: it.targetClass.name) ?: "<anonymous>" }
      return ExtractMethodResult(
        success = false,
        message = "Target class '$targetClass' cannot host this extraction. Candidate classes: $candidates.",
      )
    }

    val resolvedName = readAction {
      if (methodName != null) {
        if (!PsiNameHelper.getInstance(project).isIdentifier(methodName)) {
          mcpFail("Invalid method name: '$methodName' is not a valid Java identifier")
        }
        methodName
      }
      else {
        MethodExtractor.suggestSafeMethodNames(selected).firstOrNull() ?: "extracted"
      }
    }
    val finalOptions = selected.copy(methodName = resolvedName)

    val extracted: ExtractedElements = try {
      withContext(Dispatchers.EDT) {
        WriteCommandAction.runWriteCommandAction(
          project,
          Computable {
            MethodExtractor().extractMethod(finalOptions)
          },
        )
      }
    }
    catch (e: ExtractException) {
      return ExtractMethodResult(
        success = false,
        message = "Cannot extract the selection in $pathInProject: ${e.message}",
      )
    }

    // The headless pipeline skips the inplace editor re-indentation the interactive flow performs, so
    // normalize the new call sites and the extracted method with the platform's reformat processor - the
    // same headless path FormattingToolset uses. Running it in its own command, after the extraction
    // command, applies the reformatting synchronously instead of leaving it in the postponed queue.
    withContext(Dispatchers.EDT) {
      ReformatCodeProcessor(project, arrayOf(psiFile), "Extract Method", null, false).run()
    }

    val method = readAction {
      // ReformatCodeProcessor may replace PSI, so re-locate the method from the formatted file if needed.
      extracted.method.takeIf { it.isValid }
        ?: PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java).firstOrNull { it.name == resolvedName }
    } ?: mcpFail("Could not locate the extracted method '$resolvedName' in $pathInProject after extraction")

    return readAction {
      val targetClassName = method.containingClass?.qualifiedName ?: method.containingClass?.name
      ExtractMethodResult(
        success = true,
        message = "Extracted method '${method.name}' into ${targetClassName ?: method.containingClass?.name ?: "the file"} from $pathInProject.",
        methodName = method.name,
        targetClass = targetClassName,
        isStatic = method.hasModifierProperty(PsiModifier.STATIC),
        returnType = method.returnType?.presentableText,
        parameters = method.parameterList.parameters.map { parameter -> "${parameter.type.presentableText} ${parameter.name}" },
        extractedCode = method.text,
        changedFiles = snapshotChangedFiles(project, psiFile, method),
      )
    }
  }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtractMethodResult(
  val success: Boolean,
  val message: String,
  val methodName: String? = null,
  val targetClass: String? = null,
  @EncodeDefault val isStatic: Boolean = false,
  val returnType: String? = null,
  val parameters: List<String> = emptyList(),
  val extractedCode: String? = null,
  val changedFiles: List<String> = emptyList(),
)

/**
 * Converts a 1-based, inclusive line/column selection into an exclusive [TextRange].
 *
 * `endColumn` counts the last selected character (inclusive), so the exclusive end offset is
 * `lineStart + endColumn`. When `endColumn` is omitted the selection runs to the end of `endLine`
 * (the trailing line separator is not selected).
 */
private fun buildSelectionRange(
  document: Document,
  startLine: Int,
  startColumn: Int,
  endLine: Int,
  endColumn: Int?,
): TextRange {
  val startLineIndex = startLine - 1
  val endLineIndex = endLine - 1
  if (!DocumentUtil.isValidLine(startLineIndex, document)) {
    mcpFail("startLine is out of bounds (file has ${document.lineCount} lines)")
  }
  if (!DocumentUtil.isValidLine(endLineIndex, document)) {
    mcpFail("endLine is out of bounds (file has ${document.lineCount} lines)")
  }
  if (endLineIndex < startLineIndex) {
    mcpFail("endLine ($endLine) must be >= startLine ($startLine)")
  }

  val startLineStart = document.getLineStartOffset(startLineIndex)
  if (startColumn < 1 || startColumn - 1 > document.getLineEndOffset(startLineIndex) - startLineStart) {
    mcpFail("startColumn is out of bounds for line $startLine")
  }
  val startOffset = startLineStart + startColumn - 1

  val endLineStart = document.getLineStartOffset(endLineIndex)
  val endLineEnd = document.getLineEndOffset(endLineIndex)
  val endOffset = if (endColumn == null) {
    endLineEnd
  }
  else {
    if (endColumn < 1 || endColumn - 1 > endLineEnd - endLineStart) {
      mcpFail("endColumn is out of bounds for line $endLine")
    }
    endLineStart + endColumn
  }

  if (endOffset <= startOffset) {
    mcpFail("Empty selection: the end of the range must come after its start")
  }
  return TextRange(startOffset, endOffset)
}

/**
 * Selects the destination class. Without an explicit [targetClass] the innermost enclosing class is
 * used (anonymous classes are skipped unless they are the only option), matching the production default.
 */
private fun selectExtractOption(options: List<ExtractOptions>, targetClass: String?): ExtractOptions? {
  if (targetClass == null) {
    return options.firstOrNull { it.targetClass !is PsiAnonymousClass } ?: options.first()
  }
  return options.firstOrNull { it.targetClass.qualifiedName == targetClass }
}

private fun snapshotChangedFiles(project: Project, sourceFile: PsiJavaFile, method: PsiMethod): List<String> {
  val files = listOfNotNull(
    PsiUtilCore.getVirtualFile(sourceFile),
    PsiUtilCore.getVirtualFile(method),
  ).distinct()
  return files.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}
