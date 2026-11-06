package KondoKit.pluginmanager

import KondoKit.pluginmanager.GitLabPlugin
import KondoKit.pluginmanager.PluginProperties
import KondoKit.util.HttpFetcher
import KondoKit.pluginmanager.GitLabConfig
import KondoKit.util.HttpStatusException
import KondoKit.util.JsonParser
import com.google.gson.JsonObject
import java.util.concurrent.*
import javax.swing.SwingUtilities

object GitLabPluginFetcher {
    private const val TAG = "GitLabPluginFetcher"

    private val executorService = Executors.newFixedThreadPool(5)

    private fun debugLog(message: String) = PluginLogger.debug(TAG, message)

    fun fetchGitLabPlugins(onComplete: (List<GitLabPlugin>) -> Unit) {
        Thread {
            val plugins = mutableListOf<GitLabPlugin>()
            try {
                debugLog("Starting to fetch GitLab plugins...")
                val apiUrl = GitLabConfig.getRepositoryTreeUrl()
                debugLog("API URL: $apiUrl")

                val response = HttpFetcher.fetchString(apiUrl)
                debugLog("Response length: ${response.length} characters")
                debugLog("Response preview: ${response.take(200)}...")

                val treeItems = JsonParser.fromJson(response, Array<JsonObject>::class.java)
                debugLog("Parsed ${treeItems.size} items from JSON")

                val pluginDirectories = mutableListOf<Pair<String, String>>()
                // Filter for directories (trees) by mode 040000 so we only consider plugin folders
                for (jsonObject in treeItems) {
                    if (jsonObject["type"].asString == "tree" && jsonObject["mode"].asString == "040000") {
                        val folderId = jsonObject["id"].asString
                        val folderPath = jsonObject["path"].asString
                        pluginDirectories.add(folderId to folderPath)
                        debugLog("Found directory: $folderPath (ID: $folderId)")
                    }
                }
                debugLog("Found ${pluginDirectories.size} plugin directories")

                val pluginFutures = mutableListOf<Future<GitLabPlugin>>()
                for ((folderId, folderPath) in pluginDirectories) {
                    val future = executorService.submit(Callable<GitLabPlugin> {
                        try {
                            val pluginProperties = fetchPluginProperties(folderPath)
                            debugLog("Successfully fetched properties for: $folderPath")
                            GitLabPlugin(folderId, folderPath, pluginProperties)
                        } catch (e: Exception) {
                            debugLog("Error fetching plugin.properties for $folderPath: ${e.message}")
                            GitLabPlugin(folderId, folderPath, null)
                        }
                    })
                    pluginFutures.add(future)
                }

                for (future in pluginFutures) {
                    try {
                        plugins.add(future.get())
                    } catch (e: Exception) {
                        debugLog("Error getting plugin result: ${e.message}")
                    }
                }
            } catch (e: HttpStatusException) {
                debugLog("HTTP error ${e.statusCode} fetching plugin tree: ${e.body.take(200)}")
            } catch (e: Exception) {
                debugLog("Exception occurred: ${e.message}")
                e.printStackTrace()
            }

            debugLog("Completed fetching plugins. Total plugins: ${plugins.size}")
            SwingUtilities.invokeLater {
                onComplete(plugins)
            }
        }.start()
    }
    
    private fun fetchPluginProperties(folderPath: String): PluginProperties {
        val pluginFilePath = "$folderPath/plugin.properties"
        val pluginUrl = GitLabConfig.getRawFileUrl(pluginFilePath)
        debugLog("Fetching plugin.properties from: $pluginUrl")
        
        try {
            val rawContent = HttpFetcher.fetchString(pluginUrl)
            debugLog("plugin.properties content length: ${rawContent.length} characters")
            debugLog("plugin.properties content preview: ${rawContent.take(200)}...")

            return parseProperties(rawContent)
        } catch (e: HttpStatusException) {
            debugLog("Failed to fetch plugin.properties. Response code: ${e.statusCode}")
            debugLog("Error response for plugin.properties: ${e.body.take(200)}")
            throw Exception("Plugin file not found for folder: $folderPath", e)
        }
    }
    
    private fun parseProperties(content: String): PluginProperties {
        debugLog("Parsing plugin.properties content")
        val lines = content.split("\n")
        var author = "Unknown"
        var version = "Unknown"
        var description = "No description available"
        
        for (line in lines) {
            val parts = line.split("=")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].replace("\"", "").replace("'", "").trim()
                
                when {
                    key.startsWith("AUTHOR", ignoreCase = true) -> {
                        author = value
                        debugLog("Found author: $value")
                    }
                    key.startsWith("VERSION", ignoreCase = true) -> {
                        version = value
                        debugLog("Found version: $value")
                    }
                    key.startsWith("DESCRIPTION", ignoreCase = true) -> {
                        description = value
                        debugLog("Found description: $value")
                    }
                }
            }
        }
        
        debugLog("Parsed properties - Author: $author, Version: $version, Description: $description")
        return PluginProperties(author, version, description)
    }
}
