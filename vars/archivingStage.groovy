/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.archive.ArchiveException
import org.hitachivantara.ci.archive.ArchivingHelper
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_TO_JENKINS_MASTER
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_ARCHIVING_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_ARCHIVING
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_HOSTING_ROOT
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runArchiving) {
    utils.timer(
      {
        doArchiving(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_ARCHIVING, duration)
        log.info "${STAGE_LABEL_ARCHIVING} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_ARCHIVING)
    buildData.time(STAGE_LABEL_ARCHIVING, 0)
  }
}

void doArchiving(BuildData buildData) {
  log.debug "Running artifacts archiving."

  List<JobItem> jobItemsToInclude = buildData.buildMap.collectMany { String key, List value -> return value }
    .findAll { JobItem ji -> ji.archivable }

  if (!jobItemsToInclude) {
    utils.createStageEmpty(STAGE_LABEL_ARCHIVING)
    return
  }

  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)
  int chunkSize = buildData.getInt(PARALLEL_ARCHIVING_CHUNKSIZE) ?: jobItemsToInclude.size()

  List jobItemPartitions = partition(jobItemsToInclude, chunkSize) { List<JobItem> partition, JobItem next ->
    partition.every { it.buildWorkDir != next.buildWorkDir }
  }

  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_ARCHIVING : "${STAGE_LABEL_ARCHIVING} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      [(jobItem.jobID): getItemClosure(buildData, jobItem)]
    }

    entries = utils.setNode(entries)
    entries.failFast = true

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

  // when in a upstream build that archives to hosted, create a symlink so that all the artifacts become accessible
  // from jenkins main build
  if (buildData.isSet(ARCHIVE_TO_JENKINS_MASTER) && !buildData.isMinion() && buildData.isSet(BUILD_HOSTING_ROOT)) {
    FileUtils.createSymLink(currentBuild.rawBuild.getRootDir() as String, ArchivingHelper.getHostedRoot(buildData), 'archive')
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
        (RUN_UNIT_TESTS)   : false,
        (ARCHIVE_ARTIFACTS): true
      ])
      .getExecution()
  }
  // Execute locally
  else {
    itemClosure = { ->
      retry(buildData.getInt(BUILD_RETRIES)) {
        ArchivingHelper archiving = new ArchivingHelper(this, buildData)
        if (!archiving.isCopyToFolderAvailable()) {
          log.error "Target location is not accessible", archiving.getArchivingTargetRootFolder()
          throw new ArchiveException('Copying artifacts is not available')
        }
        archiving.archiveArtifacts(jobItem)
      }
    }
  }

  return { ->
    utils.handleError(
      {
        utils.timer(itemClosure) { long duration ->
          buildData.time(STAGE_LABEL_ARCHIVING, jobItem, duration)
        }
      },
      { Throwable e ->
        buildData.error(jobItem, e)
        throw e
      }
    )
  }
}
