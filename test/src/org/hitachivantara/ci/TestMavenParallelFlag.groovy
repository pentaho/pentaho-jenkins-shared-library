/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.FilePath
import org.hitachivantara.ci.build.BuilderFactory
import org.hitachivantara.ci.build.impl.MavenBuilder
import org.hitachivantara.ci.maven.tools.CommandBuilder
import org.hitachivantara.ci.maven.tools.FilteredProjectDependencyGraph
import org.hitachivantara.ci.maven.tools.MavenModule
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import spock.lang.Unroll

import static org.hitachivantara.ci.build.helper.BuilderUtils.ADDITIVE_EXPR

class TestMavenParallelFlag extends BasePipelineSpecification {
  ConfigurationRule configRule = new ConfigurationRule(this)
  JenkinsShellRule shellRule = new JenkinsShellRule(this)
  TestRule workdirRule = new TestRule() {
    String workdir = null
    @Override
    Statement apply(Statement base, Description description) {
      registerAllowedMethod('dir', [String, Closure], { String dir, Closure c ->
        workdir = dir
        c.delegate = delegate
        helper.callClosure(c)
      })
      return base
    }
  }

  @Rule RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(shellRule)
    .around(workdirRule)

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

    registerAllowedMethod('projectDependencyGraph', [Map], {Map params ->
      MavenModule module = params.module
      List<String> whitelist = params.whitelist
      return new FilteredProjectDependencyGraph(module, whitelist)
    })
  }

  @Unroll
  def "job data is expanded for parallel entries with overrides: '#overrides'"() {
    setup:
      configRule.addProperty('BUILDS_ROOT_PATH', 'test/resources/multi-module-profiled-project')
      JobItem jobItem = configRule.newJobItem(['buildFramework': 'Maven', 'parallelize': 'true'] + overrides)

      MavenBuilder builder = BuilderFactory.builderFor(mockScript, jobItem) as MavenBuilder
    when:
      List items = builder.expandWorkItem(jobItem)
    then:
      items.size() == expected.size()
      items.eachWithIndex { List<JobItem> workItems, int i ->
        workItems.eachWithIndex { JobItem workItem, int j ->
          assertBuildDirectives(workItem, expected[i][j])
        }
      }
    where:
      overrides << [
          ['directives': ''],
          ['directives': "${ADDITIVE_EXPR} -DskipDefault -P profile-A"],
          ['directives': "${ADDITIVE_EXPR} -pl sub-1,sub-3"],
          ['directives': 'install', 'buildFile': 'sub-1/pom.xml'],
          ['directives': 'install', 'root': 'sub-2'],
          ['directives': 'install', 'buildFile': 'sub-2/pom.xml'],
      ]

      expected << [
          // override 1
          [[[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -N',
             workdir: 'test/resources/multi-module-profiled-project']
           ],
           [[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1',
             workdir: 'test/resources/multi-module-profiled-project'],
            [cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-2,sub-2/sub-1,sub-2/subsub-2',
             workdir: 'test/resources/multi-module-profiled-project'],
            [cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-3,sub-3/sub-1',
             workdir: 'test/resources/multi-module-profiled-project']
           ]],

          // override 2
          [[[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -DskipDefault -P profile-A -N',
             workdir: 'test/resources/multi-module-profiled-project'],
           ],
           [[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -DskipDefault -P profile-A -pl sub-1',
             workdir: 'test/resources/multi-module-profiled-project'],
            [cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -DskipDefault -P profile-A -pl sub-2,sub-2/sub-1,sub-2/subsub-2',
             workdir: 'test/resources/multi-module-profiled-project']
           ]],

          // override 3
          [[[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -N',
             workdir: 'test/resources/multi-module-profiled-project'],
           ],
           [[cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1',
             workdir: 'test/resources/multi-module-profiled-project'],
            [cmds   : 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-3',
             workdir: 'test/resources/multi-module-profiled-project']
           ]],

          // override 4
          [[[cmds   : 'mvn install -f sub-1/pom.xml -Daether.connector.resumeDownloads=false -DskipTests',
             workdir: 'test/resources/multi-module-profiled-project']
           ]],

          // override 5
          [[[cmds   : 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -N',
             workdir: 'test/resources/multi-module-profiled-project/sub-2'],
           ],
           [[cmds   : 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1',
             workdir: 'test/resources/multi-module-profiled-project/sub-2'],
            [cmds   : 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl subsub-2',
             workdir: 'test/resources/multi-module-profiled-project/sub-2']
           ]],

          // override 6
          [[[cmds   : 'mvn install -f sub-2/pom.xml -Daether.connector.resumeDownloads=false -DskipTests -N',
             workdir: 'test/resources/multi-module-profiled-project'],
           ],
           [[cmds   : 'mvn install -f sub-2/pom.xml -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1',
             workdir: 'test/resources/multi-module-profiled-project'],
            [cmds   : 'mvn install -f sub-2/pom.xml -Daether.connector.resumeDownloads=false -DskipTests -pl subsub-2',
             workdir: 'test/resources/multi-module-profiled-project']
           ]]
      ]
  }

  def "test inter-dependencies project"() {
    setup:
      configRule.addProperty('BUILDS_ROOT_PATH', 'test/resources/inter-module-dependency-project')
      JobItem jobItem = configRule.newJobItem(['buildFramework': 'Maven', 'parallelize': 'true'] + overrides)
      MavenBuilder builder = BuilderFactory.builderFor(mockScript, jobItem) as MavenBuilder
    when:
      List items = builder.expandWorkItem(jobItem)
    then:
      items.size() == expected.size()
      items.eachWithIndex { List<JobItem> workItems, int i ->
        workItems.eachWithIndex { JobItem workItem, int j ->
          assertBuildDirectives(workItem, expected[i][j])
        }
      }
    where:
      overrides << [
          ['directives': ''],
          ['directives': 'install -pl sub-3,sub-2,sub-1'],
          ['directives': 'install -N'],
          ['directives': 'install -pl !sub-3,!sub-5'],
      ]

      expected << [
          // override 1
          [[
               // parallel parent
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -N'],
           ],
           [
               // parallel subgroup 1, all projects without parent dependencies
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1'],
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-4'],
           ],
           [
               // parallel group 2, all projects that depends on previous subgroups
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-2'],
           ],
           [
               // parallel group 3, all projects that depends on previous subgroups
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-5'],
               [cmds: 'mvn clean install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-3'],
           ]],

          // override 2
          [[
               // parallel parent
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -N'],
           ],
           [
               // parallel subgroup 1, all projects without parent dependencies
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1'],
           ],
           [
               // parallel group 2, all projects that depends on previous subgroups
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-2'],
           ],
           [
               // parallel group 3, all projects that depends on previous subgroups
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-3'],
           ]],

          // override 3
          [[
               // parallel parent
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -N'],
           ]],

          // override 4
          [[
               // parallel parent
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -N'],
           ],
           [
               // parallel subgroup 1, all projects without parent dependencies
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-1'],
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-4'],
           ],
           [
               // parallel group 2, all projects that depends on previous subgroups
               [cmds: 'mvn install -Daether.connector.resumeDownloads=false -DskipTests -pl sub-2'],
           ]],
      ]
  }

  private assertBuildDirectives(JobItem jobItem, Map<String, Object> expected) {
    MavenBuilder builder = BuilderFactory.builderFor(mockScript, jobItem) as MavenBuilder

    Closure mvnBuild = builder.getBuildClosure(jobItem)
    mvnBuild()

    expected.each { String k, v ->
      switch (k) {
        case 'cmds':
          assert shellRule.cmds.pop() == v
          break
        case 'workdir':
          assert workdirRule.workdir == v
          break
      }
      if (jobItem.hasProperty(k)) {
        assert jobItem[k] == v
      }
    }
  }
}
