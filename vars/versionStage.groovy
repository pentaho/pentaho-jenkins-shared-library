/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import com.cloudbees.groovy.cps.NonCPS
import groovy.time.TimeCategory
import org.hitachivantara.ci.FilePathException
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.StringUtils
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.config.BuildData


import java.util.regex.Matcher

import static org.hitachivantara.ci.GroovyUtils.groupBy
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_CONFIG_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_ID_TAIL
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_VERSIONS_FILE
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_MODE
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_VERSION
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_VERSIONING
import static org.hitachivantara.ci.config.LibraryProperties.UNMAPPED_DEPENDENCIES_FAIL_BUILD
import static org.hitachivantara.ci.config.LibraryProperties.VERSION_COMMIT_FILES
import static org.hitachivantara.ci.config.LibraryProperties.VERSION_MERGER_LOG_LEVEL
import static org.hitachivantara.ci.config.LibraryProperties.VERSION_MERGER_VERSION
import static org.hitachivantara.ci.config.LibraryProperties.VERSION_MESSAGE
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

def call() {
  BuildData buildData = BuildData.instance
  stage(STAGE_LABEL_VERSIONING) {
    if (buildData.runVersioning) {
      utils.timer(
        {
          doVersioning(buildData)
        },
        { long duration ->
          buildData.time(STAGE_LABEL_VERSIONING, duration)
          log.info "${STAGE_LABEL_VERSIONING} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
        }
      )
    } else {
      utils.markStageSkipped()
      buildData.time(STAGE_LABEL_VERSIONING, 0)
    }
  }
}

