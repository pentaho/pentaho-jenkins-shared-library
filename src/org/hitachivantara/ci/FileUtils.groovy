/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import groovy.io.FileType
import groovy.io.FileVisitResult
import hudson.FilePath
import hudson.Util
import hudson.model.TaskListener
import org.jenkinsci.plugins.workflow.cps.CpsScript

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

class FileUtils {
  static Script getSteps() {
    ({} as CpsScript)
  }

  /**
   * Returns a list of absolute paths to files that were found
   *
   * It has to be annotated with NonCPS - otherwise it will not be executed
   * while no error is given
   *
   * @param rootFolderPath root location where to look
   * @param artifactPattern a pattern to look for
   * @param excludesPattern an exclusion pattern to ignore files
   * @return
   */
  @NonCPS
  static List<String> findFiles(String rootFolderPath, String artifactPattern, String excludesPattern = null) {

    List<String> artifactPaths = []

    if (!artifactPattern) {
      return artifactPaths
    }

    String defaultSyntaxPrefix = "regex:"

    if (!artifactPattern.startsWith('glob:') && !artifactPattern.startsWith('regex:')) {
      artifactPattern = defaultSyntaxPrefix + artifactPattern
    }

    if (excludesPattern && !excludesPattern.startsWith('glob:') && !excludesPattern.startsWith('regex:')) {
      excludesPattern = defaultSyntaxPrefix + excludesPattern
    }

    FileSystem fs = FileSystems.getDefault()
    PathMatcher includeMatcher = fs.getPathMatcher(artifactPattern)
    PathMatcher excludeMatcher

    if (excludesPattern) {
      excludeMatcher = fs.getPathMatcher(excludesPattern)
    }

    if (!rootFolderPath.endsWith(File.separator)) {
      rootFolderPath = rootFolderPath + File.separator
    }

    File rootFolder = new File(rootFolderPath)
    if (rootFolder.exists()) {
      rootFolder.traverse(type: FileType.FILES, preDir: {
        File file ->

          if (excludeMatcher?.matches(file.toPath())) {
            return FileVisitResult.SKIP_SUBTREE
          }

          return FileVisitResult.CONTINUE

      }, filter: {
        File file ->
          includeMatcher.matches(file.toPath()) && !(excludeMatcher?.matches(file.toPath()))

      }) { File file ->
        artifactPaths << file.getAbsolutePath().toString()
      }
    }

    return artifactPaths?.unique()
  }

  /**
   * Retireves file MD5 checksum
   *
   * @param file
   * @return
   */
  static String getMd5(File file) {
    create(file).digest()
  }

  /**
   * Determines if two files are equal ( have the same content )
   *
   * @param file1
   * @param file2
   * @return
   */
  static boolean equal(File file1, File file2) {
    return getMd5(file1) == getMd5(file2)
  }

  /**
   * Copy files between two locations.
   * If this file already exists, it will be overwritten.
   * If the directory doesn't exist, it will be created.
   * @param source
   * @param target
   */
  static void copy(def source, def target) {
    FilePath sourcePath = create(source)
    FilePath targetPath = create(target)
    sourcePath.copyTo(targetPath)
  }

  /**
   * Copies this file to the specified target on master node.
   * @param src
   * @param target
   */
  static void copyToMaster(def src, def target) {
    // use File to create a local file with channel as null, which usually stands for master
    switch (target) {
      case File:
        copy(src, create(target))
        break
      default:
        copy(src, create(Paths.get(getPath(target)).toFile()))
    }
  }

  /**
   * Creates a FilePath in the current context
   * @return
   * @throws IOException
   */
  static FilePath create() throws IOException {
    steps.getContext(FilePath)
  }

  /**
   * Creates a FilePath from the given pathname
   * @param pathname
   * @return
   */
  static FilePath create(def pathname) throws IOException {
    switch (pathname) {
      case CharSequence:
        FilePath filePath = create()
        return filePath.child(pathname as String)
      case File:
        return new FilePath(pathname as File)
      case FilePath:
        return pathname as FilePath
      default:
        throw new FilePathException("Can't create FilePath for object ${pathname?.class}")
    }
  }

  /**
   * Construct a path starting with a base location.
   * @param base a base path for resolution
   * @param rel a path which if relative will be resolved against base
   * @return
   * @throws IOException
   */
  static FilePath create(def base, String rel) throws IOException {
    create(base).child(rel)
  }

  static String getPath(def file) {
    switch (file) {
      case CharSequence:
        return Paths.get(file as String).toString()
      case FilePath:
        return file.remote
      case File:
        return file.path
    }
  }

  static String getPath(def base, String rel) {
    resolve(rel, getPath(base))
  }

  @NonCPS
  static String relativize(String path, String parent) {
    Paths.get(parent).relativize(Paths.get(path)).normalize().toString()
  }

  @NonCPS
  static boolean isChild(String path, String parent) {
    Paths.get('/', path).startsWith(Paths.get('/', parent))
  }

  @NonCPS
  static String resolve(String path, String parent) {
    Paths.get(parent).resolve(path).normalize().toString()
  }

  /**
   * Checks whether a certain path is available
   * @param pathname
   * @return
   */
  static boolean exists(def pathname) {
    return create(pathname).exists()
  }

  /**
   * Returns the basic attributes of a given file
   *
   * @param filePath
   * @return
   */
  static BasicFileAttributes getBasicAttributes(FilePath filePath) {
    return Files.readAttributes(new File(getPath(filePath)).toPath(), BasicFileAttributes.class)
  }

  /**
   * Creates a Symbolic Link to a file or folder
   *
   * @param rootDir
   * @param targetFolder
   * @param symLinkPath
   */
  static void createSymLink(String rootDir, String targetFolder, String symLinkPath) {
    if (!exists(rootDir)) {
      create(rootDir).mkdirs()
    }
    Util.createSymlink(rootDir as File, targetFolder, symLinkPath, steps.getContext(TaskListener.class) as TaskListener)
  }

  /**
   * Creates a Hard Link to a file
   *
   * @param name
   * @param target
   */
  static void createHardLink(String linkBaseDir, String targetFolder, String fileName) {
    String targetedFile = "${targetFolder}/${fileName}"

    if (exists(targetedFile)) {
      if (steps.isUnix()) { // if anyone ever run this on another OS
        steps.dir(linkBaseDir) {
          // not using 'Files.createLink' due to having an intermittent error 'java.nio.file.NoSuchFileException' even
          // when the file is actually in place!
          steps.sh "ln ${targetedFile} ${fileName}"
        }
      } else {
        steps.log.warn 'No hard link will be created!'
      }
    } else {
      steps.log.error "'${targetedFile}' doesn't seem to exist!"
    }

  }
}