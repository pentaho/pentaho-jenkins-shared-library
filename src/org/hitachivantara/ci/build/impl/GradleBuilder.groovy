/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.FileUtils
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.SonarAnalyser

import static org.hitachivantara.ci.build.helper.BuilderUtils.process
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS

import static org.hitachivantara.ci.build.helper.BuilderUtils.applyBuildDirectives

class GradleBuilder extends AbstractBuilder implements Serializable {

  String name = BuildFramework.GRADLE.name()

  GradleBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    StringBuilder command = new StringBuilder(getBaseCommand())
    applyBuildDirectives(command, getDefaultDirectives(id), item.getDirectives(id))
    return command.toString()
  }

  private String getBaseCommand() {
    List command = []

    String gradlewPath = FileUtils.getPath(item.buildWorkDir, 'gradlew')
    if (FileUtils.exists(gradlewPath)) {
      command << './gradlew'
    } else {
      command << 'gradle'
    }

    if (item.buildFile) command << "-b ${item.buildFile}"
    if (item.settingsFile) command << "-c ${item.settingsFile}"

    return command.join(' ')
  }

  @Override
  Closure getExecution() {
    String gradleCommand = getExecutionCommand()
    steps.log.info "Gradle directives for ${item.jobID}: $gradleCommand"
    return getGradleDsl(gradleCommand)
  }

  @Override
  List<List<JobItem>> expandItem() {
    steps.log.warn "Expanding jobItem not implemented for Gradle, reverting to normal"
    [[item]]
  }

  @Override
  void applyScmChanges() {
    // not implemented
  }

  @Override
  Closure getSonarExecution() {
    SonarAnalyser analyser = new GradleSonarAnalyser(getBaseCommand())
    String gradleCommand = analyser.getCommand()
    steps.log.info "Gradle SonarAnalyser directives for ${item.jobID}: $gradleCommand"
    return getGradleDsl(gradleCommand)
  }

  Closure getGradleDsl(String cmd) {
    String localRepoPath = "${buildData.getString(LIB_CACHE_ROOT_PATH)}/gradle"

    return { ->
      this.steps.dir(item.buildWorkDir) {
        steps.withEnv([
          "GRADLE_OPTS=${opts}"
        ]) {
          if (item.containerized) {
            process("${cmd} -g ${localRepoPath}", steps)
          } else {
            throw new BuilderException('Non containerized builds are not allowed for Gradle at the moment')
          }
        }
      }
    }
  }

  class GradleSonarAnalyser extends SonarAnalyser implements Serializable {
    String task = 'sonarqube'
    String baseCommand

    GradleSonarAnalyser(String baseCommand) {
      this.baseCommand = baseCommand
    }

    @Override
    String getCommand() {
      // need an actual command builder, but this will do for now
      List command = [baseCommand]
      command << task

      if (buildData.isPullRequest()) {
        command << "-Dsonar.pullrequest.branch=${buildData.getString(BRANCH_NAME)}"
        command << "-Dsonar.pullrequest.key=${buildData.get(CHANGE_ID)}"
        command << "-Dsonar.pullrequest.base=${buildData.get(CHANGE_TARGET)}"

        // pipeline-github plugin provides PR head commit so we can tell sonar and avoid considering the merge commit
        command << "-Dsonar.scm.revision=${steps.pullRequest.head}"

        if (buildData.getBool(PR_STATUS_REPORTS)) {
          command << "-Dsonar.pullrequest.github.repository=${item.scmInfo.organization}/${item.scmInfo.repository}"
        }
      } else if (buildData.getString(BRANCH_NAME) != 'master') {
        // send branch name only if it's not master, sending master on a first scan causes error
        command << "-Dsonar.branch.name=${buildData.getString(BRANCH_NAME)}"
      }

      return command.join(' ')
    }
  }
}