void doVersioning(BuildData buildData) {
  // NOTE: this code works under the assumption that it's running on the node where
  // the pipeline was checked out, it will fail otherwise.
  // TODO: The version merger should be included into the jenkins plugin.

  if (!buildData.getBool(RUN_BUILDS)) {
    log.warn "RUN_BUILDS is false: No versioning performed."
    return
  }

  String versionsDir = buildData.getString(BUILD_CONFIG_ROOT_PATH)

  String versionsFilename = buildData.getString(BUILD_VERSIONS_FILE)
  String versionsFile = "${versionsDir}/${versionsFilename}"

  // Don't do anything if the versions file does not exist
  if (!FileUtils.exists(versionsFile)) {
    throw new FilePathException("Versions file does not exist: ${versionsFile}")
  }

  String vmGroupId = 'pentaho'
  String vmArtifactId = 'version-merger'
  String vmVersion = buildData.getString(VERSION_MERGER_VERSION)

  String vmJar = "${vmArtifactId}-${vmVersion}.jar"
  String vmJarPath = FileUtils.getPath(buildData.getString(WORKSPACE), vmJar)
  String vmUrl = "${buildData.getString('MAVEN_RESOLVE_REPO_URL')}/${vmGroupId}/${vmArtifactId}/${vmVersion}/${vmJar}"

  // download it if not already present locally
  if (!FileUtils.exists(vmJarPath)) {
    httpRequest(url: vmUrl, httpMode: 'GET', outputFile: vmJarPath)
  }

  boolean isReleaseMode = buildData.getBool(RELEASE_MODE)
  String releaseVersion = buildData.getString(RELEASE_VERSION)
  String buildIDTail = buildData.getString(BUILD_ID_TAIL)
  String releaseBuildVersion = "${releaseVersion}${buildIDTail}"

  String logBackConfiguration = createVersionMergerLogBackConfiguration()

  String vmCmd = [
    'java',
    "-Dlogback.configurationFile=${logBackConfiguration}",
    '-DMATCH_PATTERN="(.*_REVISION$)|(^dependency\\..+\\.revision$)|(.*\\.version$)"',
    "-DTARGET_FILES='${versionsFilename}'",
    "-DRELEASE_MODE=${isReleaseMode}",
    "-DADD_BUILD_ID_TAIL_VERSION=${buildIDTail}",
    "-jar ${vmJarPath}",
    '.',
    "-f '${versionsFile}'",
    'commit',
    "-version ${releaseVersion}"
  ].join(' ')

  Boolean unmappedDepsFailBuild = buildData.getBool(UNMAPPED_DEPENDENCIES_FAIL_BUILD)
  String logFile = FileUtils.getPath(buildData.getString(WORKSPACE), 'version-merger.log')

  dir(versionsDir) {
    logfile(logFile) {
      // Update version.properties in the resources config folder
      withEnv(["DEFAULT_LEVEL=${logLevel}"]) {
        retry(buildData.getInt(BUILD_RETRIES)) {
          BuilderUtils.process(vmCmd, this, unmappedDepsFailBuild)
        }
      }
    }
  }

  Properties properties = new Properties()
  properties.load(new StringReader(readFile(file: versionsFile, encoding: 'UTF-8') as String))

  // For each repo, run version merger
  List allJobItems = buildData.buildMap.collectMany { String key, List<JobItem> items -> items }
  Map<String,List> allJobItemsGroupedByScm = groupBy(allJobItems, { it.scmID })

  allJobItemsGroupedByScm.each { scmID , List jobItems ->
    jobItems.each { JobItem ji ->
      if (buildData.isNoop() || ji.isExecNoop() || !ji.checkout) {
        log.info "NoOp: Update versions: ${ji.jobID}"
      } else {
        String scmBranch = ji.scmBranch
        String versionProperty = StringUtils.fixNull(ji.versionProperty)
        if (!versionProperty) {
          log.warn "Unset versionProperty for JobItem ${ji.jobID}, defaulting to ${releaseBuildVersion}!"
        }
        String jobVersion = properties.getProperty(versionProperty, releaseBuildVersion)

        String vmJobCmd = [
          'java',
          "-Dlogback.configurationFile=${logBackConfiguration}",
          "-DRELEASE_MODE=${isReleaseMode}",
          "-jar '${vmJarPath}'",
          '.',
          "-f '${versionsFile}'",
          'commit',
          "project.revision=${jobVersion}",
          "project.version=${jobVersion}",
          "version=${jobVersion}",
          "distribution.version=${jobVersion}",
          "project.stage=${scmBranch}",
        ].join(' ')

        log.info "Updating versions for ${ji.jobID}"
        dir(ji.getBuildWorkDir()) {
          logfile(file: logFile, appendExisting: true) {
            withEnv(["DEFAULT_LEVEL=${logLevel}"]) {
              retry(buildData.getInt(BUILD_RETRIES)) {
                BuilderUtils.process(vmJobCmd, this, false)
              }
            }
          }
        }       
      }
    }

    List execJobItems = jobItems.findAll { JobItem ji -> !ji.execNoop }
    if (buildData.isNoop() || execJobItems.empty) {
      JobItem ji = jobItems.first()
      log.info "NoOp: Commit versions: ${ji.jobID}"
    } else {
      JobItem ji = execJobItems.first()
      // commit changes per scm
      dir(ji.getBuildWorkDir()) {
        Map scmInfo = ji.scmInfo + [credentials: ji.scmCredentials]
        utils.withGit(scmInfo) {
          List versionedFiles = buildData.getList(VERSION_COMMIT_FILES)

          sh("""
          git ls-files --modified | grep '${versionedFiles.join('\\|')}' | xargs git add 2> /dev/null ;

          if ! git diff-index --quiet --cached HEAD
          then
            git commit -v --untracked-files=no -m '${buildData.getString(VERSION_MESSAGE)}'
          fi
        """)
        }
      }
    }
  }

  if (isReleaseMode) {
    handleVersionMergerLog(logFile, buildData)
  }

}

void handleVersionMergerLog(String logFile, BuildData build) {
  final String logContent = readFile(logFile)

  String unmappedLogMessage = parseLogMessages(logContent)
  if (unmappedLogMessage) {
    job.setBuildUnstable()
    build.warning(unmappedLogMessage)
  }
}

@NonCPS
String parseLogMessages(String logContent) {
  Matcher matcher = (logContent =~ "((?s)(?i)(Property '.*?' value '.*?' is a snapshot version that will not be updated!))")
  Set<String> matches = []

  while (matcher.find()) {
    matches.add(matcher.group().trim())
  }

  if (matches.isEmpty()) {
    return ''
  }

  StringBuilder sb = new StringBuilder('Release not possible, you have unmapped dependencies on snapshot artifacts:\n')
  matches.each {
    sb << "\t- ${it}\n"
  }
  sb.toString()
}

String getLogLevel() {
  BuildData.instance.getString(VERSION_MERGER_LOG_LEVEL) ?: BuildData.instance.logLevel.label
}

String createVersionMergerLogBackConfiguration() {
  String resource = libraryResource 'logback-version-merger.xml'
  String configFile = FileUtils.getPath(FileUtils.create(BuildData.instance.getString(WORKSPACE), 'logback-version-merger.xml'))
  writeFile(file: configFile, text: resource, encoding: 'UTF-8')
  return configFile
}
