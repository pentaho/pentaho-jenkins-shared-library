/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.build.SonarAnalyser
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.maven.tools.CommandBuilder
import org.hitachivantara.ci.maven.tools.FilteredProjectDependencyGraph
import org.hitachivantara.ci.maven.tools.MavenModule

import java.nio.file.Paths

import static org.hitachivantara.ci.JobItem.ExecutionType.AUTO_DOWNSTREAMS
import static org.hitachivantara.ci.build.helper.BuilderUtils.applyBuildDirectives
import static org.hitachivantara.ci.build.helper.BuilderUtils.forceRemainingJobItems
import static org.hitachivantara.ci.FileUtils.relativize
import static org.hitachivantara.ci.GroovyUtils.intersect
import static org.hitachivantara.ci.FileUtils.isChild
import static org.hitachivantara.ci.build.helper.BuilderUtils.process
import static org.hitachivantara.ci.FileUtils.resolve
import static org.hitachivantara.ci.config.LibraryProperties.ARTIFACT_DEPLOYER_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_JDK_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_MAVEN_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_DEFAULT_COMMAND_OPTIONS
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_DEFAULT_DIRECTIVES
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_OPTS
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_RESOLVE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_PUBLIC_RELEASE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_PUBLIC_SNAPSHOT_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_PRIVATE_RELEASE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_PRIVATE_SNAPSHOT_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_SETTINGS
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_TEST_OPTS
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS


class MavenBuilder implements IBuilder, Builder, Serializable {

  private BuildData buildData
  private JobItem jobItem
  private Script steps

  MavenBuilder(Script steps, BuildData buildData, JobItem jobItem) {
    this.steps = steps
    this.jobItem = jobItem
    this.buildData = buildData
  }

  @Override
  String getExecutionCommand() {
    CommandBuilder command = getCommandBuilder(jobItem)
    return command.build()
  }

  @Override
  Closure getExecution() {
    String mvnCommand = getExecutionCommand()
    steps.log.info "Maven directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(jobItem, mvnCommand)
  }

  @Override
  Closure getSonarExecution() {
    CommandBuilder command = getCommandBuilder(jobItem)

    SonarAnalyser analyser = new MavenSonarAnalyser(command)
    String mvnCommand = analyser.getCommand()
    steps.log.info "Maven SonarAnalyser directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(jobItem, mvnCommand)
  }

