/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.jenkins

import hudson.model.Item
import hudson.model.ItemGroup
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.ConfigurationException
import org.hitachivantara.ci.config.ConfigurationMap
import org.jenkinsci.plugins.workflow.cps.CpsScript

import java.nio.file.Paths

import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_FILE
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_PLAN_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET
import static org.hitachivantara.ci.config.LibraryProperties.FIRST_JOB
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.IS_MULTIBRANCH_MINION
import static org.hitachivantara.ci.config.LibraryProperties.LAST_JOB
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.LOGS_TO_KEEP
import static org.hitachivantara.ci.config.LibraryProperties.MINIONS_FOLDER
import static org.hitachivantara.ci.config.LibraryProperties.MINION_BUILD_DATA_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.MINION_LOGS_TO_KEEP
import static org.hitachivantara.ci.config.LibraryProperties.MINION_PIPELINE_TEMPLATE
import static org.hitachivantara.ci.config.LibraryProperties.MINION_POLL_CRON_INTERVAL
import static org.hitachivantara.ci.config.LibraryProperties.OVERRIDE_JOB_PARAMS
import static org.hitachivantara.ci.config.LibraryProperties.OVERRIDE_PARAMS
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL
import static org.hitachivantara.ci.config.LibraryProperties.PR_SLACK_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_CHANNEL
import static org.hitachivantara.ci.config.LibraryProperties.SLACK_INTEGRATION
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_ARCHIVING
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_BUILD
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_CHECKOUT
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_UNIT_TEST
import static org.hitachivantara.ci.config.LibraryProperties.USE_MINION_JOBS
import static org.hitachivantara.ci.config.LibraryProperties.USE_MINION_MULTIBRANCH_JOBS
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

class MinionHandler {

  static Script getSteps() {
    ({} as CpsScript)
  }

  /**
   * Manage minion job creation and update
   */
  static void manageJobs() {
    BuildData buildData = BuildData.instance

    // collect job items
    List<JobItem> allJobItems = buildData.buildMap.collectMany { String key, List<JobItem> value -> value }
    List<JobItem> workJobItems = allJobItems.findAll { JobItem ji -> !ji.execNoop }

    try {
      // generate minion build data
      steps.dir(getBuildDataPath()) {
        workJobItems.each { JobItem ji ->
          String filename = getBuildDataFilename(ji)
          Map data = getYamlData(ji)

          steps.log.debug "Creating minion build file ${filename}"

          steps.delete(path: filename)
          steps.writeYaml(file: filename, data: data)
        }
      }

      // the minion jobs are created inside a configurable folder
      String rootFolderPath = getRootFolderPath()

      // use the defined template or the default ones
      Map templateSource
      if(buildData.isSet(MINION_PIPELINE_TEMPLATE)){
        templateSource = [file: buildData.getString(MINION_PIPELINE_TEMPLATE)]
      } else {
        templateSource = [text: getDefaultPipelineTemplate()]
      }

      // add all the current build libraries
      List libraries = JobUtils.getLoadedLibraries(steps.currentBuild)

      workJobItems.each { JobItem item ->
        steps.log.debug "Creating minion job for ${item.jobID}"

        String script = steps.resolveTemplate(templateSource + [
          parameters: [
            libraries : libraries,
            properties: new ConfigurationMap(buildData.buildProperties, [
              (BUILD_DATA_ROOT_PATH): getBuildDataPath(),
              (BUILD_DATA_FILE)     : getBuildDataFilename(item),
            ]),
            item: item.export()
          ]
        ])

        Map jobConfig = [
          name: getJobName(item),
          folder: rootFolderPath,
          script: script
        ]

        if (buildData.getBool(USE_MINION_MULTIBRANCH_JOBS)) {
          jobConfig << [
            multibranch: true,
            scmConfig: [
              organization  : item.scmOrganization,
              repository    : item.scmRepository,
              credentials   : item.scmCredentials,
              branches      : item.scmBranch,
              markerFile    : Paths.get(item.root ?: '', item.buildFile ?: item.buildFramework.buildFile) as String,
              scanInterval  : item.scmScanInterval,
              prStatusLabel : item.prStatusLabel,
              prReportStatus: item.prReportStatus,
              prScan        : item.prScan,
              prMerge       : item.prMerge
            ]
          ]
        } 
        
        steps.createJob(jobConfig)
      }

      // cleanup the minion folder
      ItemGroup minionFolder = JobUtils.getFolder(rootFolderPath)
      List minionJobNames = allJobItems.collect { JobItem ji -> getJobName(ji) }
      List existingMinions = minionFolder.getItems()

      existingMinions.each { Item item ->
        if (!minionJobNames.contains(item.name)) {
          item.delete()
          steps.log.debug("Deleted '${item.name}'")
        }
      }
    } catch (Throwable e) {
      throw new JobHandlingException('Minion job management failed', e)
    }
  }

  /**
   * Return the default template for minion pipelines
   * @return
   */
  static String getDefaultPipelineTemplate(){
    BuildData buildData = BuildData.instance

    if (buildData.getBool(USE_MINION_MULTIBRANCH_JOBS)) {
      steps.libraryResource resource: 'templates/minion-multibranch-pipeline-default.vm', encoding: 'UTF-8'
    } else {
      steps.libraryResource resource: 'templates/minion-pipeline-default.vm', encoding: 'UTF-8'
    }
  }

  /**
   * Retrieves the root folder path
   * @return
   */
  static String getRootFolderPath() {
    BuildData buildData = BuildData.instance
    String rootFolderName = buildData.getString(MINIONS_FOLDER)

    if (!rootFolderName) {
      throw new ConfigurationException("Could not retrieve the minion job folder: ${MINIONS_FOLDER} property is required!")
    }

    // sanitize it a bit...
    rootFolderName = rootFolderName.trim().toLowerCase().replaceAll("[\\s-]+", "-")
    return rootFolderName
  }

