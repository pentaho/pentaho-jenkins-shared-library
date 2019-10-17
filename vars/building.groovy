/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.ConfigurationException

import static org.hitachivantara.ci.build.helper.BuilderUtils.prepareForExecution
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_POST_BUILD
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_PRE_BUILD

def preStage() {
  stage(BuildData.instance.preBuildMap, STAGE_LABEL_PRE_BUILD)
}

def postStage() {
  stage(BuildData.instance.postBuildMap, STAGE_LABEL_POST_BUILD)
}

def stage(Map data, String predefinedLabel) {
  BuildData buildData = BuildData.instance
  if (data.isEmpty()) {
    utils.createStageSkipped(predefinedLabel)
    buildData.time(predefinedLabel, 0)
  } else {
    utils.timer(
      {
        doEventBuild(data, predefinedLabel)
      },
      { long duration ->
        buildData.time(predefinedLabel, duration)
        log.info "${predefinedLabel} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  }
}

void doEventBuild(Map data, String predefinedLabel) {
  BuildData buildData = BuildData.instance
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)
  Boolean singleGroup = data.size() <= 1

  data.each { String jobGroup, List<JobItem> jobItems ->
    List<List<JobItem>> jobSubGroups = prepareForExecution(jobItems)

    if (!jobSubGroups) {
      utils.createStageEmpty(predefinedLabel)
      return
    }

    jobSubGroups.eachWithIndex { List<JobItem> jobSubGroup, int groupIndex ->
      String stageLabel = singleGroup ? predefinedLabel : "${predefinedLabel} ${jobGroup}"

      if (jobSubGroups.size() > 1) {
        stageLabel += " (${groupIndex + 1}/${jobSubGroups.size()})"
      }

      Map entries = jobSubGroup.collectEntries { JobItem jobItem ->
        [(jobItem.jobID): getItemClosure(jobItem, predefinedLabel)]
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

Closure getItemClosure(JobItem jobItem, String predefinedLabel) {
  BuildData buildData = BuildData.instance
  Closure itemClosure

  boolean buildable = (jobItem.buildFramework in [BuildFramework.JENKINS_JOB, BuildFramework.DSL_SCRIPT])

  if (!buildable) {
    throw new ConfigurationException(
      "Job items with 'buildFramework' as '${jobItem.buildFramework}' are not suppose to run in this stage!"
    )
  }

  itemClosure = { ->
    retry(buildData.getInt(BUILD_RETRIES)) {
      BuilderFactory
        .getBuildManager(jobItem, [buildData: buildData, dsl: this])
        .getBuildClosure(jobItem)
        .call()
    }
  }

  // execute within a given docker image
  if (jobItem.containerized) {
    Closure buildClosure = itemClosure
    itemClosure = { utils.withContainer(jobItem.dockerImage, buildClosure) }
  }

  return { ->
    utils.handleError(
      {
        utils.timer(itemClosure) { long duration ->
          buildData.time(predefinedLabel, jobItem, duration)
        }
      },
      { Throwable e ->
        buildData.error(jobItem, e)
        throw e
      }
    )
  }
}
