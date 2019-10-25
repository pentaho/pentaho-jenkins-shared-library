/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci


import com.cloudbees.groovy.cps.NonCPS
import com.cloudbees.groovy.cps.impl.CpsClosure

import java.util.regex.Pattern

/**
 * Pipeline's CPS doesn't have some groovy utilities implemented which make our lives harder.
 * Here we re-implement some of the default groovy methods in replacement.
 */
class GroovyUtils implements Serializable {

  @NonCPS
  static <T> List<T> intersect(Collection<T> listA, Collection<T> listB) {
    if (listB) {
      return listA.intersect(listB)
    }
    return listA
  }

  static <K, T> Map<K, List<T>> groupBy(List<T> list, Closure<K> condition) {
    Map<K, List<T>> answer = [:]
    list.each { T element ->
      K key = condition.call(element)
      answer.get(key, []).add(element)
    }
    return answer
  }

  static <K, T> Collection<T> unique(Collection<T> self, Closure<K> comparable) {
    Map<K, T> map = [:]
    self.each { item ->
      K key = comparable.call(item)
      map.putIfAbsent(key, item)
    }
    return map.values().toList()
  }

  static <K, V> Map<K, V> sort(Map<K, V> self, Closure closure) {
    if (closure instanceof CpsClosure)
      throw new IllegalArgumentException("This closure needs to be synchronized in a NonCPS transformation")
    return self.sort(closure)
  }

  static <T> List<T> sort(Collection<T> self, Closure closure) {
    if (closure instanceof CpsClosure)
      throw new IllegalArgumentException("This closure needs to be synchronized in a NonCPS transformation")
    return self.sort(closure)
  }

  @NonCPS
  static <T> List<T> grep(Collection<T> self, Pattern pattern) {
    return self.grep(pattern)
  }
}
