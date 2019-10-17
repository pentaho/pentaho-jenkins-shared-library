/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import hudson.FilePath
import org.hitachivantara.ci.utils.FileUtilsRule
import org.hitachivantara.ci.utils.JenkinsShellRule
import org.hitachivantara.ci.utils.ReplacePropertyRule
import org.junit.Rule
import org.junit.rules.RuleChain

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class TestFileUtils extends BasePipelineSpecification {
  JenkinsShellRule shellRule = new JenkinsShellRule(this)

  @Rule
  RuleChain rules = RuleChain.outerRule(new ReplacePropertyRule((FileUtils): ['static.getSteps': { -> mockScript }]))
    .around(new FileUtilsRule(this))
    .around(shellRule)

  def "test the creation of symbolic links"() {
    setup:
      Path result = Paths.get(destination + '/srcAtTargetLink')

    when:
      FileUtils.createSymLink(destination, targetFolder, 'srcAtTargetLink')

    then:
      Files.isSymbolicLink(result) && Files.isReadable(result) == readable

    where:
      targetFolder                  | destination                                      || readable
      '../../test/resources'        | 'target/something-' + System.currentTimeMillis() || true
      'non-existing-test/resources' | 'target'                                         || false
  }

  def "test the creation of hard links"() {
    given:
      registerAllowedMethod('isUnix', [], { -> true })
    when:
      File folder = new File('test/resources/archive')
      FileUtils.createHardLink('target', folder.getAbsolutePath(), 'version.properties')

    then:
      shellRule.cmds[0] ==~ /^ln (?<src>(.*)) (?<dst>(.*))/
  }

  def "test basic file attributes"() {
    when:
      File file = new File('test/resources/archive/version.properties')
      def result = FileUtils.getBasicAttributes(new FilePath(file))

    then:
      result != null
  }

  def "test file copy"() {
    setup:
      File sourcefile = new File('test/resources/archive/version.properties')
      File targetFile = new File(targetFilePath)

    when:
      FileUtils.copy(sourcefile, targetFile)

    then:
      targetFile.exists() == exists

    where:
      targetFilePath              || exists
      'target/version.properties' || true
      'target/version.properties' || true // a 2nd time to guarantee that the file gets replaced
  }

  def "test find files"() {
    expect:
      FileUtils.findFiles('test/resources/archive', "regex:${pattern}", excludes).size() == filesFound

    where:
      pattern                                      | excludes       || filesFound
      '(.*/pad-ee-0.0.0.0-SNAPSHOT(?:-dist)?.zip)' | '(.*/.git/.*)' || 1
      null                                         | null           || 0
  }

  def "test if files are equal"() {

    setup:
      File file1 = new File('test/resources/archive/version.properties')
      File file2 = new File(otherFile)

    expect:
      FileUtils.equal(file1, file2) == result

    where:
      otherFile                                        || result
      'test/resources/archive/artifacts/manifest.yaml' || false
      'test/resources/archive/version.properties'      || true
  }

  def "test file creation"() {
    FilePath filePath
    String fileName = 'some-file.txt'

    when:
      filePath = FileUtils.create(parent, fileName)

    then:
      filePath.remote == 'target/' + fileName

    where:
      parent << [
        new File('target'),
        new FilePath(new File('target')),
        'target',
      ]
  }

  def "test invalid file creation"() {
    when:
      FileUtils.create(5)

    then:
      thrown(FilePathException)
  }

  def "test get file path"() {
    String filePath

    when:
      filePath = child ? FileUtils.getPath(parent, child) : FileUtils.getPath(parent)

    then:
      noExceptionThrown()

    and:
      filePath == child ? "target/${child}" : 'target'

    where:
      parent                           || child
      new File('target')               || 'some-file.txt'
      new File('target')               || null
      new FilePath(new File('target')) || null
      'target'                         || null
  }

  def "test resolve"() {
    expect:
      FileUtils.resolve('version.properties', 'test/resources/archive/') == 'test/resources/archive/version.properties'
  }

  def "test if is child"() {
    expect:
      FileUtils.isChild('test/resources/archive/version.properties', 'test/resources/')
  }

  def "test relativize"() {
    expect:
      FileUtils.relativize('test/resources/archive/version.properties', 'test/resources/') == 'archive/version.properties'
  }
}
