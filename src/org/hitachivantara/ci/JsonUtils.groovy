package org.hitachivantara.ci

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic


class JsonUtils implements Serializable {

  /**
   * Serializes a object as a JSON structure
   *
   * @param object Object to serialize
   * @return a string representation of the object as JSON
   */
  @NonCPS
  static String toJsonString(def object) {
    return JsonOutput.toJson(object)
  }

  /**
   * Parse a text representation of a JSON data structure
   *
   * @param text JSON text to parse
   * @return a data structure of lists and maps
   */
  @NonCPS
  static def toObject(String text) {
    return new JsonSlurperClassic().parseText(text)
  }
}