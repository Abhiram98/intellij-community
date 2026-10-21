// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mcpserver.impl

import com.intellij.mcpserver.ToolDescriptionVariant
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.delete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = logger<ToolDescriptionVariantService>()

/**
 * Holds the active [ToolDescriptionVariant] and keeps it in step with the variant control file.
 *
 * The file is the single runtime channel: the settings page writes it, and any external process can
 * write it too. Writers create a temporary file and rename it over the target, so the watcher sees
 * either the old or the new content, never a partial write.
 *
 * The watcher watches the parent directory of the file, because `WatchService` cannot watch a file
 * that does not exist yet. It debounces the event bursts of a rename and republishes the value.
 */
@Service
class ToolDescriptionVariantService(private val coroutineScope: CoroutineScope) {
  private val _variant = MutableStateFlow(readVariantFromFile())
  val variant: StateFlow<ToolDescriptionVariant> = _variant.asStateFlow()

  init {
    coroutineScope.launch(Dispatchers.IO) {
      watchVariantFile()
    }
  }

  private suspend fun watchVariantFile() {
    val file = variantFile()
    val directory = file.parent
    if (directory == null || !directory.isDirectory()) {
      logger.warn("Cannot watch for tool description variant changes: directory of $file does not exist")
      return
    }
    val watchService = try {
      directory.fileSystem.newWatchService()
    }
    catch (e: Exception) {
      logger.warn("Cannot create a watcher for $directory", e)
      return
    }
    val watchKey = try {
      directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
    }
    catch (e: Exception) {
      logger.warn("Cannot watch $directory for tool description variant changes", e)
      return
    }

    while (currentCoroutineContext().isActive) {
      val key = watchService.poll(1, TimeUnit.SECONDS) ?: continue
      val fileChanged = key.pollEvents().any { event -> (event.context() as? Path)?.name == file.name }
      key.reset()
      if (!fileChanged) continue
      // A rename lands as a burst of events; wait for the burst to settle before the read.
      delay(FILE_SETTLE_DELAY)
      refreshFromVariantFile()
    }
    watchKey.cancel()
  }

  private fun refreshFromVariantFile() {
    _variant.value = readVariantFromFile()
  }

  companion object {
    /**
     * Overrides the location of the variant control file. The default is
     * `.mcp-description-variant.txt` in the user home directory.
     */
    const val VARIANT_FILE_PROPERTY: String = "mcp.tool.description.variant.file"

    private const val DEFAULT_FILE_NAME: String = ".mcp-description-variant.txt"
    private val DEFAULT_VARIANT_FILE: Path = Path.of(System.getProperty("user.home"), DEFAULT_FILE_NAME)

    /**
     * How long the watcher waits for the event burst of a rename to settle before it reads the file.
     */
    private val FILE_SETTLE_DELAY: Duration = 200.milliseconds

    @JvmStatic
    fun getInstance(): ToolDescriptionVariantService = service<ToolDescriptionVariantService>()

    fun variantFile(): Path = System.getProperty(VARIANT_FILE_PROPERTY)?.let(Path::of) ?: DEFAULT_VARIANT_FILE

    fun readVariantFromFile(): ToolDescriptionVariant {
      val content = try {
        variantFile().takeIf { it.isRegularFile() }?.readText()?.trim()
      }
      catch (e: Exception) {
        logger.warn("Cannot read the tool description variant file", e)
        null
      }
      if (content.isNullOrEmpty()) return ToolDescriptionVariant.BASELINE
      val variant = ToolDescriptionVariant.fromId(content)
      if (variant == null) {
        logger.warn("Ignoring unknown tool description variant '$content' in ${variantFile()}")
        return ToolDescriptionVariant.BASELINE
      }
      return variant
    }

    /**
     * Writes the variant control file and republishes the value, so the change applies even when the
     * watcher cannot see the file (a parent directory that appeared after this service started).
     */
    fun writeVariantToFile(variant: ToolDescriptionVariant) {
      val file = variantFile()
      try {
        if (variant == ToolDescriptionVariant.BASELINE) {
          Files.deleteIfExists(file)
        }
        else {
          file.parent?.let { Files.createDirectories(it) }
          val tempFile = createTempFile(directory = file.parent, prefix = file.name, suffix = ".tmp")
          tempFile.outputStream().use { output -> output.write(variant.id.toByteArray()) }
          try {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
          }
          catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
          }
        }
      }
      catch (e: Exception) {
        logger.error("Cannot write the tool description variant file $file", e)
        return
      }
      getInstanceOrNull()?.refreshFromVariantFile()
    }

    private fun getInstanceOrNull(): ToolDescriptionVariantService? = serviceOrNull<ToolDescriptionVariantService>()
  }
}
