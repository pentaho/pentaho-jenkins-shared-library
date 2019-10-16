package org.hitachivantara.ci


import org.hitachivantara.ci.build.BuildFramework
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.jenkins.JobBuild
import org.hitachivantara.ci.build.impl.JenkinsJobBuilder
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

class TestJenkinsJobBuild extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new ReplacePropertyRule([(JobBuild): ['getSteps': { -> mockScript }]]))
    .around(new JenkinsVarRule(this, 'job'))

  def "test that factory returns a jenkins job builder"() {
    setup:
      JobItem jobItem = Mock(JobItem) {
        getBuildFramework() >> BuildFramework.JENKINS_JOB
      }

    when:
      Builder builder = BuilderFactory.builderFor(jobItem)

    then:
      builder instanceof JenkinsJobBuilder
  }

  @Unroll
  def "test build command for #jobData"() {
    setup:
      registerAllowedMethod('string', [Map.class], { Map param -> "string${param.toString()}" })
      registerAllowedMethod('booleanParam', [Map.class], { Map param -> "boolean${param.toString()}" })
      registerAllowedMethod('text', [Map.class], { Map param -> "text${param.toString()}" })

      Map result = null
      registerAllowedMethod('build', [Map.class], { Map args ->
        result = args
        return GroovyMock(RunWrapper)
      })

      JobItem jobItem = configRule.newJobItem(jobData)
      configRule.setProperties([P1: 'BUILD_PARAM1', P2: 'BUILD_PARAM2'])

    when:
      Builder builder = BuilderFactory.builderFor(jobItem)
      builder.execution.call()

    then:
      result == expected

    where:
      jobData << [
        [
          buildFramework: 'JENKINS_JOB',
          targetJobName : 'target-job'
        ],
        [
          buildFramework: 'JENKINS_JOB',
          targetJobName : 'target-job',
          properties    : [
            SOMETHING_ELSE: 'why not?'
          ]
        ],
        [
          buildFramework       : 'JENKINS_JOB',
          targetJobName        : 'target-job',
          passOnBuildParameters: false,
          properties           : [
            SOMETHING_ELSE: 'why not?'
          ]
        ]
      ]
      expected << [
        [
          job       : 'target-job',
          parameters: [
            'string[name:P1, value:BUILD_PARAM1]',
            'string[name:P2, value:BUILD_PARAM2]'
          ],
          propagate : false,
          wait      : true
        ],
        [
          job       : 'target-job',
          parameters: [
            'string[name:P1, value:BUILD_PARAM1]',
            'string[name:P2, value:BUILD_PARAM2]',
            'string[name:SOMETHING_ELSE, value:why not?]'
          ],
          propagate : false,
          wait      : true],
        [
          job       : 'target-job',
          wait      : true,
          propagate : false,
          parameters: [
            'string[name:SOMETHING_ELSE, value:why not?]'
          ]
        ]
      ]
  }

}
