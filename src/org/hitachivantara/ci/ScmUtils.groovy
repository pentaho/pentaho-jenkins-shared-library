package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import hudson.EnvVars
import hudson.FilePath
import hudson.model.Cause
import hudson.model.Job
import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import hudson.plugins.git.BranchSpec
import hudson.plugins.git.GitChangeLogParser
import hudson.plugins.git.GitChangeSet
import hudson.plugins.git.GitSCM
import hudson.plugins.git.UserRemoteConfig
import hudson.plugins.git.browser.GitRepositoryBrowser
import hudson.scm.ChangeLogSet
import jenkins.scm.api.SCMHead
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.MinionHandler
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.scm.SCMData
import org.jenkinsci.plugins.gitclient.ChangelogCommand
import org.jenkinsci.plugins.gitclient.GitClient
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

import java.nio.charset.StandardCharsets
import java.util.regex.Matcher
import java.util.regex.Pattern

import static org.hitachivantara.ci.build.helper.BuilderUtils.process
import static org.hitachivantara.ci.build.helper.BuilderUtils.processOutput
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_TARGET
import static org.hitachivantara.ci.config.LibraryProperties.CHECKOUT_TIMEOUT_MINUTES
import static org.hitachivantara.ci.config.LibraryProperties.CHECKOUT_DEPTH
import static org.hitachivantara.ci.config.LibraryProperties.SHALLOW_CLONE
import static org.hitachivantara.ci.config.LibraryProperties.CHANGES_FROM_LAST

class ScmUtils implements Serializable {
  static final String GIT_BRANCH = 'GIT_BRANCH'
  static final String GIT_COMMIT = 'GIT_COMMIT'
  static final String GIT_PREVIOUS_COMMIT = 'GIT_PREVIOUS_COMMIT'
  static final String GIT_URL = 'GIT_URL'
  static final String GIT_REV_LIST = 'GIT_REV_LIST'

  static final String COMMIT_ID = 'COMMIT_ID'
  static final String COMMIT_TITLE = 'COMMIT_TITLE'
  static final String COMMIT_AUTHOR = 'COMMIT_AUTHOR'
  static final String COMMIT_COMMENT = 'COMMIT_COMMENT'
  static final String COMMIT_URL = 'COMMIT_URL'
  static final String COMMIT_DATE = 'COMMIT_DATE'
  static final String COMMIT_PATHS = 'COMMIT_PATHS'

  static final Pattern GIT_REF = Pattern.compile("^(refs/[^/]+)/(.+)")
  static final Pattern GIT_REFTAG = Pattern.compile("^(refs/tags/[^/]+)(.*)")

  static protected String getRefSpec(JobItem jobItem) {
    String refspecs
    String src = getRemoteRefSpecPattern(jobItem.scmBranch)
    String dst = getLocalRefSpecPattern(src)
    // The + tells Git to update the reference even if it isn’t a fast-forward.
    refspecs = "+${src}:${dst}"

    if (isTag(src)) {
      // tags have special treatments, add another ref, the first one is treated as local branch, this one is tags
      refspecs += " +${src}:${src}"
    }
    return refspecs
  }

  @NonCPS
  static protected String getRemoteRefSpecPattern(String branchName) {
    if (branchName ==~ GIT_REF) {
      // branchName is refs/(heads|tags|whatever)/branch
      return branchName
    }
    if (branchName.contains('/')) {
      //head is remote/branchName, also ambiguous, but complement with refs/
      return "refs/$branchName"
    }
    // ambiguous name, fall back to branch behaviour
    return "refs/heads/$branchName"
  }

  @NonCPS
  static protected String getLocalRefSpecPattern(String branchName) {
    String name = getRemoteRefSpecPattern(branchName)
    // convert branchName `refs/(heads|tags|whatever)/branch` into shortcut notation `remote/branch`
    Matcher matcher = GIT_REF.matcher(name)
    matcher.matches() //always true
    return 'refs/remotes/origin' + name.substring(matcher.group(1).size())
  }

