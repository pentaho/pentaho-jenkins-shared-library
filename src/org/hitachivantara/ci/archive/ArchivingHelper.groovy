/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.archive

import hudson.FilePath
import hudson.model.Cause
import hudson.model.Run
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData

import java.nio.file.Paths
import java.time.Instant

import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVING_CONFIG
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVING_EXCLUDE_PATTERN
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVING_PATH_GROUP_REGEX
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_TO_JENKINS_MASTER
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_CONFIG_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_HOSTING_ROOT
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_VERSIONS_FILE
import static org.hitachivantara.ci.config.LibraryProperties.DEPLOYMENT_FOLDER
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER

class ArchivingHelper implements Serializable {

  Script dsl
  BuildData buildData
  transient Run currentBuild

  ArchivingHelper(Script dsl, BuildData buildData) {
    this.dsl = dsl
    this.buildData = buildData
    this.currentBuild = dsl.currentBuild.rawBuild
  }

  /**
   * Reads the artifacts manifest list either from a
   * manifest file, a Map or a List
   *
   * @return list of the files names
   */
  List<String> getArtifactsNames() {
    List<String> fileNames = []
    def manifestConfig = buildData.get(ARCHIVING_CONFIG)

    if (manifestConfig in CharSequence) {

      def manifest

      // tries to load the string as a valid yaml
      manifest = dsl.readYaml(text: manifestConfig)

      if (manifest in String) {
        dsl.log.warn "Could not read ${manifestConfig} as a valid yaml structure. Trying as a file path..."

        if (!FileUtils.exists(manifestConfig)) {
          throw new ManifestNotFoundException("Artifact archiving is misconfigured. '${manifestConfig}' is not a valid manifest file path!")
        }

        manifest = dsl.readYaml(file: manifestConfig)
        dsl.log.debug "Read manifest from ${manifestConfig}", manifest
      }

      if (!manifest) {
        dsl.log.warn "Manifest is empty"
      } else {
        fileNames = handleManifestConfig(manifest)
      }
    } else {
      fileNames = handleManifestConfig(manifestConfig)
    }

    return fileNames
  }

  /**
   * Checks and handles all of the possible content structure
   * @param manifest generic content
   * @return
   */
  List<String> handleManifestConfig(def manifest) {
    List<String> fileNames = []

    if (manifest in Map) {
      manifest.values().each {
        fileNames.addAll(handleManifestConfig(it))
      }

    } else if (manifest in List) {
      fileNames.addAll(manifest)
    }

    return fileNames?.unique()
  }

  /**
   * Determines the root target folder to copy to
   *
   * @param buildData
   * @return
   */
  String getArchivingTargetRootFolder() {

    String targetRootFolder = buildData.getString(BUILD_HOSTING_ROOT) //if custom property is set

    if (!targetRootFolder) {
      if (buildData.isMinion()) {
        //after finished with a minion archive, create a symlink in the upstream artifact folder so that it becomes also available through there
        Cause.UpstreamCause upstreamCause = currentBuild.getCause(Cause.UpstreamCause)
        Run upstream = upstreamCause?.getUpstreamRun()

        if (upstream) {
          targetRootFolder = upstream.rootDir as String
        }
      } else {
        targetRootFolder = currentBuild.rootDir as String
      }
    }

    return targetRootFolder.trim()
  }

