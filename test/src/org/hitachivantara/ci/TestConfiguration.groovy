/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci


import org.hitachivantara.ci.config.BuildDataBuilder
import org.hitachivantara.ci.config.BuildData
import org.hitachivantara.ci.config.ConfigurationException
import org.hitachivantara.ci.config.ConfigurationFileNotFoundException
import org.hitachivantara.ci.config.CyclicPropertyException
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.hitachivantara.ci.utils.Rules
import org.junit.Rule
import org.junit.rules.RuleChain
import spock.lang.Unroll

import static org.hitachivantara.ci.JobItem.ExecutionType.AUTO
import static org.hitachivantara.ci.JobItem.ExecutionType.FORCE
import static org.hitachivantara.ci.JobItem.ExecutionType.NOOP
import static org.hitachivantara.ci.build.BuildFramework.DSL_SCRIPT
import static org.hitachivantara.ci.build.BuildFramework.JENKINS_JOB
import static org.hitachivantara.ci.build.BuildFramework.MAVEN

class TestConfiguration extends BasePipelineSpecification {

  ConfigurationRule configRule = new ConfigurationRule(this)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new ReplacePropertyRule((BuildDataBuilder): ['getSteps': { -> mockScript }]))

  def "test configuration merging across sources"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          BUILD_NUMBER            : 123,
          AN_AMAZING_PROPERTY     : 'Environmental Value',
          WORKSPACE               : '.',
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams([
          BUILD_DATA_FILE       : 'buildDataSample.yaml',
          AN_AMAZING_PROPERTY   : 'Paramental Value',
          ANT_DEFAULT_DIRECTIVES: 'clean-all',

        ])
        .build()

    then:
      noExceptionThrown()

    and:
      mbd.buildProperties.subMap([
        'BUILD_DATA_FILE',
        'AN_AMAZING_PROPERTY',
        'ANT_DEFAULT_DIRECTIVES',
        'BUILD_NUMBER',
        'DEFAULT_BUILD_PROPERTIES',
        'AN_AMAZING_PROPERTY',
        'WORKSPACE'
      ]) == [
        BUILD_DATA_FILE         : 'buildDataSample.yaml',
        ANT_DEFAULT_DIRECTIVES  : 'clean-all',
        AN_AMAZING_PROPERTY     : 'Paramental Value',
        BUILD_NUMBER            : 123,
        DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml',
        WORKSPACE               : '.',
      ]

    and: "check for the environment properties that need to be requested directly"
      mbd.buildProperties['DEFAULT_BUILD_PROPERTIES'] == 'test/resources/globalDefaults.yaml'
  }

  def "test job item configuration and defaults"() {
    setup:
      List jobItemKeys = ['jobID', 'scmUrl', 'scmBranch', 'root', 'execType', 'testable']

    when:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams([
          BUILD_DATA_FILE: 'jobTestBuildData.yaml'
        ])
        .build()

    then:
      noExceptionThrown()

    and:
      JobItem jobItem1 = mbd.buildMap['20'][0]
      JobItem jobItem2 = mbd.buildMap['30'][0]
      JobItem jobItem3 = mbd.buildMap['30'][1]

      jobItem1.jobData.subMap(jobItemKeys) == [
        jobID    : 'pdi-plugins',
        scmUrl   : 'https://github.com/pentaho/pentaho-ee.git',
        scmBranch: '8.0',
        root     : 'data-integration/plugins',
        execType : AUTO,
        testable : false
      ]
      jobItem2.jobData.subMap(jobItemKeys) == [
        jobID    : 'sparkl-plugin',
        scmUrl   : 'https://github.com/webdetails/sparkl.git',
        scmBranch: '8.0',
        root     : '',
        execType : AUTO,
        testable : true
      ]
      jobItem3.jobData.subMap(jobItemKeys) == [
        jobID    : 'pdi-assemblies',
        scmUrl   : 'https://github.com/pentaho/pentaho-ee.git',
        scmBranch: '8.0',
        root     : 'data-integration/assemblies',
        execType : AUTO,
        testable : true
      ]
  }

  def "test configuration filtering"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          PROP_1: 'My ${PROP_2}',
        ])
        .withParams([
          PROP_2: 'amazing ${PROP_3}',
          PROP_3: 'property',
          PROP_4: false,
          PROP_5: '${PROP_4}'
        ])
        .build()

    then:
      noExceptionThrown()

    and:
      mbd.buildProperties.subMap(['PROP_1', 'PROP_2', 'PROP_3', 'PROP_5']) == [
        PROP_1: 'My amazing property',
        PROP_2: 'amazing property',
        PROP_3: 'property',
        PROP_5: 'false'
      ]

    and:
      mbd.buildProperties.getBool('PROP_5') == Boolean.FALSE

  }

  def "test configuration filtering inheritance"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/buildDefaultsSimple.yaml'
        ])
        .withParams([
          P1: 2
        ])
        .build()

    then:
      mbd.getInt('P2') == 2
  }

  def "test configuration filtering recursion"() {
    setup:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml',
          PROP_4: 'Go ${PROP_5}'
        ])
        .withParams([
          BUILD_DATA_FILE         : 'buildDataSample.yaml',
          PROP_5                  : 'back to ${PROP_4}',
        ])
        .build()

    when:
      mbd.buildProperties['PROP_4']

    then:
      thrown(CyclicPropertyException)

  }

  def "test configuration filtering with composite keys"() {
    setup:
      BuildData mbd = new BuildDataBuilder()
        .withEnvironment([
          PROP_4: [
            SUB_PROP_1: 'the future!'
          ]
        ])
        .withParams([
          PROP_5: 'Back to ${PROP_4.SUB_PROP_1}',
        ])
        .build()

    expect:
      mbd.getString('PROP_5') == 'Back to the future!'
  }


  def "test job item defaults overriding at build file"() {
    when:
      BuildData bd = new BuildDataBuilder()
        .withGlobalConfig('test/resources/jobDefaultsOverrideBuildDefaults.yaml')
        .withBuildConfig('test/resources/jobDefaultsOverrideBuildData.yaml')
        .build()

    then:
      noExceptionThrown()

    and:
      (bd.buildProperties['JOB_ITEM_DEFAULTS'] as Map).subMap('buildFile', 'buildFramework', 'directives', 'scmBranch', 'testable') == [
        'buildFile'     : 'pom.xml',
        'buildFramework': 'ANT',
        'directives'    : 'clean install',
        'scmBranch'     : 'master',
        'testable'      : true
      ]
  }

  @Unroll
  def "test first and last job params [#firstJob:#lastJob]"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withBuildConfig('test/resources/thinBuildDataTestFile.yaml')
        .withParams([FIRST_JOB: firstJob, LAST_JOB: lastJob])
        .build()

    then:
      List jobItems = []
      mbd.buildMap.each { k, v ->
        jobItems += v.collect { JobItem ji ->
          [jobID: ji.jobID, execType: ji.execType]
        }
      }

      // get a list of all jobIds grouped by Execution Type
      Map result = jobItems.groupBy { it.execType }.collectEntries { k, v -> [(k): v.collect { it.jobID }] }

      result == expected

    where:
      firstJob << [
        'sparkl-plugin',
        '',
        'database-model'
      ]

      lastJob << [
        null,
        'sparkl-plugin',
        'sparkl-plugin'
      ]

      expected << [
        [(NOOP): ['parent-poms', 'database-model', 'versionchecker'], (AUTO): ['sparkl-plugin', 'cgg-plugin', 'data-refinery', 'pdi-plugins']],
        [(FORCE): ['parent-poms'], (AUTO): ['database-model', 'versionchecker', 'sparkl-plugin'], (NOOP): ['cgg-plugin', 'data-refinery', 'pdi-plugins']],
        [(NOOP): ['parent-poms', 'cgg-plugin', 'data-refinery', 'pdi-plugins'], (AUTO): ['database-model', 'versionchecker', 'sparkl-plugin']]
      ]
  }


  @Unroll
  def "test overriding job fields with OVERRIDE_JOB_PARAMS"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withBuildConfig('test/resources/overrideJobParamBuildData.yaml')
        .withParams([OVERRIDE_JOB_PARAMS: override])
        .withEnvironment([
          PENTAHO_SCM_ROOT: 'https://github.com/pentaho/',
          DEFAULT_BRANCH  : '8.0'
        ])
        .build()

    then:
      noExceptionThrown()

    and:
      if (expected) {
        expected.each { jobGroup, jobs ->
          jobs.each { jobID, jobParams ->
            Map resultJobData = mbd.buildMap[jobGroup].find { JobItem jobItem -> jobItem.jobID == jobID }.jobData
            jobParams.each { param, expectedValue ->
              def resultValue = resultJobData[param]

              assert resultValue == expectedValue
            }
          }
        }
      }

    where:
      override                                          || expected
      '- {jobID: parent-poms, directives: -DskipTests}' || ['1': ['parent-poms': ['directives': '-DskipTests']]]
      '''
      - jobID: parent-poms
        execType: AUTO
        directives: -DskipTests
      - jobID: versionchecker
        root: core
      '''                                      || ['1': ['parent-poms': ['execType': AUTO, 'directives': '-DskipTests']],
                                                   '2': ['versionchecker': ['root': 'core']]]
      ' '                                               || [:]
      '- {jobID: parent-poms, directives:}'             || ['1': ['parent-poms': ['directives': null]]]
      '''
        jobID: parent-poms
        directives: -DskipTests=false
      '''                                      || ['1': ['parent-poms': ['directives': '-DskipTests=false']]]
      '''
        jobID: parent-poms
        scmBranch: dev
      '''                                      || ['1': ['parent-poms': ['scmID': 'pentaho.maven-parent-poms.dev']]]
      'scmBranch: a-branch'                             || ['1': ['parent-poms': ['scmBranch': 'a-branch']],
                                                            '2': ['database-model': ['scmBranch': 'a-branch']]]
      '''
      - jobID: parent-poms
        scmBranch: specific
      - scmBranch: all
      '''                                      || ['1': ['parent-poms': ['scmBranch': 'specific']],
                                                   '2': ['versionchecker': ['scmBranch': 'all'],
                                                         'database-model': ['scmBranch': 'all']]]
  }

  @Unroll
  def "test errors overriding job fields with OVERRIDE_JOB_PARAMS"() {
    when:
      new BuildDataBuilder()
        .withBuildConfig('test/resources/overrideJobParamBuildData.yaml')
        .withEnvironment([
          BUILDS_ROOT_PATH: 'builds'
        ])
        .withParams([OVERRIDE_JOB_PARAMS: override])
        .build()

    then:
      thrown(error)

    where:
      override                                         || error
      '''
        jobID: parent-poms
          directives: -DskipTests
      '''                                     || ConfigurationException
      '- {jobID: parent-poms directives: -DskipTests}' || ConfigurationException
  }

  def "test scm label and checkout directory with allow atomic scm checkouts"() {
    when:
      BuildData mbd = new BuildDataBuilder()
        .withBuildConfig('test/resources/atomicBuildDataTestFile.yaml')
        .withParams([
          BUILDS_ROOT_PATH: 'builds'
        ])
        .build()

      List allJobs = mbd.buildMap.collectMany { String jobGroup, List jobList -> jobList }

    then:
      mbd.buildProperties['JOB_ITEM_DEFAULTS']['atomicScmCheckout'] == true

    and:
      allJobs.collectEntries { JobItem ji -> [(ji.scmID): ji.jobID] } == [
        'pentaho.maven-parent-poms.8.0~1.parent-poms'          : 'parent-poms',
        'pentaho.pentaho-commons-database.8.0~2.database-model': 'database-model',
        'pentaho.pentaho-r-plugin.8.0~3.pdi-r-plugin-release'  : 'pdi-r-plugin-release',
        'pentaho.pentaho-r-plugin.8.0~3.pdi-r-plugin'          : 'pdi-r-plugin',
        'pentaho.pentaho-versionchecker.8.0~2.versionchecker'  : 'versionchecker'
      ]

    and:
      allJobs.collectEntries { JobItem ji -> [(ji.checkoutDir): ji.jobID] } == [
        'builds/pentaho.maven-parent-poms.8.0~1.parent-poms'          : 'parent-poms',
        'builds/pentaho.pentaho-commons-database.8.0~2.database-model': 'database-model',
        'builds/pentaho.pentaho-r-plugin.8.0~3.pdi-r-plugin-release'  : 'pdi-r-plugin-release',
        'builds/pentaho.pentaho-r-plugin.8.0~3.pdi-r-plugin'          : 'pdi-r-plugin',
        'builds/pentaho.pentaho-versionchecker.8.0~2.versionchecker'  : 'versionchecker'
      ]

  }

  def "test overriding build params with OVERRIDE_PARAMS"() {
    when:
      BuildData bd = new BuildDataBuilder()
        .withParams(
          BUILD_DATA_ROOT_PATH: 'test/resources',
          OVERRIDE_PARAMS: overrideParams,
          BUILD_DATA_FILE: 'fakeBuildData.yaml'
        )
        .build()

    then:
      noExceptionThrown()

    and:
      // grab the properties that matter to this test
      Map result = bd.buildProperties.subMap([
        'BUILD_PLAN_ID',
        'BUILD_DATA_FILE',
        'PARAM_TO_OVERRIDE',
        'JOB_ITEM_DEFAULTS',
        'OVERRIDE_PARAMS'
      ])

      result.put('JOB_ITEM_DEFAULTS', (result['JOB_ITEM_DEFAULTS'] as Map).subMap('buildFramework', 'scmBranch'))

      result == [
        BUILD_PLAN_ID    : 'Test param overrides',
        BUILD_DATA_FILE  : 'overrideParamsBuildData.yaml',
        JOB_ITEM_DEFAULTS: [
          buildFramework: 'ANT',
          scmBranch     : 'dev'
        ],
        OVERRIDE_PARAMS  : overrideParams,
        PARAM_TO_OVERRIDE: 'overriden'
      ]

    where:
      overrideParams << ['''
        PARAM_TO_OVERRIDE: overriden
        BUILD_DATA_FILE: 'overrideParamsBuildData.yaml'
        JOB_ITEM_DEFAULTS :
          scmBranch: dev
        ''', [
        PARAM_TO_OVERRIDE: 'overriden',
        BUILD_DATA_FILE  : 'overrideParamsBuildData.yaml',
        JOB_ITEM_DEFAULTS: [
          scmBranch: 'dev'
        ]
      ]]
  }

  def "test unrecognized OVERRIDE_PARAM"() {
    when:
      new BuildDataBuilder()
        .withEnvironment(
          BUILD_DATA_ROOT_PATH: 'test/resources'
        )
        .withParams(
          OVERRIDE_PARAMS: ['list is not valid'],
          BUILD_DATA_FILE: 'fakeBuildData.yaml'
        )
        .build()

    then:
      thrown(ConfigurationException)
  }

  def "test invalid build data files"() {
    when:
      new BuildDataBuilder()
        .withBuildConfig('file.not.found.yaml')
        .build()
    then:
      thrown(ConfigurationFileNotFoundException)
  }

  def "test data when BUILD_DATA_YAML is set"() {
    when:
      BuildData bd = new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams(
          BUILD_DATA_ROOT_PATH: 'test/resources',
          BUILD_DATA_YAML:
            '''
            buildProperties:
              BUILD_PLAN_ID                  : Build plan set by property
              MAVEN_DEFAULT_DIRECTIVES       : clean unknown-goal
              DEFAULT_BRANCH                 : a-default-branch
              SOME_PROP_THAT_EXISTS_ONLY_HERE: true
            
            jobGroups:
              10:
                 - jobID             :  database-model-from-hell
                   scmUrl            :  https://github.com/pentaho/pentaho-commons-database.git
                   scmBranch         :  unknown-branch
                ''',
          BUILD_DATA_FILE: 'buildDataSample.yaml' // data in this yaml should be ignored
        )
        .build()

    then:
      noExceptionThrown()

    and:
      Map result = bd.buildProperties.subMap([
        'BUILD_PLAN_ID',
        'MAVEN_DEFAULT_DIRECTIVES',
        'DEFAULT_BRANCH',
        'SOME_PROP_THAT_EXISTS_ONLY_HERE'
      ])

      List<JobItem> jobItems = bd.buildMap.values().flatten()

      result == [
        BUILD_PLAN_ID                  : 'Build plan set by property',
        MAVEN_DEFAULT_DIRECTIVES       : 'clean unknown-goal',
        DEFAULT_BRANCH                 : 'a-default-branch',
        SOME_PROP_THAT_EXISTS_ONLY_HERE: true
      ] &&
        jobItems.first().jobID == 'database-model-from-hell'
  }

  @Unroll
  def "test validation of PUSH_CHANGES and atomicScmCheckout no exception"() {
    when:
      new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams([
          BUILD_DATA_ROOT_PATH    : 'test/resources',
          BUILD_DATA_FILE         : 'buildDataSample.yaml',
          OVERRIDE_PARAMS         : override
        ])
        .build()

    then:
      noExceptionThrown()

    where:
      override   || expected
      '''
    PUSH_CHANGES: false
    JOB_ITEM_DEFAULTS:
      atomicScmCheckout: true
    ''' || _
      '''
    PUSH_CHANGES: true
    JOB_ITEM_DEFAULTS:
      atomicScmCheckout: false
    ''' || _
      '''
    PUSH_CHANGES: false
    JOB_ITEM_DEFAULTS:
      atomicScmCheckout: false
    ''' || _
  }

  def "test validation of PUSH_CHANGES and atomicScmCheckout with exception"() {
    when:
      new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams(
          BUILD_DATA_ROOT_PATH: 'test/resources',
          BUILD_DATA_FILE: 'buildDataSample.yaml',
          OVERRIDE_PARAMS: '''\
           PUSH_CHANGES: true
           JOB_ITEM_DEFAULTS:
             atomicScmCheckout: true
          '''
        )
        .build()

    then:
      thrown(ConfigurationException)

  }

  def "test pre/post stages build data"() {
    when:

      BuildDataBuilder bdb = new BuildDataBuilder()
        .withEnvironment([
          DEFAULT_BUILD_PROPERTIES: 'test/resources/globalDefaults.yaml'
        ])
        .withParams(
          BUILD_DATA_ROOT_PATH: 'test/resources',
          BUILD_DATA_YAML:
            '''
                buildProperties:
                  BUILD_PLAN_ID                  : Pre/Post stages Build plan
                  MAVEN_DEFAULT_DIRECTIVES       : clean something
                  DEFAULT_BRANCH                 : a-default-branch

                preBuild:
                  scripts:
                    - jobID         :  script-1
                      buildFramework:  DSL_SCRIPT
                      script        :  |
                        { ->
                          sh 'whoami\'
                          echo " Job name: $\\{env.JOB_NAME}"
                        }
                      testable:  false

                jobGroups:
                  10:
                     - jobID             :  database-model
                       scmUrl            :  https://github.com/pentaho/pentaho-commons-database.git
                       scmBranch         :  unknown-branch

                postBuild:
                  parallel-jobs:
                    - jobID         :  job-1
                      buildFramework:  'JENKINS_JOB\'
                      targetJobName :  dummy-job-1
                      asynchronous  :  true

                    - jobID         :  job-2
                      buildFramework:  'JENKINS_JOB\'
                      targetJobName :  dummy-job-2
                      asynchronous  :  true
                    '''
        )

      BuildData bd = bdb.build()
      bdb.consumeMessages()
      bd.toString()

    then:
      noExceptionThrown()

    and:
      Map result = bd.buildProperties.subMap([
        'BUILD_PLAN_ID',
        'MAVEN_DEFAULT_DIRECTIVES',
        'DEFAULT_BRANCH',
      ])
      List<JobItem> jobItems = bd.buildMap.values().flatten()
      List<JobItem> preBuildItems = bd.preBuildMap.collectMany { String jobGroup, List jobList -> jobList }
      List<JobItem> postBuildItems = bd.postBuildMap.collectMany { String jobGroup, List jobList -> jobList }

      result == [
        BUILD_PLAN_ID           : 'Pre/Post stages Build plan',
        MAVEN_DEFAULT_DIRECTIVES: 'clean something',
        DEFAULT_BRANCH          : 'a-default-branch',
      ] &&
        jobItems.first().jobID == 'database-model' &&
        jobItems.first().buildFramework == MAVEN &&
        preBuildItems.first().jobID == 'script-1' &&
        preBuildItems.first().buildFramework == DSL_SCRIPT &&
        postBuildItems.last().jobID == 'job-2' &&
        postBuildItems.last().buildFramework == JENKINS_JOB
  }

}
