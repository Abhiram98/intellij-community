package com.intellij.mcpserver.toolsets.general

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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodHandler
import com.intellij.refactoring.move.moveInstanceMethod.MoveInstanceMethodProcessor
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MoveMethodToolset : McpToolset {
  override fun displayName(): String = McpServerBundle.message("toolset.display.name.moveMethod")

  override fun displayDescription(toolName: String): String? = McpServerBundle.message("tool.description.$toolName")

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
      snapshotChangedFiles(project, prepared.usages, sourceClass, targetPsiClass)
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

private fun snapshotChangedFiles(
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