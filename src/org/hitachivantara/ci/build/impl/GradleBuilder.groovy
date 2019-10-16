package org.hitachivantara.ci.build.impl

import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_DEFAULT_COMMAND_OPTIONS
import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_DEFAULT_DIRECTIVES
import static org.hitachivantara.ci.config.LibraryProperties.GRADLE_TEST_TARGETS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_GRADLE_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_JDK_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

class GradleBuilder implements IBuilder, Builder, Serializable {

  private BuildData buildData
  private JobItem jobItem
  private Script dsl

  private final static String BASE_COMMAND = "gradle"

  GradleBuilder(Script dsl, BuildData buildData, JobItem jobItem) {
    this.dsl = dsl
    this.jobItem = jobItem
    this.buildData = buildData
  }

  @Override
  Closure getExecution() {
    throw new BuilderException('Not yet implemented')
  }

  @Override
  void setBuilderData(Map builderData) {
    this.buildData = builderData['buildData']
    this.dsl = builderData['dsl']
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {

    if (buildData.noop || jobItem.execNoop) {
      return { -> dsl.echo "${jobItem.getJobID()} NOOP so not building ${jobItem.getScmID()}" }
    }

    Map buildProperties = buildData.getBuildProperties()
    String gradleLocalRepoPath = "${buildProperties[WORKSPACE] ?: ''}/caches/.gradle"
    String defaultGradleOpts = buildProperties[GRADLE_DEFAULT_COMMAND_OPTIONS]

    StringBuilder gradleCmd = new StringBuilder(BASE_COMMAND)

    if (defaultGradleOpts) {
      gradleCmd << " ${buildProperties[GRADLE_DEFAULT_COMMAND_OPTIONS]}"
    }

    if (jobItem.buildFile) {
      gradleCmd << " -b ${jobItem.buildFile}"
    }

    if (jobItem.settingsFile) {
      gradleCmd << " -c ${jobItem.settingsFile}"
    }

    BuilderUtils.applyBuildDirectives(gradleCmd, buildProperties[GRADLE_DEFAULT_DIRECTIVES] as String, jobItem.directives)
    String testTargets = buildData.buildProperties[GRADLE_TEST_TARGETS] ?: ''

    gradleCmd << " --gradle-user-home=${gradleLocalRepoPath}"

    if (!testTargets.empty) {
      gradleCmd << " -x "
      gradleCmd << testTargets
    }

    dsl.echo "Gradle build directives for ${jobItem.getJobID()}: ${gradleCmd}"

    return getGradleDsl(jobItem, gradleCmd.toString())

  }

  @Override
  Closure getTestClosure(JobItem jobItem) {

    if (buildData.noop || jobItem.execNoop || !jobItem.testable) {
      return { -> dsl.echo "${jobItem.jobID}: skipped testing ${jobItem.scmID}" }
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
      return { -> dsl.echo "${jobItem.jobID}: no test targets defined" }

    }
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    return expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    dsl.log.warn "Expanding jobItem not implemented for Gradle, reverting to normal"
    [[jobItem]]
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
      dsl.dir(jobItem.buildWorkDir) {
        dsl.withEnv(["PATH+GRADLE=${dsl.tool "${buildProperties[JENKINS_GRADLE_FOR_BUILDS]}"}/bin",
                     "JAVA_HOME=${dsl.tool "${buildProperties[JENKINS_JDK_FOR_BUILDS]}"}"]) {
          BuilderUtils.process(gradleCmd, dsl)
        }
      }
    }
  }

}
