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
import org.hitachivantara.ci.build.impl.MavenBuilder
import org.hitachivantara.ci.maven.tools.CommandBuilder
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

import static org.hitachivantara.ci.config.LibraryProperties.MAVEN_TEST_OPTS
import static org.hitachivantara.ci.config.LibraryProperties.CHANGE_ID
import static org.hitachivantara.ci.config.LibraryProperties.PR_STATUS_REPORTS

class TestMavenBuild extends BasePipelineSpecification {
  JenkinsShellRule shellRule = new JenkinsShellRule(this)
  ConfigurationRule configRule = new ConfigurationRule(this)

  @Rule
  public RuleChain ruleChain = Rules
    .getCommonRules(this)
    .around(shellRule)
    .around(configRule)

  def setup() {
    registerAllowedMethod('withEnv', [List, Closure], null)
    registerAllowedMethod("withCredentials", [List, Closure], null)
    registerAllowedMethod("usernamePassword", [Map], null)
    registerAllowedMethod("withMaven", [Map, Closure], null)

    registerAllowedMethod('getMavenCommandBuilder', [Map], { Map params ->
      def cmd = new CommandBuilder()
      if (params.options) cmd << params.options
      return cmd
    })
  }

  def "test that factory returns a maven builder"() {
    given:
      JobItem jobItem = new JobItem('buildFramework': 'Maven')
      Builder builder = BuilderFactory.builderFor(jobItem)
    expect:
      builder instanceof MavenBuilder
  }

  def "test getExecution"() {
    setup:
      JobItem jobItem = new JobItem(buildFramework: 'Maven', directives: 'clean install')
    when:
      Builder builder = BuilderFactory.builderFor(jobItem)
      builder.getExecution().call()
    then:
      shellRule.cmds[0] == 'mvn clean install'
  }

  @Unroll
  def "build command for #jobData"() {
    setup:
      configRule.addProperty('MAVEN_DEFAULT_COMMAND_OPTIONS', '-B -e')
      configRule.addProperty('MAVEN_DEFAULT_DIRECTIVES', 'clean install')
      configRule.addProperty('MAVEN_PR_DEFAULT_DIRECTIVES', 'clean verify')
      configRule.addProperty('CHANGE_ID', isPr ? '123' : null)

      JobItem jobItem = configRule.newJobItem(jobData)

    when:
      Builder builder = BuilderFactory.builderFor(jobItem)
      builder.getExecution().call()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData << [
        [buildFramework: 'Maven'],
        [buildFramework: 'Maven', directives: 'package', execType: 'force'],
        [buildFramework: 'Maven', directives: 'install', buildFile: './core/pom.xml'],
        [buildFramework: 'Maven'],
        [buildFramework: 'Maven', directives: '-= install += deploy'],
        [buildFramework: 'Maven', prDirectives: '-= verify += integration-test']
      ]
      isPr << [false, false, false, true, false, true]
      expected << [
        'mvn clean install -B -e',
        'mvn package -B -e',
        'mvn install -B -e -f ./core/pom.xml',
        'mvn clean verify -B -e',
        'mvn clean deploy -B -e',
        'mvn clean integration-test -B -e'
      ]
  }

  @Unroll
  def "test command for #jobData"() {
    setup:
      configRule.buildProperties[MAVEN_TEST_OPTS] = '-DsurefireArgLine=-Xmx1g'
      JobItem jobItem = new JobItem(jobData)

    when:
      IBuilder builder = BuilderFactory.builderFor(jobItem)
      builder.getTestClosure(jobItem).call()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData                                             || expected
      ['buildFramework': 'Maven']                         || 'mvn test -DsurefireArgLine=-Xmx1g'
      ['buildFramework': 'Maven', 'buildFile': 'pom.xml'] || 'mvn test -f pom.xml -DsurefireArgLine=-Xmx1g'
  }

  @Unroll
  def "test directive [#directives]"() {
    setup:
      configRule.buildProperties[MAVEN_TEST_OPTS] = '-DsurefireArgLine=-Xmx1g'
      jobData.directives = directives
      JobItem jobItem = new JobItem(jobData)

    when:
      IBuilder builder = BuilderFactory.builderFor(jobItem)
      builder.getTestClosure(jobItem).call()

    then:
      shellRule.cmds[0] == expected

    where:
      jobData = ['buildFramework': 'Maven']

      directives << [
        "${BuilderUtils.ADDITIVE_EXPR} -Daudit -P core",
        "${BuilderUtils.SUBTRACTIVE_EXPR} -B -e -DsurefireArgLine=-Xmx1g",
        "${BuilderUtils.ADDITIVE_EXPR} -Daudit ${BuilderUtils.SUBTRACTIVE_EXPR} -DsurefireArgLine=-Xmx1g",
        ""
      ]

      expected << [
        'mvn test -DsurefireArgLine=-Xmx1g -Daudit -P core',
        'mvn test',
        'mvn test -Daudit',
        'mvn test -DsurefireArgLine=-Xmx1g'
      ]
  }

  @Unroll
  def "test sonar execution"() {
    setup:
      if (PR) configRule.buildProperties[CHANGE_ID] = '1'
      configRule.buildProperties[PR_STATUS_REPORTS] = true
      jobData.directives = directives
      JobItem jobItem = new JobItem(jobData)
      CommandBuilder commandBuilder = null

      registerAllowedMethod('getMavenCommandBuilder', [Map], { Map params ->
        commandBuilder = new CommandBuilder()
        if (params.options) commandBuilder << params.options
        return commandBuilder
      })

      mockScript.binding.setVariable('pullRequest', [head: '12345'])

    when:
      Builder builder = BuilderFactory.builderFor(jobItem)
      builder.getSonarExecution().call()

    then:
      verifyAll {
        commandBuilder
        commandBuilder.goals == ['sonar:sonar']
        assert commandBuilder.getOptionsValues('am').empty, "must not contain any reactor make upstream (am)"
        assert commandBuilder.getOptionsValues('amd').empty, "must not contain any reactor make downstream (amd)"

        commandBuilder.getOptionsValues('pl') == [expectedPl]

        if (PR) {
          commandBuilder.userProperties.containsKey('sonar.pullrequest.branch')
          commandBuilder.userProperties.containsKey('sonar.pullrequest.key')
          commandBuilder.userProperties.containsKey('sonar.pullrequest.base')
          commandBuilder.userProperties.containsKey('sonar.pullrequest.github.repository')
          commandBuilder.userProperties.containsKey('sonar.scm.revision')
        }
      }

    where:
      PR    | directives                              || expectedInclusions                                         | expectedExclusions | expectedPl
      false | '-pl plugins/core/impl,plugins/core/ui' || '.,plugins,plugins/core,plugins/core/impl,plugins/core/ui' | null               | '.,plugins,plugins/core,plugins/core/impl,plugins/core/ui'
      false | '-pl core,!engine'                      || '.,core'                                                   | '!engine'          | '.,core,!engine'
      true  | '-pl plugins/core/impl,plugins/core/ui' || '.,plugins,plugins/core,plugins/core/impl,plugins/core/ui' | null               | '.,plugins,plugins/core,plugins/core/impl,plugins/core/ui'

      jobData = ['buildFramework': 'Maven']
  }

}
