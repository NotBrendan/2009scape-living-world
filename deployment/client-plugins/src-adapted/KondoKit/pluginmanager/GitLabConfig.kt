package KondoKit.pluginmanager

object GitLabConfig {
    const val GITLAB_PROJECT_ID = "38297322"
    const val GITLAB_PROJECT_PATH = "2009scape/tools/client-plugins"
    const val GITLAB_BRANCH = "master"
    
    const val DEBUG = false // Spams pretty hard
    
    fun getGitLabApiBaseUrl(): String = "https://gitlab.com/api/v4/projects/$GITLAB_PROJECT_ID"
    
    fun getRepositoryTreeUrl(): String = 
        "${getGitLabApiBaseUrl()}/repository/tree?ref=${GITLAB_BRANCH}&per_page=100"
    
    fun getRawFileUrl(filePath: String): String = 
        "${getGitLabApiBaseUrl()}/repository/files/${filePath.replace("/", "%2F")}/raw?ref=${GITLAB_BRANCH}"

    fun getArchiveBaseUrl(): String = 
        "https://gitlab.com/$GITLAB_PROJECT_PATH/-/archive/$GITLAB_BRANCH/${GITLAB_PROJECT_PATH.replace("/", "-")}-$GITLAB_BRANCH.zip"
    
    fun getArchiveUrl(pluginPath: String): String = 
        "${getArchiveBaseUrl()}?path=$pluginPath"
    
    fun getArchiveUrlWithRefType(pluginPath: String): String = 
        "${getArchiveBaseUrl()}?path=$pluginPath&ref_type=heads"
}
