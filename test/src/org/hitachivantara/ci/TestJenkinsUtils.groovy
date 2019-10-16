package org.hitachivantara.ci

import hudson.triggers.SCMTrigger
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.jenkins.JobUtils
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty
import spock.lang.Specification

import static org.hitachivantara.ci.config.LibraryProperties.IS_MINION
import static org.hitachivantara.ci.config.LibraryProperties.POLL_CRON_INTERVAL

class TestJenkinsUtils extends Specification {

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
}
