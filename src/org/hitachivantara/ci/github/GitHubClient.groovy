package org.hitachivantara.ci.github

import org.hitachivantara.ci.config.BuildData

import static org.hitachivantara.ci.config.LibraryProperties.SCM_CREDENTIALS_ID

/**
 * Simple GitHub API v4 client
 */
class GitHubClient implements Serializable {
  String githubApiUrl = 'https://api.github.com/graphql'
  Map<String, String> headers = ['Accept-Encoding': 'gzip', 'Accept': 'application/vnd.github.queen-beryl-preview+json']

  GitHubRequest buildRequest(String query, Map variables = [:]) {
    BuildData buildData = BuildData.instance
    return new GitHubRequest().with {
      it.url = githubApiUrl
      it.credentialsId = buildData.getString(SCM_CREDENTIALS_ID)
      it.headers = this.headers
      it.query = query
      it.variables = variables
      return it
    }
  }

}
