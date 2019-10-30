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

import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_DEFAULT_COMMAND_OPTIONS
import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_DEFAULT_DIRECTIVES
import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_TEST_TARGETS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_GRADLE_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_JDK_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

class GradleBuilder extends AbstractBuilder implements IBuilder, Serializable {

  String name = BuildFramework.GRADLE.name()

  private final static String BASE_COMMAND = "gradle"

  GradleBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    Map buildProperties = buildData.getBuildProperties()
    String gradleLocalRepoPath = "${buildProperties[WORKSPACE] ?: ''}/caches/.gradle"
    String defaultGradleOpts = buildProperties[GRADLE_DEFAULT_COMMAND_OPTIONS]

    StringBuilder gradleCmd = new StringBuilder(BASE_COMMAND)

    if (defaultGradleOpts) {
      gradleCmd << " ${buildProperties[GRADLE_DEFAULT_COMMAND_OPTIONS]}"
    }

    if (item.buildFile) {
      gradleCmd << " -b ${item.buildFile}"
    }

    if (item.settingsFile) {
      gradleCmd << " -c ${item.settingsFile}"
    }

    BuilderUtils.applyBuildDirectives(gradleCmd, buildProperties[GRADLE_DEFAULT_DIRECTIVES] as String, item.directives)
    String testTargets = buildData.buildProperties[GRADLE_TEST_TARGETS] ?: ''

    gradleCmd << " --gradle-user-home=${gradleLocalRepoPath}"

    if (!testTargets.empty) {
      gradleCmd << " -x "
      gradleCmd << testTargets
    }

    steps.echo "Gradle build directives for ${item.getJobID()}: ${gradleCmd}"
    return gradleCmd.toString()
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

    if (buildData.noop || jobItem.execNoop) {
      return { -> steps.echo "${jobItem.getJobID()} NOOP so not building ${jobItem.getScmID()}" }
    }

    String gradleCmd = getExecutionCommand()

    steps.echo "Gradle build directives for ${jobItem.getJobID()}: ${gradleCmd}"

    return getGradleDsl(jobItem, gradleCmd)

  }

  @Override
  Closure getTestClosure(JobItem jobItem) {

    if (buildData.noop || jobItem.execNoop || !jobItem.testable) {
      return { -> this.steps.echo "${jobItem.jobID}: skipped testing ${jobItem.scmID}" }
    }

    String testTargets = buildData.buildProperties[GRADLE_TEST_TARGETS] ?: ''

    if (!testTargets.empty) {
      StringBuilder gradleCmd = new StringBuilder(BASE_COMMAND)
      gradleCmd << ' '

      if (jobItem.buildFile) {
        gradleCmd << "-b ${jobItem.buildFile}"
        gradleCmd << ' '
      }

      if (jobItem.settingsFile) {
        gradleCmd << "-c ${jobItem.settingsFile}"
        gradleCmd << ' '
      }

      gradleCmd << testTargets
      gradleCmd << ' '

      BuilderUtils.applyBuildDirectives(gradleCmd, buildData.buildProperties[GRADLE_DEFAULT_DIRECTIVES] as String, jobItem.directives)

      String forbidden = ['clean', 'build'].join('|')
      gradleCmd = new StringBuilder(gradleCmd.replaceAll(~/(?i)\s?($forbidden)\s?/, ''))

      return getGradleDsl(jobItem, gradleCmd.toString())

    } else {
      return { -> this.steps.echo "${jobItem.jobID}: no test targets defined" }

    }
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    return expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    steps.log.warn "Expanding jobItem not implemented for Gradle, reverting to normal"
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

  Closure getGradleDsl(JobItem jobItem, String gradleCmd) {
    Map buildProperties = buildData.getBuildProperties()

    return { ->
      this.steps.dir(jobItem.buildWorkDir) {
        this.steps.withEnv(["PATH+GRADLE=${this.steps.tool "${buildProperties[JENKINS_GRADLE_FOR_BUILDS]}"}/bin",
                            "JAVA_HOME=${this.steps.tool "${buildProperties[JENKINS_JDK_FOR_BUILDS]}"}"]) {
          BuilderUtils.process(gradleCmd, this.steps)
        }
      }
    }
  }

}
