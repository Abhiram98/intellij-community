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
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.extractMethod.newImpl.ExtractException
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.ExtractedElements
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodHandler
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.util.DocumentUtil
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

class RefactoringToolset : McpToolset {
  override fun displayName(): String = McpServerBundle.message("toolset.display.name.refactoring")

  override fun isExperimental(): Boolean = false

  override fun displayDescription(toolName: String): String? = McpServerBundle.message("tool.description.$toolName")

  @McpTool
  @McpDescription("""
        Renames a symbol (variable, function, class, etc.) in the specified file.
        Use this tool to perform rename refactoring operations.

        The `rename_refactoring` tool is a powerful, context-aware utility. Unlike a simple text search-and-replace,
        it understands the code's structure and will intelligently update ALL references to the specified symbol throughout the project,
        ensuring code integrity and preventing broken references. It is ALWAYS the preferred method for renaming programmatic symbols.

        Requires three parameters:
            - pathInProject: The relative path to the file from the project's root directory (e.g., `src/api/controllers/userController.js`)
            - symbolName: The exact, case-sensitive name of the existing symbol to be renamed (e.g., `getUserData`)
            - newName: The new, case-sensitive name for the symbol (e.g., `fetchUserData`).

        Returns a success message if the rename operation was successful.
        Returns an error message if the file or symbol cannot be found or the rename operation failed.
    """)
  suspend fun rename_refactoring(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Name of the symbol to rename")
    symbolName: String,
    @McpDescription("New name for the symbol")
    newName: String,
  ): String {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.renaming.symbol", symbolName, newName, pathInProject))
    val project = currentCoroutineContext().project
    val resolvedPath = project.resolveInProject(pathInProject)

    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
                      ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
                      ?: mcpFail("File not found: $pathInProject")

    val document = readAction {
      FileDocumentManager.getInstance().getDocument(virtualFile)
    } ?: mcpFail("Cannot read file: $pathInProject")

    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    val psiFile = readAction {
      psiDocumentManager.getPsiFile(document)
    } ?: mcpFail("couldn't get PSI file for: $pathInProject")

    val targetElement = readAction {
      findSymbolInFile(psiFile, symbolName)
    } ?: mcpFail("Couldn't find symbol '$symbolName' in file '$pathInProject'")

    if (targetElement !is PsiNamedElement) {
      mcpFail("Element is not renamable: $symbolName")
    }

    val renameProcessor = readAction { McpRenameProcessor(project, targetElement, newName, false, false) }

    val usages = readAction { renameProcessor.findUsages() }

    withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        renameProcessor.run()
      }
    }
    return "Successfully renamed '$symbolName' to '$newName' in $pathInProject with ${usages.size} usages."
  }

  @McpTool
  @McpDescription("""
    Moves a Java method from its containing class to a target class, updating all call sites to
    reference the method in its new location (IntelliJ "Move Method" / "Move Static Members" refactoring).

    The tool auto-dispatches based on the method's modifiers:
      - the method is `static`  -> moved as a static member of the target class;
      - the method is an instance method -> moved to the target class, taking the target-typed
        variable as its receiver (`this`). The variable is auto-detected (the first method parameter
        or field whose type resolves to the target class) or selected explicitly via `targetVariableName`.

    Requires the file path of the Java file declaring the method, the exact method name, and the
    fully-qualified name of the destination class. Provide `parameterTypes` (e.g. ["String", "int"])
    to disambiguate overloaded methods.

    Returns a structured result with `success`, the updated usages count and the list of changed files.
    When the refactoring detects conflicts (e.g. member name clash in the target class, inaccessible
    members), nothing is changed and `success` is `false` with the conflict descriptions in `conflicts`.
    """)
  suspend fun move_method(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the method to move")
    methodName: String,
    @McpDescription("Fully-qualified name of the destination class, e.g. \"org.acme.Bar\"")
    targetClass: String,
    @McpDescription("Visibility applied to the moved method: \"public\", \"protected\", \"private\" or \"package\" (package-private)")
    visibility: String = "public",
    @McpDescription("For instance methods: name of the parameter or field in the source class that receives the moved method. Defaults to the first matching variable of the target class type")
    targetVariableName: String? = null,
    @McpDescription("Optional parameter types to disambiguate overloaded methods, e.g. [\"String\", \"int\"]")
    parameterTypes: List<String>? = null,
  ): MoveMethodResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.moving.method", methodName, targetClass, pathInProject))
    val project = currentCoroutineContext().project

    val resolvedPath = project.resolveInProject(pathInProject)

    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
                      ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
                      ?: mcpFail("File not found: $pathInProject")

    val document = readAction {
      FileDocumentManager.getInstance().getDocument(virtualFile)
    } ?: mcpFail("Cannot read file: $pathInProject")

    val psiDocumentManager = PsiDocumentManager.getInstance(project)
    val psiFile = readAction {
      psiDocumentManager.getPsiFile(document)
    } ?: mcpFail("Couldn't get PSI file for: $pathInProject")

    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java methods can be moved, but '$pathInProject' is not a Java file")
    }

    val matchingMethods = readAction {
      findMethodsInFile(psiFile, methodName, parameterTypes)
    }
    if (matchingMethods.isEmpty()) {
      mcpFail("Couldn't find method '$methodName' in file '$pathInProject'")
    }
    if (matchingMethods.size > 1) {
      val signatures = matchingMethods.map { it.parameterList.parameters.map { p -> p.type.presentableText }.joinToString(", ", "(", ")") }
      mcpFail("Several methods named '$methodName' found in '$pathInProject': $signatures. Pass `parameterTypes` to disambiguate.")
    }
    val method = matchingMethods[0]
    val sourceClass = readAction { method.containingClass }
                      ?: mcpFail("Method '$methodName' is not declared inside a class")

    val targetPsiClass = readAction {
      JavaPsiFacade.getInstance(project).findClass(targetClass, GlobalSearchScope.projectScope(project))
    } ?: mcpFail("Target class '$targetClass' not found in project")

    if (sourceClass == targetPsiClass) {
      mcpFail("Method '$methodName' is already declared in the target class '$targetClass'")
    }

    val modifierVisibility = parseVisibility(visibility)
    val isStatic = readAction { method.hasModifierProperty(PsiModifier.STATIC) }
    val sourceClassName = readAction { sourceClass.qualifiedName ?: sourceClass.name }
                          ?: mcpFail("Cannot determine a name for the source class")

    val prepared = readAction {
      if (!isStatic && !method.findSuperMethods().isEmpty()) {
        mcpFail("Method '$methodName' cannot be moved: it is part of an inheritance hierarchy (overrides or is overridden)")
      }
      if (isStatic) {
        val moveProcessor = McpMoveMembersProcessor(project, McpMoveMembersOptions(method, targetClass, modifierVisibility))
        PreparedMove(moveProcessor, moveProcessor.findUsagesPublicly())
      }
      else {
        val variable = resolveTargetVariable(method, sourceClass, targetPsiClass, targetVariableName)
        val moveProcessor = McpMoveInstanceMethodProcessor(
          project,
          method = method,
          targetVariable = variable,
          newVisibility = modifierVisibility,
          parameterNames = MoveInstanceMethodHandler.suggestParameterNames(method, variable),
        )
        PreparedMove(moveProcessor, moveProcessor.findUsagesPublicly())
      }
    }

    val changedFiles = readAction {
      snapshotChangedFilesAfterMove(project, prepared.usages, sourceClass, targetPsiClass)
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          prepared.processor.run()
        }
      }
    }
    catch (e: MoveConflictsFoundException) {
      return MoveMethodResult(
        success = false,
        message = "Move of '$methodName' to '$targetClass' aborted: ${e.conflicts.size} conflict(s) found",
        methodName = methodName,
        sourceClass = sourceClassName,
        targetClass = targetClass,
        isStatic = isStatic,
        conflicts = e.conflicts,
      )
    }

    return MoveMethodResult(
      success = true,
      message = "Moved '$methodName' from $sourceClassName to $targetClass; ${prepared.usages.size} usage(s) updated.",
      methodName = methodName,
      sourceClass = sourceClassName,
      targetClass = targetClass,
      isStatic = isStatic,
      usagesUpdated = prepared.usages.size,
      changedFiles = changedFiles,
    )
  }

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
        changedFiles = snapshotChangedFilesAfterExtract(project, psiFile, method),
      )
    }
  }

}

