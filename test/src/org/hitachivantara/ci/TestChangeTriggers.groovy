/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.model.AbstractBuild
import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.maven.tools.CommandBuilder
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain

class TestChangeTriggers extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(specification: this, buildConfigPath: 'test/resources/buildDataSample.yaml')

  @Rule RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new ReplacePropertyRule([(ScmUtils): ['static.setChangelog': { Script dsl, JobItem jobItem ->
      // do nothing
    }]]))

  def setupSpec() {
    def wrapper = GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(AbstractBuild)
    }
    mockScript.binding.setVariable('currentBuild', wrapper)
  }

  def "test that changes in a job group forces all next jobs groups"() {
    setup:
      Map buildMap = configRule.buildMap
      List allItems = configRule.allItems

      allItems.each { it.setExecType(JobItem.ExecutionType.AUTO_DOWNSTREAMS) }
      registerAllowedMethod('getMavenCommandBuilder', [Map], { Map params ->
        def cmd = new CommandBuilder()
        if (params.options) cmd << params.options
        return cmd
      })

    and: "has more than 1 group"
      assert buildMap.size() > 1

    and: "all job items execution type are set to AUTO"
      assert allItems.every { it.execAuto }

    when: "first job gets executed"
      JobItem firstJob = buildMap['20'].first()
      Builder firstJobBuilder = BuilderFactory.builderFor(mockScript, firstJob)
      firstJobBuilder.applyScmChanges()

    then: "the remaining other group jobs are set to FORCE"
      buildMap['30'].every { it.execForce }
      buildMap['30'].every { it.execForce }
  }
}
