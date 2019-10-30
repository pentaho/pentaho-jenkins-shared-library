/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

import groovy.transform.Field
import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.GroovyUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.archive.ArchiveException
import org.hitachivantara.ci.archive.ArchivingHelper
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.stages.ConfigStage
import org.hitachivantara.ci.stages.ParallelItemWorkStage
import org.hitachivantara.ci.stages.SimpleStage
import org.hitachivantara.ci.github.GitHubManager
import org.hitachivantara.ci.jenkins.MinionHandler

import static org.hitachivantara.ci.config.LibraryProperties.BUILD_HOSTING_ROOT
import static org.hitachivantara.ci.config.LibraryProperties.BUILD_RETRIES
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_ALL_CACHES
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_BUILD_WORKSPACE
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_CACHES_REGEX
import static org.hitachivantara.ci.config.LibraryProperties.CLEAN_SCM_WORKSPACES
import static org.hitachivantara.ci.config.LibraryProperties.TAG_MESSAGE
import static org.hitachivantara.ci.config.LibraryProperties.TAG_NAME

@Field BuildData buildData = BuildData.instance

void configure(Map defaultParams = [:]) {
  new ConfigStage({
    if (utils.hasScmConfig()) {
      // checkout the pipeline repo
      checkout(poll: false, changelog: false, scm: scm)
    }

    config.load(defaultParams)
    config.applyToJob(defaultParams)

    if (buildData.useMinions) {
      log.info "Using Minion jobs to perform the build"
      MinionHandler.manageJobs()
    }
  }).run()
}

void checkout(String id = 'checkout', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      items.findAll { JobItem item -> item.checkout }
    },
    itemExecution: { JobItem item ->
      dir(item.checkoutDir) {
        ScmUtils.doCheckout(this, item, item.scmPoll)
      }
    },
    itemChunkInclusionCriteria: { List<JobItem> chunk, JobItem next ->
      chunk.every { it.scmID != next.scmID }
    },
    onSkipped: {
      ScmUtils.rebuildCheckouts(currentBuild)
    }
  ).run()
}

void version(String id = 'version', String label = '') {
  new SimpleStage(id: id, label: label ?: id.capitalize(),
    body: {
      // simple approach for now, not going into version merger madness
      versionStage.doVersioning(buildData)
    }
  ).run()
}

void build(String id = 'build', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      BuilderUtils.applyChanges(id, items)
      items.findAll { JobItem item -> !item.skip }
    },
    itemExpansion: { List<JobItem> items ->
      BuilderUtils.expand(id, items)
    },
    itemExecution: { JobItem item ->
      BuilderFactory.builderFor(id, item)
        .getExecution()
        .call()
    }
  ).run()
}

void test(String id = 'test', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      BuilderUtils.applyChanges(id, items)
      items.findAll { JobItem item -> item.testable && !item.skip }
    },
    itemExpansion: { List<JobItem> items ->
      BuilderUtils.expand(id, items)
    },
    itemExecution: { JobItem item ->
      BuilderFactory.builderFor(id, item)
        .getExecution()
        .call()
    },
    itemPostExecution: { JobItem item ->
      job.archiveTests(item)
    }
  ).run()
}

void buildAndTest(String id = 'build', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      BuilderUtils.applyChanges(id, items)
      items.findAll { JobItem item -> !item.skip }
    },
    itemExpansion: { List<JobItem> items ->
      BuilderUtils.expand(id, items)
    },
    itemExecution: { JobItem item ->
      BuilderFactory.builderFor(id, item)
        .getExecution()
        .call()
    },
    itemPostExecution: { JobItem item ->
      job.archiveTests(item)
    }
  ).run()
}

void archive(String id = 'archive', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      items.findAll { JobItem item -> item.archivable }
    },
    itemChunkInclusionCriteria: { List<JobItem> chunk, JobItem next ->
      chunk.every { it.buildWorkDir != next.buildWorkDir }
    },
    itemExecution: { JobItem item ->
      ArchivingHelper archiving = new ArchivingHelper(this, buildData)
      if (!archiving.isCopyToFolderAvailable()) {
        log.error "Target location is not accessible", archiving.getArchivingTargetRootFolder()
        throw new ArchiveException('Copying artifacts is not available')
      }
      archiving.archiveArtifacts(item)
    },
    onFinished: {
      // when in a upstream build that archives to hosted, create a symlink so that all the artifacts become accessible
      // from jenkins main build
      if (!buildData.isMinion() && buildData.isSet(BUILD_HOSTING_ROOT)) {
        FileUtils.createSymLink(currentBuild.rawBuild.getRootDir() as String, ArchivingHelper.getHostedRoot(buildData), 'archive')
      }
    }
  ).run()
}

void preClean(String label = 'Pre Clean') {
  new SimpleStage(id: 'preclean', label: label,
    body: {
      if (buildData.getBool(CLEAN_ALL_CACHES)) {
        retry(buildData.getInt(BUILD_RETRIES)) {
          clean.caches()
        }
      } else if (buildData.isSet(CLEAN_CACHES_REGEX)) {
        retry(buildData.getInt(BUILD_RETRIES)) {
          clean.caches(buildData.getString(CLEAN_CACHES_REGEX))
        }
      }
      if (buildData.getBool(CLEAN_SCM_WORKSPACES)) {
        retry(buildData.getInt(BUILD_RETRIES)) {
          clean.checkouts(true)
        }
      }
    },
    isRun: {
      buildData.isSet(CLEAN_CACHES_REGEX) || buildData.getBool(CLEAN_ALL_CACHES) || buildData.getBool(CLEAN_SCM_WORKSPACES)
    }
  ).run()
}

void postClean(String label = 'Post Clean') {
  new SimpleStage(id: 'postclean', label: label,
    body: {
      if (buildData.getBool(CLEAN_BUILD_WORKSPACE)) {
        retry(buildData.getInt(BUILD_RETRIES)) {
          clean.workspace()
        }
      }
    },
    isRun: {
      buildData.getBool(CLEAN_BUILD_WORKSPACE)
    }
  ).run()
}

void push(String id = 'push', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    itemFilter: { List<JobItem> items ->
      GroovyUtils.unique(items, { JobItem item -> item.scmID })
    },
    itemExecution: { JobItem item ->
      utils.pushItem(item)
    }
  ).run()
}

void tag(String id = 'tag', String label = '') {
  String tagName = utils.evaluateTagName(buildData.getString(TAG_NAME))
  String tagMessage = buildData.getString(TAG_MESSAGE)

  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    itemFilter: { List<JobItem> items ->
      GroovyUtils.unique(items, { JobItem item -> item.scmID }) as List
    },
    itemExecution: { JobItem item ->
      utils.tagItem(item, tagName, tagMessage)

      if (item.isCreateRelease()) {
        GitHubManager.createRelease(item, tagName)
      }
    }
  ).run()
}

void sonar(String id = 'sonar', String label = '') {
  new ParallelItemWorkStage(id: id, label: label ?: id.capitalize(),
    ignoreGroups: true,
    allowMinions: true,
    itemFilter: { List<JobItem> items ->
      BuilderUtils.applyChanges(id, items)
      items.findAll { JobItem item -> !item.skip && item.auditable }
    },
    itemExecution: { JobItem item ->
      BuilderFactory.builderFor(id, item)
        .getSonarExecution()
        .call()
    }
  ).run()
}

void report() {
  new SimpleStage(id: 'report', label: 'Report',
    body: {
      reportStage.doReport(buildData)
    },
    onError: { Throwable e ->
      log.warn "Could not generate report: ${e.message}", e
    }
  ).run()
}