  /**
   * Retrieves the location to write and read from the temporary yaml files
   * @return
   */
  static String getBuildDataPath() {
    BuildData buildData = BuildData.instance
    return buildData.getString(MINION_BUILD_DATA_ROOT_PATH) ?: buildData.getString(WORKSPACE) + '/minions'
  }

  /**
   * Get job item build data filename
   * @param jobItem
   * @return
   */
  static String getBuildDataFilename(JobItem jobItem) {
    getJobName(jobItem) + '.yaml'
  }

  /**
   * Get the job item job name
   * @param jobItem
   * @return
   */
  static String getJobName(JobItem jobItem) {
    jobItem.jobID
  }

  /**
   * Get the full job item job name
   * @param jobItem
   * @return
   */
  static String getFullJobName(JobItem jobItem) {
    "${getRootFolderPath()}/${getJobName(jobItem)}"
  }

  /**
   * Generate yaml data for a minion
   * @param jobItem
   * @return
   */
  static Map getYamlData(JobItem jobItem) {
    BuildData buildData = BuildData.instance

    Map buildProperties = new ConfigurationMap()
    buildProperties << buildData.pipelineProperties
    buildProperties << buildData.pipelineParams.findAll { String k, v ->
      !(k in [OVERRIDE_PARAMS, OVERRIDE_JOB_PARAMS])
    }

    buildProperties.put(BUILD_PLAN_ID, jobItem.jobID)
    buildProperties.put(USE_MINION_JOBS, false)
    buildProperties.put(USE_MINION_MULTIBRANCH_JOBS, false)
    buildProperties.put(IS_MINION, true)
    buildProperties.put(IS_MULTIBRANCH_MINION, buildData.getBool(USE_MINION_MULTIBRANCH_JOBS))
    buildProperties.put(PR_STATUS_REPORTS, jobItem.prReportStatus)
    buildProperties.put(LOGS_TO_KEEP, buildData.getString(MINION_LOGS_TO_KEEP))
    buildProperties.put(POLL_CRON_INTERVAL, buildData.getString(MINION_POLL_CRON_INTERVAL))
    buildProperties.put(IGNORE_PIPELINE_FAILURE, false)
    buildProperties.put(FIRST_JOB, null)
    buildProperties.put(LAST_JOB, null)
    buildProperties.put(RELEASE_BUILD_NUMBER, buildData.getString(RELEASE_BUILD_NUMBER))
    buildProperties.put(SLACK_CHANNEL, jobItem.slackChannel)
    buildProperties.put(PR_SLACK_CHANNEL, jobItem.prSlackChannel)

    return [
      buildProperties: buildProperties.getRawMap(),
      jobGroups      : [
        (jobItem.jobGroup): [
          jobItem.export(true)
        ]
      ]
    ]
  }

  /**
   * Sets the different stages that a minion job went through in the build's description
   *
   * @param currentBuild
   * @param buildData
   */
  static void setBuildDisplayName(currentBuild) {
    List<String> suffixes = []
    BuildData buildData = BuildData.instance

    // do not label multibranch minions
    if (buildData.getBool(IS_MULTIBRANCH_MINION)) {
      return
    }

    if (buildData.getBool(RUN_CHECKOUTS)) {
      suffixes.add(STAGE_LABEL_CHECKOUT)
    }
    if (buildData.getBool(RUN_BUILDS)) {
      suffixes.add(STAGE_LABEL_BUILD)
    }
    if (buildData.getBool(RUN_UNIT_TESTS)) {
      suffixes.add(STAGE_LABEL_UNIT_TEST)
    }
    if (buildData.getBool(ARCHIVE_ARTIFACTS)) {
      suffixes.add(STAGE_LABEL_ARCHIVING)
    }
    if (!suffixes.isEmpty()) {
      currentBuild.displayName = "${currentBuild.displayName}: ${suffixes.join(' / ')}"
    }
  }

  /**
   * Minion specific configuration sanitization
   * @param buildProperties
   */
  static void sanitize(Map buildProperties) {
    // update build cache path for Multibranch building a Pull Request, use the target branch cache
    if (buildProperties[CHANGE_ID]) {
      buildProperties[LIB_CACHE_ROOT_PATH] = "${buildProperties[LIB_CACHE_ROOT_PATH]}/${buildProperties[CHANGE_TARGET]}"
    }
    // update build cache path for Multibranch building a Base Branch, use the same cache for all
    else if (buildProperties[BRANCH_NAME]) {
      buildProperties[LIB_CACHE_ROOT_PATH] = "${buildProperties[LIB_CACHE_ROOT_PATH]}/${buildProperties[BRANCH_NAME]}"
    }

    // when in a multibranch minion build, check if it's a PR or a branch build to see which slack channel to send
    // a notification if intended
    if (buildProperties[IS_MULTIBRANCH_MINION]) {
      def slackChannel = buildProperties[CHANGE_ID] ? buildProperties[PR_SLACK_CHANNEL] : buildProperties[SLACK_CHANNEL]
      if (slackChannel) {
        buildProperties[SLACK_INTEGRATION] = true
        buildProperties[SLACK_CHANNEL] = slackChannel
        buildProperties[BUILD_PLAN_ID] = (buildProperties[BUILD_PLAN_ID] + '@' + buildProperties[BRANCH_NAME]) as String
        buildProperties[RELEASE_BUILD_NUMBER] = buildProperties[BUILD_NUMBER]
      }
    }
  }

}