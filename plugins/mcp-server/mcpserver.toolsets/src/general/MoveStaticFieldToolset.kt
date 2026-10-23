// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiUtilCore
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MoveStaticFieldToolset : McpToolset {
  override fun displayName(): String = McpServerBundle.message("toolset.display.name.moveStaticField")

  override fun displayDescription(toolName: String): String? = McpServerBundle.message("tool.description.$toolName")

  @McpTool
  @McpDescription("""
    Moves a *static* Java field from its containing class to a target class, updating every reference
    to point at the field in its new location (IntelliJ "Move Members" refactoring).

    This tool supports static fields only. IntelliJ has no "Move Members" refactoring for instance
    (non-static) fields, so an instance field is rejected with `success` = false and a clear message.
    Use the `move_instance_field` tool for a non-static field.

    Requires the file path of the Java file that declares the field, the exact field name, and the
    fully-qualified name of the destination class.

    Returns a structured result with `success`, the updated usages count and the list of changed files.
    When the refactoring detects conflicts (e.g. a field of the same name already exists in the target
    class, or the moved field becomes inaccessible), nothing is changed and `success` is false with the
    conflict descriptions in `conflicts`.
    """)
  suspend fun move_static_field(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the static field to move")
    fieldName: String,
    @McpDescription("Fully-qualified name of the destination class, e.g. \"org.acme.Bar\"")
    targetClass: String,
    @McpDescription("Visibility applied to the moved field: \"public\", \"protected\", \"private\" or \"package\" (package-private)")
    visibility: String = "public",
  ): MoveStaticFieldResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.moving.static.field", fieldName, targetClass, pathInProject))
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
      mcpFail("Only Java fields can be moved, but '$pathInProject' is not a Java file")
    }

    val field = readAction {
      findFieldInFile(psiFile, fieldName)
    } ?: mcpFail("Couldn't find field '$fieldName' in file '$pathInProject'")

    val sourceClass = readAction { field.containingClass }
                      ?: mcpFail("Field '$fieldName' is not declared inside a class")

    if (!readAction { field.hasModifierProperty(PsiModifier.STATIC) }) {
      return MoveStaticFieldResult(
        success = false,
        message = "Field '$fieldName' is not static. The move_static_field tool supports static fields only; " +
                  "use the move_instance_field tool for an instance field.",
        fieldName = fieldName,
        sourceClass = readAction { sourceClass.qualifiedName ?: sourceClass.name } ?: fieldName,
        targetClass = targetClass,
      )
    }

    val targetPsiClass = readAction {
      JavaPsiFacade.getInstance(project).findClass(targetClass, GlobalSearchScope.projectScope(project))
    } ?: mcpFail("Target class '$targetClass' not found in project")

    if (sourceClass == targetPsiClass) {
      mcpFail("Field '$fieldName' is already declared in the target class '$targetClass'")
    }

    val modifierVisibility = parseFieldVisibility(visibility)
    val sourceClassName = readAction { sourceClass.qualifiedName ?: sourceClass.name }
                          ?: mcpFail("Cannot determine a name for the source class")

    val prepared = readAction {
      val moveProcessor = McpMoveStaticFieldProcessor(project, McpMoveFieldOptions(field, targetClass, modifierVisibility))
      PreparedFieldMove(moveProcessor, moveProcessor.findUsagesPublicly())
    }

    val changedFiles = readAction {
      snapshotChangedFieldFiles(project, prepared.usages, sourceClass, targetPsiClass)
    }

    try {
      withContext(Dispatchers.EDT) {
        writeIntentReadAction {
          prepared.processor.run()
        }
      }
    }
    catch (e: MoveFieldConflictsFoundException) {
      return MoveStaticFieldResult(
        success = false,
        message = "Move of '$fieldName' to '$targetClass' aborted: ${e.conflicts.size} conflict(s) found",
        fieldName = fieldName,
        sourceClass = sourceClassName,
        targetClass = targetClass,
        conflicts = e.conflicts,
      )
    }

    return MoveStaticFieldResult(
      success = true,
      message = "Moved static field '$fieldName' from $sourceClassName to $targetClass; ${prepared.usages.size} usage(s) updated.",
      fieldName = fieldName,
      sourceClass = sourceClassName,
      targetClass = targetClass,
      usagesUpdated = prepared.usages.size,
      changedFiles = changedFiles,
    )
  }
}

@Serializable
data class MoveStaticFieldResult(
  val success: Boolean,
  val message: String,
  val fieldName: String,
  val sourceClass: String,
  val targetClass: String,
  val usagesUpdated: Int = 0,
  val changedFiles: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
)

/**
 * Static-members move ({@link MoveMembersProcessor}) with all UI hooks disabled. Conflicts abort the
 * refactoring with [MoveFieldConflictsFoundException] instead of showing the conflicts dialog.
 */
private class McpMoveStaticFieldProcessor(
  project: Project,
  options: MoveMembersOptions,
) : MoveMembersProcessor(project, null, options, /*openInEditor=*/false) {
  override fun isPreviewUsages(usages: Array<out UsageInfo?>): Boolean = false

  override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo?>?): Boolean {
    if (conflicts.isEmpty) return true
    throw MoveFieldConflictsFoundException(conflicts.values().toList())
  }

  fun findUsagesPublicly(): Array<out UsageInfo> = findUsages()
}

private class McpMoveFieldOptions(
  private val field: PsiField,
  private val targetClassName: String,
  private val visibility: String?,
) : MoveMembersOptions {
  override fun getSelectedMembers(): Array<PsiMember> = arrayOf<PsiMember>(field)

  override fun getTargetClassName(): String = targetClassName

  override fun getMemberVisibility(): String? = visibility

  override fun makeEnumConstant(): Boolean = false
}

private class MoveFieldConflictsFoundException(val conflicts: List<String>) : Exception() {
  override val message: String = "Move static field refactoring found conflicts: ${conflicts.joinToString()}"
}

private data class PreparedFieldMove(
  val processor: MoveMembersProcessor,
  val usages: Array<out UsageInfo>,
)

private fun parseFieldVisibility(visibility: String): String? = when (visibility) {
  "public" -> PsiModifier.PUBLIC
  "protected" -> PsiModifier.PROTECTED
  "private" -> PsiModifier.PRIVATE
  "package" -> null
  else -> mcpFail("Invalid visibility: '$visibility'. Expected one of: public, protected, private, package")
}

private fun findFieldInFile(psiFile: PsiJavaFile, fieldName: String): PsiField? {
  for (psiClass in psiFile.classes) {
    val field = findFieldInClass(psiClass, fieldName)
    if (field != null) return field
  }
  return null
}

private fun findFieldInClass(psiClass: PsiClass, fieldName: String): PsiField? {
  psiClass.fields.firstOrNull { it.name == fieldName }?.let { return it }
  for (inner in psiClass.innerClasses) {
    val field = findFieldInClass(inner, fieldName)
    if (field != null) return field
  }
  return null
}

private fun snapshotChangedFieldFiles(
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
