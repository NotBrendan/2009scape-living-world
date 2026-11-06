package KondoKit.pluginmanager

import KondoKit.util.HttpFetcher
import KondoKit.pluginmanager.GitLabConfig
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities

/**
 * Manages downloading and installing plugins from GitLab with concurrent support
 */
object PluginDownloadManager {
    private const val TAG = "PluginDownloadManager"
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    private val downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)
    
    interface DownloadProgressCallback {
        fun onProgress(pluginName: String, progress: Int)
        fun onComplete(pluginName: String, success: Boolean, errorMessage: String? = null)
    }

    private fun debugLog(message: String) = PluginLogger.debug(TAG, message)

    fun getDownloadUrlForLogging(plugin: GitLabPlugin): String {
        return GitLabConfig.getArchiveUrl(plugin.path)
    }

    /**
     * Download a single plugin with progress updates
     */
    fun downloadPlugin(plugin: GitLabPlugin, callback: DownloadProgressCallback) {
        downloadExecutor.submit {
            try {
                debugLog("Starting download for plugin: ${plugin.path}")
                callback.onProgress(plugin.path, 0)
                
                val success = downloadAndExtractPlugin(plugin, callback)
                
                if (success) {
                    debugLog("Successfully downloaded and extracted plugin: ${plugin.path}")
                    SwingUtilities.invokeLater {
                        callback.onComplete(plugin.path, true)
                    }
                } else {
                    debugLog("Failed to download plugin: ${plugin.path}")
                    SwingUtilities.invokeLater {
                        callback.onComplete(plugin.path, false, "Failed to download plugin")
                    }
                }
            } catch (e: Exception) {
                debugLog("Exception during download of ${plugin.path}: ${e.message}")
                e.printStackTrace()
                SwingUtilities.invokeLater {
                    callback.onComplete(plugin.path, false, e.message)
                }
            }
        }
    }

    /**
     * Download multiple plugins concurrently
     */
    fun downloadPlugins(plugins: List<GitLabPlugin>, callback: (String, Boolean, String?) -> Unit) {
        debugLog("Starting concurrent download of ${plugins.size} plugins")
        
        for (plugin in plugins) {
            downloadPlugin(plugin, object : DownloadProgressCallback {
                override fun onProgress(pluginName: String, progress: Int) {
                }
                
                override fun onComplete(pluginName: String, success: Boolean, errorMessage: String?) {
                    callback(pluginName, success, errorMessage)
                }
            })
        }
    }

    /**
     * Download and extract a plugin from GitLab
     */
    private fun downloadAndExtractPlugin(plugin: GitLabPlugin, callback: DownloadProgressCallback): Boolean {
        try {
            if (plugin.path.isBlank()) {
                debugLog("Plugin path is blank for plugin: ${plugin.path}")
                return false
            }
            
            debugLog("Starting download for plugin: ${plugin.path}")
            
            // Try multiple URL formats since GitLab has changed their API
            val downloadUrls = listOf(
                // Format 1: Using ref_type parameter
                GitLabConfig.getArchiveUrlWithRefType(plugin.path),
                // Format 2: Using ref parameter
                GitLabConfig.getArchiveUrl(plugin.path),
                GitLabConfig.getArchiveBaseUrl()
            )
            
            for (downloadUrl in downloadUrls) {
                debugLog("Trying download URL: $downloadUrl")
                
                val url = try {
                    URL(downloadUrl)
                } catch (e: Exception) {
                    debugLog("Invalid URL: $downloadUrl for plugin: ${plugin.path}. Error: ${e.message}")
                    continue
                }
                
                val connection = try {
                    HttpFetcher.openGetConnection(url.toString())
                } catch (e: Exception) {
                    debugLog("Failed to open connection for plugin: ${plugin.path} with URL: $downloadUrl. Error: ${e.message}")
                    continue
                }
                
                debugLog("Connection opened for plugin: ${plugin.path}")
                debugLog("Request headers - User-Agent: ${connection.getRequestProperty("User-Agent")}")
                debugLog("Request headers - Accept: ${connection.getRequestProperty("Accept")}")
                
                val contentLength = connection.contentLength
                debugLog("Content length: $contentLength bytes for plugin: ${plugin.path}")
                
                val responseCode = connection.responseCode
                debugLog("Response code: $responseCode for plugin: ${plugin.path}")
                
                val responseMessage = connection.responseMessage
                debugLog("Response message: $responseMessage for plugin: ${plugin.path}")
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    if (inputStream == null) {
                        debugLog("Input stream is null for plugin: ${plugin.path}")
                        continue
                    }
                    
                    debugLog("Input stream available for plugin: ${plugin.path}")
                    
                    val pluginsDir = File(rt4.GlobalJsonConfig.instance.pluginsFolder)
                    debugLog("Plugins directory: ${pluginsDir.absolutePath}")
                    
                    if (!pluginsDir.exists()) {
                        debugLog("Plugins directory does not exist: ${pluginsDir.absolutePath}")
                        if (!pluginsDir.mkdirs()) {
                            debugLog("Failed to create plugins directory: ${pluginsDir.absolutePath}")
                            return false
                        }
                        debugLog("Created plugins directory: ${pluginsDir.absolutePath}")
                    }
                    
                    if (!pluginsDir.isDirectory) {
                        debugLog("Plugins path is not a directory: ${pluginsDir.absolutePath}")
                        return false
                    }
                    
                    val pluginDir = File(pluginsDir, plugin.path)
                    debugLog("Plugin directory: ${pluginDir.absolutePath}")
                    
                    if (!pluginDir.exists()) {
                        pluginDir.mkdirs()
                        debugLog("Created plugin directory: ${pluginDir.absolutePath}")
                    }
                    
                    val tempZipFile = File.createTempFile("plugin_", ".zip")
                    tempZipFile.deleteOnExit()
                    debugLog("Created temp file: ${tempZipFile.absolutePath}")
                    
                    var totalBytesRead = 0L
                    inputStream.use { input ->
                        FileOutputStream(tempZipFile).use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            
                            debugLog("Starting to read data for plugin: ${plugin.path}")
                            
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                
                                // Report progress if content length is known
                                if (contentLength > 0) {
                                    val progress = (totalBytesRead * 100 / contentLength).toInt()
                                    // Limit progress updates to avoid flooding the UI
                                    if (progress % 5 == 0) {
                                        SwingUtilities.invokeLater {
                                            callback.onProgress(plugin.path, progress)
                                        }
                                    }
                                }
                            }
                            
                            debugLog("Finished reading data for plugin: ${plugin.path}. Total bytes: $totalBytesRead")
                        }
                    }
                    
                    debugLog("Downloaded ${totalBytesRead} bytes to ${tempZipFile.absolutePath}")
                    
                    if (extractZipFile(tempZipFile, pluginDir, plugin.path)) {
                        debugLog("Successfully extracted plugin to ${pluginDir.absolutePath}")
                        tempZipFile.delete()
                        return true
                    } else {
                        debugLog("Failed to extract plugin")
                        tempZipFile.delete()
                        continue // Try next URL format
                    }
                } else {
                    debugLog("HTTP error: $responseCode for plugin: ${plugin.path} with URL: $downloadUrl")
                    
                    // Try to read error stream for more details
                    try {
                        val errorStream = connection.errorStream
                        if (errorStream != null) {
                            val errorReader = BufferedReader(InputStreamReader(errorStream))
                            val errorResponse = errorReader.use { it.readText() }
                            debugLog("Error response: $errorResponse for plugin: ${plugin.path}")
                        } else {
                            debugLog("Error stream is null for plugin: ${plugin.path}")
                        }
                    } catch (e: Exception) {
                        debugLog("Exception while reading error stream: ${e.message} for plugin: ${plugin.path}")
                    }
                    
                    continue // Try next URL format
                }
            }
            
            debugLog("All URL formats failed for plugin: ${plugin.path}")
            return false
        } catch (e: Exception) {
            debugLog("Exception during download and extraction for plugin ${plugin.path}: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Extract a ZIP file to the specified directory
     */
    private fun extractZipFile(zipFile: File, targetDir: File, pluginPath: String): Boolean {
        try {
            debugLog("Extracting ZIP file: ${zipFile.absolutePath} to ${targetDir.absolutePath}")
            
            if (!zipFile.exists()) {
                debugLog("ZIP file does not exist: ${zipFile.absolutePath}")
                return false
            }
            
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                debugLog("Failed to create target directory: ${targetDir.absolutePath}")
                return false
            }
            
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry?
                var entryCount = 0
                
                while (zis.nextEntry.also { entry = it } != null) {
                    entryCount++
                    val entryName = entry!!.name
                    
                    debugLog("Processing ZIP entry $entryCount: $entryName")
                    
                    // Strip the top-level GitLab archive directory so we extract only the plugin contents
                    val relativePath = if (entryName.contains("/")) {
                        entryName.substring(entryName.indexOf("/") + 1)
                    } else {
                        entryName
                    }
                    
                    debugLog("Relative path for entry: $relativePath")
                    
                    if (relativePath.startsWith(pluginPath) && relativePath != pluginPath) {
                        val fileName = relativePath.substring(pluginPath.length + 1) // +1 for the trailing slash
                        
                        debugLog("File name to extract: $fileName")
                        
                        if (fileName.isNotEmpty()) {
                            val file = File(targetDir, fileName)
                            
                            debugLog("Target file path: ${file.absolutePath}")
                            
                            val parent = file.parentFile
                            if (parent != null && !parent.exists()) {
                                if (parent.mkdirs()) {
                                    debugLog("Created parent directories: ${parent.absolutePath}")
                                } else {
                                    debugLog("Failed to create parent directories: ${parent.absolutePath}")
                                    zis.closeEntry()
                                    continue
                                }
                            }
                            
                            if (entry!!.isDirectory) {
                                if (!file.exists()) {
                                    if (file.mkdirs()) {
                                        debugLog("Created directory: ${file.absolutePath}")
                                    } else {
                                        debugLog("Failed to create directory: ${file.absolutePath}")
                                    }
                                } else {
                                    debugLog("Directory already exists: ${file.absolutePath}")
                                }
                            } else {
                                try {
                                    FileOutputStream(file).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                    debugLog("Extracted file: ${file.absolutePath}")
                                } catch (e: Exception) {
                                    debugLog("Failed to extract file ${file.absolutePath}: ${e.message}")
                                }
                            }
                        } else {
                            debugLog("Skipping empty file name")
                        }
                    } else {
                        debugLog("Skipping entry (not part of plugin): $relativePath")
                    }
                    
                    zis.closeEntry()
                }
                
                debugLog("Processed $entryCount entries from ZIP file")
            }
            
            debugLog("Successfully extracted ZIP file")
            return true
        } catch (e: Exception) {
            debugLog("Exception during ZIP extraction: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