  @NonCPS
  static protected String deriveLocalBranchName(String branchName) {
    String name = getRemoteRefSpecPattern(branchName)
    // convert branchName `refs/(heads|tags|whatever)/branch` into shortcut notation `remote/branch`
    Matcher matcher = GIT_REF.matcher(name)
    matcher.matches() //always true
    return matcher.group(2).replaceFirst('^[^/]+/', '')
  }

  @NonCPS
  static private boolean isTag(String remoteRefSpec) {
    return remoteRefSpec ==~ GIT_REFTAG
  }

  static GitSCM getGitSCM(JobItem jobItem) {
    return new GitSCM(
      [new UserRemoteConfig(jobItem.scmUrl, 'origin', getRefSpec(jobItem), jobItem.scmCredentials)],
      [new BranchSpec(getRemoteRefSpecPattern(jobItem.scmBranch))],
      false, [], null, null, []
    )
  }

  static GitClient getGitClient(Script steps, GitSCM scm, String checkoutDir) {
    Run build = steps.currentBuild.rawBuild
    TaskListener listener = steps.getContext(TaskListener.class)
    FilePath ws = FileUtils.create(checkoutDir)
    EnvVars env = steps.getContext(EnvVars.class)
    return scm.createClient(listener, env, build, ws)
  }

  /**
   * Checkout the repository with GitSCM.
   *
   * @param steps the steps steps
   * @param jobItem JobItem with scm url to clone from
   * @param poll configure polling or not
   * @return
   */
  static Map doCheckout(Script steps, JobItem jobItem, boolean poll) {
    BuildData.instance.isPullRequest() ? checkoutPR(steps) : checkoutBranch(steps, jobItem, poll)

    /*
     * Note: The result of checkout can't be trusted!
     * see https://issues.jenkins-ci.org/browse/JENKINS-53346
     */
    Map result = buildCheckoutMetadata(steps, jobItem, null)
    setCheckoutMetadata(jobItem, result)

    // bind this metadata to this build
    steps.currentBuild.rawBuild.addAction(
      new SCMData(
        result[GIT_URL] as String,
        result[GIT_BRANCH] as String,
        result[GIT_COMMIT] as String
      )
    )

    // tell the upstream we have changes
    updateUpstreamChangeSets(steps.currentBuild)
    return result
  }

  /**
   * Checks out a branch or tag
   * @param steps
   * @param jobItem
   * @param poll
   * @return
   */
  static private void checkoutBranch(Script steps, JobItem jobItem, boolean poll) {
    BuildData buildData = BuildData.instance

    int timeout = buildData.getInt(CHECKOUT_TIMEOUT_MINUTES)
    int depth = buildData.getInt(CHECKOUT_DEPTH)
    boolean shallow = buildData.getBool(SHALLOW_CLONE) && !jobItem.isCreateRelease()

    /*
     * The Git plugin checks code out to a detached head.
     * We configure a LocalBranch extension to force checkout to a specific local branch.
     */
    String remoteRefSpec = jobItem.scmRevision ?: getRemoteRefSpecPattern(jobItem.scmBranch)

    String scmUrl
    // test if scm exists on cache
    if (jobItem.scmCacheUrl && process("git ls-remote -q --exit-code ${jobItem.scmCacheUrl} ${remoteRefSpec} 2>1 1> /dev/null", steps, false) == 0) {
      scmUrl = jobItem.scmCacheUrl
    } else {
      // use upstream url
      scmUrl = jobItem.scmUrl
    }

    steps.checkout(
      poll: poll && !isTag(remoteRefSpec),
      changelog: true,
      scm: [
        $class                           : 'GitSCM',
        branches                         : [[name: remoteRefSpec]],
        doGenerateSubmoduleConfigurations: false,
        extensions                       : [
          [$class: 'CloneOption', depth: depth, timeout: timeout, honorRefspec: true, noTags: !jobItem.isCreateRelease(), reference: '', shallow: shallow],
          [$class: 'LocalBranch', localBranch: deriveLocalBranchName(jobItem.scmBranch)],
          [$class: 'CleanBeforeCheckout'],
          [$class: 'PruneStaleBranch'],
        ],
        submoduleCfg                     : [],
        userRemoteConfigs                : [
          [
            credentialsId: jobItem.scmCredentials,
            name         : 'origin',
            refspec      : getRefSpec(jobItem),
            url          : scmUrl
          ],
        ],
      ],
    )
  }

