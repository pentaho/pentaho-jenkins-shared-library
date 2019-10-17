/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import groovy.transform.Field
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.StringUtils.normalizeString
import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.build.helper.BuilderUtils.prepareForExecution
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_AUDIT
import static org.hitachivantara.ci.config.LibraryProperties.RUN_DEPENDENCY_CHECK
import static org.hitachivantara.ci.config.LibraryProperties.RUN_NEXUS_LIFECYCLE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_SONARQUBE
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_AUDIT
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS

// must be power of 2
@Field int DEPENDENCY_CHECK = 0x1
@Field int NEXUS_IQ_SCAN = 0x2
@Field int SONARQUBE = 0x4
@Field int ALL = DEPENDENCY_CHECK | NEXUS_IQ_SCAN | SONARQUBE


def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runAudit) {
    utils.timer(
      {
        runStage(buildData, getEnabledScanners(buildData))
      },
      { long duration ->
        buildData.time(STAGE_LABEL_AUDIT, duration)
        log.info "${STAGE_LABEL_AUDIT} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_AUDIT)
    buildData.time(STAGE_LABEL_AUDIT, 0)
  }
}

int getEnabledScanners(BuildData buildData) {
  // get the sum of known enabled scanners
  int enabledScanners = 0
  enabledScanners |= buildData.getBool(RUN_DEPENDENCY_CHECK) ? DEPENDENCY_CHECK : 0
  enabledScanners |= buildData.getBool(RUN_NEXUS_LIFECYCLE) ? NEXUS_IQ_SCAN : 0
  enabledScanners |= buildData.getBool(RUN_SONARQUBE) ? SONARQUBE : 0
  return enabledScanners
}

void runStage(BuildData buildData, int enabledScanners) {
  if (!enabledScanners) {
    stage(STAGE_LABEL_AUDIT) {
      log.warn "No Scanners selected!"
    }
    return
  }

  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

  // Collect all job items into a single list of workable items
  List<JobItem> jobItems = prepareForExecution(buildData.allItems, false, { JobItem item -> item.auditable }).flatten()

  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_AUDIT)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt("PARALLEL_${normalizeString(STAGE_LABEL_AUDIT).toUpperCase()}_CHUNKSIZE") ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize)
  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_AUDIT : "${STAGE_LABEL_AUDIT} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      getEntries(buildData, jobItem, enabledScanners)
    }

    entries = utils.setNode(entries)
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

Map<String, Closure> getEntries(BuildData buildData, JobItem jobItem, int enabledScanners = ALL) {
  // Minion call
  if (buildData.useMinions) {
    return [(jobItem.jobID): new JobBuild(MinionHandler.getFullJobName(jobItem))
      .withParameters([
        (RUN_CHECKOUTS): false,
        (RUN_BUILDS)   : false,
        (RUN_AUDIT)    : true,
      ]).getExecution()
    ]
  }

  // Local execution
  Map<String, Closure> scanners = [:]

  if (enabledScanners & DEPENDENCY_CHECK) {
    scanners << [(jobItem.jobID + ': OWASP dependency check'): { -> dependencyCheck(/*buildData, jobItem*/) }]
  }
  if (enabledScanners & NEXUS_IQ_SCAN) {
    scanners << [(jobItem.jobID + ': Nexus IQ'): { -> nexusIQScan(/*buildData, jobItem*/) }]
  }
  if (enabledScanners & SONARQUBE) {
    scanners << [(jobItem.jobID + ': SonarQube'): { -> sonar(buildData, jobItem) }]
  }

  return scanners
}

/**
 * Perform sonarqube's analysis
 *
 * @param buildData
 * @param jobItem
 */
void sonar(BuildData buildData, JobItem jobItem) {
  Builder builder = BuilderFactory.builderFor(jobItem)
  Closure execution = builder.sonarExecution

  // apply retries
  if (buildData.getInt(BUILD_RETRIES)) {
    Closure current = execution
    execution = { -> retry(buildData.getInt(BUILD_RETRIES), current) }
  }

  // apply container
  if (jobItem.containerized) {
    Closure current = execution
    execution = { -> utils.withContainer(jobItem.dockerImage, current) }
  }

  utils.timer(execution) { long duration ->
    buildData.time(STAGE_LABEL_AUDIT, jobItem.jobID + ': SonarQube', duration)
  }
}

/**
 *
 * @param buildData
 * @param jobItem
 */
void nexusIQScan(/*BuildData buildData, JobItem jobItem*/) {
  //TODO
  log.warn "Not implemented yet"
}

/**
 *
 * @param buildData
 * @param jobItem
 */
void dependencyCheck(/*BuildData buildData, JobItem jobItem*/) {
  //TODO
  log.warn "Not implemented yet"
}