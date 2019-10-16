package org.hitachivantara.ci

import hudson.FilePath
import hudson.model.Run
import org.hitachivantara.ci.archive.ArchivingHelper
import org.hitachivantara.ci.archive.ManifestNotFoundException
import org.hitachivantara.ci.utils.FileUtilsRule
import org.hitachivantara.ci.utils.ConfigurationRule
import org.hitachivantara.ci.utils.Rules
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import org.junit.Rule
import org.junit.rules.RuleChain

class TestArtifactArchiving extends BasePipelineSpecification {
  static String buildConfigRootPath = 'test/resources/archive'
  static String targetRootFolder = "target"

  Map params = [
    BUILD_CONFIG_ROOT_PATH    : buildConfigRootPath,
    RELEASE_BUILD_NUMBER      : '23',
    ARCHIVING_CONFIG          : "${buildConfigRootPath}/artifacts/manifest.yaml",
    ARCHIVING_PATH_GROUP_REGEX: '.*/',
    BUILD_HOSTING_ROOT        : "${targetRootFolder}/hosted-fake",
    DEPLOYMENT_FOLDER         : '0.0.0.0',
    BUILDS_ROOT_PATH          : 'test/resources/archive',
    ARCHIVING_EXCLUDE_PATTERN : '(.*/.git/.*)'
  ]

  ConfigurationRule configRule = new ConfigurationRule(this, params)

  @Rule
  RuleChain rules = Rules.getCommonRules(this)
    .around(configRule)
    .around(new FileUtilsRule(this))

  JobItem dummyJobItem = new JobItem('', ['root': buildConfigRootPath], [:])

  def setup() {
    registerAllowedMethod('readFile', [Map], { Map<String, String> params ->
      if (params.file) {
        return new File(params.file).text
      } else {
        return ""
      }
    })
    mockScript.currentBuild = GroovyMock(RunWrapper) {
      getRawBuild() >> GroovyMock(Run) {
        getRootDir() >> new File(targetRootFolder)
        getArtifactsDir() >> new File(targetRootFolder, "archive")
      }
    }
  }

  def "test if copy to target is available"() {
    setup:
      mockScript.currentBuild = GroovyMock(RunWrapper) {
        getRawBuild() >> GroovyMock(Run) {
          getRootDir() >> new File(targetFolder)
          getArtifactsDir() >> new File(targetFolder, "archive")
        }
      }
      configRule.addProperties([BUILD_HOSTING_ROOT: '', IS_MINION: isMinion])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)

    expect:
      archiving.isCopyToFolderAvailable() == result

