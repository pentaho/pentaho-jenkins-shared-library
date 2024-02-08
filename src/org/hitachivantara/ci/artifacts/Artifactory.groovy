package org.hitachivantara.ci.artifacts

import okhttp3.HttpUrl
import org.hitachivantara.ci.JsonUtils

class Artifactory {

  HttpUrl baseUrl
  Script dsl
  String rtUsername
  String rtPassword

  Artifactory(Script dsl, String rtURL, String rtUsername, String rtPassword) {

    this.dsl = dsl
    this.baseUrl = HttpUrl.get(rtURL)
    this.rtUsername = rtUsername
    this.rtPassword = rtPassword
  }

  List<Map> searchArtifacts(List<String> filenames) {

    String repo = ''//baseUrl.pathSegments().last()
    def sb = "" << "items.find({"
    if (repo) sb << '"repo": "' << repo << '", '
    sb << '"type": "file", '
    sb << '"$or": ['
    int lastIdx = filenames.size() - 1
    filenames.eachWithIndex { filename, idx ->
      sb << '{"name": "' << filename << '"}'
      if (idx < lastIdx) sb << ', '
    }
    sb << ']'
    sb << '})'
    sb << '.include("repo", "path", "name", "actual_md5", "actual_sha1")'

    dsl.log.debug(sb.toString())

    aql(sb.toString())?.results ?: [] as List<Map>
  }

  def aql(String query) {
    def url = baseUrl.newBuilder('api/search/aql').build()

    def result = dsl.sh(script: "curl -L -u '$rtUsername:$rtPassword' -k -X POST -H 'Content-Type:text/plain' $url -d '$query'", returnStdout: true).trim()
    dsl.log.info("*****")
    dsl.log.info(result)
    return JsonUtils.toObject(result as String) as Map
  }


}
