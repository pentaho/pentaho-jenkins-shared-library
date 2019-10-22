/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci

import org.hitachivantara.ci.config.ConfigurationMap
import spock.lang.Specification

class TestMapWrapper extends Specification {

  def "test getInt return's an Integer"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:])
      map.putAll(defaults)

    expect: "asking for the specific type should return anything but null"
      map.getInt('k').class == Integer

    where:
      defaults << [
          [:],
          ['k': 1],
          ['k': null]
      ]
  }

  def "test getDouble return's a Double"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:])
      map.putAll(defaults)

    expect: "asking for the specific type should return anything but null"
      map.getDouble('k').class == Double

    where:
      defaults << [
          [:],
          ['k': 1d],
          ['k': null]
      ]
  }

  def "test getBool return's a Boolean"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:])
      map.putAll(defaults)

    expect: "asking for the specific type should return anything but null"
      map.getBool('k').class == Boolean

    where:
      defaults << [
          [:],
          ['k': true],
          ['k': null]
      ]
  }

  def "test getString return's a String"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:])
      map.putAll(defaults)

    expect: "asking for the specific type should return anything but null"
      map.getString('k').class == String

    where:
      defaults << [
          [:],
          ['k': 'hello world'],
          ['k': null]
      ]
  }

  def "test getList return's a List"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:])
      map.putAll(defaults)

    expect: "asking for the specific type should return anything but null"
      map.getList('k').class == ArrayList

    where:
      defaults << [
          [:],
          ['k': ['hello world']],
          ['k': null]
      ]
  }

  def "test getting raw map"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:], [
        PROP_1: '1',
        PROP_2: '${PROP_1} + 2',
        PROP_3: '${PROP_2} + 3'
      ])

    expect:
      map == [
        PROP_1: '1',
        PROP_2: '1 + 2',
        PROP_3: '1 + 2 + 3'
      ]
      map.getRawMap() == [
        PROP_1: '1',
        PROP_2: '${PROP_1} + 2',
        PROP_3: '${PROP_2} + 3'
      ]
  }

  def "test plus implementation does not wrongly perform filtering"() {
    given:
      ConfigurationMap map = new ConfigurationMap([:], [
        PROP_1: '1',
        PROP_2: '${PROP_1} + 2',
        PROP_3: '${PROP_2} + 3'
      ])
      Map right = [PROP_1: 'X']

    expect:
      map + right == [
        PROP_1: 'X',
        PROP_2: 'X + 2',
        PROP_3: 'X + 2 + 3'
      ]
  }

  def "test leftshift "() {
    given:
      ConfigurationMap map = new ConfigurationMap([:], [
        PROP_1: [
          INNER_PROP_1: 1,
          INNER_PROP_2: 2,
          INNER_PROP_3: [
            INNER_INNER_PROP_1: 1
          ],
        ],
        PROP_2: 'hello',
        PROP_3: '${PROP_2} world'
      ])
      Map right = [
        PROP_1: [
          INNER_PROP_2: 3,
          INNER_PROP_3: [
            INNER_INNER_PROP_2: 2
          ],
          INNER_PROP_4: 4
        ],
        PROP_2: 'bye',
      ]

    expect:
      map << right == [
        PROP_1: [
          INNER_PROP_1: 1,
          INNER_PROP_2: 3,
          INNER_PROP_3: [
            INNER_INNER_PROP_1: 1,
            INNER_INNER_PROP_2: 2
          ],
          INNER_PROP_4: 4
        ],
        PROP_2: 'bye',
        PROP_3: 'bye world'
      ]
  }

}
