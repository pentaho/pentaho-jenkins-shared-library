/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
import hudson.FilePath
import hudson.model.Node
import org.hitachivantara.ci.JobItem
import org.hitachivantara.ci.ScmException
import org.hitachivantara.ci.StageException
import org.hitachivantara.ci.build.PipelineSignalException
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.docker.workflow.client.ControlGroup
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.flow.FlowDefinition
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition

import static org.hitachivantara.ci.config.LibraryProperties.DOCKER_IMAGE_HOST
import static org.hitachivantara.ci.config.LibraryProperties.SLAVE_NODE_LABEL
import static org.hitachivantara.ci.config.LibraryProperties.DOCKER_REGISTRY_URL
import static org.hitachivantara.ci.config.LibraryProperties.WORKSPACE

/**
 * Call the body, and catch any exception.
 * If an exception occurs, it calls the handler for custom error manipulation. However the handler is ignored if the
 * body was terminated, and the error is rethrown.
 *
 * The default implementation is equivalent to:
 * <pre> {@code
 * try {
 *   body()
 * } catch (Throwable e) {
 *   handler(e)
 * } finally {
 *   last()
 * }
 * }</pre>
 * @param body
 * @param handler
 * @param last
 * @return
 */
def handleError(Closure body, Closure handler = {}, Closure last = {}) {
  try {
    body.call()
  }
  catch (FlowInterruptedException fie) {
    //bypass handler
    throw fie
  }
  catch (PipelineSignalException pie) {
    // job was manually interrupted
    log.error pie.message, pie
    job.setBuildResult(pie.result)

    throw pie
  }
  catch (Throwable e) {
    handler.call(e)
  }
  finally {
    last.call()
  }
}

/**
 * This method takes in a map with git configuration and wraps the provided closure with a
 * withCredentials and withEnv block while passing an authenticated URL to the closure so that
 * git commands can be performed properly.
 *
 * The given map is assumed to contain:
 *
 *  connection - the connection type (@see org.hitachivantara.ci.JobItem.SCMConnectionType)
 *  organization - the organization name
 *  repository - the repository name
 *  credentials - jenkins credentials ID to use
 *
 * @param info Map containing the information required to setup git environment and credentials
 * @return
 */
def withGit(Map info, Closure body) {
  List gitEnvironment = [
    "GIT_ORG=${info.organization}",
    "GIT_REPO=${info.repository}"
  ]

  // find the proper credentials based on the scm connection
  switch (info.connection) {
    case JobItem.SCMConnectionType.HTTP:
      withCredentials([usernamePassword(
        credentialsId: info.credentials,
        usernameVariable: 'GIT_USERNAME',
        passwordVariable: 'GIT_PASSWORD'
      )]) {

        String gitUserEncoded = URLEncoder.encode(GIT_USERNAME as String, 'UTF-8')
        String gitPassEncoded = URLEncoder.encode(GIT_PASSWORD as String, 'UTF-8')

        // Wrap the encoded user/pass so it gets masked
        wrap([
          $class          : 'MaskPasswordsBuildWrapper',
          varPasswordPairs: [[password: gitUserEncoded], [password: gitPassEncoded]]
        ]) {
          gitEnvironment += [
            "GIT_AUTHOR_NAME=$GIT_USERNAME",
            "GIT_COMMITTER_NAME=$GIT_USERNAME",
            "GIT_AUTHOR_EMAIL=$GIT_USERNAME@hitachivantara.com",
            "GIT_COMMITTER_EMAIL=$GIT_USERNAME@hitachivantara.com",
            "GIT_USERNAME=${gitUserEncoded}",
            "GIT_PASSWORD=${gitPassEncoded}"
          ]

          withEnv(gitEnvironment) {
            body('https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${GIT_ORG}/${GIT_REPO}.git')
          }
        }
      }
      break

    case JobItem.SCMConnectionType.SSH:
      withCredentials([sshUserPrivateKey(
        credentialsId: info.credentials,
        usernameVariable: 'GIT_USERNAME',
        keyFileVariable: 'GIT_KEY'
      )]) {
        gitEnvironment += [
          "GIT_AUTHOR_NAME=$GIT_USERNAME",
          "GIT_COMMITTER_NAME=$GIT_USERNAME",
          "GIT_AUTHOR_EMAIL=$GIT_USERNAME@hitachivantara.com",
          "GIT_COMMITTER_EMAIL=$GIT_USERNAME@hitachivantara.com",
          'GIT_SSH_COMMAND=ssh -i $GIT_KEY -o StrictHostKeyChecking=no'
        ]
        withEnv(gitEnvironment) {
          body('git@github.com:${GIT_ORG}/${GIT_REPO}.git')
        }
      }
      break

    default:
      throw new ScmException("Cannot process connection type [${info.connection}]")
  }
}