@Serializable
data class MoveMethodResult(
  val success: Boolean,
  val message: String,
  val methodName: String,
  val sourceClass: String,
  val targetClass: String,
  val isStatic: Boolean,
  val usagesUpdated: Int = 0,
  val changedFiles: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
)

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
 * https://youtrack.jetbrains.com/issue/IJPL-196163
 */
private class McpRenameProcessor(
  project: Project,
  val element: PsiElement,
  val newName: String,
  isSearchInComments: Boolean,
  isSearchTextOccurrences: Boolean,
) : RenameProcessor(project,
                    element,
                    newName,
                    isSearchInComments,
                    isSearchTextOccurrences) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean {
    return false
  }

  override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer?) = false

  override fun preprocessUsages(refUsages: Ref<Array<UsageInfo?>?>): Boolean {
    val usagesIn: Array<UsageInfo?> = refUsages.get() ?: return false
    val conflicts = MultiMap<PsiElement?, String?>()

    RenameUtil.addConflictDescriptions(usagesIn, conflicts)
    RenamePsiElementProcessor.forElement(element).findExistingNameConflicts(
      element, newName, conflicts, myAllRenames
    )
    if (!conflicts.isEmpty) {
      throw ConflictsFoundException() // todo: specify the conflicts
    }
    return true
  }
}

