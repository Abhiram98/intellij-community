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
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.PsiUtilCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MoveInstanceFieldToolset : McpToolset {
  override fun displayName(): String = McpServerBundle.message("toolset.display.name.moveInstanceField")

  override fun displayDescription(toolName: String): String? = McpServerBundle.message("tool.description.$toolName")

  @McpTool
  @McpDescription("""
    Moves an *instance* (non-static) Java field from its containing class to a target class, relocating
    the declaration and rewriting every access to go through a receiver that links the source class to
    the target class.

    IntelliJ has no built-in "Move Instance Field" refactoring, so this tool composes the move itself:
      - it moves the field declaration (type, initializer and visibility) into the target class;
      - inside the source class, a bare `field` or `this.field` access becomes `receiver.field`;
      - an external `owner.field` access becomes `owner.receiver.field`.

    The receiver is a field or parameter in the source class whose type resolves to the target class,
    exactly like the instance path of the `move_method` tool. The receiver is auto-detected (the first
    matching field or parameter) or selected explicitly with `receiverName`. When the source class has
    no such link, the move fails with a message that asks for a target-typed field first. Use the
    `move_static_field` tool for a static field.

    Requires the file path of the Java file that declares the field, the exact field name, and the
    fully-qualified name of the destination class.

    Returns a structured result with `success`, the updated usages count and the list of changed files.
    When the move would produce a conflict (e.g. a field of the same name already exists in the target
    class, or a final field is written from outside the source class), nothing is changed and `success`
    is false with the conflict descriptions in `conflicts`.
    """)
  suspend fun move_instance_field(
    @McpDescription(Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION)
    pathInProject: String,
    @McpDescription("Exact name of the instance field to move")
    fieldName: String,
    @McpDescription("Fully-qualified name of the destination class, e.g. \"org.acme.Bar\"")
    targetClass: String,
    @McpDescription("Visibility applied to the moved field: \"public\", \"protected\", \"private\" or \"package\" (package-private)")
    visibility: String = "public",
    @McpDescription("Name of the field or parameter in the source class that links to the target class. Defaults to the first matching variable of the target class type")
    receiverName: String? = null,
  ): MoveInstanceFieldResult {
    currentCoroutineContext().reportToolActivity(McpServerBundle.message("tool.activity.moving.instance.field", fieldName, targetClass, pathInProject))
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
      findInstanceFieldInFile(psiFile, fieldName)
    } ?: mcpFail("Couldn't find field '$fieldName' in file '$pathInProject'")

    val sourceClass = readAction { field.containingClass }
                      ?: mcpFail("Field '$fieldName' is not declared inside a class")

    if (readAction { field.hasModifierProperty(PsiModifier.STATIC) }) {
      return MoveInstanceFieldResult(
        success = false,
        message = "Field '$fieldName' is static. The move_instance_field tool supports instance fields only; " +
                  "use the move_static_field tool for a static field.",
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

    val modifierVisibility = parseInstanceFieldVisibility(visibility)
    val sourceClassName = readAction { sourceClass.qualifiedName ?: sourceClass.name }
                          ?: mcpFail("Cannot determine a name for the source class")

    // Resolve the receiver variable (a source-class field of the target type) up front so a missing
    // link fails before anything is changed, mirroring the instance path of move_method.
    val receiver = readAction {
      resolveReceiverField(sourceClass, targetPsiClass, receiverName)
    }
    val resolvedReceiverName = readAction { receiver.name }
                               ?: mcpFail("Receiver field has no name")

    val references = readAction {
      ReferencesSearch.search(field, GlobalSearchScope.projectScope(project), false)
        .asIterable()
        .mapNotNull { it.element as? PsiReferenceExpression }
        .filter { it.isValid }
    }

    val conflicts = readAction {
      collectConflicts(field, fieldName, sourceClass, targetPsiClass, references)
    }
    if (conflicts.isNotEmpty()) {
      return MoveInstanceFieldResult(
        success = false,
        message = "Move of '$fieldName' to '$targetClass' aborted: ${conflicts.size} conflict(s) found",
        fieldName = fieldName,
        sourceClass = sourceClassName,
        targetClass = targetClass,
        conflicts = conflicts,
      )
    }

    val changedFiles = readAction {
      snapshotChangedInstanceFieldFiles(project, references, sourceClass, targetPsiClass)
    }

    val usagesUpdated: Int = withContext(Dispatchers.EDT) {
      writeCommandAction(project, McpServerBundle.message("command.name.move.instance.field")) {
        performInstanceFieldMove(project, field, sourceClass, targetPsiClass, receiver, modifierVisibility, references)
      }
    }

    return MoveInstanceFieldResult(
      success = true,
      message = "Moved instance field '$fieldName' from $sourceClassName to $targetClass through '$resolvedReceiverName'; " +
                "$usagesUpdated reference(s) updated.",
      fieldName = fieldName,
      sourceClass = sourceClassName,
      targetClass = targetClass,
      receiverName = resolvedReceiverName,
      usagesUpdated = usagesUpdated,
      changedFiles = changedFiles,
    )
  }
}

@Serializable
data class MoveInstanceFieldResult(
  val success: Boolean,
  val message: String,
  val fieldName: String,
  val sourceClass: String,
  val targetClass: String,
  val receiverName: String? = null,
  val usagesUpdated: Int = 0,
  val changedFiles: List<String> = emptyList(),
  val conflicts: List<String> = emptyList(),
)

private fun parseInstanceFieldVisibility(visibility: String): String = when (visibility) {
  "public" -> PsiModifier.PUBLIC
  "protected" -> PsiModifier.PROTECTED
  "private" -> PsiModifier.PRIVATE
  "package" -> PsiModifier.PACKAGE_LOCAL
  else -> mcpFail("Invalid visibility: '$visibility'. Expected one of: public, protected, private, package")
}

private fun findInstanceFieldInFile(psiFile: PsiJavaFile, fieldName: String): PsiField? {
  for (psiClass in psiFile.classes) {
    findInstanceFieldInClass(psiClass, fieldName)?.let { return it }
  }
  return null
}

private fun findInstanceFieldInClass(psiClass: PsiClass, fieldName: String): PsiField? {
  psiClass.fields.firstOrNull { it.name == fieldName }?.let { return it }
  for (inner in psiClass.innerClasses) {
    findInstanceFieldInClass(inner, fieldName)?.let { return it }
  }
  return null
}

/**
 * Finds the variable the field move is performed through. Mirrors the `move_method` instance path:
 * source-class fields whose type resolves to [targetClass]. An explicit [receiverName] takes
 * precedence and is validated against the target type.
 */
private fun resolveReceiverField(
  sourceClass: PsiClass,
  targetClass: PsiClass,
  receiverName: String?,
): PsiField {
  val fields = sourceClass.fields.toList()
  val suitable = fields.filter { (it.type as? PsiClassType)?.resolve() == targetClass }

  if (receiverName != null) {
    val named = fields.firstOrNull { it.name == receiverName }
                ?: mcpFail("No field named '$receiverName' in ${sourceClass.qualifiedName ?: sourceClass.name}")
    if (named !in suitable) {
      mcpFail("Field '$receiverName' is not of type '${targetClass.qualifiedName ?: targetClass.name}'")
    }
    return named
  }

  return suitable.firstOrNull()
         ?: mcpFail(
           "No field of type '${targetClass.qualifiedName ?: targetClass.name}' found in " +
           "${sourceClass.qualifiedName ?: sourceClass.name}. Add a field of that type to the source class first, " +
           "so the moved field can be reached through it."
         )
}

private fun collectConflicts(
  field: PsiField,
  fieldName: String,
  sourceClass: PsiClass,
  targetClass: PsiClass,
  references: List<PsiReferenceExpression>,
): List<String> {
  val conflicts = mutableListOf<String>()

  if (targetClass.findFieldByName(fieldName, false) != null) {
    conflicts.add("Field '$fieldName' already exists in the target class '${targetClass.qualifiedName ?: targetClass.name}'")
  }

  if (field.hasModifierProperty(PsiModifier.FINAL)) {
    val writtenOutsideSource = references.any { ref ->
      PsiUtil.isAccessedForWriting(ref) && PsiTreeUtil.getParentOfType(ref, PsiClass::class.java) != sourceClass
    }
    if (writtenOutsideSource) {
      conflicts.add("Final field '$fieldName' is assigned outside the source class and cannot be moved safely")
    }
  }

  return conflicts
}

private fun performInstanceFieldMove(
  project: Project,
  field: PsiField,
  sourceClass: PsiClass,
  targetClass: PsiClass,
  receiver: PsiField,
  visibility: String,
  references: List<PsiReferenceExpression>,
): Int {
  val factory = JavaPsiFacade.getElementFactory(project)
  val receiverName = receiver.name ?: error("Receiver field has no name")

  var updated = 0
  for (ref in references) {
    if (!ref.isValid) continue
    rewriteReference(factory, ref, sourceClass, receiverName)?.let { updated = it + updated }
  }

  // Move the declaration into the target class with the requested visibility.
  val moved = targetClass.add(field.copy()) as PsiField
  PsiUtil.setModifierProperty(moved, PsiModifier.STATIC, false)
  applyVisibility(moved, visibility)
  field.delete()

  JavaCodeStyleManager.getInstance(project).shortenClassReferences(targetClass)
  return updated
}

/**
 * Rewrites a single reference to the moved field so it is reached through the receiver.
 * Returns 1 when a rewrite happened, null otherwise.
 */
private fun rewriteReference(
  factory: PsiElementFactory,
  ref: PsiReferenceExpression,
  sourceClass: PsiClass,
  receiverName: String,
): Int? {
  // The receiver itself must not be rewritten (it still belongs to the source class).
  val qualifier: PsiExpression? = ref.qualifierExpression

  val newText: String = if (qualifier == null) {
    // Bare `field` or implicit this.field inside the source class -> receiver.field
    "$receiverName.${ref.referenceName}"
  }
  else {
    // Qualified `owner.field` -> `owner.receiver.field`. `this.field` keeps the this qualifier.
    "${qualifier.text}.$receiverName.${ref.referenceName}"
  }

  val replacement = factory.createExpressionFromText(newText, ref)
  ref.replace(replacement)
  return 1
}

private fun applyVisibility(field: PsiField, visibility: String) {
  // PACKAGE_LOCAL is not a settable keyword; package-private means none of the three set.
  for (modifier in listOf(PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE)) {
    PsiUtil.setModifierProperty(field, modifier, modifier == visibility)
  }
}

private fun snapshotChangedInstanceFieldFiles(
  project: Project,
  references: List<PsiReferenceExpression>,
  sourceClass: PsiClass,
  targetClass: PsiClass,
): List<String> {
  val elementFiles = references.mapNotNull { PsiUtilCore.getVirtualFile(it) }
  val allFiles = (elementFiles + listOfNotNull(
    PsiUtilCore.getVirtualFile(sourceClass),
    PsiUtilCore.getVirtualFile(targetClass),
  )).distinct()
  return allFiles.map { file -> project.projectDirectory.relativizeIfPossible(file) }.sorted()
}
