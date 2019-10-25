/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.config

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.LogLevel
import org.hitachivantara.ci.PrettyPrinter
import org.hitachivantara.ci.report.BuildStatus

import java.time.Clock

import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_TIMEOUT
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_ALL_CACHES
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_BUILD_WORKSPACE
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_CACHES_REGEX
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_SCM_WORKSPACES
import static org.hitachivantara.ci.config.LibraryProperties.CREATE_TAG
import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.IS_MULTIBRANCH_MINION
import static org.hitachivantara.ci.config.LibraryProperties.NOOP
import static org.hitachivantara.ci.config.LibraryProperties.PUSH_CHANGES
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_VERSION
import static org.hitachivantara.ci.config.LibraryProperties.RUN_AUDIT
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_DEPENDENCY_CHECK
import static org.hitachivantara.ci.config.LibraryProperties.RUN_NEXUS_LIFECYCLE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_VERSIONING
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_NAME
import static org.hitachivantara.ci.config.LibraryProperties.TAG_SKIP_SNAPSHOT
import static org.hitachivantara.ci.config.LibraryProperties.USE_MINION_JOBS
import static org.hitachivantara.ci.config.LibraryProperties.USE_MINION_MULTIBRANCH_JOBS

@Singleton
class BuildData implements Serializable {

  /**
   * Clock implementation to get date/time from
   */
  Clock clock = new BuildClock()

  /**
   * Current build logging level
   */
  LogLevel logLevel = LogLevel.INFO

  /**
   * Holds JobItems in the groups defined in the configuration
   */
  Map<String, List<JobItem>> buildMap = [:]

  /**
   * Holds the JobItems for the preStage
   */
  Map<String, List<JobItem>> preBuildMap = [:]

  /**
   * Holds the JobItems for the postStage
   */
  Map<String, List<JobItem>> postBuildMap = [:]

  /**
   * Holds the default configuration for all jobs
   *
   * This is not meant to be used unless you want a raw version of the default configuration defined
   * for all the the jobs through the default config yaml file.
   */
  Map<String, Object> globalProperties = [:]

  /**
   * Holds the the params for this particular pipeline job execution.
   *
   * This is not meant to be used unless you want a raw version of the params defined
   * for the job execution.
   */
  Map<String, Object> pipelineParams = [:]

  /**
   * Holds the the config for this particular pipeline job without considering defaults.
   *
   * This is not meant to be used unless you want a raw version of the configuration defined
   * for the job through the yaml file and the params.
   */
  Map<String, Object> pipelineProperties = [:]

  /**
   * Holds the resolved configuration for this pipeline with defaults included
   */
  Map<String, Object> buildProperties = [:]

  /**
   * Holds the build's status messages and errors
   */
  BuildStatus buildStatus = new BuildStatus()

  /*****************************************/
  /*** Build Properties shortcut getters ***/
  /*****************************************/

  Boolean isSet(String property){
    !(buildProperties.get(property) in [null, ''])
  }

  Object get(String property){
    buildProperties.get(property)
  }

  Integer getInt(String property) {
    (buildProperties as FilteredMapWithDefault).getInt(property)
  }

  Double getDouble(String property) {
    (buildProperties as FilteredMapWithDefault).getDouble(property)
  }

  Boolean getBool(String property) {
    (buildProperties as FilteredMapWithDefault).getBool(property)
  }

  String getString(String property) {
    (buildProperties as FilteredMapWithDefault).getString(property)
  }

  List getList(String property) {
    (buildProperties as FilteredMapWithDefault).getList(property)
  }

  /******************************************/
  /*** Stage Execution validation methods ***/
  /******************************************/

  Boolean isNoop() {
    getBool(NOOP)
  }