  /**
   * Checks out a Pull Request using the scm configuration on the job
   * @param steps
   * @return
   */
  static private void checkoutPR(Script steps) {
    Job job = steps.getContext(Job.class)

    if (!prSourceExists(job)) {
      throw new ScmException('Pull request build was aborted: Source fork/repository no longer exists! Please consider closing it.')
    }

    steps.checkout(steps.scm)
  }

  /**
   * Build the checkout metadata.
   * This is a workaround for https://issues.jenkins-ci.org/browse/JENKINS-53346
   * @param steps steps steps
   * @param jobItem
   * @param commitHash commit hash we are building on
   * @return
   */
  static private Map buildCheckoutMetadata(Script steps, JobItem jobItem, String commitHash) {
    String gitBranch = getBranchName(jobItem)
    String gitUrl = jobItem.scmUrl
    if (!commitHash) {
      // default back to local origin
      steps.log.debug "Using ${jobItem.scmRevision ?: gitBranch} revision!"
      commitHash = jobItem.scmRevision ?: processOutput("git rev-parse $gitBranch^{commit}", steps)
    }

    RunWrapper build = findSCMData(steps, steps.currentBuild, jobItem)
    String previousCommit

    if (BuildData.instance.isPullRequest()) {
      previousCommit = getBranchName(BuildData.instance.getString(CHANGE_TARGET))
    } else {
      RunWrapper lastBuild = lastBuildWithResult(Result.FAILURE, build)
      previousCommit = getLastCommitHash(lastBuild, gitBranch, gitUrl)
    }

    List<String> revList = getCommitsBetween(steps, commitHash, previousCommit)

    Map data = [
      (GIT_BRANCH)         : gitBranch,
      (GIT_COMMIT)         : commitHash,
      (GIT_PREVIOUS_COMMIT): previousCommit,
      (GIT_URL)            : gitUrl,
      (GIT_REV_LIST)       : revList,
    ]

    return data
  }

  static Map deriveCheckoutMetadata(Script steps, JobItem jobItem) {
    Map data = getCheckoutMetadata(jobItem)
    if (data) return data
    // Cache miss, which mean there are no checkout data present, why is that?
    // This could be a minion phased build, or a build where the checkouts are skipped.
    // We no longer rely on the git's HEAD state, because we can't be sure what stages had run previously, they may
    // have added commits like the versionStage.

    // Lets find the last checkout stage, and build the metadata from there!
    RunWrapper build = findSCMData(steps, steps.currentBuild, jobItem)
    String gitBranch = getBranchName(jobItem)
    String gitUrl = jobItem.scmUrl
    String head = getLastCommitHash(build, gitBranch, gitUrl)
    data = buildCheckoutMetadata(steps, jobItem, head)
    setCheckoutMetadata(jobItem, data)
    return data
  }

  static void copyCheckoutMetadata(JobItem orig, JobItem other) {
    Map<String, Object> scm = getCheckoutMetadata(orig)
    if (scm) {
      // shallow clone data
      setCheckoutMetadata(other, scm.clone() as Map)
    }
  }

  static void setCheckoutMetadata(JobItem jobItem, Map metadata) {
    jobItem.scmInfo.checkout = metadata
  }

  static Map getCheckoutMetadata(JobItem jobItem) {
    jobItem.scmInfo.checkout as Map ?: [:]
  }

  static List<String> getRevList(JobItem jobItem) {
    getCheckoutMetadata(jobItem).get(GIT_REV_LIST, [])
  }

  static String getBranchName(JobItem jobItem) {
    return getBranchName(jobItem.scmBranch)
  }

  static String getBranchName(String scmBranch) {
    // convert head `refs/(heads|tags|whatever)/branch` into shortcut notation `remote/branch`
    String name = getLocalRefSpecPattern(scmBranch)
    return cutRefs(name)
  }

