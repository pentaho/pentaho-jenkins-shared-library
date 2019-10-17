/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.build.helper.BuilderUtils.prepareForExecution
import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_UNIT_TESTS_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_UNIT_TEST

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runUnitTests) {
    utils.timer(
      {
        doUnitTests(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_UNIT_TEST, duration)
        log.info "${STAGE_LABEL_UNIT_TEST} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_UNIT_TEST)
    buildData.time(STAGE_LABEL_UNIT_TEST, 0)
  }
}

void doUnitTests(BuildData buildData) {
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

  // Collect all job items into a single list of workable items
  List<JobItem> jobItems = prepareForExecution(buildData.allItems,{ JobItem item -> item.testable }).flatten()

  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_UNIT_TEST)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt(PARALLEL_UNIT_TESTS_CHUNKSIZE) ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize)
  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_UNIT_TEST : "${STAGE_LABEL_UNIT_TEST} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      [(jobItem.jobID): getItemClosure(buildData, jobItem)]
    }

    entries = utils.setNode(entries)
    // failfast currently doesn't add much, because we are defining
    // -Dmaven.test.failure.ignore=true in our settings.xml
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

Closure getItemClosure(BuildData buildData, JobItem jobItem) {
  Closure itemClosure

  // Minion call
  if (buildData.useMinions) {
    itemClosure = new JobBuild(MinionHandler.getFullJobName(jobItem))
      .withParameters([
        (RUN_CHECKOUTS)    : false,
        (RUN_BUILDS)       : false,
        (RUN_UNIT_TESTS)   : true,
        (ARCHIVE_ARTIFACTS): false
      ])
      .getExecution()
  }
  // Execute locally
  else {
    itemClosure = { ->
      retry(buildData.getInt(BUILD_RETRIES)) {
        BuilderFactory
          .getBuildManager(jobItem, [buildData: buildData, dsl: this])
          .getTestClosure(jobItem)
          .call()
      }
    }

    Integer jobTimeout = jobItem.getTimeout()
    if (jobTimeout) {
      Closure buildClosure = itemClosure
      itemClosure = { -> timeout(jobTimeout, buildClosure) }
    }

    // execute within a given docker image
    if (jobItem.containerized) {
      Closure buildClosure = itemClosure
      itemClosure = { utils.withContainer(jobItem.dockerImage, buildClosure) }
    }
  }

  return { ->
    utils.handleError(
      {
        utils.timer(itemClosure) { long duration ->
          buildData.time(STAGE_LABEL_UNIT_TEST, jobItem, duration)
        }
      },
      { Throwable e ->
        buildData.error(jobItem, e)
        throw e
      },
      {
        // Archive the test reports
        if (buildData.archiveTests) {
          log.info "Archiving tests for job item ${jobItem.jobID} with pattern ${jobItem.testsArchivePattern}"
          def paths = jobItem.modulePaths ?: [jobItem.buildWorkDir]
          paths.each { String path ->
            dir(path) {
              junit allowEmptyResults: true, testResults: jobItem.testsArchivePattern
            }
          }
        }
      }
    )
  }
}
