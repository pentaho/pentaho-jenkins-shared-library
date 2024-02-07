package org.hitachivantara.ci.artifacts

import groovy.json.JsonSlurper
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Route

class Artifactory {

  def parser = new JsonSlurper()

  static final MediaType TEXT = MediaType.parse('text/plain; charset=utf-8')

  HttpUrl baseUrl
  OkHttpClient http
  Script dsl

  Artifactory(Script dsl, String rtURL, String rtUsername, String rtPassword, String rtApiToken) {

    this.dsl = dsl
    this.baseUrl = HttpUrl.get(rtURL)
    this.http = new OkHttpClient.Builder()
        .authenticator({ Route route, Response response ->
          if (response.request().header("Authorization") != null) {
            return null // Give up, we've already attempted to authenticate.
          }
          def request = response.request().newBuilder()
          if (rtApiToken) {
            request.header('X-JFrog-Art-Api', rtApiToken)
          } else {
            request.header('Authorization', Credentials.basic(rtUsername, rtPassword))
          }
          return request.build()
        })
        .addInterceptor({ Interceptor.Chain chain ->
          // workaround for Artifactory 6x because it doesn't add a challenge for authentication
          Request request = chain.request()
          def authenticatedRequest = request.newBuilder()
          if (rtApiToken) {
            authenticatedRequest.header('X-JFrog-Art-Api', rtApiToken)
          } else {
            authenticatedRequest.header('Authorization', Credentials.basic(rtUsername, rtPassword))
          }
          return chain.proceed(authenticatedRequest.build())
        })
        .build()
  }

  List<Map> searchArtifacts(List<String> filenames) {

    dsl.log.info("44444444444444")
    dsl.log.info(filenames)

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

    dsl.log.info("777777")
    dsl.log.info(sb.toString())

    aql(sb.toString(), TEXT)?.results ?: []
  }

  def aql(String query, MediaType type) {
    def url = baseUrl.newBuilder('api/search/aql').build()
    safeRequest(new Request.Builder().url(url)
        .post(RequestBody.create(query, type))
        .build()
    )
  }

  def safeRequest(Request request) { return httpRequest(request, false) }

  def httpRequest(Request request, boolean safeCall = false) throws IOException {

    dsl.log.info("çççççççççsçsçsçsçsçsçsççss")

    http.newCall(request).execute().withCloseable { Response response ->
      dsl.log.info("çççççççç")
      if (response.isSuccessful() || safeCall) {
        dsl.log.info("çççççççsssssssdddsdjfskjdhfishfsdhç")

        String body = response.body().string()
        return body ? parser.parseText(body) : body
      }
      dsl.log.info("MMMMMMMMMMMM")
      throw new IOException("$response")
    }
  }
}
