/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuildFrameworkException
import org.hitachivantara.ci.config.ConfigurationMap

import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.hitachivantara.ci.StringUtils.normalizeString

import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.BUILDS_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID

class JobItem implements Serializable {

  // Build execution types
  static enum ExecutionType {
    AUTO,             // build only the changes
    AUTO_DOWNSTREAMS, // build the current changes in the job Group, but FORCE every next job Groups
    FORCE,            // build no matter what
    NOOP              // don't build
  }

  // SCM connection types
  static enum SCMConnectionType {
    HTTP,   // http/https
    SSH,    // ssh
    LOCAL   // local path (TODO: not yet supported)
  }

  private static Map<SCMConnectionType, Pattern> GIT_URL_PATTERNS = [
      (SCMConnectionType.HTTP): Pattern.compile(/https?:\/\/([\w-\.]+)\/(?<org>[\w-]+)\/(?<repo>[\w-]+)\.git/),
      (SCMConnectionType.SSH) : Pattern.compile(/git@([\w-\.]+):(?<org>[\w-]+)\/(?<repo>[\w-]+)\.git/)
  ]

  private ConfigurationMap<String, Object> data
  private ConfigurationMap<String, Object> buildProperties

  // User configurable properties
  private static final List configurable = [
    'jobID',
    'archivable',
    'asynchronous',
    'atomicScmCheckout',
    'auditable',
    'buildFile',
    'buildFramework',
    'createRelease',
    'directives',
    'dockerImage',
    'dockerSonarImage',
    'execType',
    'parallelize',
    'passOnBuildParameters',
    'prDirectives',
    'previousReleaseTag',
    'prExecType',
    'prMerge',
    'properties',
    'prReportStatus',
    'prScan',
    'prSlackChannel',
    'prStatusLabel',
    'root',
    'scmBranch',
    'scmCacheUrl',
    'scmCredentials',
    'scmPoll',
    'scmProtectBranch',
    'scmRevision',
    'scmScanInterval',
    'scmUrl',
    'script',
    'securityScannable',
    'settingsFile',
    'slackChannel',
    'targetJobName',
    'testable',
    'testsArchivePattern',
    'timeout',
    'versionProperty',
  ]

  JobItem(Map jobData, Map buildProperties = [:]) {
    this('jobs', jobData, buildProperties)
  }

  JobItem(String jobGroup, Map jobData, Map buildProperties = [:]) {
    this.buildProperties = buildProperties
    Map jobDefaults = buildProperties.JOB_ITEM_DEFAULTS ?: [:]

    // init
    data = new ConfigurationMap(buildProperties)

    // set given configuration
    configurable.each { String configKey ->
      if (jobData.containsKey(configKey)) {
        // use given data
        set(configKey, jobData[configKey])
      } else if (jobDefaults.containsKey(configKey)) {
        // use default properties defaults
        set(configKey, jobDefaults[configKey])
      }
    }

    data.jobGroup = jobGroup
    data.scmInfo = parseSCM(data.scmUrl as String)

    data.scmID = ''
    if (data.scmInfo) {
      data.scmID += "${data.scmInfo.organization}.${data.scmInfo.repository}.${data.scmBranch}"
      if (data.atomicScmCheckout) data.scmID += "~${jobGroup}.${data.jobID}"

      data.scmID = normalizeString(data.scmID)
    }

    Path buildsDir = Paths.get(data.getString(BUILDS_ROOT_PATH))
    Path checkoutDir = buildsDir.resolve(data.getString('scmID'))
    Path buildWorkDir = checkoutDir.resolve(data.getString('root'))

    data.checkoutDir = checkoutDir as String
    data.buildWorkDir = buildWorkDir as String
  }

  @NonCPS
  void setExecType(type) {
    switch (type) {
      case String:
        data.execType = ExecutionType.valueOf(type.toUpperCase() as String)
        break
      case ExecutionType:
        data.execType = type
        break
      default:
        throw new ExecutionTypeException("Invalid Execution Type $type")
    }
  }

  @NonCPS
  void setRoot(String root) {
    if (!root || root == '.') {
      data.root = ''
    } else {
      data.root = root
    }
  }

  @NonCPS
  void setBuildFramework(framework) {
    if (!framework) return
    switch (framework) {
      case String:
        data.buildFramework = BuildFramework.valueOf(framework.toUpperCase() as String)
        break
      case BuildFramework:
        data.buildFramework = framework
        break
      default:
        throw new BuildFrameworkException("Invalid Build Framework $framework")
    }
  }

