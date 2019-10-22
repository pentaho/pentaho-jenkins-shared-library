/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.build.helper.BuilderUtils

import static org.hitachivantara.ci.config.LibraryProperties.ANT_DEFAULT_COMMAND_OPTIONS
import static org.hitachivantara.ci.config.LibraryProperties.ANT_DEFAULT_DIRECTIVES
import static org.hitachivantara.ci.config.LibraryProperties.ANT_TEST_TARGETS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_ANT_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_JDK_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_MAVEN_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

@Deprecated
class AntBuilder extends AbstractBuilder implements IBuilder, Serializable {

  String name = BuildFramework.ANT.name()

  private final static String BASE_COMMAND = "ant"

  AntBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  Closure getExecution() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData['buildData']
    this.steps = builderData['dsl']
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {
    if (buildData.noop || jobItem.isExecNoop()) {
      return { -> this.steps.echo "${jobItem.getJobID()} NOOP so not building ${jobItem.getScmID()}" }
    }

    Map buildProperties = buildData.getBuildProperties()
    String ivyLocalRepoPath = "${buildProperties[WORKSPACE] ?: ''}/caches/.ivy2"
    String defaultAntOpts = buildProperties[ANT_DEFAULT_COMMAND_OPTIONS]

    StringBuilder antCmd = new StringBuilder()
    antCmd << BASE_COMMAND
    if (defaultAntOpts) {
      antCmd << "  ${buildProperties[ANT_DEFAULT_COMMAND_OPTIONS]}"
    }
    antCmd << " -Divy.default.ivy.user.dir=${ivyLocalRepoPath}"

    if (jobItem.buildFile) {
      antCmd << " -buildfile ${jobItem.buildFile}"
    }

    BuilderUtils.applyBuildDirectives(antCmd, buildProperties[ANT_DEFAULT_DIRECTIVES] as String, jobItem.directives)
    String testTargets = buildData.buildProperties[ANT_TEST_TARGETS] ?: ''

    if (!testTargets.empty) {
      String forbidden = testTargets.split().join('|')
      antCmd.replaceAll(~/(?i)\s?($forbidden)\s?/, '')
    }

    steps.echo "Ant build directives for ${jobItem.getJobID()}: ${antCmd}"

    return getAntDsl(jobItem, antCmd.toString())
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    if (buildData.noop || jobItem.isExecNoop() || !jobItem.testable) {
      return { -> this.steps.echo "${jobItem.jobID}: skipped testing ${jobItem.scmID}" }
    }

    StringBuilder antCmd = new StringBuilder()
    antCmd << BASE_COMMAND
    if (jobItem.buildFile) {
      antCmd << " -buildfile ${jobItem.buildFile}"
    }

    String testTargets = buildData.buildProperties[ANT_TEST_TARGETS] ?: ''
    if (!testTargets.empty) {
      antCmd << " $testTargets"
    }

    steps.echo "Ant unit test build directives for ${jobItem.getJobID()}: ${antCmd}"

    return getAntDsl(jobItem, antCmd.toString())
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    steps.log.warn "Expanding jobItem not implemented for Ant, reverting to normal"
    [[item]]
  }

  @Override
  void markChanges(JobItem jobItem) {
    applyScmChanges()
  }

  @Override
  void applyScmChanges() {
    // not implemented
  }

  @Override
  Closure getSonarExecution() {
    // not implemented
    return { -> }
  }

  private Closure getAntDsl(JobItem jobItem, String antCmd) {
    Map buildProperties = buildData.getBuildProperties()

    return { ->
      this.steps.dir(jobItem.buildWorkDir) {
        this.steps.withEnv(["PATH+MAVEN=${this.steps.tool "${buildProperties[JENKINS_MAVEN_FOR_BUILDS]}"}/bin"]) {
          this.steps.withAnt(
            installation: "${buildProperties[JENKINS_ANT_FOR_BUILDS]}",
            jdk: "${buildProperties[JENKINS_JDK_FOR_BUILDS]}",
          ) {
            BuilderUtils.process(antCmd, this.steps)
          }
        }
      }
    }
  }
}
