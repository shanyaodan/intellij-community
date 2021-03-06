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
package com.intellij.openapi.components.impl.stores;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.NotificationsManager;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.*;
import com.intellij.openapi.components.store.ReadOnlyModificationException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectBundle;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.project.impl.ProjectMacrosUtil;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.LineSeparator;
import com.intellij.util.SmartList;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jdom.Parent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.event.HyperlinkEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.*;

public class StorageUtil {
  public static final String DEFAULT_EXT = ".xml";

  static final Logger LOG = Logger.getInstance(StorageUtil.class);

  @TestOnly
  public static String DEBUG_LOG = null;

  private static final byte[] XML_PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes(CharsetToolkit.UTF8_CHARSET);

  private static final Pair<byte[], String> NON_EXISTENT_FILE_DATA = Pair.create(null, SystemProperties.getLineSeparator());

  private StorageUtil() { }

  public static void checkUnknownMacros(@NotNull final ComponentManager componentManager, @NotNull final Project project) {
    Application application = ApplicationManager.getApplication();
    if (application.isHeadlessEnvironment() || application.isUnitTestMode()) {
      return;
    }

    // should be invoked last
    StartupManager.getInstance(project).runWhenProjectIsInitialized(new Runnable() {
      @Override
      public void run() {
        notifyUnknownMacros(ComponentsPackage.getStateStore(componentManager), project, null);
      }
    });
  }

