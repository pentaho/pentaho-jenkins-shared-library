/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.hitachivantara.ci.config

import com.cloudbees.groovy.cps.NonCPS

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Helper Map wrapper to allow getting properties from Environment
 * The environment properties can be overriden in any stage, so given that they are
 * not static it makes sense to just fallback to them when a property isn't found
 * on a higher priority source.
 */
class ConfigurationMap<K, V> extends LinkedHashMap<K, V> {
  private static final Pattern PROPERTY_REPLACE_PATTERN = Pattern.compile(/\$\{(?<key>[\w|_|-|\.]+)\}/)

  def defaults = [:]

  ConfigurationMap(defaults = [:], Map initialValues = [:]) {
    this.defaults = defaults
    this.putAll(initialValues)
  }

  @NonCPS
  private V getFromDefaults(Object key) {
    // Note: need to use [key] notation here because org.jenkinsci.plugins.workflow.cps.EnvActionImpl does not
    // implement get() method
    if (defaults instanceof Collection) {
      def defaultsSource = defaults.find { it && it[key] != null }
      return (defaultsSource ? defaultsSource[key] : null) as V
    }
    return defaults[key] as V
  }

  @NonCPS
  @Override
  V get(Object key) {
    def value = super.get(key)

    // search the defaults if not found
    // Match with null cause we should consider empty string as a valid override
    if (value == null) {
      value = getFromDefaults(key)
    }
    try {
      return filter(value)
    }
    catch (StackOverflowError e) {
      throw new CyclicPropertyException("Cyclic filtering detected while resolving property [$key]")
    }
  }

  @NonCPS
  @Override
  V put(K key, V value) {
    merge([(key): value], this)
    return get(key)
  }

  @NonCPS
  @Override
  void putAll(Map<? extends K, ? extends V> source) {
    merge(source, this)
  }

  @NonCPS
  def leftShift(Map source) {
    merge(source, this)
    return this
  }

  @NonCPS
  private void merge(Map source, Map destination) {
    source.each { key, value ->
      if (value instanceof Map && destination[key] instanceof Map) {
        merge(value as Map, destination[key] as Map)
      } else {
        if (destination == this) {
          super.put(key, value)
        } else {
          destination[key] = value
        }
      }
    }
  }

  @NonCPS
  def plus(Map right) {
    new ConfigurationMap(defaults, getRawMap()) << right
  }

  @NonCPS
  private def filter(toFilter) {
    if (toFilter && toFilter instanceof String) {
      Matcher matcher = PROPERTY_REPLACE_PATTERN.matcher(toFilter)
      StringBuffer sb = new StringBuffer(toFilter.length())

      while (matcher.find()) {
        String key = matcher.group('key')

        // try for composite key 'my.key'
        List keyParts = key.tokenize('.')
        def value = this

        for (String keyPart : keyParts) {
          if (value instanceof Map) {
            value = value.get(keyPart)
          } else {
            break
          }
        }

        // if no value is set, leave the key untouched
        if (value == null) value = matcher.group()
        matcher.appendReplacement(sb, Matcher.quoteReplacement(value as String))
      }
      matcher.appendTail(sb)

      return sb.toString()
    }

    return toFilter
  }

  @NonCPS
  private def getAs(Class clazz, String key) {
    def value = get(key)

    if (value == null) return null

    switch (value) {
      case clazz:
        return value
      case CharSequence:
        return clazz.valueOf(value)
      default:
        throw new PropertyCastException("Value '$value' cannot be read as $clazz")
    }
  }

  @NonCPS
  Integer getInt(String key) {
    getAs(Integer, key) as Integer ?: 0i
  }

  @NonCPS
  Double getDouble(String key) {
    getAs(Double, key) as Double ?: 0d
  }

  @NonCPS
  Boolean getBool(String key) {
    getAs(Boolean, key) as Boolean ?: false
  }

  @NonCPS
  String getString(String key) {
    get(key) as String ?: ''
  }

  @NonCPS
  List getList(String key) {
    get(key) as List ?: []
  }


  @NonCPS
  @Override
  boolean containsKey(Object key) {
    super.containsKey(key) || getFromDefaults(key) != null
  }

  @NonCPS
  @Override
  boolean containsValue(Object value) {
    throw new UnsupportedOperationException('Operation not supported')
  }

  @NonCPS
  @Override
  V remove(Object key) {
    throw new UnsupportedOperationException('Operation not supported')
  }

  @NonCPS
  @Override
  void clear() {
    throw new UnsupportedOperationException('Operation not supported')
  }

  @NonCPS
  @Override
  Collection<V> values() {
    throw new UnsupportedOperationException('Operation not supported')
  }

  @NonCPS
  @Override
  Set<Map.Entry<K, V>> entrySet() {
    // provide a filtered set of entries
    super.entrySet().collect { Map.Entry entry ->
      [getKey: { entry.key as K }, getValue: { get(entry.key) as V }] as Map.Entry<K, V>
    } as Set
  }

  @NonCPS
  Map getRawMap() {
    keySet().collectEntries { k ->
      def value = super.get(k)

      switch (value) {
        case ConfigurationMap:
          value = (value as ConfigurationMap).getRawMap()
          break
        case Enum:
          value = value.toString()
          break
        default:
          break
      }

      [(k): value]
    } as LinkedHashMap
  }
}
