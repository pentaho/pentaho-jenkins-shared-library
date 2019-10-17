/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.build.helper.BuilderUtils.prepareForExecution
import static org.hitachivantara.ci.config.LibraryProperties.ARCHIVE_ARTIFACTS
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.RUN_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_CHECKOUTS
import static org.hitachivantara.ci.config.LibraryProperties.RUN_UNIT_TESTS
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_BUILD

def call() {
  BuildData buildData = BuildData.instance
  if (buildData.runBuilds) {
    utils.timer(
      {
        doBuilds(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_BUILD, duration)
        log.info "${STAGE_LABEL_BUILD} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_BUILD)
    buildData.time(STAGE_LABEL_BUILD, 0)
  }
}

void doBuilds(BuildData buildData) {
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)
  Boolean singleGroup = buildData.buildMap.size() <= 1

  buildData.buildMap.each { String jobGroup, List<JobItem> jobItems ->
    List<List<JobItem>> jobSubGroups = prepareForExecution(jobItems)

    if (!jobSubGroups) {
      utils.createStageEmpty(STAGE_LABEL_BUILD)
      return
    }

    jobSubGroups.eachWithIndex { List<JobItem> jobSubGroup, int groupIndex ->
      String stageLabel = singleGroup ? STAGE_LABEL_BUILD : "${STAGE_LABEL_BUILD} ${jobGroup}"

      if(jobSubGroups.size() > 1) {
        stageLabel += " (${groupIndex + 1}/${jobSubGroups.size()})"
      }

      Map entries = jobSubGroup.collectEntries { JobItem jobItem ->
        [(jobItem.jobID): getItemClosure(buildData, jobItem)]
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
}

Closure getItemClosure(BuildData buildData, JobItem jobItem) {
  Closure itemClosure

  // Minion call
  if (buildData.useMinions) {
    itemClosure = new JobBuild(MinionHandler.getFullJobName(jobItem))
      .withParameters([
        (RUN_CHECKOUTS)    : false,
        (RUN_BUILDS)       : true,
        (RUN_UNIT_TESTS)   : false,
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
          .getBuildClosure(jobItem)
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
          buildData.time(STAGE_LABEL_BUILD, jobItem, duration)
        }
      },
      { Throwable e ->
        buildData.error(jobItem, e)
        throw e
      }
    )
  }
}
