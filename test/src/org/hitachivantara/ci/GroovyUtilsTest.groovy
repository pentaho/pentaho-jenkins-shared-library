/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import spock.lang.Unroll

class GroovyUtilsTest extends CpsSpecification {

  Class classForTest() { GroovyUtils.class }

  @Unroll("#script")
  def "test custom intersect"() {
    expect:
      evalCPS(script) == expected
    where:
      script                                          | expected
      "GroovyUtils.intersect([], [])"                 | [].intersect([])
      "GroovyUtils.intersect([1, 2, 3], [1, 3])"      | [1, 2, 3].intersect([1, 3])
      "GroovyUtils.intersect(['a', 'b'], ['a', 'c'])" | ['a', 'b'].intersect(['a', 'c'])
  }

  def "test custom collection sort"() {
    setup:
      def binding = [cmp: cmp]
      def script = "GroovyUtils.sort(${slist}, cmp)"
    expect:
      evalCPS(script, binding) == list.sort(cmp)
    where:
      slist       | list      | cmp
      '[3, 1, 2]' | [3, 1, 2] | { a, b -> a <=> b }
      '[3, 1, 2]' | [3, 1, 2] | { it }
  }

  @Unroll("#script")
  def "test custom collection sort expected to fail"() {
    expect: "cps does not implement sort yet, expect different value"
      evalCPSonly(script) != expected
    where:
      script                                | expected
      "[3, 1, 2].sort({ a, b -> a <=> b })" | [3, 1, 2].sort({ a, b -> a <=> b })
      "[3, 1, 2].sort({ it })"              | [3, 1, 2].sort({ it })
  }

  def "custom sort fails if attempted with a non synchronous way"() {
    when: "cps does not implement sort yet, expect to fail"
      evalCPSonly(script)
    then:
      thrown IllegalArgumentException
    where:
      script                                             | expected
      "GroovyUtils.sort([3, 1, 2], { a, b -> a <=> b })" | [3, 1, 2].sort({ a, b -> a <=> b })
      "GroovyUtils.sort([3, 1, 2], { it })"              | [3, 1, 2].sort({ it })
  }

  def "test custom map sort"() {
    setup:
      def binding = [cmp: cmp]
      def script = "GroovyUtils.sort(${smap}, cmp)"
    when:
      Map result = evalCPS(script, binding)
      Map expected = map.sort(cmp)
    then:
      verifyAll {
        result == expected
        result.keySet().toList() == expected.keySet().toList()
      }
    where:
      smap                       | map                      | cmp
      "[(2): 'a', (1): 'b']"     | [(2): 'a', (1): 'b']     | { a, b -> a.key <=> b.key }
      "[(2): 'a', (1): 'b']"     | [(2): 'a', (1): 'b']     | { it.key }
      "['c': 2, 'a': 1, 'z': 3]" | ['c': 2, 'a': 1, 'z': 3] | { a, b -> a.key <=> b.key }
  }

  @Unroll("#script")
  def "test custom collection groupby"() {
    expect:
      evalCPS(script) == expected
    where:
      script                                                  | expected
      "GroovyUtils.groupBy([1, 2, 3, 4, 5], { it % 2 != 0 })" | [1, 2, 3, 4, 5].groupBy { it % 2 != 0 }
      "GroovyUtils.groupBy(['Aa', 'BB'], { it.hashCode() })"  | ['Aa', 'BB'].groupBy { it.hashCode() }
  }

  @Unroll("#script")
  def "test custom collection unique"() {
    expect:
      evalCPS(script) == expected
    where:
      script                                                      | expected
      "GroovyUtils.unique([1, 1, 4, 5, 3], { it })"               | [1, 1, 4, 5, 3].unique({ it })
      "GroovyUtils.unique(['Aa', 'BB'], { it.hashCode() })"       | ['Aa', 'BB'].unique { it.hashCode() }
      "GroovyUtils.unique([[a: 10], [a: 10], [a: 11]], { it.a })" | [[a: 10], [a: 10], [a: 11]].unique { it.a }
      "GroovyUtils.unique([1, 2, -2, 3], { i -> i * i })"         | [1, 2, -2, 3].unique { int i -> i * i }
  }
}
