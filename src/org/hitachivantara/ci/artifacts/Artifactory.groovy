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

  List<Map> searchArtifacts(List<String> filenames, final String pathMatch) {

    String repo = ''//baseUrl.pathSegments().last()
    def sb = "" << "items.find({"
    if (repo) sb << '"repo": "' << repo << '", '
    sb << '"type": "file", "path": {"\$match":"*/' << pathMatch << '"},'
    sb << '"$or": ['
    int lastIdx = filenames.size() - 1
    filenames.eachWithIndex { filename, idx ->
      filename = filename.replace("-dist", "*")
      sb << '{"name": {"\$match":"' << filename << '"}}'
      if (idx < lastIdx) sb << ', '
    }
    sb << ']'
    sb << '})'
    sb << '.include("repo", "path", "name", "actual_md5", "actual_sha1", "sha256", "size", "created")'
    sb << '.sort({"\$asc" : ["created"]})' // only to return the very last snapshot - most recent
    dsl.log.info sb.toString()
    Map aql = aql(sb.toString())
    aql?.results as List<Map> ?: []
  }

  Map aql(String query) {
    def url = baseUrl.newBuilder('api/search/aql').build()
    def result = dsl.sh(script: "#!/bin/sh -e\n curl -L -u '$rtUsername:$rtPassword' -k -X POST -H 'Content-Type:text/plain' $url -d '$query'", returnStdout: true).trim()

    return JsonUtils.toObject(result as String) as Map
  }


}