  /**
   * Determines the target folder to copy to
   * @return
   */
  String getArchivingTargetFolder(JobItem jobItem) {

    String targetFolder = currentBuild.artifactsDir

    if (buildData.isSet(BUILD_HOSTING_ROOT)) {
      targetFolder = getHostedRoot(buildData)

    } else if (buildData.isMinion()) {
      Cause.UpstreamCause upstreamCause = currentBuild.getCause(Cause.UpstreamCause)
      Run upstream = upstreamCause?.getUpstreamRun()

      if (upstream) {
        targetFolder = Paths.get(upstream.artifactsDir as String, jobItem.jobID) as String
      }
    }

    return Paths.get(targetFolder) as String
  }

/**
 * Checks if the target folder is accessible
 * @return
 */
  boolean isCopyToFolderAvailable() {
    final String targetRootFolder = getArchivingTargetRootFolder()
    final boolean locationIsAccessible = FileUtils.exists(targetRootFolder)
    return locationIsAccessible
  }

/**
 * Looks for the artifacts and does the actual archiving
 */
  boolean archiveArtifacts(JobItem jobItem) {
    final String rootFolder = jobItem.buildWorkDir
    final String targetFolder = getArchivingTargetFolder(jobItem)
    final String pattern = getSearchPattern()
    final String exclusionPattern = getSearchExcludePattern(Collections.emptyList())

    final String logPattern = simplifyLogEntries(rootFolder, pattern, ')|')
    final String logExclusionPattern = simplifyLogEntries(rootFolder, exclusionPattern, ')|')
    dsl.log.debug """
Starting search at '${rootFolder}' with:
- exclusion pattern: ${logExclusionPattern} 
- pattern: ${logPattern}
"""
    List<String> artifactPaths = FileUtils.findFiles(rootFolder, "regex:${pattern}", exclusionPattern)

    if (!artifactPaths.isEmpty()) {
      final String logArtifactPaths = simplifyLogEntries(rootFolder, artifactPaths.toString(), ', ')
      dsl.log.info """
Archiving artifacts by copying them to '${targetFolder}':
- files found(${artifactPaths.size()}): ${logArtifactPaths}
"""
      Map<String, FilePath> artifacts = [:]

      artifactPaths.each { String file ->
        FilePath filePath = FileUtils.create(file)
        String archiveFileName = filePath.getName()

        if (!artifacts[archiveFileName]) {
          artifacts[archiveFileName] = filePath
        } else {
          StringBuilder logMsg = new StringBuilder("A duplicate of '${archiveFileName}' has been found at '${filePath.getParent()}'.")

          FilePath existingPath = artifacts[archiveFileName]
          if (getCreationInstant(existingPath).isBefore(getCreationInstant(filePath))) {
            artifacts[archiveFileName] = filePath
            logMsg << " Replacing the file location from '${existingPath.getParent()}' to '${filePath.getParent()}'"
          }
          dsl.log.info logMsg
        }
      }

      artifacts.each { String archiveFileName, FilePath file ->
        FileUtils.shellCopy(file, FileUtils.create(Paths.get(targetFolder, archiveFileName) as String))
      }

      if (buildData.isSet(ARCHIVE_TO_JENKINS_MASTER) && buildData.isMinion()) {
       if (buildData.isSet(BUILD_HOSTING_ROOT)) {
         String artifactsDir = currentBuild.artifactsDir as String
         artifacts.keySet().each {
           FileUtils.createHardLink(artifactsDir, targetFolder, it)
         }
       } else {
         FileUtils.createSymLink(currentBuild.getRootDir() as String, targetFolder, 'archive')
       }
      }

    } else {
      dsl.log.info "No artifacts found with current pattern. Looking in '${rootFolder}'"
      return false
    }

    return true
  }

/**
 * Retrieves the file's creation datetime
 * @param path
 * @return
 */
  Instant getCreationInstant(FilePath filePath) {
    return FileUtils.getBasicAttributes(filePath).creationTime().toInstant()
  }

  String simplifyLogEntries(String rootFolder, String inString, String lineBreaker = null) {
    if (lineBreaker) {
      inString = inString.replace(lineBreaker, lineBreaker + '\n')
    }
    return inString.replace(rootFolder + '/', '')
  }

/**
 * determines the search regex pattern
 * @return
 */
  String getSearchPattern() {

    Map props = loadVersionsFromFile()

    props << buildData.buildProperties.collectEntries { k, v ->
      [(k): v ?: '']
    }

    String artifactPathGroupRegex = buildData.getString(ARCHIVING_PATH_GROUP_REGEX) ?: ''

    StringBuilder pattern = new StringBuilder()
    pattern << '('
    pattern << artifactPathGroupRegex
    pattern << getArtifactsNames()
      .join(")|(${artifactPathGroupRegex}")
      .replaceAll(/\$\{(.*?)\}/) { m, k ->

        String properVersion
        //jenkins sandbox gives different results than regular groovy
        if (k) {
          properVersion = props.get(k)
        } else if (m) {
          properVersion = props.get(m[1])
        }

        if (properVersion) {
          properVersion = properVersion.replace('BUILDTAG', buildData.getString(RELEASE_BUILD_NUMBER))
        }
        return properVersion

      }
      .replaceAll('-dist.', '(?:-dist)?.')

    pattern << ')'
  }

/**
 * Handles the exclusion pattern for the files search
 * @param excludedFoldersPaths
 * @return
 */
  String getSearchExcludePattern(List<String> excludedFoldersPaths) {

    String defaultExclusionRegex = buildData.getString(ARCHIVING_EXCLUDE_PATTERN) ?: ''

    StringBuilder pattern = new StringBuilder()
    pattern << defaultExclusionRegex

    if (excludedFoldersPaths) {
      if (defaultExclusionRegex) {
        pattern << '|'
      }
      pattern << '('
      pattern << excludedFoldersPaths.join(")|(")
      pattern << ')'
    }

    return pattern.toString()
  }

  Map loadVersionsFromFile() {
    String propsFile = "${buildData.getString(BUILD_CONFIG_ROOT_PATH)}/${buildData.getString(BUILD_VERSIONS_FILE)}"

    if (!FileUtils.exists(propsFile)) {
      propsFile = Paths.get(buildData.getString(BUILD_CONFIG_ROOT_PATH), 'version.properties') as String
    }

    Properties properties = new Properties()
    properties.load(new StringReader(dsl.readFile(file: propsFile, encoding: 'UTF-8') as String))

    return properties.collectEntries { k, v ->
      [(k): v]
    }
  }

  /**
   * Retrieves the target path on hosted
   * @return
   */
  static String getHostedRoot(BuildData buildData) {

    if (!buildData.isSet(BUILD_HOSTING_ROOT)) {
      return ''
    }

    return Paths.get(
      buildData.getString(BUILD_HOSTING_ROOT).trim(),
      buildData.getString(DEPLOYMENT_FOLDER).trim(),
      buildData.getString(RELEASE_BUILD_NUMBER).trim()
    ) as String
  }

}
