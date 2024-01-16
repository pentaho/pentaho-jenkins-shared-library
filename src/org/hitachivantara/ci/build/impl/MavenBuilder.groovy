/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.hitachivantara.ci.build.impl

import com.cloudbees.groovy.cps.NonCPS
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmUtils
import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.BuilderException
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.build.SonarAnalyser
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
import static org.hitachivantara.ci.build.helper.BuilderUtils.processOutput
import static org.hitachivantara.ci.config.LibraryProperties.ARTIFACTORY_BASE_URL
import static org.hitachivantara.ci.config.LibraryProperties.ARTIFACT_DEPLOYER_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.SCM_API_TOKEN_CREDENTIALS_ID
import static org.hitachivantara.ci.config.LibraryProperties.BRANCH_NAME
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET
import static org.hitachivantara.ci.config.LibraryProperties.JENKINS_JDK_FOR_BUILDS
import static org.hitachivantara.ci.config.LibraryProperties.LIB_CACHE_ROOT_PATH
import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_TEST_OPTS
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS
import static org.hitachivantara.ci.config.LibraryProperties.DOCKER_RESOLVE_REPO
import static org.hitachivantara.ci.config.LibraryProperties.DOCKER_PUBLIC_PUSH_REPO
import static org.hitachivantara.ci.config.LibraryProperties.DOCKER_PRIVATE_PUSH_REPO
import static org.hitachivantara.ci.config.LibraryProperties.NODEJS_BUNDLE_REPO_URL
import static org.hitachivantara.ci.config.LibraryProperties.NPM_RELEASE_REPO_URL

class MavenBuilder extends AbstractBuilder implements IBuilder, Serializable {

  String name = BuildFramework.MAVEN.name()

  // This is for using Takari Concurrent Local Repository which uses aether so to avoid the occasional .part file
  // 'resume' (see: https://github.com/takari/takari-local-repository/issues/4) issue we send this:
  final String BASE_OPTS = '-Daether.connector.resumeDownloads=false'

  MavenBuilder(String id, JobItem item) {
    this.item = item
    this.id = id
  }

  @Override
  String getExecutionCommand() {
    CommandBuilder command = getCommandBuilder()
    return command.build()
  }

  @Override
  Closure getExecution() {
    String mvnCommand = getExecutionCommand()
    steps.log.info "Maven directives for ${item.jobID}: $mvnCommand"
    return getMvnDsl(mvnCommand)
  }

  @Override
  Closure getSonarExecution() {
    CommandBuilder command = getCommandBuilder()

    SonarAnalyser analyser = new MavenSonarAnalyser(command)
    String mvnCommand = analyser.getCommand()
    steps.log.info "Maven SonarAnalyser directives for ${item.jobID}: $mvnCommand"
    return getMvnDsl(mvnCommand)
  }

  @Override
  Closure getFrogbotExecution() {
    // Frogbot will only run on a PR
    if (!buildData.isPullRequest()) {
      steps.log.info "This is not a Pull Request build! Skipping..."
      return { -> }
    }

    String artifactoryURL = buildData.getString(ARTIFACTORY_BASE_URL)
    String gitProvider = "github"
    String gitRepo = item.scmInfo.repository
    String gitOwner = item.scmInfo.organization
    String gitPrNbr = buildData.get(CHANGE_ID)
    String deployCredentials = buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)
    String scmApiTokenCredential = buildData.getString(SCM_API_TOKEN_CREDENTIALS_ID)

