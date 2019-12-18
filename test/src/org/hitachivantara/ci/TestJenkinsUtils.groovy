/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.EnvVars
import hudson.model.AbstractBuild
import hudson.model.Item
import hudson.model.ItemGroup
import hudson.model.Job
import hudson.model.Result
import hudson.model.TaskListener
import hudson.tasks.junit.TestResultAction
import hudson.triggers.SCMTrigger
import jenkins.scm.api.SCMHead
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.github_branch_source.BranchSCMHead
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain

import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL

class TestJenkinsUtils extends BasePipelineSpecification {

  ConfigurationRule configRule = new ConfigurationRule(this)
  ReplacePropertyRule jobUtilsMetaClass = new ReplacePropertyRule((JobUtils): ['static.getSteps': { -> mockScript }])
  ReplacePropertyRule headByItemMetaClass = new ReplacePropertyRule()

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(jobUtilsMetaClass)
    .around(headByItemMetaClass)

  def "test SCM trigger management"() {
    setup:

    BuildData bd = BuildData.instance
    bd.buildProperties[POLL_CRON_INTERVAL] = configuredCron
    bd.buildProperties[IS_MINION] = isMinion

    SCMTrigger trigger = new SCMTrigger(currentCron)
    WorkflowJob job = GroovyMock(WorkflowJob, global: true) {
      getSCMTrigger() >> trigger
      addTrigger(_) >> { SCMTrigger scmTrigger -> trigger = scmTrigger }
      getTriggersJobProperty() >> {
        Mock(PipelineTriggersJobProperty.class) {
          removeTrigger(_) >> {
            trigger = null
          }
        }
      }
      asBoolean() >> true
    }

    when:
    JobUtils.managePollTrigger(job)

    then:
    trigger?.spec == expected

    where:
    currentCron    || configuredCron || isMinion || expected
    ''             || 'H/5 * * * *'  || true     || 'H/5 * * * *'
    'H/5 * * * *'  || 'H/15 * * * *' || true     || 'H/15 * * * *'
    'H/15 * * * *' || ''             || true     || null
    ''             || 'H/5 * * * *'  || false    || 'H/5 * * * *'
    'H/5 * * * *'  || 'H/15 * * * *' || false    || 'H/15 * * * *'
    'H/15 * * * *' || ''             || false    || 'H/15 * * * *'
  }

  def "test job data collecting"() {
    setup:
    Map jobItemData = [
      'item-with-pr-success'   : ['pr-number': '111', 'build-result': Result.SUCCESS, 'branch': 'PR-111'],
      'item-with-pr-failed'    : ['pr-number': '222', 'build-result': Result.FAILURE, 'branch': 'PR-222'],
      'item-without-pr-success': ['pr-number': '', 'build-result': Result.SUCCESS, 'branch': 'master'],
      'item-without-pr-aborted': ['pr-number': '', 'build-result': Result.ABORTED, 'branch': 'master'],
      'item-without-pr-failure': ['pr-number': '', 'build-result': Result.FAILURE, 'branch': 'master']
    ]

    List mockedJobItems = []
    jobItemData.keySet().each {
      mockedJobItems << configRule.newJobItem('group', ['jobID': it])
    }
    configRule.setBuildMap(['group': mockedJobItems])

    jobUtilsMetaClass.addReplacement(JobUtils, [
      'static.getFolder'      : { String name ->
        List<JobItem> jobItems = configRule.buildMap.collectMany { String key, List<JobItem> value -> value }
        return Mock(ItemGroup) {
          allItems() >> {
            List<Item> items = []
            jobItems = jobItems.findAll { JobItem ji -> name.endsWith(ji.jobID) }.each { JobItem ji ->
              Job item = Mock(Job) {
                getName() >> { jobItemData[ji.jobID]['branch'] }
                getParent() >> Mock(ItemGroup) {
                  getFullName() >> ji.jobID
                }
              }
              items << item
            }
            items
          }
        }
      },
      'static.getLastBuildJob': { Job job ->
        return GroovyMock(RunWrapper) {
          resultIsWorseOrEqualTo(_) >> { String other ->
            Result otherResult = Result.fromString(other)
            Result lastResult = jobItemData[job.parent.fullName]['build-result'] as Result
            return lastResult.isWorseOrEqualTo(otherResult)
          }
          getResult() >> {
            jobItemData[job.parent.fullName]['build-result'] as Result
          }
          getRawBuild() >> GroovyMock(AbstractBuild) {
            getEnvironment(_) >> { TaskListener listener ->
              EnvVars envVars = new EnvVars([CHANGE_ID: jobItemData[job.parent.fullName]['pr-number'] as String])
              return envVars
            }
            getAction(TestResultAction.class) >> GroovyMock(TestResultAction) {
              getFailCount() >> 10
            }
          }
        }
      }
    ])

    headByItemMetaClass.addReplacement(SCMHead.HeadByItem, ['static.findHead': { Item item ->
      return Mock(PullRequestSCMHead) {
        getTarget() >> {
          return Mock(BranchSCMHead) {
            getName() >> 'master'
          }
        }
      }
    }])

    when:
    JobUtils.collectJobData()

    then:
    noExceptionThrown()

    and:
    BuildData.instance.buildStatus.hasBranchStatus()
  }
}