  @Override
  void setBuilderData(Map builderData) {
    throw new BuilderException('Deprecated method that should not be used')
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {
    CommandBuilder command = getCommandBuilder(jobItem, '-DskipTests')
    String mvnCommand = command.build()
    steps.log.info "Maven build directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(jobItem, mvnCommand)
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    CommandBuilder command = getCommandBuilder(jobItem, 'test', buildData.getString(MAVEN_TEST_OPTS))

    // list of goals that we want stripped from the final command
    command -= ['clean', 'validate', 'compile', 'verify', 'package', 'install', 'deploy', '-Dmaven.test.skip', '-Drelease'].join(' ')

    String mvnCommand = command.build()
    steps.log.info "Maven test directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(jobItem, mvnCommand)
  }

  MavenModule buildMavenModule(JobItem jobItem, CommandBuilder command = null) throws Exception {
    command = command ?: getCommandBuilder(jobItem)

    Properties properties = command.getUserProperties()
    List<String> activeProfiles = command.getActiveProfileIds()
    List<String> inactiveProfiles = command.getInactiveProfileIds()
    String file = Paths.get(jobItem.buildWorkDir, jobItem.buildFile ?: 'pom.xml').toString()

    try {
      return steps.buildMavenModule(
        file: file,
        activeProfiles: activeProfiles,
        inactiveProfiles: inactiveProfiles,
        userProperties: properties
      )
    } catch(Throwable e) {
      buildData.error('Model Building', jobItem, e)
      throw new BuilderException("Couldn't generate a Maven Module for this project.")
    }
  }

  List<MavenProjectWrapper> getActiveModules(JobItem jobItem, boolean alsoMakeParent = true) {
    List<MavenProjectWrapper> activeMavenProjects = []

    CommandBuilder command = getCommandBuilder(jobItem)
    MavenModule rootModule = buildMavenModule(jobItem, command)

    if (!command.hasOption('N')) {
      List<String> projectList = command.getProjectList()
      List<String> exclProjects = command.getExcludedProjectList()

      FilteredProjectDependencyGraph dependencyGraph = steps.projectDependencyGraph(
        module: rootModule,
        whitelist: projectList
      )

      activeMavenProjects = dependencyGraph.getSortedProjects().collect { def mavenProject ->
        new MavenProjectWrapper(
          relativize(mavenProject.basedir.path, rootModule.pom.parent.remote),
          "${mavenProject.groupId}:${mavenProject.artifactId}"
        )
      }

      if (exclProjects) {
        activeMavenProjects = activeMavenProjects.findAll { MavenProjectWrapper mavenProject ->
          !exclProjects.any { isChild(mavenProject.path, resolve(it, jobItem.root)) }
        }
      }
    }

    if (alsoMakeParent) {
      activeMavenProjects.add(0, new MavenProjectWrapper(rootModule.fullPath, rootModule.id))
    }

    return activeMavenProjects
  }

  @Override
  List<List<JobItem>> expandWorkItem(JobItem jobItem) {
    return expandItem()
  }

  @Override
  List<List<JobItem>> expandItem() {
    // Don't expand if we are a noop
    if (jobItem.execNoop) {
      return [[jobItem]]
    }

    CommandBuilder command = getCommandBuilder(jobItem)
    // Nothing to expand if NON_RECURSIVE
    if (command.hasOption('N')) {
      return [[jobItem]]
    }

    List<String> projectList = command.getProjectList()
    List<String> exclProjects = command.getExcludedProjectList()

    MavenModule rootModule = buildMavenModule(jobItem)
    List<List<String>> activeModules = steps.projectDependencyGraph(module: rootModule, whitelist: projectList).sortedProjectsByGroups

    if (activeModules.flatten().empty) {
      // we are a leaf, nothing to expand
      return [[jobItem]]
    }

    if (exclProjects) {
      // NOTE: if for some obscure reason we are excluding everything,
      // we'll still want to build the parent, but non recursively
      activeModules = activeModules.collect { List<String> modules ->
        modules - exclProjects
      }
      activeModules.removeAll { it.empty }
    }

    String directives = command.getGoals().join(' ')

    List<List<JobItem>> result = activeModules.collect { List<String> modules ->
      return modules.collect { String module ->
        // module is of type module,module/sub1,module/sub...
        List<String> paths = module.split(',')

        command.removeOption('-pl')
        command << "-pl ${module}"

        Map newJobData = [
          jobID       : "${jobItem.jobID} (${module})",
          directives  : "${directives} ${command.getOptions().join(' ')}",
          parallelize : false,
          checkoutDir : jobItem.checkoutDir,
          buildWorkDir: jobItem.buildWorkDir,
        ]

        JobItem innerJobItem = jobItem.clone()
        innerJobItem.setJobData(newJobData)
        // this extra steps are needed so when archiving results, we only get this module(s) once
        // still doesn't guard us if a later jobItem calls the junit plugin again on the same folders
        // TODO: remove this when the problem of tests duplication get fixed
        String parent = new File(innerJobItem.buildFile ?: '').getParent()
        innerJobItem.setModulePaths(paths.collect {
          Paths.get(jobItem.buildWorkDir, parent ?: '', it).toString()
        } as String[])
        return innerJobItem
      }
    }
    // also make parent
    command.removeOption('-pl')
    command << "-N"
    JobItem parent = jobItem.clone()
    parent.set('testable', false) // we don't want to archive the tests twice!
    parent.set('directives', "${directives} ${command.getOptions().join(' ')}")
    result.add(0, [parent])
    return result
  }

  @Override
  void markChanges(JobItem jobItem) {
    applyScmChanges()
  }

  /**
   * Change the initial jobItem directives in accordance to the detected scm changes.
   * @param jobItem
   */
  @Override
  void applyScmChanges() {
    // in a complete build (checkout, build + test),
    // we mark this item as changed to prevent recalculating what changed in latter stages
    if (jobItem.execAuto && !jobItem.changed) {
      ScmUtils.setChangelog(steps, jobItem)
      CommandBuilder command = getCommandBuilder(jobItem)

      if (updateProjectList(command, jobItem)) {
        steps.log.debug "Changes detected for ${jobItem.getJobID()}:", ScmUtils.getCheckoutMetadata(jobItem)

        if (jobItem.execType == AUTO_DOWNSTREAMS) {
          // change the remaining groups to FORCE
          // TODO: use some kind of dependency graph to change only the affected downstreams
          forceRemainingJobItems(jobItem, buildData.buildMap)
        }
      } else {
        steps.log.debug "No changes detected for ${jobItem.getJobID()}:", ScmUtils.getCheckoutMetadata(jobItem)
        // no changes detected, this job should be skipped
        jobItem.set('skip', true)
      }
    }
  }

  CommandBuilder getCommandBuilder(JobItem jobItem, String... args) {
    List<String> options = [buildData.getString(MAVEN_DEFAULT_COMMAND_OPTIONS)]
    if (jobItem.buildFile) options += "-f ${jobItem.buildFile}"

    // This is for using Takari Concurrent Local Repository which uses aether so to avoid the occasional .part file
    // 'resume' (see: https://github.com/takari/takari-local-repository/issues/4) issue we send this:
    options += '-Daether.connector.resumeDownloads=false'
    options.addAll(args)

    CommandBuilder command = steps.getMavenCommandBuilder(options: options.join(' '))
    applyBuildDirectives(command, buildData.getString(MAVEN_DEFAULT_DIRECTIVES), jobItem.directives)
    return command
  }

  Closure getMvnDsl(JobItem jobItem, String cmd) throws Exception {
    String mavenSettingsFile = buildData.getString(MAVEN_SETTINGS)
    String globalMavenSettingsFile = mavenSettingsFile
    String mavenLocalRepoPath = "${buildData.getString(LIB_CACHE_ROOT_PATH)}/maven"
    String mavenRepoMirror = buildData.getString(MAVEN_RESOLVE_REPO_URL)
    String mavenPublicReleaseRepo = buildData.getString(MAVEN_PUBLIC_RELEASE_REPO_URL)
    String mavenPublicSnapshotRepo = buildData.getString(MAVEN_PUBLIC_SNAPSHOT_REPO_URL)
    String mavenPrivateReleaseRepo = buildData.getString(MAVEN_PRIVATE_RELEASE_REPO_URL)
    String mavenPrivateSnapshotRepo = buildData.getString(MAVEN_PRIVATE_SNAPSHOT_REPO_URL)
    String jdk = buildData.getString(JENKINS_JDK_FOR_BUILDS)
    String mavenTool = buildData.getString(JENKINS_MAVEN_FOR_BUILDS)
    String mavenOPTS = buildData.getString(MAVEN_OPTS)
    String artifactDeployerCredentialsId = buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)

    return { ->
      steps.dir(jobItem.buildWorkDir) {
        steps.withEnv([
          "RESOLVE_REPO_MIRROR=${mavenRepoMirror}",
          "PUBLIC_RELEASE_REPO_URL=${mavenPublicReleaseRepo}",
          "PUBLIC_SNAPSHOT_REPO_URL=${mavenPublicSnapshotRepo}",
          "PRIVATE_RELEASE_REPO_URL=${mavenPrivateReleaseRepo}",
          "PRIVATE_SNAPSHOT_REPO_URL=${mavenPrivateSnapshotRepo}",
          "MAVEN_OPTS=${mavenOPTS}"
        ]) {
          steps.withCredentials([steps.usernamePassword(credentialsId: artifactDeployerCredentialsId,
            usernameVariable: 'NEXUS_DEPLOY_USER', passwordVariable: 'NEXUS_DEPLOY_PASSWORD')]) {

            if (jobItem.containerized) {
              process("${cmd} -V -s ${mavenSettingsFile} -Dmaven.repo.local='${mavenLocalRepoPath}'", steps)
            } else {
              steps.withMaven(
                mavenSettingsFilePath: mavenSettingsFile,
                globalMavenSettingsFilePath: globalMavenSettingsFile,
                jdk: jdk,
                maven: mavenTool,
                mavenLocalRepo: mavenLocalRepoPath,
                mavenOpts: mavenOPTS,
                publisherStrategy: 'EXPLICIT') {
                process(cmd, steps)
              }
            }
          }
        }
      }
    }
  }

