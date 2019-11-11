/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.stages

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.OVERRIDE_PARAMS

class ItemWorkStage extends Stage {

  Boolean allowMinions = false
  Boolean allowContainers = true
  Closure<Collection<JobItem>> itemFilter = { List<JobItem> items -> items }

  Closure itemExecution = { JobItem item -> }
  Closure itemPostExecution = { JobItem item -> }

  @Override
  void run() {
    wrap {
      stage()
    }
  }

  /**
   * Create a stage to execute the work
   */
  void stage() {
    Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

    // Collect all job items into a single list of workable items
    List<JobItem> enabledItems = buildData.allItems.findAll { JobItem item -> !item.execNoop }
    List<JobItem> workableItems = itemFilter.call(enabledItems)

    if (!workableItems) {
      steps.utils.createStageEmpty(label)
      return
    }

    steps.stage(label) {
      steps.utils.handleError(
        {
          workableItems.each { JobItem item ->
            // execution
            Closure execution = {
              steps.utils.timer(getItemExecution(item)) { long duration ->
                buildData.time(label, item, duration)
              }
            }

            // post execution
            Closure postExecution = { -> itemPostExecution.call(item) }

            // error handling
            Closure onError = { Throwable e ->
              buildData.error(item, e)
              throw e
            }

            steps.utils.handleError(execution, onError, postExecution)
          }
        },
        { Throwable e ->
          if (ignoreFailures) {
            steps.job.setBuildUnstable()
          } else {
            throw e
          }
        }
      )
    }
  }

  /**
   * Generate the execution closure for the given item
   * @param item
   * @return
   */
  Closure getItemExecution(JobItem item) {
    Closure execution

    // Minion call
    if (allowMinions && buildData.useMinions) {
      execution = new JobBuild(MinionHandler.getFullJobName(item))
        .withParameters([
          (OVERRIDE_PARAMS): "${MINION_STAGE}: ${id}"
        ])
        .getExecution()
    }
    // Local execution
    else {
      execution = { -> itemExecution.call(item) }

      // apply retries
      if (buildData.getInt(BUILD_RETRIES)) {
        Closure currentExecution = execution
        execution = { -> steps.retry(buildData.getInt(BUILD_RETRIES), currentExecution) }
      }

      // apply timeout
      if (item.timeout) {
        Closure currentExecution = execution
        execution = { -> steps.timeout(item.timeout, currentExecution) }
      }

      // apply container
      if (allowContainers && item.containerized) {
        Closure currentExecution = execution
        execution = { -> steps.utils.withContainer(item.dockerImage, currentExecution) }
      }
    }

    return execution
  }

}
