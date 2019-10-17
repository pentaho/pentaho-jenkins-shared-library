/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_CHECKOUT_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_CHECKOUT

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runCheckouts) {
    utils.timer(
      {
        doCheckouts(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_CHECKOUT, duration)
        log.info "${STAGE_LABEL_CHECKOUT} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_CHECKOUT)
    buildData.time(STAGE_LABEL_CHECKOUT, 0)
  }
}

void doCheckouts(BuildData buildData) {
  // Collect all job items eligible for checkout into a single list
  List jobItems = buildData.buildMap.collectMany { String key, List value ->
    value.findAll { JobItem ji -> ji.checkout }
  }

  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_CHECKOUT)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt(PARALLEL_CHECKOUT_CHUNKSIZE) ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize) { List<JobItem> partition, JobItem next ->
    partition.every { it.scmID != next.scmID }
  }

  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_CHECKOUT : "${STAGE_LABEL_CHECKOUT} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      [(jobItem.jobID): getItemClosure(buildData, jobItem)]
    }

    entries = utils.setNode(entries)
    entries.failFast = true

    stage(stageLabel) {
      parallel entries
    }
  }
}

Closure getItemClosure(BuildData buildData, JobItem jobItem) {
  Closure itemClosure

  // Minion call
  if (buildData.useMinions) {
    itemClosure = new JobBuild(MinionHandler.getFullJobName(jobItem))
      .withParameters([
        (RUN_CHECKOUTS)    : true,
        (RUN_BUILDS)       : false,
        (RUN_UNIT_TESTS)   : false,
        (ARCHIVE_ARTIFACTS): false
      ])
      .getExecution()
  }
  // Execute locally
  else {
    itemClosure = { ->
      retry(buildData.getInt(BUILD_RETRIES)) {
        dir(jobItem.checkoutDir) {
          ScmUtils.doCheckout(this, jobItem, jobItem.scmPoll)
        }
      }
    }
  }

  return { ->
    utils.handleError(
      {
        utils.timer(itemClosure) { long duration ->
          buildData.time(STAGE_LABEL_CHECKOUT, jobItem, duration)
        }
      },
      { Throwable e ->
        buildData.error(jobItem, e)
        throw e
      }
    )
  }
}