  @NonCPS
  void setScmBranch(String scmBranch) {
    // if BRANCH_NAME is set means we are in a multibranch scenario, use that
    data.scmBranch = buildProperties[BRANCH_NAME] ?: scmBranch
  }

  @NonCPS
  void setDirectives(directives) {
    if (directives instanceof Map) {
      data.directives = new ConfigurationMap(data, directives)
    } else {
      data.directives = directives as String
    }
  }

  @NonCPS
  void setPrDirectives(directives) {
    if (directives instanceof Map) {
      data.prDirectives = new ConfigurationMap(data, directives)
    } else {
      data.prDirectives = directives as String
    }
  }

  /**
   * Use the setter if it's available, set on the data directly if not
   * this allows us to do extra treatment on input in the setter if needed.
   *
   * NOTE: setters need to be marked with @NonCPS or initialization won't work
   *
   * @param property
   * @param value
   */
  @NonCPS
  void set(String property, value) {
    if (this.respondsTo("set${property.capitalize()}")) {
      this[property] = value
    } else {
      data[property] = value
    }
  }

  void setJobData(Map inData) {
    inData.each { String key, value ->
      set(key, value)
    }
  }

  String getJobGroup() {
    data.jobGroup
  }

  String getJobID() {
    data.jobID
  }

  String getScmUrl() {
    data.scmUrl
  }

  String getScmCacheUrl() {
    data.scmCacheUrl
  }

  String getScmBranch() {
    data.scmBranch
  }

  String getScmRevision() {
    data.scmRevision
  }

  Boolean getScmPoll() {
    data.scmPoll
  }

  Boolean isScmProtectBranch() {
    data.scmProtectBranch
  }

  String getScmCredentials() {
    data.scmCredentials
  }

  Map getScmInfo() {
    data.scmInfo as Map
  }

  void setChangeLog(List changelog) {
    scmInfo.changeLog = changelog
  }

  List<String> getChangeLog() {
    scmInfo.changeLog
  }

  Boolean hasChangeLog() {
    scmInfo.containsKey('changeLog')
  }

  String getScmOrganization() {
    scmInfo.organization
  }

  String getScmRepository() {
    scmInfo.repository
  }

  String getScmScanInterval(){
    data.scmScanInterval
  }

  BuildFramework getBuildFramework() {
    data.buildFramework as BuildFramework
  }

  String getDirectives(String id = null) {
    if (isPullRequest() && data.prDirectives) {
      if (id && data.prDirectives instanceof Map) {
        return data.prDirectives[id]
      }
      return data.prDirectives
    } else {
      if (id && data.directives instanceof Map) {
        return data.directives[id]
      }
      return data.directives
    }
  }

  String updateDirectives(String id = null, String directives) {
    if (isPullRequest()) {
      if (id && data.prDirectives instanceof Map) {
        data.prDirectives[id] = directives
      } else {
        data.prDirectives = directives
      }
    } else {
      if (id && data.directives instanceof Map) {
        data.directives[id] = directives
      } else {
        data.directives = directives
      }
    }
  }

  String getBuildFile() {
    data.buildFile
  }

  String getRoot() {
    data.root
  }

  ExecutionType getExecType() {
    ((isPullRequest() && data.prExecType) ? data.prExecType : data.execType) as ExecutionType
  }

  String getVersionProperty() {
    data.versionProperty
  }

  String getSettingsFile() {
    data.settingsFile
  }

  Boolean isTestable() {
    data.testable
  }

  String getTestsArchivePattern() {
    data.testsArchivePattern
  }

  String getScmID() {
    data.scmID
  }

  void setScmID(String ID) {
    data.scmID = ID
  }

  String getBuildWorkDir() {
    data.buildWorkDir
  }

  void setBuildWorkDir(String dir) {
    data.buildWorkDir = dir
  }

  String getCheckoutDir() {
    data.checkoutDir
  }

  void setCheckoutDir(String checkoutDir) {
    data.checkoutDir = checkoutDir
  }

  Boolean isExecForce() {
    execType == ExecutionType.FORCE
  }

  Boolean isExecAuto() {
    execType == ExecutionType.AUTO || execType == ExecutionType.AUTO_DOWNSTREAMS
  }

