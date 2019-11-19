/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.stages

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.StringUtils

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_SIZE

class ParallelItemWorkStage extends ItemWorkStage {
  static final String PARALLEL_SIZE_PREFIX = 'PARALLEL_SIZE_'

  Closure<Boolean> itemChunkInclusionCriteria = { List<JobItem> chunk, JobItem next -> true }
  Closure<Collection<Collection<JobItem>>> itemExpansion = { List<JobItem> items -> [items] }

  Boolean ignoreGroups = false

  @Override
  void run() {
    wrap {
      ignoreGroups ? runAll() : runByGroup()
    }
  }

  /**
   * Run the stage performing parallel work in chunks of items per job group
   */
  void runByGroup() {
    Boolean singleGroup = buildData.buildMap.size() <= 1

    buildData.buildMap.each { String itemGroup, List<JobItem> items ->
      List<JobItem> enabledItems = items.findAll { JobItem item -> !item.execNoop }
      List<List<JobItem>> workableItemGroups = itemExpansion.call(itemFilter.call(enabledItems)).findAll { it }

      String groupLabel = singleGroup ? label : "${label} ${itemGroup}"

      if (!workableItemGroups) {
        steps.utils.createStageEmpty(groupLabel)
        return
      }

      workableItemGroups.eachWithIndex { List<JobItem> workableItemGroup, int index ->
        if (workableItemGroups.size() > 1) {
          groupLabel += " (P${index + 1})"
        }

        runInChunks(groupLabel, workableItemGroup)
      }
    }
  }

  /**
   * Run the stage performing parallel work in chunks of items ignoring job groups
   */
  void runAll() {
    // Collect all job items into a single list of workable items
    List<JobItem> enabledItems = buildData.allItems.findAll { JobItem item -> !item.execNoop }
    List<JobItem> workableItems = itemExpansion.call(itemFilter.call(enabledItems)).flatten()

    if (!workableItems) {
      steps.utils.createStageEmpty(label)
      return
    }

    runInChunks(label, workableItems)
  }

  private void runInChunks(String baseLabel, List<JobItem> items) {
    int chunkSize = getChunkSizeConfig() ?: items.size()

    List itemChunks = partition(items, chunkSize, itemChunkInclusionCriteria)
    int totalChunks = itemChunks.size()
    boolean singleChunk = totalChunks <= 1

    itemChunks.eachWithIndex { List<JobItem> itemChunk, int index ->
      String stageLabel = singleChunk ? baseLabel : "${baseLabel} (${index + 1})"

      stage(stageLabel, getParallelExecutionMap(itemChunk))
    }
  }

  /**
   *
   * @return
   */
  Integer getChunkSizeConfig() {
    String parallelSizeProperty = getParallelSizeProperty()

    if (buildData.isSet(parallelSizeProperty) && buildData.getInt(parallelSizeProperty) > 0) {
      // use stage specific chunk size
      return buildData.getInt(parallelSizeProperty)
    } else if (buildData.isSet(PARALLEL_SIZE) && buildData.getInt(PARALLEL_SIZE) > 0) {
      // use global chunk size
      return buildData.getInt(PARALLEL_SIZE)
    }

    return 0
  }

  /**
   * Create a stage to execute the given entries in parallel
   * @param label
   * @param executionMap
   */
  void stage(String label, Map executionMap) {
    Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

    steps.utils.setNode(executionMap)
    executionMap.failFast = !ignoreFailures

    steps.stage(label) {
      steps.utils.handleError(
        {
          steps.parallel executionMap
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
   * Generate an execution map with the given items
   * @param items
   * @return Map ready to be used by a parallel step
   */
  Map getParallelExecutionMap(List<JobItem> items) {
    items.collectEntries { JobItem item ->
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

      [(item.jobID): { steps.utils.handleError(execution, onError, postExecution) }]
    }
  }

  /**
   * Get the generated run parallel size property based on the stage id
   * @return
   */
  String getParallelSizeProperty() {
    "${PARALLEL_SIZE_PREFIX}${StringUtils.normalizeString(id).toUpperCase()}"
  }

}