  /**
   * This will update the command to best accommodate the detected scm changes.
   * Also, if any change doesn't belong to the initial project list, that change is not included.
   * @param command
   * @param jobItem
   * @return true if the command can be evaluated, false otherwise
   */
  boolean updateProjectList(CommandBuilder command, JobItem jobItem) {
    List<String> changes = jobItem.changeLog

    // changes is null, then this is the first build or there are no previous successful builds,
    // return true to trigger a new build
    if (changes == null) return true

    // changes is empty, that means nothing changed,
    // return false to skip this build
    if (changes.empty) return false

    List<String> projectList = command.getProjectList()
    List<String> exclProjects = command.getExcludedProjectList()

    MavenModule baseModule = buildMavenModule(jobItem, command)

    String checkoutDir = Paths.get(jobItem.checkoutDir).toAbsolutePath().toString()
    String buildFileDir = relativize(baseModule.pom.parent.remote, checkoutDir)

    if (command.hasOption('N')) {
      // NON_RECURSIVE item, check only changes for base module
      List<String> modules = baseModule.activeModules

      // changes must be under the base project but not on it's modules
      return changes.any { String changedFile ->
        isChild(changedFile, buildFileDir) && !modules.any { String module ->
          isChild(changedFile, resolve(module, buildFileDir))
        }
      }
    }

    List<String> modulePaths = baseModule.allActiveModules + baseModule.path
    modulePaths = intersect(modulePaths, projectList)
    modulePaths -= exclProjects

    steps.log.debug "Considered module paths:", modulePaths

    // maven modules to consider, ordered by descending depth
    List<MavenModule> mavenModules = (baseModule.allModules + baseModule)
      .findAll { it.fullPath in modulePaths }
      .sort(true, descendingDepth)

    steps.log.debug "Considered maven modules:", mavenModules

    // calculate the maven modules that contain the changes
    Set<MavenModule> changedModules = []
    changes.each { String changedFile ->
      for (MavenModule module : mavenModules) {
        // if the change is contained in this module we stop looking as it's the
        // most specific on due to the fact the modules are in descending depth order
        if (isChild(changedFile, resolve(module.fullPath, buildFileDir))) {
          changedModules << module
          break
        }
      }
    }

    steps.log.debug "Changed maven modules:", changedModules

    // changes do not fit the considered modules
    if (!changedModules) return false

    // if the build file is in the list of changes, don't update and build everything
    if (changedModules.contains(baseModule)) {
      return true
    }

    // We wan't to manually define the project list,
    // we can't remove Profiles because we can't control their activation condition
    command.removeOption('-pl')

    // sorting is to help test/debug only, there is no direct gain doing this
    String changedModulePaths = changedModules.collect { it.fullPath }.sort().join(',')
    command << "-pl ${changedModulePaths}"
    steps.log.debug "Command project list for ${jobItem.getJobID()} changed to:", changedModulePaths

    jobItem.updateDirectives("${command.goals.join(' ')} ${command.options.join(' ')}")
    jobItem.setChanged(true)
    steps.log.debug "Directives for ${jobItem.getJobID()} updated to:", jobItem.directives

    return true
  }

