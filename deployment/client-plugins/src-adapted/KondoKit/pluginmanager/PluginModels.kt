package KondoKit.pluginmanager

data class GitLabPlugin(
    val id: String,
    val path: String,
    val pluginProperties: PluginProperties?
)

data class PluginProperties(
    val author: String,
    val version: String,
    val description: String
)

/**
 * Data class representing a plugin with its installation status and update information
 */
data class PluginInfoWithStatus(
    val name: String,
    val installedVersion: String?,
    val remoteVersion: String?,
    val description: String?,
    val author: String?,
    val gitLabPlugin: GitLabPlugin?,
    val isInstalled: Boolean,
    val needsUpdate: Boolean,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
)
