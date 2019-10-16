import groovy.time.TimeCategory
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.github.GitHubManager

import static org.hitachivantara.ci.build.helper.BuilderUtils.partition
import static org.hitachivantara.ci.GroovyUtils.unique

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.IGNORE_PIPELINE_FAILURE
import static org.hitachivantara.ci.config.LibraryProperties.PARALLEL_TAG_CHUNKSIZE
import static org.hitachivantara.ci.config.LibraryProperties.SLAVE_NODE_LABEL
import static org.hitachivantara.ci.config.LibraryProperties.STAGE_LABEL_TAG
import static org.hitachivantara.ci.config.LibraryProperties.TAG_MESSAGE
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

def stage() {
  BuildData buildData = BuildData.instance

  if (buildData.runTag) {
    utils.timer(
      {
        runStage(buildData)
      },
      { long duration ->
        buildData.time(STAGE_LABEL_TAG, duration)
        log.info "${STAGE_LABEL_TAG} completed in ${TimeCategory.minus(new Date(duration), new Date(0))}"
      }
    )
  } else {
    utils.createStageSkipped(STAGE_LABEL_TAG)
    buildData.time(STAGE_LABEL_TAG, 0)
  }
}

void runStage(BuildData buildData) {
  Boolean ignoreFailures = buildData.getBool(IGNORE_PIPELINE_FAILURE)

  // Collect all items to be worked
  List jobItems = buildData.buildMap.collectMany {
    String key, List value -> value.findAll { JobItem ji -> !ji.execNoop }
  }

  // making job items unique by repo so that only tries to tag once for each
  jobItems = unique(jobItems, { it.scmID })

  // no jobItems to build, leave
  if (!jobItems) {
    utils.createStageEmpty(STAGE_LABEL_TAG)
    return
  }

  // if no chunk value was specified don't split it
  int chunkSize = buildData.getInt(PARALLEL_TAG_CHUNKSIZE) ?: jobItems.size()

  List jobItemPartitions = partition(jobItems, chunkSize)
  int totalChunks = jobItemPartitions.size()
  boolean singleChunk = totalChunks <= 1

  String tagName = evaluateTagName(buildData.getString(TAG_NAME))
  String tagMessage = buildData.getString(TAG_MESSAGE)

  jobItemPartitions.eachWithIndex { List<JobItem> jobItemsChunk, int currentChunk ->
    String stageLabel = singleChunk ? STAGE_LABEL_TAG : "${STAGE_LABEL_TAG} (${++currentChunk}/${totalChunks})"

    Map entries = jobItemsChunk.collectEntries { JobItem jobItem ->
      Closure execution = getItemExecution(jobItem, tagName, tagMessage)

      // apply retries
      if (buildData.getInt(BUILD_RETRIES)) {
        Closure currentExecution = execution
        execution = { retry(buildData.getInt(BUILD_RETRIES), currentExecution) }
      }

      [(jobItem.jobID): {
        utils.handleError(
          {
            node(buildData.get(SLAVE_NODE_LABEL), execution)
          },
          { Throwable err ->
            buildData.error(jobItem, err)
            throw err
          })
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

/**
 * Get tag execution closure for the given job item
 * @param jobItem
 * @param tagName
 * @param tagMessage
 * @return
 */
Closure getItemExecution(JobItem jobItem, String tagName, String tagMessage) {
  Map scmInfo = jobItem.scmInfo + [credentials: jobItem.scmCredentials]

  return { ->
    dir(jobItem.buildWorkDir) {
      utils.withGit(scmInfo) { String gitUrl ->
        sh("git tag -a ${tagName} -m '${tagMessage}' --force")
        sh("git push ${gitUrl} ${tagName} --force")
      }
    }
    if ( jobItem.isCreateRelease() ) {// if is to create GH release
      dir(jobItem.checkoutDir) {
        GitHubManager.createRelease(jobItem)
      }
    }
  }
}

/**
 * Evaluate a given tag name to provide dynamic tag naming expressions
 *
 * Currently supported expressions:
 * - date|<format expression> : date|yyyyMMdd-${BUILD_NUMBER}
 *
 * @param tagName
 * @return
 */
String evaluateTagName(String tagName) {
  List parts = tagName.tokenize('|')

  if (parts.size() < 2) {
    return tagName
  }

  switch (parts[0]) {
    case 'date':
      return BuildData.instance.clock.format(parts[1])
    default:
      return tagName
  }
}