  static Comparator<MavenModule> descendingDepth = new Comparator<MavenModule>() {
    @NonCPS
    int compare(MavenModule o1, MavenModule o2) {
      o2.depth <=> o1.depth
    }
  }

  class MavenProjectWrapper implements Serializable {
    String path
    String versionlessKey

    MavenProjectWrapper(String path, String versionlessKey) {
      this.path = path
      this.versionlessKey = versionlessKey
    }
  }

  class MavenSonarAnalyser extends SonarAnalyser implements Serializable {
    CommandBuilder commandBuilder

    String goal = 'sonar:sonar'
    List<String> optBlacklist = ['-pl', '-am', '-amd']

    MavenSonarAnalyser(CommandBuilder commandBuilder) {
      this.commandBuilder = commandBuilder

      this.inclusions = generateInclusions(commandBuilder.getProjectList())
      this.exclusions = generateExclusions(commandBuilder.getExcludedProjectList())

      this.commandBuilder.goals.clear()
      optBlacklist.each { String opt -> this.commandBuilder.removeOption(opt) }
    }

    @Override
    String getCommand() {
      commandBuilder << goal
      commandBuilder << "-Dsonar"

      if (inclusions || exclusions) {
        commandBuilder << "-pl ${(inclusions + exclusions).join(',')}"
      }

      if (buildData.isPullRequest()) {
        commandBuilder << "-Dsonar.pullrequest.branch=${buildData.getString(BRANCH_NAME)}"
        commandBuilder << "-Dsonar.pullrequest.key=${buildData.get(CHANGE_ID)}"
        commandBuilder << "-Dsonar.pullrequest.base=${buildData.get(CHANGE_TARGET)}"

        // pipeline-github plugin provides PR head commit so we can tell sonar and avoid considering the merge commit
        commandBuilder << "-Dsonar.scm.revision=${steps.pullRequest.head}"

        if (buildData.getBool(PR_STATUS_REPORTS)) {
          commandBuilder << "-Dsonar.pullrequest.github.repository=${jobItem.scmInfo.organization}/${jobItem.scmInfo.repository}"
        }
      } else {
        commandBuilder << "-Dsonar.branch.name=${buildData.getString(BRANCH_NAME)}"
      }

      return commandBuilder.build()
    }

    /**
     * Sonar maven plugin needs all the modules between the root and the module to scan so we calculate that here.
     * For example, the module "m1/m2/m3" will turn into [., m1, m1/m2, m1/m2/m3] so that we can make the
     * sonar plugin happy.
     *
     * @param modules
     * @return
     */
    @NonCPS
    private List<String> generateInclusions(List<String> modules){
      if(!modules) return []

      Set<String> allModuleParts = ['.']
      modules.each { String modulePath ->
        List pathParts = modulePath.tokenize('/')

        (0..pathParts.size() - 1).each { int i ->
          allModuleParts << pathParts[0..i].join('/')
        }
      }

      allModuleParts.sort() as List<String>
    }

    /**
     * For exclusions we can just negate the modules to exclude, no extra magic required.
     *
     * @param modules
     * @return
     */
    @NonCPS
    private List<String> generateExclusions(List<String> modules){
      if(!modules) return []

      modules.collect { String modulePath -> "!${modulePath}" }.sort() as List<String>
    }

  }
}