  Boolean isRunBuilds() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(RUN_BUILDS)
  }

  Boolean isRunCheckouts() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(RUN_CHECKOUTS)
  }

  Boolean isRunPush() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(PUSH_CHANGES)
  }

  Boolean isRunSecurity() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) &&
      (getBool(RUN_NEXUS_LIFECYCLE) || getBool(RUN_DEPENDENCY_CHECK))
  }

  Boolean isRunAudit() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(RUN_AUDIT)
  }

  Boolean isRunTag() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(CREATE_TAG) &&
        !(getBool(TAG_SKIP_SNAPSHOT) && getString(RELEASE_VERSION).contains('SNAPSHOT'))
  }

  Boolean isRunUnitTests() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(RUN_UNIT_TESTS)
  }

  Boolean isRunPreClean() {
    !getBool(NOOP) &&
        (isSet(CLEAN_CACHES_REGEX) || getBool(CLEAN_ALL_CACHES) || getBool(CLEAN_SCM_WORKSPACES))
  }

  Boolean isRunPostClean() {
    !getBool(NOOP) && getBool(CLEAN_BUILD_WORKSPACE)
  }

  Boolean isRunVersioning() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(RUN_VERSIONING)
  }

  Boolean isRunArchiving() {
    !getBool(NOOP) && !getBool(USE_MINION_MULTIBRANCH_JOBS) && getBool(ARCHIVE_ARTIFACTS)
  }

  /****************************************/
  /*** Build Status registering methods ***/
  /****************************************/

  void time(String stage = buildProperties[STAGE_NAME], item, data) {
    buildStatus.time(stage, item, data)
  }

  void time(String stage = buildProperties[STAGE_NAME], data) {
    buildStatus.time(stage, data)
  }

  void error(String stage = buildProperties[STAGE_NAME], item, data) {
    buildStatus.error(stage, item, data)
  }

  void error(String stage = buildProperties[STAGE_NAME], data) {
    buildStatus.error(stage, data)
  }

  void warning(String stage = buildProperties[STAGE_NAME], item, data) {
    buildStatus.warning(stage, item, data)
  }

  void warning(String stage = buildProperties[STAGE_NAME], String message) {
    buildStatus.warning(stage, message)
  }

  void release(String stage = buildProperties[STAGE_NAME], data) {
    buildStatus.release(stage, data)
  }

  /*********************/
  /*** Other methods ***/
  /*********************/

  List<JobItem> getAllItems(){
    buildMap.collectMany { String key, List<JobItem> items -> items }
  }

  /**
   * Get overall timeout in minutes
   * @return
   */
  Integer getTimeout() {
    getInt(BUILD_TIMEOUT)
  }

  Boolean isUseMinions() {
    getBool(USE_MINION_JOBS) || getBool(USE_MINION_MULTIBRANCH_JOBS)
  }

  Boolean isMinion() {
    getBool(IS_MINION) || getBool(IS_MULTIBRANCH_MINION)
  }

  Boolean isArchiveTests(){
    // we should no longer need this check once we fix the repos so that we don't scan directories multiple times,
    // then we should be able to archive tests both at the minion and orchestrator level
    !getBool(IS_MINION) || getBool(IS_MULTIBRANCH_MINION)
  }

  /**
   * Check if the current job is building a pull request.
   *
   * For a multibranch project corresponding to some kind of change request,
   * CHANGE_ID will be set to the change ID, such as a pull request number, if supported; else unset.
   * @return
   */
  Boolean isPullRequest() {
    isSet(CHANGE_ID)
  }

  /**
   * Check if the current job is a multibranch build.
   *
   * For a multibranch project, BRANCH_NAME will be set to the name of the branch being built, for example in case you
   * wish to deploy to production from master but not from feature branches; if corresponding to some kind of
   * change request, the name is generally arbitrary (refer to CHANGE_ID and CHANGE_TARGET).
   * @return
   */
  Boolean isMultibranch() {
    isSet(BRANCH_NAME)
  }

  @Override
  @NonCPS
  String toString() {
    StringBuilder sb = new StringBuilder()

    sb << 'Resolved build properties:\n'
    sb << new PrettyPrinter(buildProperties.sort()).toPrettyPrint()
    sb << '\n\n'

    appendPlan(sb, 'Pre stage plan:\n', preBuildMap)
    appendPlan(sb, '\nJob execution plan:\n', buildMap)
    appendPlan(sb, '\nPost stage plan:\n', postBuildMap)

    return sb.toString()
  }

  @NonCPS
  private void appendPlan(StringBuilder sb, String header, Map<String, List<JobItem>> dataMap) {
    sb << header

    boolean first = true
    dataMap.each { String group, List<JobItem> jobItems ->
      if (first) first = false
      else sb << '\n\n'
      sb << "[${group}]\n"
      sb << new PrettyPrinter(jobItems).incrementIndent().toPrettyPrint()
    }
  }

  /**
   * For testing purposes
   */
  void reset() {
    preBuildMap = [:]
    postBuildMap = [:]
    buildMap = [:]
    globalProperties = [:]
    pipelineParams = [:]
    pipelineProperties = [:]
    buildProperties = [:]
    buildStatus = new BuildStatus()
    clock = new BuildClock()
    logLevel = LogLevel.INFO
  }

}