  Boolean isExecNoop() {
    execType == ExecutionType.NOOP
  }

  Boolean isArchivable() {
    data.archivable
  }

  Boolean isAuditable() {
    data.auditable
  }

  Boolean isParallel() {
    data.parallelize
  }

  void setSkip(Boolean skip) {
    data.skip = skip
  }

  Boolean isSkip() {
    data.skip
  }

  String getDockerImage() {
    data.dockerImage
  }

  String getDockerSonarImage() {
    data.dockerSonarImage
  }

  Boolean isContainerized() {
    data.dockerImage
  }

  Boolean getPrReportStatus(){
    data.prReportStatus
  }

  Boolean getPrMerge(){
    data.prMerge
  }

  Boolean getPrScan(){
    data.prScan
  }

  String getPrStatusLabel(){
    data.prStatusLabel
  }

  void setModulePaths(String... paths) {
    // TODO: consider removing this when we have a proper way to not report duplicated tests,
    // maybe using a later stage for archiving tests?
    data.modulePaths = paths
  }

  String[] getModulePaths() {
    // TODO: consider removing this when we have a proper way to not report duplicated tests,
    // maybe using a later stage for archiving tests?
    data.modulePaths
  }

  JobItem clone() {
    Map jobData = configurable.collectEntries { String configKey ->
      [(configKey): data[configKey]]
    }
    // add SCM related info
    JobItem copy = new JobItem(this.jobGroup, jobData, buildProperties)
    ScmUtils.copyCheckoutMetadata(this, copy)
    return copy
  }

  Boolean isAsynchronous() {
    data.asynchronous
  }

  Map getJobProperties() {
    data.properties as Map
  }

  Boolean isCheckout() {
    !execNoop && !(buildFramework in [BuildFramework.JENKINS_JOB, BuildFramework.DSL_SCRIPT])
  }

  String getTargetJobName() {
    data.targetJobName
  }

  Boolean isPassOnBuildParameters() {
    data.passOnBuildParameters
  }

  Boolean isAtomicScmCheckout(){
    data.atomicScmCheckout
  }

  Boolean isSecurityScannable() {
    data.securityScannable
  }

  Boolean isCreateRelease() {
    data.createRelease
  }

  String getPreviousReleaseTag(){
    data.previousReleaseTag
  }

  String getScript() {
    data.script
  }

  def getSlackChannel() {
    data.slackChannel
  }

  def getPrSlackChannel() {
    data.prSlackChannel
  }
  /**
   * Get timeout in minutes
   * @return
   */
  Integer getTimeout() {
    data.timeout as Integer
  }

  @Override
  @NonCPS
  String toString() {
    return new PrettyPrinter(getJobData().sort(getComparator()))
      .toPrettyPrint()
  }

  /**
   * Builds a closure comparator that sorts its keys in a natural order,
   * but starting with jobID
   */
  @NonCPS
  private Closure getComparator() {
    { Map.Entry o1, Map.Entry o2 ->
      if (o1.key == o2.key) {
        return 0
      } else if (o1.key == 'jobID') {
        return -1
      } else if (o2.key == 'jobID') {
        return 1
      }
      return o1.key <=> o2.key
    }
  }

  @NonCPS
  Map getJobData() {
    data
  }

  Map export(Boolean raw = false) {
    (raw ? data.getRawMap() : data).subMap(configurable)
  }

  @NonCPS
  private static Map parseSCM(String scmUrl) {
    if (!scmUrl) return [:] // is empty a good value here?

    String repo, org
    SCMConnectionType connectionType

    GIT_URL_PATTERNS.each { SCMConnectionType scmConnType, Pattern pattern ->
      Matcher urlMatcher = (scmUrl =~ pattern)

      if (urlMatcher.matches()) {
        repo = urlMatcher.group('repo')
        org = urlMatcher.group('org')
        connectionType = scmConnType
      }
    }

    if (!repo || !org || !connectionType) {
      throw new ScmException("Sorry, I don't know how to handle this kind of scm URL yet!\n  ${scmUrl}")
    }

    return [
        scm         : 'git',
        connection  : connectionType,
        organization: org,
        repository  : repo,
        fullName    : "${org}/${repo}".toString()
    ]
  }

  Boolean isPullRequest() {
    buildProperties[CHANGE_ID] as boolean
  }
}
