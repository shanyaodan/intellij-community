/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testFramework

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.SmartList
import org.junit.rules.ExternalResource
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.IOException

public class TemporaryDirectory : ExternalResource() {
  private val files = SmartList<File>()

  private var sanitizedName: String? = null

  override fun apply(base: Statement, description: Description): Statement {
    sanitizedName = FileUtil.sanitizeName(description.getMethodName())
    return super.apply(base, description)
  }

  override fun after() {
    for (file in files) {
      FileUtil.delete(file)
    }
    files.clear()
  }

  /**
   * Directory is not created.
   */
  public fun newDirectory(directoryName: String? = null): File {
    val file = generatePath(directoryName)
    LocalFileSystem.getInstance()?.let { fs ->
      // If a temp directory is reused from some previous test run, there might be cached children in its VFS. Ensure they're removed.
      val virtualFile = fs.findFileByIoFile(file)
      if (virtualFile != null) {
        VfsUtil.markDirtyAndRefresh(false, true, true, virtualFile)
      }
    }
    return file
  }

  public fun generatePath(suffix: String?): File {
    var fileName = sanitizedName!!
    if (suffix != null) {
      fileName += "_$suffix"
    }

    var file = generateTemporaryPath(fileName)
    files.add(file)
    return file
  }

  public fun newVirtualDirectory(directoryName: String? = null): VirtualFile {
    val file = generatePath(directoryName)
    if (!file.mkdirs()) {
      throw AssertionError("Cannot create directory")
    }

    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    VfsUtil.markDirtyAndRefresh(false, true, true, virtualFile)
    return virtualFile!!
  }
}

public fun generateTemporaryPath(fileName: String?): File {
  val tempDirectory = FileUtilRt.getTempDirectory()
  var file = File(tempDirectory, fileName)
  var i = 0
  while (file.exists() && i < 9) {
    file = File(tempDirectory, "${fileName}_$i")
    i++
  }

  if (file.exists()) {
    throw IOException("Cannot generate unique random path")
  }
  return file
}