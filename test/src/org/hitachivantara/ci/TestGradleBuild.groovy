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

  final static String FAKE_PATH = "workspace/fake/path"

  def setup() {
    registerAllowedMethod('withEnv', [List, Closure], null)
  }

  def "test that factory returns a gradle builder"() {
    setup:
      JobItem jobItem = configRule.newJobItem(buildFramework: 'gradle')
      Builder builder = BuilderFactory.builderFor(jobItem)
    expect:
      builder instanceof GradleBuilder
  }

  @Unroll
  def "build command for #directives"() {
    setup:
      JobItem jobItem = configRule.newJobItem([
        buildFramework: 'gradle',
        dockerImage   : 'some/image',
        directives    : directives
      ])

    when:
      Builder builder = BuilderFactory.builderFor(jobItem)
      Closure gradleBuild = builder.getExecution()
      gradleBuild()

    then:
      shellRule.cmds[0] == expected

    where:
      directives               || expected
      ''                       || 'gradle clean build -g caches/gradle'
      '--info --console=plain' || 'gradle --info --console=plain -g caches/gradle'
      '+= -b testing.gradle'   || 'gradle clean build -b testing.gradle -g caches/gradle'
      '+= --build-cache'       || 'gradle clean build --build-cache -g caches/gradle'
  }

}