    where:
      isMinion | targetFolder              || result
      true     | "target/"                 || true
      false    | "target/hosted-fake-root" || false

  }

  def "test artifact archiving search pattern"() {
    setup:
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      archiving.getSearchPattern() == expected
    where:
      expected = '(.*/pad-ee-0.0.0.0-SNAPSHOT(?:-dist)?.zip)|(.*/pentaho-business-analytics-0.0.0.0-SNAPSHOT-x64.exe)|(.*/paz-plugin-ee-0.0.0.0-SNAPSHOT(?:-dist)?.zip)|(.*/pentaho-server-ee-0.0.0.0-SNAPSHOT(?:-dist)?.zip)|(.*/pentaho-big-data-ee-cdh512-package-0.0.0.0-SNAPSHOT(?:-dist)?.zip)|(.*/pentaho-upgrade-utility-0.0.0.0-SNAPSHOT-ba.zip)|(.*/pad-ce-0.0.0.0-SNAPSHOT.zip)|(.*/kettle-sdk-plugin-assembly-0.0.0.0-SNAPSHOT.zip)|(.*/pentaho-server-ce-0.0.0.0-SNAPSHOT.zip)|(.*/pentaho-hadoop-shims-cdh512-package-0.0.0.0-SNAPSHOT(?:-dist)?.zip)|(.*/pre-classic-sdk-0.0.0.0-SNAPSHOT.zip)'
  }

  def "test artifact search capability"() {
    setup:
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      FileUtils.findFiles('test/resources/archive', "regex:${archiving.getSearchPattern()}", null).size() == 1
  }

  def "test default artifact archiving"() {
    setup:
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      archiving.archiveArtifacts(dummyJobItem)
  }

  def "test custom artifact archiving with List"() {
    setup:
      configRule.addProperties([ARCHIVING_CONFIG: ['something.zip', 'something2.zip']])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      !archiving.archiveArtifacts(dummyJobItem)
  }

  def "test custom artifact archiving with Map"() {
    setup:
      configRule.addProperties([ARCHIVING_CONFIG: ['some-artifacts': 'something.zip', 'other-artifacts': 'something2.zip']])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      !archiving.archiveArtifacts(dummyJobItem)
  }

  def "test custom artifact archiving with invalid file"() {
    setup:
      configRule.addProperties([ARCHIVING_CONFIG: 'some-non-existing-file.yaml'])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    when:
      archiving.archiveArtifacts(dummyJobItem)
    then:
      thrown(ManifestNotFoundException)
  }

  def "test custom artifact archiving with List of regular expressions"() {
    setup:
      configRule.addProperties([ARCHIVING_CONFIG: ['.*\\.(zip|properties)']])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      !archiving.archiveArtifacts(new JobItem('', ['root': 'test/resources/archive'], params))
  }

  def "test custom artifact archiving with List in string format and a value null on one of the properties"() {
    setup:
      configRule.addProperty('ARCHIVING_CONFIG', """\
        SOME_FILES:
          - pad-ee-\${platform.version}-dist.zip
          - pad-something-\${platform.version}-dist.zip
        """.stripIndent())
      configRule.addProperty('SOMETHING', null)
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)

    expect:
      archiving.archiveArtifacts(dummyJobItem)
  }

  def "test custom artifact archiving with nonexistent root folder"() {
    setup:
      configRule.addProperties([BUILDS_ROOT_PATH: 'test/missing-resources-folder'])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      !archiving.archiveArtifacts(new JobItem('', ['root': 'test/resources/archive'], params))
  }

  def "test archive with and without minions"() {
    setup:
      registerAllowedMethod('isUnix', [], { -> true})
      configRule.addProperties([IS_MINION: isMinion, BUILD_HOSTING_ROOT: hosted])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)

      GroovyMock(hudson.Util, global: true) {
        createSymlink(_) >> {
          //ignore
        }
      }

    expect:
      archiving.archiveArtifacts(new JobItem('', [:], params))

    where:
      hosted           | isMinion
      targetRootFolder | true
      ''               | true
      targetRootFolder | false
      ''               | false
  }

  def "test exclusion search pattern"() {
    setup:
      configRule.addProperties([ARCHIVING_EXCLUDE_PATTERN: '(.*/some/other/path/.*)'])
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      archiving.getSearchExcludePattern(['.*/one/particular/path/.*']) == '(.*/some/other/path/.*)|(.*/one/particular/path/.*)'
  }

  def "test get hosted root"() {
    setup:
      configRule.reset()
      configRule.addProperties(parameters)
    expect:
      ArchivingHelper.getHostedRoot(configRule.buildData) == result

    where:
      parameters                                                                                                  || result
      [:]                                                                                                         || ''
      [DEPLOYMENT_FOLDER: '0.0', RELEASE_BUILD_NUMBER: '2.2 ', BUILD_HOSTING_ROOT: 'somewhere/over/the/rainbow']  || 'somewhere/over/the/rainbow/0.0/2.2'
      [DEPLOYMENT_FOLDER: '0.0 ', RELEASE_BUILD_NUMBER: '2.2', BUILD_HOSTING_ROOT: 'somewhere/over/the/rainbow/'] || 'somewhere/over/the/rainbow/0.0/2.2'
  }

  def "test creation date exists"() {
    setup:
      File file = new File('test/resources/archive/version.properties')
      ArchivingHelper archiving = new ArchivingHelper(mockScript, configRule.buildData)
    expect:
      archiving.getCreationInstant(new FilePath(file))
  }
}
