/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.FilePath
import hudson.model.AbstractBuild
import hudson.model.Result
import hudson.model.Run
import hudson.scm.ChangeLogSet
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.helper.BuilderUtils
import org.hitachivantara.ci.build.impl.MavenBuilder
import org.hitachivantara.ci.config.LibraryProperties
import org.hitachivantara.ci.maven.tools.CommandBuilder
import org.hitachivantara.ci.maven.tools.MavenModule
import org.hitachivantara.ci.scm.SCMData
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

class TestMavenChangeDetector extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  JenkinsShellRule shellRule = new JenkinsShellRule(this)

  @Rule RuleChain rules = Rules.getCommonRules(this)
    .around(new ReplacePropertyRule([(ScmUtils): ['static.setChangelog': { Script dsl, JobItem jobItem -> /*do nothing*/ }]]))
    .around(configRule)
    .around(shellRule)

  def setup() {
    registerAllowedMethod('withEnv', [List, Closure], null)
    registerAllowedMethod("withCredentials", [List, Closure], null)
    registerAllowedMethod("usernamePassword", [Map], null)
    registerAllowedMethod("string", [Map], null)
    registerAllowedMethod("withMaven", [Map, Closure], null)

    registerAllowedMethod('getMavenCommandBuilder', [Map], { Map params ->
      def cmd = new CommandBuilder()
      if (params.options) cmd << params.options
      return cmd
    })

    registerAllowedMethod('buildMavenModule', [Map], { Map params ->
      String file = params.file
      List<String> activeProfiles = params.activeProfiles as List<String>
      List<String> inactiveProfiles = params.inactiveProfiles as List<String>
      Properties props = params.userProperties as Properties
      return MavenModule.builder()
        .withActiveProfiles(activeProfiles)
        .withInactiveProfiles(inactiveProfiles)
        .withUserProperties(props)
        .build(new FilePath(new File(file)))
    })
  }

  def "no previous build yields commands"() {
    setup:
      JobItem jobItem = new JobItem(buildFramework: 'Maven', execType: 'auto', directives: 'clean install' )
      MavenBuilder builder = BuilderFactory.builderFor(jobItem) as MavenBuilder

    when: "there are no successful builds"
      def wrapper = GroovyMock(RunWrapper) {
        getRawBuild() >> Mock(AbstractBuild)
        asBoolean() >> true
      }
      mockScript.binding.setVariable('currentBuild', wrapper)

    and:
      List<List<?>> items = BuilderUtils.prepareForExecution([jobItem])
      Closure mvnBuild = builder.getBuildClosure(jobItem)
      mvnBuild()

      Closure mvnTest = builder.getTestClosure(jobItem)
      mvnTest()

    then: "the scripts yielded some commands"
      !items.flatten().empty
      shellRule.cmds.any { it ==~ /^mvn(.*)install(.*)/ }
      shellRule.cmds.any { it ==~ /^mvn(.*)test(.*)/ }
  }

  def "build is skipped if no changes detected"() {
    setup:
      JobItem jobItem = new JobItem(['buildFramework': 'Maven', 'execType': 'auto'])

    when: "there are no changes present"
      jobItem.changeLog = []

    and:
      List<List<?>> items = BuilderUtils.prepareForExecution([jobItem])

    then: "the scripts yielded no commands"
      items.flatten().empty
  }

  @Unroll
  def "command build changes for #overrides"() {
    setup:
      String workdir = null
      registerAllowedMethod('dir', [String, Closure], { String wd, Closure c ->
        workdir = wd
        c.delegate = delegate
        helper.callClosure(c)
      })

      JobItem jobItem = new JobItem('',
        [buildFramework: 'Maven', execType: 'auto'] + overrides,
        [BUILDS_ROOT_PATH: 'test/resources/multi-module-profiled-project']
      )
      MavenBuilder builder = BuilderFactory.builderFor(jobItem) as MavenBuilder

    when: "there are changes present"
      jobItem.changeLog = changes

    and: "we execute the build instance"
      JobItem item = BuilderUtils.prepareForExecution([jobItem]).flatten().first()
      Closure mvnBuild = builder.getBuildClosure(item)
      mvnBuild()

    then: "the scripts yielded commands"
      (shellRule.cmds[1] - ' -DskipTests') == expectedCommand
      workdir == expectedWorkdir

    where:
      changes = ['sub-1/pom.xml', 'sub-3/pom.xml', 'sub-3/sub-1/pom.xml']

      overrides << [
        ['directives': "install"],
        ['directives': "install -N", 'root': "sub-3"],
        ['directives': "install -DskipDefault -P profile-A"],
        ['directives': "install -pl sub-2,sub-3"],
        ['directives': "install -N", 'buildFile': "sub-3/pom.xml"],
        ['directives': "install", 'buildFile': "sub-1/pom.xml"],
        ['directives': "install", 'root': "sub-3"],
        ['directives': "install", 'root': "sub-3", 'buildFile': "sub-1/pom.xml"],
      ]

      expectedCommand << [
        'mvn install -pl sub-1,sub-3,sub-3/sub-1',
        'mvn install -N',
        'mvn install -DskipDefault -P profile-A', // this should have -pl sub-1, but since we cannot check if the change belongs to a inactive module it builds from root
        'mvn install -pl sub-3',
        'mvn install -f sub-3/pom.xml -N',
        'mvn install -f sub-1/pom.xml',
        'mvn install',
        'mvn install -f sub-1/pom.xml',
      ]

      expectedWorkdir << [
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project/sub-3',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project/sub-3',
        'test/resources/multi-module-profiled-project/sub-3',
      ]
  }

  @Unroll
  def "build is skipped for #overrides"() {
    setup:
      JobItem jobItem = new JobItem('',
        ['buildFramework': 'Maven', 'execType': 'auto'] + overrides,
        ['BUILDS_ROOT_PATH': 'test/resources/multi-module-profiled-project'])

    when: "there are changes present"
      jobItem.changeLog = changes

    and: "filtering the items"
      def items = BuilderUtils.prepareForExecution([jobItem])

    then: "items are removed from the group"
      items.flatten().empty

    where:
      changes = ['sub-1/pom.xml', 'sub-3/pom.xml']

      overrides << [
        ['directives': "install -N"],
        ['directives': "install", 'root': "sub-2"],
      ]
  }

  @Unroll
  def "test changes at root are recognized"() {
    setup:
      configRule.addProperty('BUILDS_ROOT_PATH', 'test/resources/multi-module-profiled-project')
      JobItem jobItem = new JobItem('buildFramework': 'Maven', 'execType': 'auto', 'directives': "install")

    when: "there are changes present"
      jobItem.changeLog = ['pom.xml']

    and: "filtering the items"
      def items = BuilderUtils.prepareForExecution([jobItem])

    then: "items are removed from the group"
      !items.empty
  }

  @Unroll
  def "command test changes for #overrides"() {
    setup:
      String workdir = null
      registerAllowedMethod('dir', [String, Closure], { String wd, Closure c ->
        workdir = wd
        c.delegate = delegate
        helper.callClosure(c)
      })

      configRule.addProperty('BUILDS_ROOT_PATH', 'test/resources/multi-module-profiled-project')
      JobItem jobItem = configRule.newJobItem(['buildFramework': 'Maven', 'execType': 'auto'] + overrides)
      jobItem.changeLog = changes
      MavenBuilder builder = BuilderFactory.builderFor(jobItem) as MavenBuilder

    when: "there are changes present"
      jobItem.changeLog = changes

    and: "we execute the build instance"
      JobItem item = BuilderUtils.prepareForExecution([jobItem], { jobItem.testable }).flatten().first()
      Closure mvnBuild = builder.getTestClosure(item)
      mvnBuild()

    then: "the scripts yielded commands"
      shellRule.cmds[1] == expectedCommand
      workdir == expectedWorkdir

    where:
      changes = ['sub-1/pom.xml', 'sub-3/pom.xml', 'sub-3/sub-1/pom.xml']

      overrides << [
        ['directives': "install"],
        ['directives': "install -DskipDefault -P profile-A"],
        ['directives': "install -pl sub-2,sub-3"],
        ['directives': "install", 'buildFile': "sub-1/pom.xml"],
        ['directives': "install", 'root': "sub-3"],
      ]

      expectedCommand << [
        'mvn test -pl sub-1,sub-3,sub-3/sub-1',
        'mvn test -DskipDefault -P profile-A', // this should have -pl sub-1, but since we cannot check if the change belongs to a inactive module it builds from root
        'mvn test -pl sub-3',
        'mvn test -f sub-1/pom.xml',
        'mvn test',
      ]

      expectedWorkdir << [
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project',
        'test/resources/multi-module-profiled-project/sub-3',
      ]
  }

  @Unroll
  def "test successive builds"() {
    setup: "some hell of a mocking"
      def builds = []
      addBuild(builds, changes1.first(), changes1.last())
      addBuild(builds, changes2.first(), changes2.last())
      addBuild(builds, changes3.first(), changes3.last())

      def wrapper = builds.last()
      mockScript.binding.setVariable('currentBuild', wrapper)
      shellRule.setReturnValue(~/^git rev-list (.*) \^(.*)/, getAllShas(builds))

    and: "our job item and builder"
      configRule.addProperty('BUILDS_ROOT_PATH', 'test/resources/multi-module-profiled-project')
      JobItem jobItem = configRule.newJobItem(['buildFramework': 'Maven', 'execType': 'auto'])
      ScmUtils.setCheckoutMetadata(jobItem, [GIT_URL: 'test', GIT_BRANCH: 'origin/master'])

      MavenBuilder builder = BuilderFactory.builderFor(jobItem) as MavenBuilder
      configRule.addProperty(LibraryProperties.CHANGES_FROM_LAST, 'SUCCESS')
      jobItem.changeLog = ScmUtils.calculateChanges(mockScript, jobItem)

    when: "we execute the build instance"
      JobItem item = BuilderUtils.prepareForExecution([jobItem]).flatten().first()
      Closure mvnBuild = builder.getBuildClosure(item)
      mvnBuild()

    then: "the scripts yielded commands"
      shellRule.cmds.contains(expected)

    where:
      changes1                            | changes2                                             | changes3
      [[], Result.SUCCESS]                | [['sub-1/pom.xml'], Result.SUCCESS]                  | [['sub-3/pom.xml'], null]
      [['sub-1/pom.xml'], Result.SUCCESS] | [['sub-1/pom.xml', 'sub-3/pom.xml'], Result.FAILURE] | [[], null]
      [['sub-2/pom.xml'], Result.FAILURE] | [[], Result.FAILURE]                                 | [['sub-1/pom.xml', 'sub-3/pom.xml'], null]
      [['sub-2/pom.xml'], Result.SUCCESS] | [['sub-1/pom.xml'], Result.UNSTABLE]                 | [['sub-3/sub-1/pom.xml'], null]
      [[], Result.SUCCESS]                | [[], Result.SUCCESS]                                 | [['sub-2/pom.xml'], null]
      [[], Result.SUCCESS]                | [[], Result.SUCCESS]                                 | [['sub-2/sub-1/pom.xml'], null]

      expected << [
        'mvn clean install -DskipTests -pl sub-3',
        'mvn clean install -DskipTests -pl sub-1,sub-3',
        'mvn clean install -DskipTests',
        'mvn clean install -DskipTests -pl sub-1,sub-3/sub-1',
        'mvn clean install -DskipTests -pl sub-2',
        'mvn clean install -DskipTests -pl sub-2/sub-1',
      ]
  }

  @Unroll
  def "find changes from last build with result #from"() {
    setup: "some hell of a mocking"
      def builds = []
      addBuild(builds, ['sub-1/pom.xml'], Result.SUCCESS)
      addBuild(builds, ['sub-1/pom.xml', 'sub-3/pom.xml'], Result.UNSTABLE)
      addBuild(builds, ['sub-2/pom.xml'], Result.FAILURE)
      addBuild(builds, ['sub-3/sub-1/pom.xml'], null)

    and: "mocking that all the changes belonging to the same jobItem"
      def wrapper = builds.last()
      mockScript.binding.setVariable('currentBuild', wrapper)
      shellRule.setReturnValue(~/^git rev-list (.*) \^(.*)/, getAllShas(builds))
      GroovySpy(ScmUtils, global: true)

    and:
      JobItem jobItem = new JobItem('', [:], [:])
      ScmUtils.setCheckoutMetadata(jobItem, [GIT_URL: 'test', GIT_BRANCH: 'origin/master'])
      configRule.addProperty(LibraryProperties.CHANGES_FROM_LAST, from.toString())

    when: "we ask what the changes are from last build with result"
      def changelog = ScmUtils.calculateChanges(mockScript, jobItem)

    then:
      1 * ScmUtils.isInsideWorkTree(_)
      changelog == expected

    where:
      from            || expected
      Result.SUCCESS  || ['sub-3/sub-1/pom.xml', 'sub-2/pom.xml', 'sub-1/pom.xml', 'sub-3/pom.xml']
      Result.UNSTABLE || ['sub-3/sub-1/pom.xml', 'sub-2/pom.xml']
      Result.FAILURE  || ['sub-3/sub-1/pom.xml']
  }


  String getAllShas(builds) {
    def num = builds.size()
    (0..<num).join('\n')
  }

  void addBuild(builds, changelog, result) {
    int buildNumber = builds.size()

    def changeLogSet = GroovyMock(ChangeLogSet) {
      asBoolean() >> true
      getItems() >> [Mock(ChangeLogSet.Entry) {
        getCommitId() >> buildNumber.toString()
        getAffectedPaths() >> changelog
      }]
    }

    builds << GroovyMock(RunWrapper) {
      getNumber() >> (buildNumber + 1)
      getPreviousBuild() >> (buildNumber > 0 ? builds.last() : null)
      getChangeSets() >> [changeLogSet]
      getResult() >> (result?.toString())
      getCurrentResult() >> (result?.toString() ?: Result.SUCCESS.toString())
      asBoolean() >> true
      resultIsBetterOrEqualTo(_) >> { String other -> result.isBetterOrEqualTo(Result.fromString(other)) }
      getRawBuild() >> GroovyMock(Run) {
        getAction(_) >> GroovyMock(SCMData) {
          asBoolean() >> true
          getScmUrl() >> 'test'
          getBranch() >> 'origin/master'
          getCommitId() >> buildNumber.toString()
        }
        getActions(_) >> [GroovyMock(SCMData) {
          asBoolean() >> true
          getScmUrl() >> 'test'
          getBranch() >> 'origin/master'
          getCommitId() >> buildNumber.toString()
        }]
      }
    }
  }
}