    return { ->
      steps.dir(item.buildWorkDir) {
        steps.withEnv([
            "RESOLVE_REPO_MIRROR=${resolveRepo}",
            "JF_URL=${artifactoryURL}",
            "JF_GIT_PROVIDER=${gitProvider}",
            "JF_GIT_REPO=${gitRepo}",
            "JF_GIT_PULL_REQUEST_ID=${gitPrNbr}",
            "JF_GIT_OWNER=${gitOwner}"
        ]) {
          steps.withCredentials([steps.usernamePassword(credentialsId: deployCredentials,
              usernameVariable: 'JF_USER', passwordVariable: 'JF_PASSWORD'),
                                 steps.usernamePassword(credentialsId: deployCredentials,
                                     usernameVariable: 'NEXUS_DEPLOY_USER', passwordVariable: 'NEXUS_DEPLOY_PASSWORD'),
                                 steps.string(credentialsId: scmApiTokenCredential, variable: 'JF_GIT_TOKEN')]) {

            String localSettingsFile = item.settingsFile ?: settingsFile
            processOutput("mkdir -p \$HOME/.m2/ && cp ${localSettingsFile } \$HOME/.m2/settings.xml", steps)
            steps.log.info "Copied ${localSettingsFile} into \$HOME/.m2/"

            steps.log.info "Running /opt/frogbot scan-pull-request"
            if (item.containerized) {
              process("/opt/frogbot scan-pull-request --mvn", steps)
            }
          }
        }
      }
    }
  }

  @Override
  void setBuilderData(Map builderData) {
    throw new BuilderException('Deprecated method that should not be used')
  }

  @Override
  Closure getBuildClosure(JobItem jobItem) {
    CommandBuilder command = getCommandBuilder('-DskipTests')
    String mvnCommand = command.build()
    steps.log.info "Maven build directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(mvnCommand)
  }

  @Override
  Closure getTestClosure(JobItem jobItem) {
    CommandBuilder command = getCommandBuilder('test', buildData.getString(MAVEN_TEST_OPTS))

    // list of goals that we want stripped from the final command
    command -= ['clean', 'validate', 'compile', 'verify', 'package', 'install', 'deploy', '-Dmaven.test.skip', '-Drelease'].join(' ')

    String mvnCommand = command.build()
    steps.log.info "Maven test directives for ${jobItem.jobID}: $mvnCommand"
    return getMvnDsl(mvnCommand)
  }

  MavenModule buildMavenModule(CommandBuilder command) throws Exception {
    Map properties = command.getUserProperties()
    List<String> activeProfiles = command.getActiveProfileIds()
    List<String> inactiveProfiles = command.getInactiveProfileIds()
    String file = Paths.get(item.buildWorkDir, item.buildFile ?: item.buildFramework.buildFile).toString()

    try {
      return steps.buildMavenModule(
        file: file,
        activeProfiles: activeProfiles,
        inactiveProfiles: inactiveProfiles,
        userProperties: properties
      )
    } catch(Throwable e) {
      buildData.error('Model Building', item, e)
      throw new BuilderException("Couldn't generate a Maven Module for this project.")
    }
  }

  List<MavenProjectWrapper> getActiveModules(boolean alsoMakeParent = true) {
    List<MavenProjectWrapper> activeMavenProjects = []

    CommandBuilder command = getCommandBuilder()
    MavenModule rootModule = buildMavenModule(command)

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
          !exclProjects.any { isChild(mavenProject.path, resolve(it, item.root)) }
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
    // Check for expansion configuration
    if (!item.parallel) {
      return [[item]]
    }

    CommandBuilder command = getCommandBuilder()
    // Nothing to expand if NON_RECURSIVE
    if (command.hasOption('N')) {
      return [[item]]
    }

    List<String> projectList = command.getProjectList()
    List<String> exclProjects = command.getExcludedProjectList()

    MavenModule rootModule = buildMavenModule(command)
    FilteredProjectDependencyGraph dependencyGraph = steps.projectDependencyGraph(
      module: rootModule,
      whitelist: projectList
    )
    List<List<String>> activeModules = dependencyGraph.getSortedProjectsByGroups()

    if (activeModules.flatten().empty) {
      // we are a leaf, nothing to expand
      return [[item]]
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
          jobID       : "${item.jobID} (${module})",
          directives  : "${directives} ${command.getOptions().join(' ')}",
          parallelize : false,
          checkoutDir : item.checkoutDir,
          buildWorkDir: item.buildWorkDir,
        ]

        JobItem innerJobItem = item.clone()
        innerJobItem.setJobData(newJobData)
        // this extra steps are needed so when archiving results, we only get this module(s) once
        // still doesn't guard us if a later jobItem calls the junit plugin again on the same folders
        // TODO: remove this when the problem of tests duplication get fixed
        String parent = new File(innerJobItem.buildFile ?: '').getParent()
        innerJobItem.setModulePaths(paths.collect {
          Paths.get(item.buildWorkDir, parent ?: '', it).toString()
        } as String[])
        return innerJobItem
      }
    }
    // also make parent
    command.removeOption('-pl')
    command << "-N"
    JobItem parent = item.clone()
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
    if (item.execAuto) {
      item.skip = false

      if (!item.hasChangeLog()) {
        ScmUtils.setChangelog(steps, item)
      }

      Boolean executable = updateDirectives()
      if (executable) {
        steps.log.debug "Buildable changes detected for ${item.getJobID()}", ScmUtils.getCheckoutMetadata(item)

        if (item.execType == AUTO_DOWNSTREAMS) {
          // change the remaining groups to FORCE
          // TODO: use some kind of dependency graph to change only the affected downstreams
          forceRemainingJobItems(item, buildData.buildMap)
        }
      } else {
        steps.log.debug "Changes not buildable for ${item.jobID}, skipping", ScmUtils.getCheckoutMetadata(item)
        item.skip = true
      }
    }
  }

  CommandBuilder getCommandBuilder(String... args) {
    List<String> options = [defaultCommandOptions]

    if (item.buildFile) options.add("-f ${item.buildFile}")
    options.addAll(args)

    CommandBuilder command = steps.getMavenCommandBuilder(options: options.join(' '))
    applyBuildDirectives(command, getDefaultDirectives(id), item.getDirectives(id))

    return command
  }

  Closure getMvnDsl(String cmd) throws Exception {
    String jdk = buildData.getString(JENKINS_JDK_FOR_BUILDS)
    String mavenOpts = "${BASE_OPTS} ${opts}"
    String localRepoPath = "${buildData.getString(LIB_CACHE_ROOT_PATH)}/maven"
    String nodeDownloadRoot = "${buildData.getString(NODEJS_BUNDLE_REPO_URL)}"
    String npmDownloadRoot = "${buildData.getString(NPM_RELEASE_REPO_URL)}"
    String deployCredentials = buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)
    String scmApiTokenCredential = buildData.getString(SCM_API_TOKEN_CREDENTIALS_ID)

    return { ->
      steps.dir(item.buildWorkDir) {
        steps.withEnv([
          "RESOLVE_REPO_MIRROR=${resolveRepo}",
          "PUBLIC_RELEASE_REPO_URL=${publicReleaseRepo}",
          "PUBLIC_SNAPSHOT_REPO_URL=${publicSnapshotRepo}",
          "PRIVATE_RELEASE_REPO_URL=${privateReleaseRepo}",
          "PRIVATE_SNAPSHOT_REPO_URL=${privateSnapshotRepo}",
          "DOCKER_PULL_HOST=${buildData.getString(DOCKER_RESOLVE_REPO)}",
          "DOCKER_PUBLIC_PUSH_HOST=${buildData.getString(DOCKER_PUBLIC_PUSH_REPO)}",
          "DOCKER_PRIVATE_PUSH_HOST=${buildData.getString(DOCKER_PRIVATE_PUSH_REPO)}",
          "nodeDownloadRoot=${buildData.getString(NODEJS_BUNDLE_REPO_URL)}",
          "npmDownloadRoot=${buildData.getString(NPM_RELEASE_REPO_URL)}",
          "MAVEN_OPTS=${mavenOpts}"
        ]) {
          steps.withCredentials([steps.usernamePassword(credentialsId: deployCredentials,
            usernameVariable: 'NEXUS_DEPLOY_USER', passwordVariable: 'NEXUS_DEPLOY_PASSWORD'),
            steps.string(credentialsId: scmApiTokenCredential, variable: 'SCM_API_TOKEN')]) {

            String localSettingsFile = item.settingsFile ?: settingsFile

            if (item.containerized) {
              process("${cmd} -V -s ${localSettingsFile} -Dmaven.repo.local='${localRepoPath}' -DnodeDownloadRoot='${nodeDownloadRoot}' -DnpmDownloadRoot='${npmDownloadRoot}'", steps)
            } else {
              steps.withMaven(
                mavenSettingsFilePath: localSettingsFile,
                jdk: jdk,
                maven: jenkinsTool,
                mavenLocalRepo: localRepoPath,
                mavenOpts: mavenOpts,
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
   * This will update the directives to best accommodate the detected scm changes.
   * Also, if any change doesn't belong to the initial project list, that change is not included.
   *
   * @return true if the command can be executed, false otherwise
   */
  boolean updateDirectives() {
    List<String> changes = item.changeLog

    // changes is null, then this is the first build or there are no previous successful builds,
    // return true to trigger a new build
    if (changes == null) return true

    // changes is empty, that means nothing changed,
    // return false to skip this build
    if (changes.empty) return false

    CommandBuilder command = getCommandBuilder()
    List<String> projectList = command.getProjectList()
    List<String> exclProjects = command.getExcludedProjectList()

    MavenModule baseModule = buildMavenModule(command)

    String checkoutDir = Paths.get(item.checkoutDir).toAbsolutePath().toString()
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
    steps.log.debug "Command project list for ${item.getJobID()} changed to:", changedModulePaths

    item.updateDirectives("${command.goals.join(' ')} ${command.options.join(' ')}")
    steps.log.debug "Directives for ${item.getJobID()} updated to:", item.getDirectives(id)

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
          commandBuilder << "-Dsonar.pullrequest.github.repository=${item.scmInfo.organization}/${item.scmInfo.repository}"
        }
      } else if (buildData.getString(BRANCH_NAME) != 'master') {
        // send branch name only if it's not master, sending master on a first scan causes error
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