/**
 * The JOB_NAME will be in one of the following formats:
 *
 * Simple:                          "jobname"
 * Simple inside a folder(s):       "folder1/folder2/jobname"
 * Multibranch:                     "jobname/branch"
 * Multibranch inside a folder(s):  "folder1/folder2/jobname/branch"
 *
 * The BRANCH_NAME variable will only be present if we are dealing with a
 * multibranch pipeline so we use that to identify that situation
 *
 * @return The job name
 */
def jobName() {
  String fullJobName = env.JOB_NAME
  String branchName = env.BRANCH_NAME

  // grab the last part if no branchName or the one before last if branchName exists
  List nameParts = fullJobName.tokenize('/')
  String jobName = branchName ? nameParts[-2] : nameParts[-1]

  return jobName
}

/**
 * Generic timer method that takes a closure and passes its duration to a callback
 * @param body
 * @param callback
 */
void timer(Closure body, Closure callback = {}) {
  Date start = new Date()
  try {
    body.call()
  } finally {
    Date end = new Date()
    long duration = end.time - start.time
    callback(duration)
  }
}

/**
 * Mark current stage as skipped
 * @param name
 */
void markStageSkipped() {
  Utils.markStageSkippedForConditional(STAGE_NAME)
}

/**
 * Create a skipped stage with the given name
 * @param name
 */
void createStageSkipped(String name) {
  stage(name) {
    Utils.markStageSkippedForConditional(name)
  }
}

/**
 * Create an empty stage with the given name
 * @param name
 */
void createStageEmpty(String name) {
  stage(name) {
    log.info "Nothing to be done."
  }
}

/**
 * Create an empty stage that throws an error
 * @param name
 */
void createStageError(String name, String message) {
  stage(name) {
    throw new StageException(message)
  }
}

/**
 * Wraps a closure with a node if the number of entries to be parallelize greater than 1
 * @param entries
 * @return
 */
Map setNode(Map entries) {
  BuildData buildData = BuildData.instance
  if(!buildData.useMinions && entries.size() > 1){
    entries.each { String key, Object val ->
      entries[key] = { node(buildData.get(SLAVE_NODE_LABEL), val) }
    }
  }
  return entries
}

/**
 * Checks if current job's flow definition has scm config associated or not
 * @return
 */
boolean hasScmConfig() {
  WorkflowJob job = getContext(WorkflowJob.class)
  FlowDefinition flow = job.getDefinition()
  return (flow instanceof CpsScmFlowDefinition)
}

/**
 * Verify if the current Node is a Docker container
 * @return
 */
boolean isNodeContainer() {
  Node node = getContext(Node)
  FilePath cgroupFile = node.createPath("/proc/self/cgroup")

  return cgroupFile.exists() && ControlGroup.getContainerId(cgroupFile).present
}

/**
 * Run the given closure within a docker container
 * @param jobItem
 * @param body
 */
void withContainer(String imageName, Closure body) {
  BuildData buildData = BuildData.instance
  List volumes = []

  if (!isNodeContainer()) {
    // mount additional volumes from the host
    volumes << '/var/run/docker.sock:/var/run/docker.sock'
  }

  // DOCKER options (TODO this is a temporary implementation that should be changed when implementing BACKLOG-30236)
  String options = buildData.getString('DOCKER_OPTS')

  String gid = sh(returnStdout: true, script: 'stat -c %g /var/run/docker.sock')
  String args = volumes.collect { "-v $it" }.join(' ') + " --group-add ${gid} --memory-swappiness 0 ${options}"

  // set the current directory to the global workspace so it gets properly mounted as a volume
  // this may not be needed but it makes sure we dont mount one of the random workspace@N directories instead
  dir(buildData.getString(WORKSPACE)) {
    docker.withRegistry(buildData.getString(DOCKER_REGISTRY_URL),buildData.getString(ARTIFACT_DEPLOYER_CREDENTIALS_ID)) {
      def image = docker.image("${buildData.getString(DOCKER_IMAGE_HOST)}/${imageName}")
      image.pull()
      image.inside(args) {
        body.call()
      }
    }
  }
}

/**
 * Tag a job item git repository and push the tag to remote
 * @param item
 * @param tagName
 * @param tagMessage
 * @return
 */
void tagItem(JobItem item, String tagName, String tagMessage) {
  Map scmInfo = item.scmInfo + [credentials: item.scmCredentials]

  dir(item.buildWorkDir) {
    withGit(scmInfo) { String gitUrl ->
      sh("git tag -a ${tagName} -m '${tagMessage}' --force")
      sh("git push ${gitUrl} ${tagName} --force")
    }
  }
}

/**
 * Push a job item into the git repository
 * @param item
 * @return
 */
void pushItem(JobItem item) {
  Map scmInfo = item.scmInfo + [credentials: item.scmCredentials]

  dir(item.buildWorkDir) {
    withGit(scmInfo) { String gitUrl ->
      sh("git push ${gitUrl} HEAD:${item.scmBranch}")
    }
  }
}
