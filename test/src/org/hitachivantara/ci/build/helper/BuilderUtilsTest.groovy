package org.hitachivantara.ci.build.helper

import org.hitachivantara.ci.CpsSpecification
import org.hitachivantara.ci.IllegalArgumentException

class BuilderUtilsTest extends CpsSpecification {

  Class classForTest() { return BuilderUtils.class }

  def "test grouped items reorganization when some are expanded"() {
    setup:
      def expandWorkItem = { item, mock, depth ->
        (0..depth).collect { d ->
          (0..<mock).collect { "$item/${it + d * mock}".toString() }
        }
      }

    and: "a list of job items"
      List groupItems = ['job-0', 'job-1', 'job-2', 'job-3', 'job-4']

    when: "some are expanded"
      groupItems[0] = expandWorkItem(groupItems[0], 2, 0) // [[job-0/0, job-0/1]]
      groupItems[2] = expandWorkItem(groupItems[2], 2, 1) // [[job-2/0, job-2/1], [job-2/2, job-2/3]]
      groupItems[4] = expandWorkItem(groupItems[4], 1, 2) // [[job-4/0], [job-4/1], job-4/2]]

    then:
      evalCPSonly("BuilderUtils.organizeItems(groupItems)", [groupItems: groupItems]) == [
          ['job-0/0', 'job-0/1', 'job-1', 'job-2/0', 'job-2/1', 'job-3', 'job-4/0'],
          ['job-2/2', 'job-2/3', 'job-4/1'],
          ['job-4/2']
      ]
  }

  def "test list partitioning"() {
    expect:
      evalCPS(script) == expected
    where:
      script                                                                                                           || expected
      "BuilderUtils.partition([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 4, { list, obj -> !list.contains(obj) })"               || [[1, 2, 3, 4], [5, 6, 7, 8], [9, 10]]
      "BuilderUtils.partition([1, 1, 1, 2, 3, 4, 4, 5, 6, 7, 8, 9, 10], 4, { list, obj -> !list.contains(obj) })"      || [[1, 2, 3, 4], [1, 4, 5, 6], [1, 7, 8, 9], [10]]
      "BuilderUtils.partition([1, 1, 1, 2, 3, 4, 4, 5, 6, 7, 8, 9, 10], 4, { list, obj -> list.every { it != obj } })" || [[1, 2, 3, 4], [1, 4, 5, 6], [1, 7, 8, 9], [10]]
      "BuilderUtils.partition([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 8)"                                                     || [[1, 2, 3, 4, 5, 6, 7, 8], [9, 10]]
      "BuilderUtils.partition([1, 2, 3, 4], 1)"                                                                        || [[1], [2], [3], [4]]
      "BuilderUtils.partition([1, 2, 3, 4], 4)"                                                                        || [[1, 2, 3, 4]]
      "BuilderUtils.partition([1, 2, 3, 4], 5)"                                                                        || [[1, 2, 3, 4]]
      "BuilderUtils.partition([], 1)"                                                                                  || []
      "BuilderUtils.partition(null, 1)"                                                                                || []

  }

  def "test list partitioning invalid arguments throws Exception"() {
    when:
      evalCPSonly(script)
    then:
      thrown(IllegalArgumentException)
    where:
      script << [
          "BuilderUtils.partition([1, 2], -1)",
          "BuilderUtils.partition([1, 2, 3], -1, { list, obj -> !list.contains(obj) })",
          "BuilderUtils.partition([1, 2, 3], 2, null)",
          "BuilderUtils.partition([1, 2, 3], 2, { it })",
      ]

  }
}
