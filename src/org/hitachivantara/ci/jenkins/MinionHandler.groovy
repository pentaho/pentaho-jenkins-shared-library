package org.hitachivantara.ci.jenkins

import hudson.model.Item
import hudson.model.ItemGroup
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.ConfigurationException
import org.hitachivantara.ci.config.FilteredMapWithDefault
import org.jenkinsci.plugins.workflow.cps.CpsScript

import java.nio.file.Paths

import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_FILE
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_DATA_ROOT_PATH
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
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS
import static org.hitachivantara.ci.config.LibraryProperties.RELEASE_BUILD_NUMBER
import static org.hitachivantara.ci.config.LibraryProperties.RUN_AUDIT
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
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

      workJobItems.each { JobItem ji ->
        steps.log.debug "Creating minion job for ${ji.jobID}"

        String script = steps.resolveTemplate(templateSource + [
          parameters: [
            libraries : libraries,
            properties: new FilteredMapWithDefault(buildData.buildProperties, [
              (BUILD_DATA_ROOT_PATH): getBuildDataPath(),
              (BUILD_DATA_FILE)     : getBuildDataFilename(ji),
              (RUN_CHECKOUTS)       : buildData.getBool(RUN_CHECKOUTS) && ji.checkout,
              (RUN_UNIT_TESTS)      : buildData.getBool(RUN_UNIT_TESTS) && ji.testable,
              (RUN_AUDIT)           : buildData.getBool(RUN_AUDIT) && ji.auditable,
              (ARCHIVE_ARTIFACTS)   : buildData.getBool(ARCHIVE_ARTIFACTS) && ji.archivable
            ])
          ]
        ])

        Map jobConfig = [
          name: getJobName(ji),
          folder: rootFolderPath,
          script: script
        ]

        if (buildData.getBool(USE_MINION_MULTIBRANCH_JOBS)) {
          jobConfig << [
            multibranch: true,
            scmConfig: [
              organization  : ji.scmOrganization,
              repository    : ji.scmRepository,
              credentials   : ji.scmCredentials,
              branches      : ji.scmBranch,
              markerFile    : Paths.get(ji.root ?: '', ji.buildFile ?: ji.buildFramework.buildFile) as String,
              scanInterval  : ji.scmScanInterval,
              prStatusLabel : ji.prStatusLabel,
              prReportStatus: ji.prReportStatus,
              prScan        : ji.prScan,
              prMerge       : ji.prMerge
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
   * Generate yaml data a minion
   * @param jobItem
   * @return
   */
  static Map getYamlData(JobItem jobItem) {
    BuildData buildData = BuildData.instance

    Map buildProperties = [:]
    buildProperties << buildData.pipelineProperties
    buildProperties << buildData.pipelineParams
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

    return [
      buildProperties: buildProperties,
      jobGroups      : [
        (jobItem.jobGroup): [
          jobItem.export()
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
  }

}