package vars

import hudson.model.ItemGroup
import hudson.model.Job
import org.hitachivantara.ci.BasePipelineSpecification
import org.hitachivantara.ci.jenkins.JobException
import org.hitachivantara.ci.jenkins.JobUtils
import org.hitachivantara.ci.utils.JenkinsVarRule
import org.junit.Rule
import spock.util.mop.ConfineMetaClassChanges

class TestJob extends BasePipelineSpecification {

  @Rule
  JenkinsVarRule jobRule = new JenkinsVarRule(this, 'job')

  @ConfineMetaClassChanges(JobUtils)
  def "test find job by name"() {
    setup:
      Job job = Mock(Job) {
        getNextBuildNumber() >> 3
        asBoolean() >> true
      }
      JobUtils.metaClass.static.findJobByName = { String name -> job }

    when:
      int expected = jobRule.var.nextBuildNumber('myJob')

    then:
      expected == 3
  }

  @ConfineMetaClassChanges(JobUtils)
  def "test find job by name relative path"() {
    setup:
      Job currentJob = Mock(Job) {
        getParent() >> Mock(ItemGroup) {
          getFullName() >> 'folder'
        }
      }
      Job job = Mock(Job) {
        getNextBuildNumber() >> 3
      }
      JobUtils.metaClass.static.findJobByName = { String name ->
        ['folder/myJob': job, 'folder/currentJob': currentJob]
          .withDefault { k -> null }
          .get(name)
      }
      jobRule.setVariable('env', [JOB_NAME: 'folder/currentJob'])

    when:
      int expected = jobRule.var.nextBuildNumber('myJob')

    then:
      expected == 3
  }

  @ConfineMetaClassChanges(JobUtils)
  def "test find job by name miss"() {
    setup:
      Job currentJob = Mock(Job) {
        getParent() >> Mock(ItemGroup) {
          getFullName() >> 'folder'
        }
      }
      JobUtils.metaClass.static.findJobByName = { String name ->
        name == 'folder/currentJob' ? currentJob : null
      }
      jobRule.setVariable('env', [JOB_NAME: 'folder/currentJob'])

    when:
      jobRule.var.nextBuildNumber('myJob')

    then:
      thrown(JobException)
  }

  def "test convert map to parameters"() {
    setup:
      registerAllowedMethod('string', [Map.class], { Map param -> "string${param.toString()}" })
      registerAllowedMethod('booleanParam', [Map.class], { Map param -> "boolean${param.toString()}" })
      registerAllowedMethod('text', [Map.class], { Map param -> "text${param.toString()}" })

    when:
      List result = jobRule.var.toParameters(input, forConfig)

    then:
      result == expected

    where:
      input                       | forConfig | expected
      [:]                         | true      | []
      [STRING: 'string']          | true      | ['string[name:STRING, defaultValue:string]']
      [STRING: 'string']          | false     | ['string[name:STRING, value:string]']
      [BOOL: true]                | true      | ['boolean[name:BOOL, defaultValue:true]']
      [BOOL: true]                | false     | ['boolean[name:BOOL, value:true]']
      [TEXT: 'string\nstring']    | true      | ['text[name:TEXT, defaultValue:string\nstring]']
      [TEXT: 'string\nstring']    | false     | ['text[name:TEXT, value:string\nstring]']
      [MAP: [P1: 'v1', P2: true]] | true      | ['text[name:MAP, defaultValue:{P1: v1, P2: true}\n]']
      [MAP: [P1: 'v1', P2: true]] | false     | ['text[name:MAP, value:{P1: v1, P2: true}\n]']
  }
}
