package com.intellij.mcpserver.toolsets.general

import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.lang.java.JavaLanguage
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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.IntroduceParameterRefactoring
import com.intellij.refactoring.inline.InlineMethodHandler
import com.intellij.refactoring.inline.InlineMethodProcessor
import com.intellij.refactoring.introduceParameter.IntroduceParameterProcessor
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.refactoring.memberPullUp.PullUpConflictsUtil
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegateBase
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteMemberCalleeUsageInfo
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteOverridingMethodUsageInfo
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteParameterCallHierarchyUsageInfo
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.RefactoringHierarchyUtil
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.refactoring.util.classMembers.UsesAndInterfacesDependencyMemberInfoModel
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
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
    Replaces a local variable with a new method parameter of the variable's type, updating all
    call sites of the method in the project (IntelliJ "Introduce Parameter" refactoring). The
    local declaration is removed and every occurrence in the body becomes a reference to the new
    parameter. When the method overrides a method from the project, the parameter is also
    introduced into the deepest super method so the whole hierarchy stays consistent.

    Requires the Java file declaring the variable and its exact name. When several methods declare
    a variable with the same name, pass `methodName` (and `parameterTypes`, e.g. ["String", "int"],
    to disambiguate overloads).

    The new parameter is appended at the end of the parameter list (before a trailing varargs
    parameter), which is IntelliJ's standard position.

    Returns a structured result with `success`, the new parameter's name and type, the resulting
    method signature, the number of replaced occurrences and the list of changed files. When the
    variable or method cannot be resolved, or the refactoring detects conflicts (e.g. an
    inaccessible initializer at some call site), nothing is changed and `success` is `false` with
    the reason in `message`.
  """)
  suspend fun introduce_parameter(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the local variable to replace with the new parameter")
    variableName: String,
    @McpDescription("Optional name of the method declaring the variable, to disambiguate when several methods declare a variable with the same name")
    methodName: String? = null,
    @McpDescription("Optional method parameter types to disambiguate overloaded methods, e.g. [\"String\", \"int\"]")
    parameterTypes: List<String>? = null,
    @McpDescription("Replace every occurrence of the variable in the method body (default true). False is accepted only when the variable is referenced exactly once, because the local declaration is removed and the parameter must stand in for every reference")
    replaceAllOccurrences: Boolean = true,
    @McpDescription("Index of the new parameter in the method signature (default: appended at the end of the parameter list, IntelliJ's standard position; currently only that default position is supported)")
    position: Int? = null,
  ): IntroduceParameterResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.introducing.parameter", variableName, pathInProject))
    val project = currentCoroutineContext().project

    val psiFile = resolveProjectPsiFile(project, pathInProject)
    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java local variables can be introduced as parameters, but '$pathInProject' is not a Java file")
    }

    // The variable lookup is in-file, but the super-method analysis, occurrence search and the
    // processor's internal findUsages are all index-backed and throw during dumb mode.
    DumbService.getInstance(project).waitForSmartMode()

    val locals = readAction {
      findLocalVariablesInFile(psiFile, variableName, methodName, parameterTypes)
    }
    if (locals.size > 1) {
      val signatures = locals
        .mapNotNull { local -> PsiTreeUtil.getParentOfType(local, PsiMethod::class.java) }
        .map { method -> "${method.name}(${method.parameterList.parameters.map { it.type.presentableText }.joinToString(", ")})" }
        .distinct()
      return IntroduceParameterResult(
        success = false,
        message = "Several local variables named '$variableName' found in '$pathInProject': $signatures. " +
                  "Pass `methodName` (and `parameterTypes` for overloads) to disambiguate.",
      )
    }
    if (locals.isEmpty()) {
      val parameter = readAction { findParameterInFile(psiFile, variableName, methodName, parameterTypes) }
      if (parameter != null) {
        return IntroduceParameterResult(
          success = false,
          message = "'$variableName' in '$pathInProject' is a method parameter, not a local variable; " +
                    "only local variables can be introduced as parameters.",
        )
      }
      val where = if (methodName != null) " in method '$methodName'" else ""
      return IntroduceParameterResult(
        success = false,
        message = "Couldn't find a local variable named '$variableName'$where in '$pathInProject'.",
      )
    }
    val local = locals.single()

    val method = readAction { PsiTreeUtil.getParentOfType(local, PsiMethod::class.java) }
                 ?: mcpFail("Variable '$variableName' is not declared inside a method")
    val sourceClass = readAction { method.containingClass }
                      ?: mcpFail("Method '${method.name}' is not declared inside a class")

    if (readAction { local.parent !is PsiDeclarationStatement }) {
      return IntroduceParameterResult(
        success = false,
        message = "The declaration of '$variableName' in '${method.name}' is not a plain 'Type name = expression;' statement " +
                  "(it may be a for-loop header or enhanced-for variable); such variables cannot be introduced as parameters.",
      )
    }

    val initializer = readAction { local.initializer }
    if (initializer == null) {
      return IntroduceParameterResult(
        success = false,
        message = "Local variable '$variableName' in '${method.name}' has no initializer; callers would have nothing to pass, " +
                  "so the variable cannot be introduced as a parameter.",
      )
    }

    // Introducing a parameter into a method that overrides something breaks the override unless the
    // parameter is propagated to the base method. Production propagates to the deepest super method;
    // that is only safe headlessly when the super method is part of the project.
    val superMethods = readAction { method.findSuperMethods() }
    val deepestOutsideProject = readAction {
      val deepest = method.findDeepestSuperMethod()
      val deepestVirtualFile = deepest?.let { PsiUtilCore.getVirtualFile(it) }
      deepest == null || deepestVirtualFile == null ||
        !GlobalSearchScope.projectScope(project).contains(deepestVirtualFile)
    }
    if (superMethods.isNotEmpty() && deepestOutsideProject) {
      return IntroduceParameterResult(
        success = false,
        message = "Cannot introduce parameter '$variableName' into '${method.name}': the method overrides " +
                  "'${superMethods.firstOrNull()?.name ?: "a super method"}' outside the project, so the parameter cannot be " +
                  "propagated to the base method headlessly. Introduce it in the base method instead.",
      )
    }
    val methodToSearchFor = readAction { method.findDeepestSuperMethod() ?: method }

    val parameterType = readAction { local.type.presentableText }
    val parameterCount = readAction { method.parameterList.parametersCount }

    val occurrences = readAction { CodeInsightUtil.findReferenceExpressions(method, local) }
    if (occurrences.size > 1 && !replaceAllOccurrences) {
      return IntroduceParameterResult(
        success = false,
        message = "Cannot introduce parameter '$variableName' replacing only its first occurrence: the local declaration " +
                  "is removed, so the parameter must stand in for all ${occurrences.size} references. Use replaceAllOccurrences=true.",
      )
    }

    if (position != null && (position < 0 || position > parameterCount)) {
      mcpFail("position is out of range: '${method.name}' currently has $parameterCount parameter(s), " +
              "so the new parameter can be appended at index $parameterCount at most")
    }
    if (position != null && position < parameterCount) {
      return IntroduceParameterResult(
        success = false,
        message = "Only IntelliJ's standard position is supported: the Introduce Parameter pipeline appends the new parameter " +
                  "at the end of the parameter list (requested index $position). Omit `position` to use that default.",
      )
    }

    // The parameter is referenced from a nested class (rather than the method body directly) whenever
    // an occurrence's innermost PsiClass/PsiMethod ancestor is not the method; Java then requires the
    // parameter to be final - the same check `IntroduceParameterHandler` performs.
    val mustBeFinal = readAction {
      occurrences.any { PsiTreeUtil.getParentOfType(it, PsiClass::class.java, PsiMethod::class.java) != method }
    }

    val processor = readAction {
      McpIntroduceParameterProcessor(
        project,
        methodToReplaceIn = method,
        methodToSearchFor = methodToSearchFor,
        parameterInitializer = initializer,
        localVariable = local,
        parameterName = variableName,
        replaceOccurrencesChoice = IntroduceVariableBase.JavaReplaceChoice.ALL,
        declareFinal = mustBeFinal,
      )
    }

    val usages = readAction { processor.findUsagesPublicly() }
    val changedFiles = readAction { snapshotChangedFilesForIntroduceParameter(project, usages, methodToSearchFor) }
    val sourceClassName = readAction { sourceClass.qualifiedName ?: sourceClass.name }

    // TEMP DEBUG (remove)
    run {
      readAction {
        System.err.println("[MCP-IP-DEBUG] initializer='${initializer.text}' variableName='$variableName' mustBeFinal=$mustBeFinal method='${method.name}' methodToSearchFor='${methodToSearchFor.name}'")
        System.err.println("[MCP-IP-DEBUG] usages (${usages.size}):")
        usages.forEach { u ->
          val el = u.element
          System.err.println("  - ${u.javaClass.simpleName} element=${el?.javaClass?.simpleName} text='${el?.text?.take(100)}'")
        }
      }
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          processor.run()
        }
      }
      System.err.println("[MCP-IP-DEBUG] run() normal return")
    }
    catch (e: IntroduceParameterConflictsFoundException) {
      return IntroduceParameterResult(
        success = false,
        message = "Introducing parameter '$variableName' into '${method.name}' aborted: ${e.conflicts.size} conflict(s) found: " +
                  e.conflicts.joinToString("; "),
      )
    }

    // TEMP DEBUG (remove)
    readAction {
      val vf = psiFile.virtualFile
      val docPsm = PsiDocumentManager.getInstance(project).getDocument(psiFile)
      val docFdm = FileDocumentManager.getInstance().getDocument(vf)
      System.err.println("[MCP-IP-DEBUG] vf=${vf?.path} identity(psiFile)=${System.identityHashCode(psiFile)}")
      System.err.println("[MCP-IP-DEBUG] PsiDocManager doc(${System.identityHashCode(docPsm)})='${docPsm?.text}'")
      System.err.println("[MCP-IP-DEBUG] FileDocManager doc(${System.identityHashCode(docFdm)})='${docFdm?.text}'")
      System.err.println("[MCP-IP-DEBUG] psiFile.text='${psiFile.text}'")
      val testPathPsi = docFdm?.let { PsiDocumentManager.getInstance(project).getPsiFile(it) }
      System.err.println("[MCP-IP-DEBUG] getPsiFile(FileDocManager doc) identity=${testPathPsi?.let { System.identityHashCode(it) }} text='${testPathPsi?.text}'")
    }

    val resultMethod = readAction {
      // Defensive: the refactoring normally edits the method's parameter list in place; if the PSI was
      // replaced, re-locate the method by name and the grown parameter count.
      if (method.isValid) method
      else PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
        .firstOrNull { it.name == method.name && it.parameterList.parametersCount == parameterCount + 1 }
    }
    val methodSignature = resultMethod?.let { result ->
      readAction {
        "${result.returnType?.presentableText ?: ""} ${result.name}(" +
          result.parameterList.parameters.joinToString { parameter -> "${parameter.type.presentableText} ${parameter.name}" } + ")"
      }
    }

    return IntroduceParameterResult(
      success = true,
      message = "Introduced parameter '$variableName' ($parameterType) into '${resultMethod?.name ?: method.name}' " +
                "in ${sourceClassName ?: "the file"}; ${occurrences.size} occurrence(s) replaced, ${changedFiles.size} file(s) changed.",
      parameterName = variableName,
      parameterType = parameterType,
      methodName = resultMethod?.name ?: method.name,
      methodSignature = methodSignature,
      sourceClass = sourceClassName,
      occurrencesReplaced = occurrences.size,
      removedLocalDeclarations = 1,
      changedFiles = changedFiles,
    )
  }

  @McpTool
  @McpDescription("""
    Pulls a Java method up from its containing class into a superclass, updating all references
    with it (IntelliJ "Pull Members Up" refactoring) - the member is moved, not copied.

    The method is resolved by name in the given Java file; pass `parameterTypes` (e.g. ["String",
    "int"]) to disambiguate overloads. The destination is the class named by `targetClass`; when
    omitted, the direct superclass of the method's containing class is auto-detected.

    `makeAbstract` selects the production "make abstract" option: the superclass receives an
    `abstract <sig>;` declaration and the concrete implementation is left in the source class
    (now overriding the abstract one). The default (false) removes the member from the source
    class entirely.

    When the method depends on members of its own class that are not being pulled up (the
    production conflict analysis), the refactoring is aborted with success=false listing the
    offending members - nothing is changed.

    Returns a structured result with `success`, the moved members, the updated-usage count and
    the list of changed files. Tool-level errors (missing file/method/class, uncompilable
    destination) fail the tool call instead.
    """)
  suspend fun pull_up_method(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the method to pull up")
    methodName: String,
    @McpDescription("Optional parameter types to disambiguate overloaded methods, e.g. [\"String\", \"int\"]")
    parameterTypes: List<String>? = null,
    @McpDescription("Fully-qualified name of the destination superclass; defaults to the direct superclass of the method's containing class")
    targetClass: String? = null,
    @McpDescription("When true, the superclass receives an abstract declaration and the implementation stays in the source class (production \"make abstract\" option); when false (default) the member is removed from the source class")
    makeAbstract: Boolean = false,
  ): PullUpMethodResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.pulling.up.method", methodName, targetClass ?: "auto-detected superclass", pathInProject))
    val project = currentCoroutineContext().project
    DumbService.getInstance(project).waitForSmartMode()

    val psiFile = resolveProjectPsiFile(project, pathInProject)
    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java methods can be pulled up, but '$pathInProject' is not a Java file")
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
    val sourceClassName = readAction { sourceClass.qualifiedName ?: sourceClass.name }
                          ?: mcpFail("Cannot determine a name for the source class")

    val targetPsiClass = readAction {
      if (targetClass != null) {
        JavaPsiFacade.getInstance(project).findClass(targetClass, GlobalSearchScope.projectScope(project))
      }
      else {
        // The dialog preselects RefactoringHierarchyUtil.getNearestBaseClass (the extends class,
        // preferring the first interface over java.lang.Object); fall back to the raw extends list.
        RefactoringHierarchyUtil.getNearestBaseClass(sourceClass, false) ?: sourceClass.superClass
      }
    } ?: if (targetClass != null) {
      mcpFail("Target class '$targetClass' not found in project")
    }
    else {
      mcpFail("Cannot determine a superclass to pull '$methodName' up into: '$sourceClassName' has no base class in this project. Pass `targetClass` to select one explicitly.")
    }

    val targetName = readAction { targetPsiClass.qualifiedName ?: targetPsiClass.name }
                     ?: mcpFail("Cannot determine a name for the target class")

    if (readAction { targetPsiClass.qualifiedName } == CommonClassNames.JAVA_LANG_OBJECT) {
      mcpFail("Cannot pull '$methodName' up into 'java.lang.Object'")
    }
    if (targetPsiClass == sourceClass) {
      mcpFail("Method '$methodName' is already declared in the target class '$targetName'")
    }
    if (!readAction { sourceClass.isInheritor(targetPsiClass, true) }) {
      mcpFail("Class '$sourceClassName' is not a subclass of target class '$targetName'")
    }

    // The producer's own flow only writes into project sources; a compiled/library or read-only
    // destination would otherwise surface as a UI notification inside the processor, so reject it
    // here with a clear tool-level error instead.
    val targetFile = readAction { targetPsiClass.containingFile } ?: mcpFail("Cannot locate the file of target class '$targetName'")
    if (targetFile is PsiCompiledElement) {
      mcpFail("Target class '$targetName' is compiled (a library class); cannot pull members up into it")
    }
    val targetVirtualFile = readAction { PsiUtilCore.getVirtualFile(targetPsiClass) }
                            ?: mcpFail("Cannot locate the file of target class '$targetName'")
    if (!readAction { targetVirtualFile.isWritable }) {
      mcpFail("Target class '$targetName' is not writable; cannot pull members up into it")
    }

    // The abstract declaration is added to the TARGET class by JavaPullUpHelper.doMoveMethod
    // (RefactoringUtil.makeMethodAbstract), so a final superclass cannot host it. Interfaces are fine.
    if (makeAbstract) {
      val targetIsInterface = readAction { targetPsiClass.isInterface }
      val targetIsFinal = readAction { targetPsiClass.hasModifierProperty(PsiModifier.FINAL) }
      if (!targetIsInterface && targetIsFinal) {
        return PullUpMethodResult(
          success = false,
          message = "Cannot pull '$methodName' up as abstract: target class '$targetName' is final. " +
                    "Make the target non-final or retry with makeAbstract=false.",
          methodName = methodName,
          fromClass = sourceClassName,
          toClass = targetName,
        )
      }
    }

    // The per-member move spec the processor consumes: checked + the production "make abstract" flag.
    val memberInfo = readAction {
      MemberInfo(method).apply {
        setChecked(true)
        setToAbstract(makeAbstract)
      }
    }
    val memberInfos = arrayOf(memberInfo)

    // Production conflict gate - identical to JavaPullUpHandler.checkConflicts
    // (PullUpConflictsUtil.checkConflicts): catches a same-signature member already present in the
    // superclass, accessibility problems, and references to subclass members that are not being
    // pulled up ("X uses Y which is not moved to the superclass"). Any hit aborts with success=false
    // and the list of offending members; nothing is changed.
    val conflicts = readAction {
      val targetDirectory = targetFile.containingDirectory
      val targetPackage = if (targetDirectory != null) JavaDirectoryService.getInstance().getPackage(targetDirectory) else null
      if (targetDirectory != null && targetPackage != null) {
        PullUpConflictsUtil.checkConflicts(
          memberInfos,
          sourceClass,
          targetPsiClass,
          targetPackage,
          targetDirectory,
          UsesAndInterfacesDependencyMemberInfoModel.DEFAULT_CONTAINMENT_VERIFIER,
        )
      }
      else {
        MultiMap<PsiElement, String>()
      }
    }
    if (!conflicts.isEmpty) {
      return PullUpMethodResult(
        success = false,
        message = "Pull up of '$methodName' to '$targetName' aborted: ${conflicts.size()} conflict(s) found",
        methodName = methodName,
        fromClass = sourceClassName,
        toClass = targetName,
        conflicts = conflicts.values().toList(),
      )
    }

    val prepared = readAction {
      val processor = McpPullUpProcessor(
        sourceClass,
        targetPsiClass,
        arrayOf(memberInfo),
        DocCommentPolicy(DocCommentPolicy.ASIS),
      )
      PreparedPullUp(processor, processor.findUsagesPublicly())
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
    catch (e: PullUpConflictsFoundException) {
      return PullUpMethodResult(
        success = false,
        message = "Pull up of '$methodName' to '$targetName' aborted: ${e.conflicts.size} conflict(s) found",
        methodName = methodName,
        fromClass = sourceClassName,
        toClass = targetName,
        conflicts = e.conflicts,
      )
    }

    val movedMemberNames = readAction {
      prepared.processor.getMovedMembers().mapNotNull { it.name }.distinct()
    }
    return PullUpMethodResult(
      success = true,
      message = "Pulled '$methodName' up from $sourceClassName to $targetName; ${prepared.usages.size} usage(s) updated.",
      methodName = methodName,
      fromClass = sourceClassName,
      toClass = targetName,
      membersMoved = if (movedMemberNames.isEmpty()) listOf(methodName) else movedMemberNames,
      referencesUpdated = prepared.usages.size,
      changedFiles = changedFiles,
    )
  }

  @McpTool
  @McpDescription("""
    Pushes a single Java method from a superclass down into one or all of its direct subclasses,
    updating references (IntelliJ "Push Members Down" refactoring).

    When `targetClass` is omitted the method is pushed into ALL direct subclasses of its declaring
    class and the declaration in the superclass is kept as `abstract <sig>;`, so the class hierarchy
    stays intact. If the superclass is final this is impossible and the tool aborts with success=false.
    Static members cannot be kept abstract, so pushing a static method to all subclasses also aborts
    unless `targetClass` narrows the destination. When a single `targetClass` is given, the method
    moves entirely: the subclass receives a real implementation and nothing abstract remains in the
    source class.

    Requires the file path of the Java file declaring the method and the exact method name. Provide
    `parameterTypes` (e.g. ["String", "int"]) to disambiguate overloads.

    Returns a structured result with `success`, the source and destination classes, the count of
    references rewritten, whether the superclass kept an abstract declaration, and the list of changed
    files. When the refactoring detects conflicts (method already overridden in the target, uses of
    members that cannot be brought along, invalid destinations) nothing is changed and `success` is
    `false` with the conflicts listed in `message`.
  """)
  suspend fun push_down_method(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the method to push down")
    methodName: String,
    @McpDescription("Optional parameter types to disambiguate overloaded methods, e.g. [\"String\", \"int\"]")
    parameterTypes: List<String>? = null,
    @McpDescription("Fully-qualified name of a single destination subclass; when omitted, ALL direct subclasses receive the method and the source keeps an abstract declaration")
    targetClass: String? = null,
  ): PushDownMethodResult {
    currentCoroutineContext().reportToolActivity(
      McpServerBundle.message("tool.activity.pushing.down.method", methodName, targetClass ?: "<all direct subclasses>", pathInProject)
    )
    val project = currentCoroutineContext().project

    // All lookups below are index-backed (findClass, ClassInheritorsSearch, ReferencesSearch),
    // so flush the dumb-mode queue before touching them.
    DumbService.getInstance(project).waitForSmartMode()

    val psiFile = resolveProjectPsiFile(project, pathInProject)
    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java methods can be pushed down, but '$pathInProject' is not a Java file")
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
    val fromClass = readAction { sourceClass.qualifiedName ?: sourceClass.name }
                    ?: mcpFail("Cannot determine a name for the source class")
    val isStatic = readAction { method.hasModifierProperty(PsiModifier.STATIC) }

    // Resolve the destinations. For push-to-all this is exactly the production Java delegate target
    // set: ClassInheritorsSearch with checkDeep=false, i.e. the DIRECT inheritors, anonymous subclasses
    // included. For an explicit targetClass only that class is considered; whether it actually is a
    // direct subclass is validated below.
    val targetClasses: List<PsiClass> = readAction {
      if (targetClass == null) {
        ClassInheritorsSearch.search(sourceClass, false).findAll().toList()
      }
      else {
        val resolved = JavaPsiFacade.getInstance(project).findClass(targetClass, GlobalSearchScope.projectScope(project))
                       ?: mcpFail("Target class '$targetClass' not found in project")
        listOf(resolved)
      }
    }

    // Push-to-all keeps the member in the superclass as abstract (production "keep abstract"
    // checkbox default); pushing to a single explicit target moves the member entirely.
    val abstractInSuper = targetClass == null
    if (abstractInSuper) {
      val isFinal = readAction { sourceClass.hasModifierProperty(PsiModifier.FINAL) }
      if (isFinal) {
        // A final class can neither keep an abstract declaration nor have subclasses to receive it.
        return PushDownMethodResult(
          success = false,
          message = "Push down of '$methodName' from $fromClass aborted: the class is final, so pushing down to all subclasses is impossible.",
          methodName = methodName,
          fromClass = fromClass,
        )
      }
      if (targetClasses.isEmpty()) {
        mcpFail("Class '$fromClass' has no direct subclasses to push '$methodName' down into")
      }
      if (isStatic) {
        // `static abstract` is illegal; the abstract-in-super default cannot be honored for statics.
        return PushDownMethodResult(
          success = false,
          message = "Push down of '$methodName' from $fromClass aborted: static members cannot be kept abstract in the superclass. " +
                    "Pass `targetClass` to move the static method into a single subclass instead.",
          methodName = methodName,
          fromClass = fromClass,
        )
      }
    }
    else if (targetClasses[0] !in readAction { ClassInheritorsSearch.search(sourceClass, false).findAll() }) {
      return PushDownMethodResult(
        success = false,
        message = "Push down of '$methodName' from $fromClass aborted: class '$targetClass' is not a direct subclass.",
        methodName = methodName,
        fromClass = fromClass,
      )
    }

    // The member info drives the delegate: isChecked must be true for PushDownConflicts to track the
    // member, isToAbstract selects "keep abstract in super" vs "delete from super".
    val memberInfo = readAction {
      MemberInfo(method).apply {
        isChecked = true
        isToAbstract = abstractInSuper
      }
    }

    // Pre-run snapshot of the references and of the files that may change (source + targets + every
    // reference site). Push-down rewrites static-qualified call sites and inlines super calls inside
    // those files, so the reference-site files are the complete conservative set.
    val referencesUpdated = readAction {
      ReferencesSearch.search(method).findAll().size
    }
    val changedFiles = readAction {
      snapshotChangedFilesAfterPushDown(project, sourceClass, targetClasses, method)
    }

    val processor = readAction {
      McpPushDownProcessor(project, sourceClass, listOf(memberInfo), targetClasses)
    }

    // Class names are needed for both the conflict-abort and the success result; snapshot them
    // inside the read action because PsiClass.name/qualifiedName require read access.
    val toClassNames = readAction {
      targetClasses.map { it.qualifiedName ?: it.name ?: "<anonymous>" }
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          processor.run()
        }
      }
    }
    catch (e: McpPushDownConflictsFoundException) {
      return PushDownMethodResult(
        success = false,
        message = "Push down of '$methodName' from $fromClass aborted: ${e.conflicts.size} conflict(s) found: " + e.conflicts.joinToString("; "),
        methodName = methodName,
        fromClass = fromClass,
        toClasses = toClassNames,
        referencesUpdated = referencesUpdated,
      )
    }

    return PushDownMethodResult(
      success = true,
      message = "Pushed '$methodName' from $fromClass down into ${toClassNames.joinToString()}" +
                (if (abstractInSuper) "; kept abstract in $fromClass." else "."),
      methodName = methodName,
      fromClass = fromClass,
      toClasses = toClassNames,
      membersPushed = listOf(methodName),
      referencesUpdated = referencesUpdated,
      abstractedInSuper = abstractInSuper,
      changedFiles = changedFiles,
    )
  }

  @McpTool
  @McpDescription("""
    Inlines a Java method into all of its call sites, replacing each call with the method body
    (IntelliJ "Inline Method" refactoring), optionally removing the now-empty method declaration.

    Requires the file path of the Java file declaring the method and the exact method name.
    Provide `parameterTypes` (e.g. ["String", "int"]) to disambiguate overloaded methods.

    By default every call site in the project is inlined. Set `inlineThisOnly` to inline a single
    invocation instead; because there is no cursor in this context, this requires the method to
    have exactly one call site in the project. `deleteMethod` controls whether the declaration is
    removed after inlining all usages; it has no effect with `inlineThisOnly`, since a method that
    may still have call sites cannot be deleted.

    Returns a structured result with `success`, the number of inlined usages, whether the method
    declaration was deleted, and the list of changed files. When the method cannot be inlined
    (recursive, abstract/native, or a call site that would need interactive conflict resolution),
    nothing is changed and `success` is `false` with the reason in `message`.
  """)
  suspend fun inline_method(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the method to inline")
    methodName: String,
    @McpDescription("Optional parameter types to disambiguate overloaded methods, e.g. [\"String\", \"int\"]")
    parameterTypes: List<String>? = null,
    @McpDescription("Inline only the single existing call site instead of all usages. Requires exactly one call site in the project (default: false)")
    inlineThisOnly: Boolean = false,
    @McpDescription("Remove the method declaration after inlining all usages (default: true). Ignored when inlineThisOnly is set")
    deleteMethod: Boolean = true,
  ): InlineMethodResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.inlining.method", methodName, pathInProject))
    val project = currentCoroutineContext().project

    // findUsages references and the inlineThisOnly call-site search are index-backed.
    DumbService.getInstance(project).waitForSmartMode()

    val psiFile = resolveProjectPsiFile(project, pathInProject)
    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java methods can be inlined, but '$pathInProject' is not a Java file")
    }

    val matchingMethods = readAction {
      findMethodsInFile(psiFile, methodName, parameterTypes)
    }
    if (matchingMethods.isEmpty()) {
      return InlineMethodResult(
        success = false,
        message = "Couldn't find method '$methodName' in file '$pathInProject'.",
        methodName = methodName,
      )
    }
    if (matchingMethods.size > 1) {
      val signatures = matchingMethods.map { it.parameterList.parameters.map { p -> p.type.presentableText }.joinToString(", ", "(", ")") }
      return InlineMethodResult(
        success = false,
        message = "Several methods named '$methodName' found in '$pathInProject': $signatures. Pass `parameterTypes` to disambiguate.",
        methodName = methodName,
      )
    }
    val method = matchingMethods[0]

    // Inlining a single call site needs a concrete reference; without a caret the tool can only
    // proceed when there is exactly one call site in the project.
    val reference = if (inlineThisOnly) {
      val references = readAction { findMethodReferences(project, method) }
      if (references.isEmpty()) {
        return InlineMethodResult(
          success = false,
          message = "Method '$methodName' has no call sites to inline. Use `inlineThisOnly=false` with `deleteMethod=true` to remove the unused method.",
          methodName = methodName,
        )
      }
      if (references.size > 1) {
        return InlineMethodResult(
          success = false,
          message = "Method '$methodName' has ${references.size} call sites; `inlineThisOnly` requires exactly one. Use `inlineThisOnly=false` to inline all usages.",
          methodName = methodName,
        )
      }
      references[0]
    }
    else null

    // Pre-conditions that production InlineMethodHandler surfaces as an error hint instead of
    // the refactoring dialog; see checkInlineMethodPreconditions.
    if (!inlineThisOnly) {
      val preconditionError = readAction { checkInlineMethodPreconditions(method) }
      if (preconditionError != null) {
        return InlineMethodResult(success = false, message = preconditionError, methodName = methodName)
      }
    }

    val deleteDeclaration = deleteMethod && !inlineThisOnly

    val prepared = readAction {
      val processor = McpInlineMethodProcessor(project, method, reference, inlineThisOnly, deleteDeclaration)
      PreparedInline(processor, processor.findUsagesPublicly())
    }

    val changedFiles = readAction {
      snapshotChangedFilesAfterInline(project, prepared.usages, method, includeMethodFile = deleteDeclaration)
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          prepared.processor.run()
        }
      }
    }
    catch (e: InlineConflictsFoundException) {
      return InlineMethodResult(
        success = false,
        message = "Inlining of '$methodName' aborted: ${e.conflicts.size} conflict(s) found",
        methodName = methodName,
        conflicts = e.conflicts,
      )
    }
    catch (e: Exception) {
      // Edge cases the processor logs internally (multi-call/varargs rewrites and similar) can
      // surface as exceptions here. The command manager rolls the in-flight command back, so
      // nothing is changed; surface the failure cleanly instead of failing the whole tool call.
      return InlineMethodResult(
        success = false,
        message = "Inlining of '$methodName' failed: ${e.message ?: e.javaClass.simpleName}",
        methodName = methodName,
      )
    }

    return InlineMethodResult(
      success = true,
      message = "Inlined ${prepared.usages.size} usage(s) of '$methodName'${if (deleteDeclaration) " and deleted the method" else ""}.",
      methodName = methodName,
      usagesInlined = prepared.usages.size,
      methodDeleted = deleteDeclaration,
      changedFiles = changedFiles,
    )
  }

  @McpTool
  @McpDescription("""
    Safely deletes a code symbol (class, method, field, parameter or local variable) declared in the
    specified Java file, after verifying that every usage in the project can be resolved by the
    IntelliJ Safe Delete refactoring.

    All usages of the symbol are searched project-wide through the language's Safe Delete delegate.
    Usages inside the deleted element itself, or inside code that is deleted along with it, are
    handled automatically. If any usage lives in code that is NOT being deleted, or the deletion
    would normally raise a confirmation dialog (unsafe references, overriding implementations,
    callers of a deleted class, parameter hierarchy), nothing is changed and `success` is `false`
    with the reason in `message`.

    Requires:
        - pathInProject: The relative path to the Java file declaring the symbol
        - symbolName: The exact, case-sensitive name of the symbol to delete

    Optional:
        - kind: Restricts the search to one element kind: "class", "method", "field", "parameter" or
          "local". Default: auto - the first matching element of any supported kind, in document order.

    Returns a structured result with `success`, the detected symbol kind, the number of usages found
    and updated, the deleted file(s) (project-relative), and all changed files (project-relative).
    Tool-level errors (file not found, not a Java file, symbol not found, ambiguous name) abort with
    an error.
  """)
  suspend fun safe_delete(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the symbol to delete")
    symbolName: String,
    @McpDescription("Optional element kind to disambiguate: \"class\", \"method\", \"field\", \"parameter\" or \"local\" (default: auto)")
    kind: String? = null,
  ): SafeDeleteResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.safely.deleting.symbol", symbolName, pathInProject))
    val project = currentCoroutineContext().project
    DumbService.getInstance(project).waitForSmartMode()

    val psiFile = resolveProjectPsiFile(project, pathInProject)
    if (psiFile !is PsiJavaFile) {
      mcpFail("Only Java symbols can be safely deleted, but '$pathInProject' is not a Java file")
    }

    val requestKind = parseSafeDeleteKind(kind)
    val candidates = readAction { mcpFindSafeDeleteCandidates(psiFile, symbolName, requestKind) }
    if (candidates.isEmpty()) {
      mcpFail(if (kind == null) "Couldn't find symbol '$symbolName' in file '$pathInProject'"
              else "Couldn't find $kind '$symbolName' in file '$pathInProject'")
    }
    if (candidates.size > 1) {
      mcpFail("Found ${candidates.size} elements named '$symbolName' in '$pathInProject'; the name must be unique " +
              "among the ${mcpResolveSafeDeleteKind(candidates.first())?.text ?: "elements"} in the file before it can be deleted.")
    }
    val element = candidates.single()
    mcpValidateSafeDeleteTarget(element)
    val symbolKind = mcpResolveSafeDeleteKind(element)?.text ?: "element"

    val prepared = readAction {
      val baseElements = arrayOf<PsiElement>(element)
      val elementsToDelete = mcpSafeDeleteExpandElements(project, baseElements)
      val rawUsages = mcpSafeDeleteFindUsages(elementsToDelete)
      val usages = UsageViewUtil.removeDuplicatedUsages(rawUsages.toTypedArray())
      PreparedSafeDelete(
        processor = SafeDeleteProcessor.createInstance(project, null, baseElements, false, false, false),
        usagesFound = usages.size,
        usagesUpdated = usages.count { mcpIsChangedBySafeDelete(it) },
        blocked = mcpSafeDeleteCheckBlocking(project, elementsToDelete, usages),
        candidateFiles = mcpSafeDeleteCandidateFiles(elementsToDelete, usages),
        elementDescription = RefactoringUIUtil.getDescription(element, true),
      )
    }

    if (prepared.blocked != null) {
      return SafeDeleteResult(
        success = false,
        message = "Safe Delete of ${prepared.elementDescription} in $pathInProject aborted: ${prepared.blocked}",
        symbolName = symbolName,
        symbolKind = symbolKind,
        usagesFound = prepared.usagesFound,
      )
    }

    withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        prepared.processor.run()
      }
    }

    return readAction {
      val deletedFiles = prepared.candidateFiles
        .filter { !it.isValid }
        .map { project.projectDirectory.relativizeIfPossible(it) }
        .sorted()
      val changedFiles = prepared.candidateFiles
        .filter { it.isValid }
        .map { project.projectDirectory.relativizeIfPossible(it) }
        .sorted()
      SafeDeleteResult(
        success = true,
        message = "Safely deleted ${prepared.elementDescription} in $pathInProject: ${prepared.usagesUpdated} usage(s) processed, " +
                  "${deletedFiles.size} file(s) deleted.",
        symbolName = symbolName,
        symbolKind = symbolKind,
        usagesFound = prepared.usagesFound,
        usagesUpdated = prepared.usagesUpdated,
        deletedFiles = deletedFiles,
        deletedElement = prepared.elementDescription,
        changedFiles = changedFiles,
      )
    }
  }

}



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
 * Resolves a project-relative path to its PSI file, [mcpFail]ing with the standard diagnostics when the
 * file, the document, or the PSI cannot be produced. Errors are phrased exactly like the caller-side
 * messages in `rename_refactoring`/`move_method`/`extract_method`, which previously each inlined this
 * sequence; new refactoring tools should reuse this single helper.
 */
private suspend fun resolveProjectPsiFile(project: Project, pathInProject: String): PsiFile {
  val resolvedPath = project.resolveInProject(pathInProject)
  val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
                    ?: mcpFail("File not found: $pathInProject")
  val document = readAction {
    FileDocumentManager.getInstance().getDocument(virtualFile)
  } ?: mcpFail("Cannot read file: $pathInProject")
  return readAction {
    PsiDocumentManager.getInstance(project).getPsiFile(document)
  } ?: mcpFail("Couldn't get PSI file for: $pathInProject")
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IntroduceParameterResult(
  val success: Boolean,
  val message: String,
  val parameterName: String? = null,
  val parameterType: String? = null,
  val methodName: String? = null,
  val methodSignature: String? = null,
  val sourceClass: String? = null,
  @EncodeDefault val occurrencesReplaced: Int = 0,
  @EncodeDefault val removedLocalDeclarations: Int = 0,
  @EncodeDefault val changedFiles: List<String> = emptyList(),
)

@Serializable
data class PullUpMethodResult(
  val success: Boolean,
  val message: String,
  val methodName: String,
  val fromClass: String,
  val toClass: String,
  val membersMoved: List<String> = emptyList(),
  val referencesUpdated: Int = 0,
  val changedFiles: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PushDownMethodResult(
  val success: Boolean,
  val message: String,
  val methodName: String,
  val fromClass: String,
  val toClasses: List<String> = emptyList(),
  val membersPushed: List<String> = emptyList(),
  val referencesUpdated: Int = 0,
  @EncodeDefault val abstractedInSuper: Boolean = false,
  val changedFiles: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class InlineMethodResult(
  val success: Boolean,
  val message: String,
  val methodName: String,
  @EncodeDefault val usagesInlined: Int = 0,
  @EncodeDefault val methodDeleted: Boolean = false,
  val changedFiles: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SafeDeleteResult(
  val success: Boolean,
  val message: String,
  val symbolName: String,
  val symbolKind: String,
  @EncodeDefault val usagesFound: Int = 0,
  @EncodeDefault val usagesUpdated: Int = 0,
  val deletedFiles: List<String> = emptyList(),
  val deletedElement: String? = null,
  val changedFiles: List<String> = emptyList(),
)

/**
 * Introduce Parameter ([IntroduceParameterProcessor]) with all UI hooks disabled. Conflicts abort the
 * refactoring with [IntroduceParameterConflictsFoundException] instead of showing the conflicts dialog.
 */
private class McpIntroduceParameterProcessor(
  project: Project,
  methodToReplaceIn: PsiMethod,
  methodToSearchFor: PsiMethod,
  parameterInitializer: PsiExpression?,
  localVariable: PsiLocalVariable,
  parameterName: String,
  replaceOccurrencesChoice: IntroduceVariableBase.JavaReplaceChoice,
  declareFinal: Boolean,
) : IntroduceParameterProcessor(
  project,
  methodToReplaceIn,
  methodToSearchFor,
  parameterInitializer,
  /*expressionToSearch=*/null,
  localVariable,
  /*removeLocalVariable=*/true,
  parameterName,
  replaceOccurrencesChoice,
  IntroduceParameterRefactoring.REPLACE_FIELDS_WITH_GETTERS_NONE,
  declareFinal,
  /*generateDelegate=*/false,
  /*replaceWithLambda=*/false,
  /*forcedType=*/null,
  IntArrayList(),
) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw IntroduceParameterConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

private class IntroduceParameterConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Introduce Parameter refactoring found conflicts: ${conflicts.joinToString()}"
}

/**
 * Finds the local variables with the given name, restricted to a method by [methodName] (and
 * [parameterTypes] to disambiguate overloads, reusing `matchesParameterTypes`). Returns every
 * candidate so the caller can disambiguate when several methods declare the same variable name.
 */
private fun findLocalVariablesInFile(
  psiFile: PsiJavaFile,
  variableName: String,
  methodName: String?,
  parameterTypes: List<String>?,
): List<PsiLocalVariable> {
  val result = mutableListOf<PsiLocalVariable>()
  val visitor = object : PsiRecursiveElementVisitor() {
    override fun visitElement(element: PsiElement) {
      if (element is PsiLocalVariable && element.name == variableName) {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null
            && (methodName == null || method.name == methodName)
            && (parameterTypes == null || matchesParameterTypes(method, parameterTypes))) {
          result.add(element)
        }
      }
      super.visitElement(element)
    }
  }
  psiFile.accept(visitor)
  return result
}

/**
 * Looks for a method parameter with the given name (restricted by [methodName]/[parameterTypes]) so
 * a misdirected call can be reported as such instead of a generic "variable not found". Lambda and
 * loop parameters are excluded via the declaration-scope comparison.
 */
private fun findParameterInFile(
  psiFile: PsiJavaFile,
  variableName: String,
  methodName: String?,
  parameterTypes: List<String>?,
): PsiParameter? {
  var found: PsiParameter? = null
  val visitor = object : PsiRecursiveElementVisitor() {
    override fun visitElement(element: PsiElement) {
      if (found == null && element is PsiParameter && element.name == variableName) {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method != null
            && element.declarationScope == method
            && (methodName == null || method.name == methodName)
            && (parameterTypes == null || matchesParameterTypes(method, parameterTypes))) {
          found = element
          return
        }
      }
      super.visitElement(element)
    }
  }
  psiFile.accept(visitor)
  return found
}

/**
 * Pre-computes the set of files the refactoring will touch: each usage element's file (call sites,
 * overriding method declarations) plus the file of the method the parameter is introduced into -
 * [methodToSearchFor], which is the deepest super method in the hierarchy case and is rewritten by
 * the processor as well.
 */
private fun snapshotChangedFilesForIntroduceParameter(
  project: Project,
  usages: Array<out UsageInfo>,
  methodToSearchFor: PsiMethod,
): List<String> {
  val usageFiles = usages.mapNotNull { usage -> usage.element?.let { PsiUtilCore.getVirtualFile(it) } }
  val allFiles = (usageFiles + listOfNotNull(PsiUtilCore.getVirtualFile(methodToSearchFor))).distinct()
  return allFiles.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}

/**
 * Pull-members-up processor ([PullUpProcessor]) with all UI hooks disabled, mirroring the
 * `McpMoveMembersProcessor`/`McpMoveInstanceMethodProcessor` pattern: the usage preview is never
 * shown and conflicts abort with [PullUpConflictsFoundException] instead of showing the conflicts
 * dialog.
 */
private class McpPullUpProcessor(
  sourceClass: PsiClass,
  targetSuperClass: PsiClass,
  membersToMove: Array<MemberInfo>,
  javaDocPolicy: DocCommentPolicy,
) : PullUpProcessor(sourceClass, targetSuperClass, membersToMove, javaDocPolicy) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw PullUpConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

private class PullUpConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Pull members up refactoring found conflicts: ${conflicts.joinToString()}"
}

private data class PreparedPullUp(
  val processor: McpPullUpProcessor,
  val usages: Array<out UsageInfo>,
)

/**
 * Push Members Down ([PushDownProcessor]) with every UI hook disabled and the destination
 * classes injected directly.
 *
 * The base `PushDownProcessor` resolves the language delegate via the extension point, so the full
 * production semantics (copies into each target, qualifier rebinding, super-call inlining, source
 * cleanup) are reused. Only the target enumeration is overridden: `findUsages()` returns exactly the
 * requested destination classes, and the base `preprocessUsages` runs the Java delegate's
 * source/target conflict checks against precisely those classes. Conflicts abort the refactoring with
 * [McpPushDownConflictsFoundException] instead of showing the conflicts/preview dialogs.
 */
private class McpPushDownProcessor(
  project: Project,
  sourceClass: PsiClass,
  memberInfos: List<MemberInfo>,
  targetClasses: List<PsiClass>,
) : PushDownProcessor<MemberInfo, PsiMember, PsiClass>(sourceClass, memberInfos, DocCommentPolicy(DocCommentPolicy.ASIS)) {
  private val myTargetClasses = targetClasses

  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw McpPushDownConflictsFoundException(conflicts.values().toList())
  }

  override fun findUsages(): Array<out UsageInfo> = myTargetClasses.map { UsageInfo(it) }.toTypedArray()
}

private class McpPushDownConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Push Members Down refactoring found conflicts: ${conflicts.joinToString()}"
}

/**
 * Pre-run snapshot of the files a push-down may touch: the source class file, every destination class
 * file, and every file holding a reference to the pushed method (static-qualified call sites are
 * rebound inside those files by the delegate). Names are project-relative and sorted.
 */
private fun snapshotChangedFilesAfterPushDown(
  project: Project,
  sourceClass: PsiClass,
  targetClasses: List<PsiClass>,
  method: PsiMethod,
): List<String> {
  val referenceFiles = ReferencesSearch.search(method).findAll().mapNotNull { reference ->
    val element = reference.element ?: return@mapNotNull null
    PsiUtilCore.getVirtualFile(element)
  }
  val elementFiles = targetClasses.mapNotNull { PsiUtilCore.getVirtualFile(it) } + listOfNotNull(PsiUtilCore.getVirtualFile(sourceClass))
  val allFiles = (referenceFiles + elementFiles).distinct()
  return allFiles.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}

/**
 * Inline Method ([InlineMethodProcessor]) with all UI hooks disabled. Conflicts abort the
 * refactoring with [InlineConflictsFoundException] instead of showing the conflicts dialog.
 * The editor is null (no caret to restore), comments/text occurrences are not searched, and
 * `isPreviewUsages` is forced off so the refactoring executes instead of opening the usage
 * view.
 */
private class McpInlineMethodProcessor(
  project: Project,
  method: PsiMethod,
  reference: PsiReference?,
  inlineThisOnly: Boolean,
  deleteMethod: Boolean,
) : InlineMethodProcessor(project, method, reference, null, inlineThisOnly, false, false, deleteMethod) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw InlineConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

private class InlineConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Inline method refactoring found conflicts: ${conflicts.joinToString()}"
}

private data class PreparedInline(
  val processor: McpInlineMethodProcessor,
  val usages: Array<out UsageInfo>,
)

/**
 * Mirrors the failure branch of `InlineMethodHandler.createInlineContext`: a method without a
 * body (abstract/native) or a recursive method (when all usages are inlined) produces an error
 * hint in the IDE. Returns a user-facing message, or null when the method is inlinable.
 */
private fun checkInlineMethodPreconditions(method: PsiMethod): String? {
  if (method.body == null) {
    if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
      return "Cannot inline '${method.name}': the method is abstract and has no implementation to inline."
    }
    if (method.hasModifierProperty(PsiModifier.NATIVE)) {
      return "Cannot inline '${method.name}': the method is native and has no body."
    }
    return "Cannot inline '${method.name}': the method has no body to inline."
  }
  if (InlineMethodHandler.checkRecursive(method)) {
    return "Cannot inline '${method.name}': inlining recursive methods is not supported when inlining all usages."
  }
  return null
}

/**
 * All Java code references to [method] in the project. This is the same search
 * (`MethodReferencesSearch` with strict signature matching) the inline processor uses to find
 * usages, narrowed to Java `PsiReferenceExpression` elements - the only call-site forms the
 * Java `performRefactoring` can rewrite. Index-backed; callers must ensure smart mode.
 */
private fun findMethodReferences(project: Project, method: PsiMethod): List<PsiReference> {
  return MethodReferencesSearch.search(method, GlobalSearchScope.projectScope(project), true)
    .findAll()
    .filter { reference ->
      val element = reference.element
      element is PsiReferenceExpression && element.language == JavaLanguage.INSTANCE
    }
}

/**
 * Snapshots the project-relative paths of every file the inline will touch: the files holding
 * the call-site usages, and - when the declaration is to be deleted - the file declaring the
 * method. Taken before the processor runs, matching `snapshotChangedFilesAfterMove`.
 */
private fun snapshotChangedFilesAfterInline(
  project: Project,
  usages: Array<out UsageInfo>,
  method: PsiMethod,
  includeMethodFile: Boolean,
): List<String> {
  val files = usages.mapNotNull { usage -> usage.element?.let { element -> PsiUtilCore.getVirtualFile(element) } }.toMutableList()
  if (includeMethodFile) {
    PsiUtilCore.getVirtualFile(method)?.let { files.add(it) }
  }
  return files.distinct().map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}

private enum class SafeDeleteKind(val text: String) {
  CLASS("class"), METHOD("method"), FIELD("field"), PARAMETER("parameter"), LOCAL("local"),
}

private fun parseSafeDeleteKind(kind: String?): SafeDeleteKind? {
  if (kind == null) return null
  return SafeDeleteKind.entries.firstOrNull { it.text == kind }
         ?: mcpFail("Invalid kind: '$kind'. Expected one of: class, method, field, parameter, local")
}

private fun mcpResolveSafeDeleteKind(element: PsiElement): SafeDeleteKind? = when (element) {
  is PsiClass -> SafeDeleteKind.CLASS
  is PsiMethod -> SafeDeleteKind.METHOD
  is PsiField -> SafeDeleteKind.FIELD
  is PsiParameter -> SafeDeleteKind.PARAMETER
  is PsiLocalVariable -> SafeDeleteKind.LOCAL
  else -> null
}

/**
 * Locates every candidate declaration matching [symbolName] in document order, restricted to a
 * [kind] when one is given. With `kind == null` only the first supported kind that matches is kept,
 * mirroring `findSymbolInFile`'s "first matching element" semantics while still detecting ambiguous
 * duplicates of that kind.
 */
private fun mcpFindSafeDeleteCandidates(psiFile: PsiFile, symbolName: String, kind: SafeDeleteKind?): List<PsiElement> {
  val matches = mutableListOf<PsiElement>()
  val visitor = object : PsiRecursiveElementVisitor() {
    override fun visitElement(e: PsiElement) {
      val elementKind = mcpResolveSafeDeleteKind(e)
      if (elementKind != null && (kind == null || kind == elementKind) && e is PsiNamedElement && e.name == symbolName) {
        matches.add(e)
      }
      super.visitElement(e)
    }
  }
  psiFile.accept(visitor)
  if (kind != null || matches.isEmpty()) return matches
  val firstKind = mcpResolveSafeDeleteKind(matches.first())
  return matches.filter { mcpResolveSafeDeleteKind(it) == firstKind }
}

private fun mcpValidateSafeDeleteTarget(element: PsiElement) {
  // JavaSafeDeleteProcessor.findUsages asserts that a deletable parameter's declaration scope is a
  // method; parameters of lambdas/foreach/records are not supported by the Java delegate.
  if (element is PsiParameter && element.declarationScope !is PsiMethod) {
    mcpFail("Parameter '${element.name}' cannot be safely deleted: it is not declared by a method")
  }
}

/**
 * Replicates the element expansion of `SafeDeleteProcessor.createInstance(project, ..., askForAccessors)`
 * with `askForAccessors = false` - the field accessor/record-component handling of
 * `JavaSafeDeleteProcessor.getAdditionalElementsToDelete` never opens a dialog in that mode. The result
 * is exactly the element set the processor will search usages for and delete, so the pre-check and the
 * actual run always agree.
 */
private fun mcpSafeDeleteExpandElements(project: Project, initial: Array<PsiElement>): Array<PsiElement> {
  val elements = initial.toMutableList()
  val elementsToDeleteSet = initial.toCollection(HashSet())
  for (element in initial) {
    for (delegate in SafeDeleteProcessorDelegate.EP_NAME.extensionList) {
      if (delegate.handlesElement(element)) {
        val added = delegate.getAdditionalElementsToDelete(element, elementsToDeleteSet, false)
        if (added != null) elements.addAll(added)
        break
      }
    }
  }
  return elements.toTypedArray()
}

/**
 * Replicates `SafeDeleteProcessor.findUsages()` with the search-in-comments/search-non-Java flags off
 * (so `addNonCodeUsages` is a no-op): for each element the first handling delegate fills the usage list;
 * elements without a delegate fall back to the generic reference search.
 */
private fun mcpSafeDeleteFindUsages(elementsToDelete: Array<PsiElement>): List<UsageInfo> {
  val usages = ArrayList<UsageInfo>()
  for (element in elementsToDelete) {
    var handled = false
    for (delegate in SafeDeleteProcessorDelegate.EP_NAME.extensionList) {
      if (delegate.handlesElement(element)) {
        // The returned NonCodeUsageSearchInfo only configures text-occurrence search, which the
        // processor performs with the searchInComments/searchNonJava flags - both disabled here.
        delegate.findUsages(element, elementsToDelete, usages)
        handled = true
        break
      }
    }
    if (!handled && element is PsiNamedElement) {
      SafeDeleteProcessor.findGenericElementUsages(element, usages, elementsToDelete)
    }
  }
  return usages
}

/**
 * Replicates the blocking decisions of `SafeDeleteProcessor.preprocessUsages()` (delegate conflicts +
 * `checkConflicts`/`collectUnsafeUsages`, then the second check over usages added by the delegates'
 * `preprocessUsages` chain) and additionally treats the usage types that would pop the interactive
 * chooser dialogs of `JavaSafeDeleteProcessor.preprocessUsages` as blocking. Returns `null` when the
 * deletion is safe to run against the real processor without any dialog.
 */
private fun mcpSafeDeleteCheckBlocking(project: Project, elementsToDelete: Array<PsiElement>, usages: Array<UsageInfo>): String? {
  val messages = mutableListOf<String>()

  // 1. Delegate-reported conflicts (first loop of SafeDeleteProcessor.preprocessUsages). The check
  //    `!conflicts.isEmpty()` in checkConflicts is computed before collectUnsafeUsages runs, so only
  //    these count towards the `hasConflict` flag.
  val conflicts = MultiMap<PsiElement, String>()
  for (element in elementsToDelete) {
    for (delegate in SafeDeleteProcessorDelegate.EP_NAME.extensionList) {
      if (delegate.handlesElement(element)) {
        delegate.findConflicts(element, elementsToDelete, usages, conflicts)
        val found = if (delegate is SafeDeleteProcessorDelegateBase) {
          delegate.findConflicts(element, elementsToDelete, usages)
        }
        else {
          delegate.findConflicts(element, elementsToDelete)
        }
        if (found != null) conflicts.put(element, found)
        break
      }
    }
  }
  messages.addAll(conflicts.values().distinct())

  // 2. Unsafe usages (replicates collectUnsafeUsages + checkConflicts). Only real code usages that are
  //    not safe to delete block; non-code usages and usages in generated code never block (the platform
  //    merely skips them during execution), mirroring the host behaviour.
  for (usage in usages) {
    val reference = usage as? SafeDeleteReferenceUsageInfo ?: continue
    if (reference.isSafeDelete()) continue
    if (reference.isNonCodeUsage || mcpSafeDeleteIsInGeneratedCode(reference, project)) continue
    messages.add("Usage of ${RefactoringUIUtil.getDescription(reference.getReferencedElement(), true)} in code that will not be deleted")
  }

  // 3. Usages that would open an interactive chooser dialog in JavaSafeDeleteProcessor.preprocessUsages
  //    (overriding methods, callees of a class being deleted, parameter call hierarchies). These are
  //    "usages the user would normally be prompted about"; a headless run cannot confirm them.
  val dialogTriggers = usages.mapNotNull { mcpSafeDeleteDialogTriggerDescription(it) }.distinct()
  messages.addAll(dialogTriggers)

  // 4. The tail of SafeDeleteProcessor.preprocessUsages: the delegates may filter usages (possibly
  //    adding some) through their `preprocessUsages` chain, and the new usages are re-checked with
  //    checkConflicts against an empty conflict map. Replicate so the real run cannot block either.
  if (dialogTriggers.isEmpty()) {
    var preprocessed: Array<UsageInfo>? = usages
    for (delegate in SafeDeleteProcessorDelegate.EP_NAME.extensionList) {
      preprocessed = delegate.preprocessUsages(project, preprocessed!!) ?: return "the Safe Delete preprocessing cancelled the deletion"
    }
    val added = (preprocessed!!.toSet() - usages.toSet()).toTypedArray()
    for (usage in added) {
      val reference = usage as? SafeDeleteReferenceUsageInfo ?: continue
      if (!reference.isSafeDelete() && !reference.isNonCodeUsage && !mcpSafeDeleteIsInGeneratedCode(reference, project)) {
        messages.add("Usage of ${RefactoringUIUtil.getDescription(reference.getReferencedElement(), true)} " +
                     "introduced while preparing the deletion cannot be resolved safely")
      }
    }
  }

  return messages.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("; ")
}

private fun mcpSafeDeleteDialogTriggerDescription(usage: UsageInfo): String? = when (usage) {
  is SafeDeleteOverridingMethodUsageInfo -> "the symbol is overridden and the overriding implementations would have to be deleted too"
  is SafeDeleteMemberCalleeUsageInfo -> "the class contains methods that are called only from within it and would have to be deleted too"
  is SafeDeleteParameterCallHierarchyUsageInfo -> "the parameter is part of a call/parameter hierarchy that would have to be updated too"
  else -> null
}

private fun mcpSafeDeleteIsInGeneratedCode(usage: SafeDeleteReferenceUsageInfo, project: Project): Boolean {
  val file = usage.getVirtualFile() ?: return false
  return GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)
}

/**
 * Mirrors `SafeDeleteProcessor.isToBeChanged()`: reference usages are applied only when `isSafeDelete()`,
 * all other usages (custom usage infos like external-annotations deletions) are applied. Usages without
 * an element are dropped during execution.
 */
private fun mcpIsChangedBySafeDelete(usage: UsageInfo): Boolean {
  if (usage.getElement() == null) return false
  val reference = usage as? SafeDeleteReferenceUsageInfo
  return reference == null || reference.isSafeDelete()
}

/**
 * The files that Safe Delete may touch: the declaration of every element being deleted (the symbol plus
 * any accessors added by the expansion) and the files of all usages. Snapshot before the run; afterwards
 * files that are no longer valid were deleted, the valid ones were modified.
 */
private fun mcpSafeDeleteCandidateFiles(elementsToDelete: Array<PsiElement>, usages: Array<UsageInfo>): List<VirtualFile> {
  val files = LinkedHashSet<VirtualFile>()
  for (element in elementsToDelete) {
    PsiUtilCore.getVirtualFile(element)?.let { files.add(it) }
  }
  for (usage in usages) {
    usage.getElement()?.let { PsiUtilCore.getVirtualFile(it)?.let { file -> files.add(file) } }
  }
  return files.toList()
}

private data class PreparedSafeDelete(
  val processor: BaseRefactoringProcessor,
  val usagesFound: Int,
  val usagesUpdated: Int,
  val blocked: String?,
  val candidateFiles: List<VirtualFile>,
  val elementDescription: String,
)