private class ConflictsFoundException() : Exception() {
  override val message: String = "Conflicts were found during renaming"
}

private fun findSymbolInFile(psiFile: PsiFile, symbolName: String): PsiElement? {
  val visitor = object : PsiRecursiveElementVisitor() {
    var foundElement: PsiElement? = null

    override fun visitElement(element: PsiElement) {
      if (foundElement != null) return

      if (element is PsiNamedElement) {
        if (element.name == symbolName) {
          foundElement = element
          return
        }
      }
      super.visitElement(element)
    }
  }

  psiFile.accept(visitor)
  return visitor.foundElement
}

/**
 * Static-members move ({@link MoveMembersProcessor}) with all UI hooks disabled. Conflicts abort the
 * refactoring with [MoveConflictsFoundException] instead of showing the conflicts dialog.
 */
private class McpMoveMembersProcessor(
  project: Project,
  options: MoveMembersOptions,
) : MoveMembersProcessor(project, null, options, /*openInEditor=*/false) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw MoveConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

/**
 * Instance-method move ({@link MoveInstanceMethodProcessor}) with all UI hooks disabled. Conflicts abort
 * the refactoring with [MoveConflictsFoundException] instead of showing the conflicts dialog.
 */
private class McpMoveInstanceMethodProcessor(
  project: Project,
  method: PsiMethod,
  targetVariable: PsiVariable,
  newVisibility: String?,
  parameterNames: MutableMap<PsiClass, String>,
) : MoveInstanceMethodProcessor(project, method, targetVariable, newVisibility, parameterNames) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw MoveConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

private class McpMoveMembersOptions(
  private val method: PsiMethod,
  private val targetClassName: String,
  private val visibility: String?,
) : MoveMembersOptions {
  override fun getSelectedMembers(): Array<PsiMember> = arrayOf<PsiMember>(method)

  override fun getTargetClassName(): String = targetClassName

  override fun getMemberVisibility(): String? = visibility

  override fun makeEnumConstant(): Boolean = false
}

