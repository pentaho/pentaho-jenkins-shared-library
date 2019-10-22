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
import org.hitachivantara.ci.build.impl.GradleBuilder
import org.hitachivantara.ci.utils.FileUtilsRule
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

class TestGradleBuild extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  JenkinsShellRule shellRule = new JenkinsShellRule(this)

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(new FileUtilsRule(this))
    .around(shellRule)
    .around(configRule)

  def setup() {
    registerAllowedMethod('tool', [String],  null)
    registerAllowedMethod('withEnv', [List, Closure],  { List l, Closure c ->
      c.delegate = delegate
      helper.callClosure(c)
    })
  }

  final static String FAKE_PATH = "workspace/fake/path"

  def "test that factory returns a gradle builder"() {
    setup:
      JobItem jobItem = configRule.newJobItem(buildFramework: 'gradle')
      Builder builder = BuilderFactory.builderFor(jobItem)
    expect:
      builder instanceof GradleBuilder
  }

  @Unroll
  def "build command for #jobData"() {
    setup:
      JobItem jobItem = configRule.newJobItem(jobData)
      configRule.addProperty('WORKSPACE', FAKE_PATH)
    when:
      IBuilder builder = BuilderFactory.builderFor(jobItem)
      Closure gradleBuild = builder.getBuildClosure(jobItem)
      gradleBuild()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData                                                              || expected
      ['buildFramework': 'gradle']                                         || "gradle clean build --gradle-user-home=${FAKE_PATH}/caches/.gradle -x test"
      ['buildFramework': 'gradle', 'execType': 'noop']                     || null
      ['buildFramework': 'gradle', 'directives': '--info --console=plain'] || "gradle --info --console=plain --gradle-user-home=${FAKE_PATH}/caches/.gradle -x test"
  }

  @Unroll
  def "test command for #jobData"() {
    setup:
      JobItem jobItem = configRule.newJobItem(jobData)

    when:
      IBuilder builder = BuilderFactory.builderFor(jobItem)
      Closure mvnTest = builder.getTestClosure(jobItem)
      mvnTest()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData                                                            || expected
      ['buildFramework': 'gradle']                                       || "gradle test"
      ['buildFramework': 'gradle', 'execType': 'noop']                   || null
      ['buildFramework': 'gradle', 'testable': false]                    || null
      ['buildFramework': 'gradle', 'settingsFile': 'my-settings.gradle'] || "gradle -c my-settings.gradle test"
  }

  @Unroll
  def "test directive [#directives]"() {
    setup:
      jobData.directives = directives
      JobItem jobItem = configRule.newJobItem(jobData)

    when:
      IBuilder builder = BuilderFactory.builderFor(jobItem)
      Closure gradleTester = builder.getBuildClosure(jobItem)
      gradleTester()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData = ['buildFramework':'gradle']

      directives << [
          "${BuilderUtils.ADDITIVE_EXPR} -b testing.gradle",
          "${BuilderUtils.SUBTRACTIVE_EXPR} -q",
          "${BuilderUtils.ADDITIVE_EXPR} --build-cache ${BuilderUtils.SUBTRACTIVE_EXPR} clean"
      ]

      expected << [
        "gradle clean build -b testing.gradle --gradle-user-home=builds/caches/.gradle -x test",
        "gradle clean build --gradle-user-home=builds/caches/.gradle -x test",
        "gradle build --build-cache --gradle-user-home=builds/caches/.gradle -x test"
      ]
  }
}
