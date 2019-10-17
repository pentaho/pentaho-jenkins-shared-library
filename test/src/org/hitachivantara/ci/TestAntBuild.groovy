/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import org.hitachivantara.ci.build.Builder
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.IBuilder
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.build.impl.AntBuilder
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Ignore
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll
@Ignore("Ant builds are deprecated")
class TestAntBuild extends BasePipelineSpecification {
  JenkinsShellRule shellRule = new JenkinsShellRule(this)

  @Rule
  public RuleChain ruleChain = Rules.getCommonRules(this)
    .around(shellRule)
    .around(new ConfigurationRule(this))

  def setup() {
    registerAllowedMethod('tool', [String],  null)
    registerAllowedMethod('withEnv', [List, Closure],  { List l, Closure c ->
      c.delegate = delegate
      helper.callClosure(c)
    })
    registerAllowedMethod('withAnt', [Map, Closure],  { Map m, Closure c ->
      c.delegate = delegate
      helper.callClosure(c)
    })
  }

  def "test that factory returns an ant builder"() {
    given:
      JobItem jobItem = new JobItem('', ['buildFramework': 'Ant'], [:])
      Builder builder = BuilderFactory.builderFor(jobItem)
    expect:
      builder instanceof AntBuilder
  }

  @Unroll
  def "build command for #jobData"() {
    setup:
      JobItem jobItem = new JobItem('', jobData, [:])

    when:
      IBuilder builder = BuilderFactory.builderFor(mockScript, jobItem)
      Closure antBuild = builder.getBuildClosure(jobItem)
      antBuild()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData                                                       || expected
      ['buildFramework': 'Ant']                                     || 'ant -Divy.default.ivy.user.dir=builds/caches/.ivy2 clean-all resolve publish'
      ['buildFramework': 'Ant', 'execType':'noop']                  || null
      ['buildFramework': 'Ant', 'directives':'publish-local-nojar'] || 'ant -Divy.default.ivy.user.dir=builds/caches/.ivy2 publish-local-nojar'
  }

  @Unroll
  def "test command for #jobData"() {
    setup:
      JobItem jobItem = new JobItem('', jobData, [:])

    when:
      IBuilder builder = BuilderFactory.builderFor(mockScript, jobItem)
      Closure mvnTest = builder.getTestClosure(jobItem)
      mvnTest()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData                                      || expected
      ['buildFramework': 'Ant']                    || 'ant clean-all resolve publish'
      ['buildFramework': 'Ant', 'execType': 'noop']|| null
      ['buildFramework': 'Ant', 'testable': false] || null
  }

  @Unroll
  def "test directive [#directives]"() {
    setup:
      jobData.directives = directives
      JobItem jobItem = new JobItem('', jobData, [:])
    when:
      IBuilder builder = BuilderFactory.builderFor(mockScript, jobItem)
      Closure mvnTest = builder.getTestClosure(jobItem)
      mvnTest()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData = ['buildFramework':'Ant']

      directives << [
          "${BuilderUtils.ADDITIVE_EXPR} -Dpentaho.resolve.repo=http://nexus.pentaho.org/content/groups/omni",
          "${BuilderUtils.SUBTRACTIVE_EXPR} publish-local",
          "${BuilderUtils.ADDITIVE_EXPR} -Dpentaho.resolve.repo=http://nexus.pentaho.org/content/groups/omni ${BuilderUtils.SUBTRACTIVE_EXPR} clean-all"
      ]

      expected << [
          'ant clean-all resolve publish -Dpentaho.resolve.repo=http://nexus.pentaho.org/content/groups/omni',
          'ant clean-all resolve publish',
          'ant resolve publish -Dpentaho.resolve.repo=http://nexus.pentaho.org/content/groups/omni'
      ]
  }
}