  public static void notifyUnknownMacros(@NotNull final IComponentStore store, @NotNull final Project project, @Nullable final String componentName) {
    TrackingPathMacroSubstitutor substitutor = store.getStateStorageManager().getMacroSubstitutor();
    if (substitutor == null) {
      return;
    }

    final LinkedHashSet<String> macros = new LinkedHashSet<String>(substitutor.getUnknownMacros(componentName));
    if (macros.isEmpty()) {
      return;
    }

    AppUIUtil.invokeOnEdt(new Runnable() {
      @Override
      public void run() {
        List<String> notified = null;
        NotificationsManager manager = NotificationsManager.getNotificationsManager();
        for (UnknownMacroNotification notification : manager.getNotificationsOfType(UnknownMacroNotification.class, project)) {
          if (notified == null) {
            notified = new SmartList<String>();
          }
          notified.addAll(notification.getMacros());
        }
        if (!ContainerUtil.isEmpty(notified)) {
          macros.removeAll(notified);
        }

        if (macros.isEmpty()) {
          return;
        }

        LOG.debug("Reporting unknown path macros " + macros + " in component " + componentName);
        String format = "<p><i>%s</i> %s undefined. <a href=\"define\">Fix it</a></p>";
        String productName = ApplicationNamesInfo.getInstance().getProductName();
        String content = String.format(format, StringUtil.join(macros, ", "), macros.size() == 1 ? "is" : "are") +
                         "<br>Path variables are used to substitute absolute paths " +
                         "in " + productName + " project files " +
                         "and allow project file sharing in version control systems.<br>" +
                         "Some of the files describing the current project settings contain unknown path variables " +
                         "and " + productName + " cannot restore those paths.";
        new UnknownMacroNotification("Load Error", "Load error: undefined path variables", content, NotificationType.ERROR,
                                     new NotificationListener() {
                                       @Override
                                       public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
                                         checkUnknownMacros(store, project, true);
                                       }
                                     }, macros).notify(project);
      }
    }, project.getDisposed());
  }

  public static void checkUnknownMacros(@NotNull IComponentStore store, @NotNull Project project, boolean showDialog) {
    // default project doesn't have it
    List<TrackingPathMacroSubstitutor> substitutors;
    if (store instanceof IProjectStore) {
      substitutors = ((IProjectStore)store).getSubstitutors();
    }
    else {
      substitutors = Collections.emptyList();
    }
    Set<String> unknownMacros = new THashSet<String>();
    for (TrackingPathMacroSubstitutor substitutor : substitutors) {
      unknownMacros.addAll(substitutor.getUnknownMacros(null));
    }

    if (unknownMacros.isEmpty() || showDialog && !ProjectMacrosUtil.checkMacros(project, new THashSet<String>(unknownMacros))) {
      return;
    }

    final PathMacros pathMacros = PathMacros.getInstance();
    final Set<String> macrosToInvalidate = new THashSet<String>(unknownMacros);
    for (Iterator<String> it = macrosToInvalidate.iterator(); it.hasNext(); ) {
      String macro = it.next();
      if (StringUtil.isEmptyOrSpaces(pathMacros.getValue(macro)) && !pathMacros.isIgnoredMacroName(macro)) {
        it.remove();
      }
    }

    if (macrosToInvalidate.isEmpty()) {
      return;
    }

    Set<String> components = new THashSet<String>();
    for (TrackingPathMacroSubstitutor substitutor : substitutors) {
      components.addAll(substitutor.getComponents(macrosToInvalidate));
    }

    if (store.isReloadPossible(components)) {
      for (TrackingPathMacroSubstitutor substitutor : substitutors) {
        substitutor.invalidateUnknownMacros(macrosToInvalidate);
      }

      for (UnknownMacroNotification notification : NotificationsManager.getNotificationsManager().getNotificationsOfType(UnknownMacroNotification.class, project)) {
        if (macrosToInvalidate.containsAll(notification.getMacros())) {
          notification.expire();
        }
      }

      store.reloadStates(components);
    }
    else if (Messages.showYesNoDialog(project, "Component could not be reloaded. Reload project?", "Configuration Changed", Messages.getQuestionIcon()) == Messages.YES) {
      ProjectManagerEx.getInstanceEx().reloadProject(project);
    }
  }

  @NotNull
  public static VirtualFile writeFile(@Nullable File file,
                                      @NotNull Object requestor,
                                      @Nullable VirtualFile virtualFile,
                                      @NotNull Element element,
                                      @NotNull LineSeparator lineSeparator,
                                      boolean prependXmlProlog) throws IOException {
    final VirtualFile result;
    if (file != null && (virtualFile == null || !virtualFile.isValid())) {
      result = getOrCreateVirtualFile(requestor, file);
    }
    else {
      result = virtualFile;
      assert result != null;
    }

    if (LOG.isDebugEnabled() || ApplicationManager.getApplication().isUnitTestMode()) {
      BufferExposingByteArrayOutputStream content = writeToBytes(element, lineSeparator.getSeparatorString());
      if (isEqualContent(result, lineSeparator, content)) {
        throw new IllegalStateException("Content equals, but it must be handled not on this level: " + result.getName());
      }
      else if (DEBUG_LOG != null && ApplicationManager.getApplication().isUnitTestMode()) {
        DEBUG_LOG = result.getPath() + ":\n" + content + "\nOld Content:\n" + LoadTextUtil.loadText(result) + "\n---------";
      }
    }

    doWrite(requestor, result, element, lineSeparator, prependXmlProlog);
    return result;
  }

  private static void doWrite(@NotNull final Object requestor,
                              @NotNull final VirtualFile file,
                              @NotNull Object content,
                              @NotNull final LineSeparator lineSeparator,
                              final boolean prependXmlProlog) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Save " + file.getPresentableUrl());
    }
    AccessToken token = WriteAction.start();
    try {
      OutputStream out = file.getOutputStream(requestor);
      try {
        if (prependXmlProlog) {
          out.write(XML_PROLOG);
          out.write(lineSeparator.getSeparatorBytes());
        }
        if (content instanceof Element) {
          JDOMUtil.writeParent((Element)content, out, lineSeparator.getSeparatorString());
        }
        else {
          ((BufferExposingByteArrayOutputStream)content).writeTo(out);
        }
      }
      finally {
        out.close();
      }
    }
    catch (FileNotFoundException e) {
      // may be element is not long-lived, so, we must write it to byte array
      final BufferExposingByteArrayOutputStream byteArray = content instanceof Element ? writeToBytes((Element)content, lineSeparator.getSeparatorString()) : ((BufferExposingByteArrayOutputStream)content);
      throw new ReadOnlyModificationException(file, e, new StateStorage.SaveSession() {
        @Override
        public void save() throws IOException {
          doWrite(requestor, file, byteArray, lineSeparator, prependXmlProlog);
        }
      });
    }
    finally {
      token.finish();
    }
  }

  private static boolean isEqualContent(@NotNull VirtualFile result,
                                        @Nullable LineSeparator lineSeparatorIfPrependXmlProlog,
                                        @NotNull BufferExposingByteArrayOutputStream content) throws IOException {
    int headerLength = lineSeparatorIfPrependXmlProlog == null ? 0 : XML_PROLOG.length + lineSeparatorIfPrependXmlProlog.getSeparatorBytes().length;
    if (result.getLength() != (headerLength + content.size())) {
      return false;
    }

    byte[] oldContent = result.contentsToByteArray();

    if (lineSeparatorIfPrependXmlProlog != null &&
        (!ArrayUtil.startsWith(oldContent, XML_PROLOG) || !ArrayUtil.startsWith(oldContent, XML_PROLOG.length, lineSeparatorIfPrependXmlProlog.getSeparatorBytes()))) {
      return false;
    }

    for (int i = headerLength; i < oldContent.length; i++) {
      if (oldContent[i] != content.getInternalBuffer()[i - headerLength]) {
        return false;
      }
    }
    return true;
  }

  public static void deleteFile(@NotNull File file, @NotNull final Object requestor, @Nullable final VirtualFile virtualFile) throws IOException {
    if (virtualFile == null) {
      LOG.warn("Cannot find virtual file " + file.getAbsolutePath());
    }

    if (virtualFile == null) {
      if (file.exists()) {
        FileUtil.delete(file);
      }
    }
    else if (virtualFile.exists()) {
      try {
        deleteFile(requestor, virtualFile);
      }
      catch (FileNotFoundException e) {
        throw new ReadOnlyModificationException(virtualFile, e, new StateStorage.SaveSession() {
          @Override
          public void save() throws IOException {
            deleteFile(requestor, virtualFile);
          }
        });
      }
    }
  }

  public static void deleteFile(@NotNull Object requestor, @NotNull VirtualFile virtualFile) throws IOException {
    AccessToken token = WriteAction.start();
    try {
      virtualFile.delete(requestor);
    }
    finally {
      token.finish();
    }
  }

  @NotNull
  public static BufferExposingByteArrayOutputStream writeToBytes(@NotNull Parent element, @NotNull String lineSeparator) throws IOException {
    BufferExposingByteArrayOutputStream out = new BufferExposingByteArrayOutputStream(512);
    JDOMUtil.writeParent(element, out, lineSeparator);
    return out;
  }

  @NotNull
  public static VirtualFile getOrCreateVirtualFile(@Nullable final Object requestor, @NotNull final File file) throws IOException {
    VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file);
    if (virtualFile != null) {
      return virtualFile;
    }
    File absoluteFile = file.getAbsoluteFile();
    FileUtil.createParentDirs(absoluteFile);

    File parentFile = absoluteFile.getParentFile();
    // need refresh if the directory has just been created
    final VirtualFile parentVirtualFile = StringUtil.isEmpty(parentFile.getPath()) ? null : LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentFile);
    if (parentVirtualFile == null) {
      throw new IOException(ProjectBundle.message("project.configuration.save.file.not.found", parentFile));
    }

    if (ApplicationManager.getApplication().isWriteAccessAllowed()) {
      return parentVirtualFile.createChildData(requestor, file.getName());
    }

    AccessToken token = WriteAction.start();
    try {
      return parentVirtualFile.createChildData(requestor, file.getName());
    }
    finally {
      token.finish();
    }
  }

  @NotNull
  public static VirtualFile createDir(@NotNull File ioDir, @NotNull Object requestor) throws IOException {
    //noinspection ResultOfMethodCallIgnored
    ioDir.mkdirs();
    String parentFile = ioDir.getParent();
    VirtualFile parentVirtualFile = parentFile == null ? null : VfsUtil.createDirectoryIfMissing(parentFile);
    if (parentVirtualFile == null) {
      throw new IOException(ProjectBundle.message("project.configuration.save.file.not.found", parentFile));
    }
    return getFile(ioDir.getName(), parentVirtualFile, requestor);
  }

  /**
   * @return pair.first - file contents (null if file does not exist), pair.second - file line separators
   */
  @NotNull
  public static Pair<byte[], String> loadFile(@Nullable final VirtualFile file) throws IOException {
    if (file == null || !file.exists()) {
      return NON_EXISTENT_FILE_DATA;
    }

    byte[] bytes = file.contentsToByteArray();
    String lineSeparator = file.getDetectedLineSeparator();
    if (lineSeparator == null) {
      lineSeparator = detectLineSeparators(CharsetToolkit.UTF8_CHARSET.decode(ByteBuffer.wrap(bytes)), null).getSeparatorString();
    }
    return Pair.create(bytes, lineSeparator);
  }

  @NotNull
  public static LineSeparator detectLineSeparators(@NotNull CharSequence chars, @Nullable LineSeparator defaultSeparator) {
    for (int i = 0, n = chars.length(); i < n; i++) {
      char c = chars.charAt(i);
      if (c == '\r') {
        return LineSeparator.CRLF;
      }
      else if (c == '\n') {
        // if we are here, there was no \r before
        return LineSeparator.LF;
      }
    }
    return defaultSeparator == null ? LineSeparator.getSystemLineSeparator() : defaultSeparator;
  }

  public static void delete(@NotNull StreamProvider provider, @NotNull String fileSpec, @NotNull RoamingType type) {
    if (provider.isApplicable(fileSpec, type)) {
      provider.delete(fileSpec, type);
    }
  }

  public static boolean isProjectOrModuleFile(@NotNull String fileSpec) {
    return StoragePathMacros.PROJECT_FILE.equals(fileSpec) || fileSpec.startsWith(StoragePathMacros.PROJECT_CONFIG_DIR) || fileSpec.equals(StoragePathMacros.MODULE_FILE);
  }

  @NotNull
  public static VirtualFile getFile(@NotNull String fileName, @NotNull VirtualFile parent, @NotNull Object requestor) throws IOException {
    VirtualFile file = parent.findChild(fileName);
    if (file != null) {
      return file;
    }

    AccessToken token = WriteAction.start();
    try {
      return parent.createChildData(requestor, fileName);
    }
    finally {
      token.finish();
    }
  }
}