  @NonCPS
  private static String cutRefs(String name) {
    Matcher matcher = GIT_REF.matcher(name)
    return matcher.matches() ? matcher.group(2) : name
  }

  /**
   * Get a list of commit ids in reverse chronological order for the given interval
   * @param steps the steps dsl
   * @param sha1 head commit, inclusive
   * @param sha2 older commit, exclusive
   * @return A list of commits ids from [sha1, sha2)
   */
  static List<String> getCommitsBetween(Script steps, String sha1, String sha2) {
    if (!sha2) return [sha1]
    // if the previous build got amended, the command will fail, we just return the head then.
    processOutput("git rev-list ${sha1} ^${sha2}", steps, false).tokenize('\n') ?: [sha1]
  }

  /**
   * Returns the last commit of a given build.
   * @param build
   * @param branch
   * @param url
   * @return
   */
  static String getLastCommitHash(RunWrapper build, String branch, String url) {
    if (!build) return null
    List<SCMData> actions = build.rawBuild.getActions(SCMData.class)
    SCMData buildData = actions?.find { it.scmUrl == url && it.branch == branch }
    return buildData?.commitId
  }

  static List<String> getChangesBetween(RunWrapper build, RunWrapper end, List<String> commits) {
    if (!commits) return []
    Set<String> files = []

    while (build?.number > end.number) {
      build.changeSets.each { ChangeLogSet log ->
        log.items.each { ChangeLogSet.Entry entry ->
          if (commits.contains(entry.commitId)) {
            files.addAll(entry.affectedPaths)
          }
        }
      }
      build = build.previousBuild
    }
    return files.toList()
  }

  /**
   * Check if the current working directory is inside the work tree of a repository.
   *
   * @param steps the steps dsl
   * @throws Exception
   */
  static void isInsideWorkTree(Script steps) throws Exception {
    int result = process("git rev-parse --is-inside-work-tree &>/dev/null", steps, false)
    if (result != 0) {
      steps.log.error "Current path is not a git repository", steps.pwd()
      throw new ScmException("Invalid work dir")
    }
  }

  /**
   * Calculates the change log inside a working tree.
   *
   * If previous success build is null, then this is the first build or there are no previous successful builds,
   * return null to trigger a new build.
   * If changes are an empty list, then nothing changed, return empty list to skip this build
   *
   * @param steps
   * @param jobItem
   * @return null if no successful builds, List otherwise
   */
  static List<String> calculateChanges(Script steps, JobItem jobItem) {
    // check if we are in a git folder
    isInsideWorkTree(steps)

    if (BuildData.instance.isPullRequest()) {
      return calculatePrChanges(steps, jobItem)
    }
    return calculateJenkinsChanges(steps, jobItem)
  }

  static protected List<String> calculatePrChanges(Script steps, JobItem jobItem) {
    // always fetch all changes
    Set files = []
    getRevisionChangeSets(steps, jobItem).each { GitChangeSet gitChangeSet ->
      files.addAll(gitChangeSet.affectedPaths)
    }
    return files.toList()
  }

  static protected List<String> calculateJenkinsChanges(Script steps, JobItem jobItem) {
    Map checkoutData = deriveCheckoutMetadata(steps, jobItem)
    String gitBranch = checkoutData[GIT_BRANCH]
    String gitUrl = checkoutData[GIT_URL]
    String headCommitHash = checkoutData[GIT_COMMIT]

    // find the latest successful build and get the change list
    RunWrapper build = findSCMData(steps, steps.currentBuild, jobItem)

    Result result = Result.fromString(BuildData.instance.getString(CHANGES_FROM_LAST)) // Reverts to FAILURE
    RunWrapper lastBuild = lastBuildWithResult(result, build)
    if (!lastBuild) return null

    String lastCommitHash = getLastCommitHash(lastBuild, gitBranch, gitUrl)
    if (!lastCommitHash) {
      steps.log.warn "Couldn't find last commit, new branch?"
      return null
    }

    try {
      List<String> commits = getCommitsBetween(steps, headCommitHash, lastCommitHash)
      return getChangesBetween(build, lastBuild, commits)
    } catch (Throwable err) {
      steps.log.warn "couldn't get commit objects", err
    }

    return null
  }

