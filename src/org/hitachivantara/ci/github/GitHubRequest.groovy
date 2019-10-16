package org.hitachivantara.ci.github

import org.hitachivantara.ci.JsonUtils
import org.hitachivantara.ci.LogLevel
import org.hitachivantara.ci.config.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScript


class GitHubRequest implements Serializable {
  String url
  String credentialsId
  int timeout = 0 // timeout in seconds, 0 implies no timeout
  Map<String, String> headers
  String query
  Map variables

  static Script getSteps() {
    ({} as CpsScript)
  }

  Map execute() {
    List customHeaders = headers.collect { String key, String value ->
      [name: key, value: value, maskValue: false]
    }
    Map requestBody = ["query": query, "variables": variables]

    def response = steps.httpRequest([
      url           : url,
      authentication: credentialsId,
      timeout       : timeout,
      httpMode      : 'POST',
      contentType   : 'APPLICATION_JSON',
      quiet         : !BuildData.instance.logLevel.encompasses(LogLevel.DEBUG),
      customHeaders : customHeaders,
      requestBody   : JsonUtils.toJsonString(requestBody)
    ])
    return JsonUtils.toObject(response.content as String) as Map
  }

}