private class MoveConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Move method refactoring found conflicts: ${conflicts.joinToString()}"
}

private data class PreparedMove(
  val processor: BaseRefactoringProcessor,
  val usages: Array<out UsageInfo>,
)

private fun parseVisibility(visibility: String): String? = when (visibility) {
  "public" -> PsiModifier.PUBLIC
  "protected" -> PsiModifier.PROTECTED
  "private" -> PsiModifier.PRIVATE
  "package" -> null
  else -> mcpFail("Invalid visibility: '$visibility'. Expected one of: public, protected, private, package")
}

private fun findMethodsInFile(psiFile: PsiJavaFile, methodName: String, parameterTypes: List<String>?): List<PsiMethod> {
  val methods = mutableListOf<PsiMethod>()
  val visitor = object : PsiRecursiveElementVisitor() {
    override fun visitElement(element: PsiElement) {
      if (element is PsiMethod) {
        val candidate = element
        if (candidate.name == methodName && !candidate.isConstructor
            && (parameterTypes == null || matchesParameterTypes(candidate, parameterTypes))) {
          methods.add(candidate)
        }
      }
      super.visitElement(element)
    }
  }
  psiFile.accept(visitor)
  return methods
}

private fun matchesParameterTypes(method: PsiMethod, parameterTypes: List<String>): Boolean {
  val parameters = method.parameterList.parameters.toList()
  if (parameters.size != parameterTypes.size) return false
  return parameters.withIndex().all { (index, parameter) -> parameter.type.presentableText == parameterTypes[index] }
}

/**
 * Finds the variable the instance method move is performed onto. Mirrors the production
 * `collectSuitableVariables` selection: method parameters first, then the source class fields,
 * keeping only variables whose type resolves to [targetClass]. An explicit [targetVariableName]
 * takes precedence and is validated against the target type.
 */
private fun resolveTargetVariable(
  method: PsiMethod,
  sourceClass: PsiClass,
  targetClass: PsiClass,
  targetVariableName: String?,
): PsiVariable {
  val allVariables = method.parameterList.parameters.toList() + sourceClass.fields.toList()

  val suitable = allVariables.filter { variable ->
    (variable.type as? PsiClassType)?.resolve() == targetClass
  }

  if (targetVariableName != null) {
    val named = allVariables.firstOrNull { it.name == targetVariableName }
                 ?: mcpFail("No parameter or field named '$targetVariableName' in ${sourceClass.qualifiedName ?: sourceClass.name}")
    if (named !in suitable) {
      mcpFail("Variable '$targetVariableName' is not of type '$targetClass'")
    }
    return named
  }

  return suitable.firstOrNull()
         ?: mcpFail(
           "No variable of type '$targetClass' found among the parameters or fields of ${sourceClass.qualifiedName ?: sourceClass.name}. " +
           "Extract a field or parameter of that type first."
         )
}

private fun snapshotChangedFilesAfterMove(
  project: Project,
  usages: Array<out UsageInfo>,
  sourceClass: PsiClass,
  targetClass: PsiClass,
): List<String> {
  val elementFiles = usages.mapNotNull { usage ->
    val element = usage.element ?: return@mapNotNull null
    PsiUtilCore.getVirtualFile(element)
  }
  val allFiles = (elementFiles + listOfNotNull(
    PsiUtilCore.getVirtualFile(sourceClass),
    PsiUtilCore.getVirtualFile(targetClass),
  )).distinct()
  return allFiles.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}

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

private fun snapshotChangedFilesAfterExtract(project: Project, sourceFile: PsiJavaFile, method: PsiMethod): List<String> {
  val files = listOfNotNull(
    PsiUtilCore.getVirtualFile(sourceFile),
    PsiUtilCore.getVirtualFile(method),
  ).distinct()
  return files.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}