  /**
   * Finds the last build with a result better or equal to the given one.
   *
   * <p>
   * If the builds are phased, this gets trickier, because the information needed are within a checkout phase.
   * We'll need to traverse the consecutive builds searching for the first SCMData, then keep going
   * until the next SCMData remembering the results from each build.
   * </p>
   *
   * <p>
   * Example of a phased build
   * </p>
   * <blockquote>
   *   <code>
   *     <p>#6 Test (currentBuild)</p>
   *     <p>#5 Build (SUCCESS)</p>
   *     <p>#4 Checkout (SUCCESS)</p>
   *     <p>#3 Test (UNSTABLE)</p>
   *     <p>#2 Build (SUCCESS)</p>
   *     <p>#1 Checkout (SUCCESS)</p>
   *   </code>
   * </blockquote>
   * Trying to search for the last successful build here, would give null, because, combined, there are UNSTABLE results.
   * Although, the last UNSTABLE build would be #1.
   *
   * @param result
   * @param build
   * @return
   */
  @NonCPS
  static RunWrapper lastBuildWithResult(Result result, RunWrapper build) {
    // find the starting point for the current point
    RunWrapper r = findSCMData(build)

    // we have the first SCMData build, combine the results until the next SCMData
    r = r?.previousBuild
    Result results = Result.SUCCESS
    while (r) {
      results = results.combine(Result.fromString(r.getCurrentResult()))
      if (r.rawBuild.getAction(SCMData.class)) {
        if (results.isBetterOrEqualTo(result)) {
          break
        } else {
          // reset
          results = Result.SUCCESS
        }
      }
      r = r.previousBuild
    }
    return r
  }

  /**
   * Find the checkout point for the current build
   * @param build
   * @return the build which contains an associated SCMData
   */
  @NonCPS
  static RunWrapper findSCMData(RunWrapper build) {
    RunWrapper run = build
    while (run && !run.rawBuild.getAction(SCMData.class)) {
      run = run.previousBuild
    }
    return run
  }

  /**
   * Find the checkout point for the current build.
   * If this build acts as a delegate, find the jobItem's associated job, and use its last build.
   * @param currentBuild
   * @param jobItem
   * @return
   */
  static RunWrapper findSCMData(Script steps, RunWrapper currentBuild, JobItem jobItem) {
    if (BuildData.instance.useMinions) {
      RunWrapper run = JobUtils.getLastBuildJob(MinionHandler.getFullJobName(jobItem))
      if (!run) {
        steps.log.warn """\
          Couldn't find job ${MinionHandler.getFullJobName(jobItem)}!
          Some pipeline functionality may have unexpected results.
          Will be using $currentBuild""".stripIndent()
      }
      currentBuild = run ?: currentBuild
    }
    return findSCMData(currentBuild)
  }

  /**
   * Calculates the commit log for a given JobItem
   * @param steps steps dsl
   * @param jobItem
   * @return the commit log
   */
  static List<Map<String, Object>> getCommitLog(Script steps, JobItem jobItem) {
    GitSCM scm = getGitSCM(jobItem)

    GitRepositoryBrowser browser = scm.guessBrowser() as GitRepositoryBrowser
    if (!browser) steps.log.warn "No RepositoryBrowser available for:", scm.getUserRemoteConfigs()

    List<GitChangeSet> changeSetList = getRevisionChangeSets(steps, jobItem)
    return changeSetList.collect { GitChangeSet changeSet ->
      [
        (COMMIT_ID)     : changeSet.getCommitId(),
        (COMMIT_TITLE)  : changeSet.getMsg(),
        (COMMIT_URL)    : browser?.getChangeSetLink(changeSet)?.toString(),
        (COMMIT_AUTHOR) : changeSet.getAuthorName(),
        (COMMIT_COMMENT): changeSet.getComment(),
        (COMMIT_DATE)   : changeSet.getDate(),
        (COMMIT_PATHS)  : changeSet.getAffectedPaths(),
      ]
    }
  }

