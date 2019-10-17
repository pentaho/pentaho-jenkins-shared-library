/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.config.LibraryProperties.DEPENDENCY_CHECK_REPORT_PATTERN
import static org.hitachivantara.ci.config.LibraryProperties.DEPENDENCY_CHECK_SCAN_PATTERN
import static org.hitachivantara.ci.config.LibraryProperties.DEPENDENCY_CHECK_SUPPRESSION_PATH
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.NEXUS_IQ_STAGE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_SECURITY_SCAN_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_DEPENDENCY_CHECK
import static org.hitachivantara.ci.config.LibraryProperties.RUN_NEXUS_LIFECYCLE
import static org.hitachivantara.ci.config.LibraryProperties.SLAVE_NODE_LABEL
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_SECURITY
import static org.hitachivantara.ci.config.LibraryProperties.VULNERABILITY_DATABASE_PATH

// TODO: repurpose this to the new Audit stage

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runSecurity) {
    utils.timer('Security scan') {
      doSecurity(buildData)
    }
  } else {
    utils.createStageSkipped(STAGE_LABEL_SECURITY)
  }
}

void doSecurity(BuildData buildData) {
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

  // Collect all job items into a single list with distinct checkouts excluding noop
  List jobItems = buildData.buildMap.collectMany {
    String key, List value -> value.findAll { JobItem ji -> !ji.execNoop && ji.securityScannable }
  }

  // no jobItems to work, leave
  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_SECURITY)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt(PARALLEL_SECURITY_SCAN_CHUNKSIZE) ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize)
  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_SECURITY : "${STAGE_LABEL_SECURITY} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      [(jobItem.jobID): {
        utils.handleError(
            {
              node(buildData.get(SLAVE_NODE_LABEL)) {
                nexusIQScan(buildData, jobItem)
                dependencyCheck(buildData, jobItem)
              }
            },
            { Throwable e ->
              buildData.error(jobItem, e)
              throw e
            }
        )
      }]
    }
    entries.failFast = !ignoreFailures

    stage(stageLabel) {
      utils.handleError(
          {
            parallel entries
          },
          { Throwable e ->
            if (ignoreFailures) {
              job.setBuildUnstable()
            } else {
              throw e
            }
          }
      )
    }
  }
}

void nexusIQScan(BuildData buildData, JobItem jobItem) {
  if (!buildData.getBool(RUN_NEXUS_LIFECYCLE)) return

  utils.timer('Nexus IQ scan') {
    log.info("Nexus IQ Server Scanning ${jobItem.jobID}...")

    dir(jobItem.buildWorkDir) {
      nexusPolicyEvaluation(iqApplication: jobItem.jobID, iqStage: buildData.getString(NEXUS_IQ_STAGE))
    }

    log.info("Current build status = ${currentBuild.result}")
  }
}

void dependencyCheck(BuildData buildData, JobItem jobItem) {
  if (!buildData.getBool(RUN_DEPENDENCY_CHECK)) return

  utils.timer('OWASP dependency check scan') {
    log.info("OWASP Dependency Check Scanning ${jobItem.jobID}...")

    dir(jobItem.buildWorkDir) {
      dependencyCheckAnalyzer(
          datadir: buildData.getString(VULNERABILITY_DATABASE_PATH),
          scanpath: buildData.getString(DEPENDENCY_CHECK_SCAN_PATTERN),
          suppressionFile: buildData.getString(DEPENDENCY_CHECK_SUPPRESSION_PATH),
          hintsFile: '',
          includeCsvReports: false,
          includeHtmlReports: true,
          includeJsonReports: false,
          includeVulnReports: true,
          isAutoupdateDisabled: false,
          outdir: '',
          skipOnScmChange: false,
          skipOnUpstreamChange: false,
          zipExtensions: ''
      )
      dependencyCheckPublisher(
          pattern: buildData.getString(DEPENDENCY_CHECK_REPORT_PATTERN),
          canComputeNew: false,
          defaultEncoding: '',
          healthy: '',
          unHealthy: ''
      )
      archiveArtifacts(
          artifacts: buildData.getString(DEPENDENCY_CHECK_REPORT_PATTERN),
          allowEmptyArchive: true,
          onlyIfSuccessful: false
      )
    }

    log.info("Current build status = ${currentBuild.result}")
  }
}