  static void setChangelog(Script steps, JobItem jobItem) {
    steps.dir(jobItem.checkoutDir) {
      // calculate the changes in this working tree
      List<String> changes = calculateChanges(steps, jobItem)
      jobItem.changeLog = changes

      steps.log.info "SCM changed files for ${jobItem.jobID}:", jobItem.changeLog
    }
  }

  /**
   * Rebuilds the checkout info into the current build.
   * This is so for phased builds, to maintain any changelog report and polling facilities enabled.
   * @param build
   */
  @NonCPS
  static void rebuildCheckouts(RunWrapper build) {
    if (build) {
      Run current = build.getRawBuild()
      if (current instanceof WorkflowRun) {
        RunWrapper run = findSCMData(build)
        if (run) {
          WorkflowRun raw = run.getRawBuild() as WorkflowRun
          List checkoutList = current.checkouts(null)
          raw.checkouts(null).each { lastCheckout ->
            // adding missing checkout info
            if (!checkoutList.any { currentCheckout -> currentCheckout.scm.key == lastCheckout.scm.key }) {
              checkoutList.add(lastCheckout)
            }
          }
        }
      }
    }
  }

  /**
   * Rebuilds the checkouts on the upstream run, so that the aggregate view on the changes be accessible.
   * @param build
   */
  @NonCPS
  static void updateUpstreamChangeSets(RunWrapper build) {
    if (build) {
      Run run = build.rawBuild
      if (run instanceof WorkflowRun) {
        Cause.UpstreamCause upstreamCause = run.getCause(Cause.UpstreamCause)
        Run upstreamRun = upstreamCause?.getUpstreamRun()
        if (upstreamRun instanceof WorkflowRun) {
          List checkoutList = upstreamRun.checkouts(null)
          run.checkouts(null).each { lastCheckout ->
            // adding missing checkout info
            if (!checkoutList.any { currentCheckout -> currentCheckout.scm.key == lastCheckout.scm.key }) {
              checkoutList.add(lastCheckout)
            }
          }
          // refresh changes view
          synchronized (upstreamRun) {
            run.changeSets.each { ChangeLogSet changeLogSet ->
              if (!upstreamRun.changeSets.contains(changeLogSet)) {
                upstreamRun.changeSets.add(changeLogSet)
              }
            }
          }
        }
      }
    }
  }

  /**
   * Calculates the ChangeSet for a given JobItem
   * @param steps
   * @param jobItem
   * @return
   */
  static List<GitChangeSet> getRevisionChangeSets(Script steps, JobItem jobItem) {
    deriveCheckoutMetadata(steps, jobItem)

    GitSCM scm = getGitSCM(jobItem)
    GitClient client = getGitClient(steps, scm, jobItem.checkoutDir)

    List<String> revList = getRevList(jobItem)
    InputStream inputStream = filterChangelogRevisions(client, revList)

    GitChangeLogParser parser = scm.createChangeLogParser() as GitChangeLogParser
    List<GitChangeSet> changeSetList = parser.parse(inputStream)
    return changeSetList
  }

  static InputStream filterChangelogRevisions(GitClient gitClient, List<String> revList) {
    StringWriter writer = new StringWriter()
    ChangelogCommand changelog = gitClient.changelog().to(writer)

    if ( revList ) {
      changelog.max(revList.size())
      revList.each { String rev ->
        changelog.includes(rev)
      }
    }
    changelog.execute()
    return new ByteArrayInputStream(writer.toString().getBytes(StandardCharsets.UTF_8))
  }

  /**
   * Determines if the source of a pull request still exists
   * @return
   */
  @NonCPS
  static boolean prSourceExists(Job job) {
    PullRequestSCMHead head = (PullRequestSCMHead) SCMHead.HeadByItem.findHead(job)
    return head && head.sourceOwner && head.sourceRepo && head.sourceBranch
  }

}
