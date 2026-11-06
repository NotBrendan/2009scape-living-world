package KondoKit.pluginmanager

import KondoKit.util.FileUtils
import KondoKit.util.Helpers
import KondoKit.views.ReflectiveEditorView
import plugin.Plugin
import plugin.PluginInfo
import plugin.PluginRepository
import rt4.GlobalJsonConfig
import java.awt.Component
import java.io.File
import java.util.*
import javax.swing.*

class ReflectiveEditorPlugin : Plugin() {
    
    private var gitLabPlugins: List<GitLabPlugin> = listOf()
    private var pluginStatuses: List<PluginInfoWithStatus> = listOf()
    private var reloadPlugins = false  // Flag for scheduled plugin reload to avoid crashes
    private val pluginsDirectory: File = File(GlobalJsonConfig.instance.pluginsFolder)

    override fun Init() {
        GitLabPluginFetcher.fetchGitLabPlugins { plugins ->
            gitLabPlugins = plugins
            updatePluginStatuses()
        }
    }

    fun getGitLabPlugins(): List<GitLabPlugin> = gitLabPlugins
    
    fun getPluginStatuses(): List<PluginInfoWithStatus> = pluginStatuses
    
    fun updatePluginStatuses() {
        val loadedPluginNames = getLoadedPluginNames()
        val statuses = mutableListOf<PluginInfoWithStatus>()
        
        val disabledPluginInfo = getDisabledPluginInfo()
        
        handleDuplicatePlugins(loadedPluginNames, disabledPluginInfo)
        
        for (gitLabPlugin in gitLabPlugins) {
            val pluginName = gitLabPlugin.path
            // Skip KondoKit since it should always be loaded
            if (isKondoKit(pluginName)) {
                continue
            }
            val remoteVersion = gitLabPlugin.pluginProperties?.version ?: "Unknown"
            val description = gitLabPlugin.pluginProperties?.description ?: "No description available"
            val author = gitLabPlugin.pluginProperties?.author ?: "Unknown"
            
            val isLoaded = loadedPluginNames.contains(pluginName)
            val isDisabled = disabledPluginInfo.containsKey(pluginName)
            val disabledVersion = disabledPluginInfo[pluginName]
            
            val existingStatus = pluginStatuses.find { it.name == pluginName }
            val isDownloading = existingStatus?.isDownloading ?: false
            val downloadProgress = existingStatus?.downloadProgress ?: 0
            
            if (isLoaded) {
                statuses.add(PluginInfoWithStatus(
                    name = pluginName,
                    installedVersion = remoteVersion, // We don't have the actual installed version, but we know it's loaded
                    remoteVersion = remoteVersion,
                    description = description,
                    author = author,
                    gitLabPlugin = gitLabPlugin,
                    isInstalled = true,
                    needsUpdate = false, // We assume it's up-to-date since it's loaded
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress
                ))
            } else if (isDisabled) {
                val versionsMatch = disabledVersion != null && disabledVersion == remoteVersion
                if (!versionsMatch) {
                    statuses.add(PluginInfoWithStatus(
                        name = pluginName,
                        installedVersion = disabledVersion,
                        remoteVersion = remoteVersion,
                        description = description,
                        author = author,
                        gitLabPlugin = gitLabPlugin,
                        isInstalled = true, // It's installed but disabled
                        needsUpdate = true, // Needs update since versions don't match
                        isDownloading = isDownloading,
                        downloadProgress = downloadProgress
                    ))
                }
            } else {
                statuses.add(PluginInfoWithStatus(
                    name = pluginName,
                    installedVersion = null,
                    remoteVersion = remoteVersion,
                    description = description,
                    author = author,
                    gitLabPlugin = gitLabPlugin,
                    isInstalled = false,
                    needsUpdate = false,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress
                ))
            }
        }
        
        pluginStatuses = statuses
    }
    
    private fun getLoadedPluginNames(): Set<String> {
        val loadedPluginNames = mutableSetOf<String>()
        
        try {
            val loadedPluginsField = PluginRepository::class.java.getDeclaredField("loadedPlugins")
            loadedPluginsField.isAccessible = true
            val loadedPlugins = loadedPluginsField.get(null) as HashMap<*, *>
            
            for ((_, plugin) in loadedPlugins) {
                val pluginName = getPluginDirName(plugin as Plugin)
                loadedPluginNames.add(pluginName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return loadedPluginNames
    }
    
    private fun parsePluginProperties(content: String): PluginProperties {
        var author = "Unknown"
        var version = "Unknown"
        var description = "No description available"
        
        val lines = content.split("\n")
        for (line in lines) {
            val parts = line.split("=")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].replace("\"", "").replace("'", "").trim()
                
                when {
                    key.startsWith("AUTHOR", ignoreCase = true) -> {
                        author = value
                    }
                    key.startsWith("VERSION", ignoreCase = true) -> {
                        version = value
                    }
                    key.startsWith("DESCRIPTION", ignoreCase = true) -> {
                        description = value
                    }
                }
            }
        }
        
        return PluginProperties(author, version, description)
    }
    
    private fun getDisabledPluginInfo(): Map<String, String> {
        val disabledPluginInfo = mutableMapOf<String, String>()
        val disabledDir = File(pluginsDirectory, "disabled")
        
        if (disabledDir.exists() && disabledDir.isDirectory) {
            val disabledPlugins = disabledDir.listFiles { file -> file.isDirectory } ?: arrayOf()
            
            for (pluginDir in disabledPlugins) {
                try {
                    val propertiesFile = File(pluginDir, "plugin.properties")
                    if (propertiesFile.exists()) {
                        val content = propertiesFile.readText()
                        val properties = parsePluginProperties(content)
                        disabledPluginInfo[pluginDir.name] = properties.version
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        return disabledPluginInfo
    }
    
    private fun handleDuplicatePlugins(loadedPluginNames: Set<String>, disabledPluginInfo: Map<String, String>) {
        var needsReload = false
        for (pluginName in loadedPluginNames) {
            if (disabledPluginInfo.containsKey(pluginName)) {
                try {
                    val disabledDir = File(pluginsDirectory, "disabled")
                    val pluginDir = File(disabledDir, pluginName)
                    if (pluginDir.exists() && pluginDir.isDirectory) {
                        if (FileUtils.deleteRecursively(pluginDir)) {
                            needsReload = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // Reload plugins if we deleted any disabled duplicates
        if (needsReload) {
            PluginRepository.reloadPlugins()
        }
    }
    
    fun deletePlugin(pluginName: String, isDisabled: Boolean, mainPanel: Component) {
        try {
            val pluginDir = if (isDisabled) {
                File(File(pluginsDirectory, "disabled"), pluginName)
            } else {
                File(pluginsDirectory, pluginName)
            }

            if (pluginDir.exists() && pluginDir.isDirectory) {
                if (FileUtils.deleteRecursively(pluginDir)) {
                    Helpers.showToast(mainPanel, "Plugin deleted successfully", JOptionPane.INFORMATION_MESSAGE)
                    
                    if (!isDisabled) {
                        reloadPlugins = true
                    }
                    
                    SwingUtilities.invokeLater {
                        ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
                    }
                } else {
                    Helpers.showToast(mainPanel, "Failed to delete plugin", JOptionPane.ERROR_MESSAGE)
                }
            } else {
                Helpers.showToast(mainPanel, "Plugin directory not found", JOptionPane.ERROR_MESSAGE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Helpers.showToast(mainPanel, "Error deleting plugin: ${e.message}", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun isKondoKit(pluginName: String): Boolean {
        return pluginName == "KondoKit"
    }
    
    fun enablePlugin(pluginName: String, mainPanel: Component) {
        try {
            val disabledDir = File(pluginsDirectory, "disabled")
            val sourceDir = File(disabledDir, pluginName)
            val destDir = File(pluginsDirectory, pluginName)
            
            if (!sourceDir.exists()) {
                Helpers.showToast(mainPanel, "Plugin directory not found: ${sourceDir.absolutePath}", JOptionPane.ERROR_MESSAGE)
                return
            }
            
            if (sourceDir.renameTo(destDir)) {
                Helpers.showToast(mainPanel, "Plugin enabled")
                
                // Schedule plugin reload to avoid crashes
                reloadPlugins = true
                SwingUtilities.invokeLater {
                    ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
                }
            } else {
                Helpers.showToast(mainPanel, "Failed to enable plugin", JOptionPane.ERROR_MESSAGE)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Helpers.showToast(mainPanel, "Error enabling plugin: ${e.message}", JOptionPane.ERROR_MESSAGE)
        }
    }
    
    fun togglePlugin(plugin: Plugin, pluginInfo: PluginInfo, toggleSwitch: Component, activated: Boolean, mainPanel: Component) {
        try {
            val pluginDirName = getPluginDirName(plugin)
            
            val sourceDir = if (activated) {
                File(File(pluginsDirectory, "disabled"), pluginDirName)
            } else {
                File(pluginsDirectory, pluginDirName)
            }
            
            val destDir = if (activated) {
                File(pluginsDirectory, pluginDirName)
            } else {
                val disabledDir = File(pluginsDirectory, "disabled")
                if (!disabledDir.exists()) {
                    disabledDir.mkdirs()
                }
                File(disabledDir, pluginDirName)
            }
            
            if (!sourceDir.exists()) {
                Helpers.showToast(mainPanel, "Plugin directory not found: ${sourceDir.absolutePath}", JOptionPane.ERROR_MESSAGE)
                SwingUtilities.invokeLater {
                    if (toggleSwitch is JComponent) {
                        toggleSwitch.repaint()
                    }
                }
                return
            }
            
            if (sourceDir.renameTo(destDir)) {
                Helpers.showToast(mainPanel, if (activated) "Plugin enabled" else "Plugin disabled")
                
                // Schedule plugin reload to avoid crashes
                reloadPlugins = true
                
                SwingUtilities.invokeLater {
                    ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
                }
            } else {
                Helpers.showToast(mainPanel, "Failed to ${if (activated) "enable" else "disable"} plugin", JOptionPane.ERROR_MESSAGE)
                SwingUtilities.invokeLater {
                    if (toggleSwitch is JComponent) {
                        toggleSwitch.repaint()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Helpers.showToast(mainPanel, "Error toggling plugin: ${e.message}", JOptionPane.ERROR_MESSAGE)
            SwingUtilities.invokeLater {
                if (toggleSwitch is JComponent) {
                    toggleSwitch.repaint()
                }
            }
        }
    }
    
    private fun getPluginDirName(plugin: Plugin): String {
        // Extract the directory name from the plugin's package
        // The package name is typically like "GroundItems.plugin" so we take the first part
        val packageName = plugin.javaClass.`package`.name
        return packageName.substringBefore(".")
    }
    
    fun isPluginEnabled(plugin: Plugin, pluginInfo: PluginInfo): Boolean {
        val pluginDirName = getPluginDirName(plugin)
        val pluginDir = File(pluginsDirectory, pluginDirName)
        return pluginDir.exists() && pluginDir.isDirectory
    }
    
    fun startPluginDownload(pluginStatus: PluginInfoWithStatus, mainPanel: Component) {
        val gitLabPlugin = pluginStatus.gitLabPlugin
        if (gitLabPlugin == null) {
            Helpers.showToast(mainPanel, "Plugin information not available", JOptionPane.ERROR_MESSAGE)
            return
        }
        
        val updatedStatuses = pluginStatuses.map { 
            if (it.name == pluginStatus.name) {
                it.copy(isDownloading = true, downloadProgress = 0)
            } else {
                it
            }
        }
        pluginStatuses = updatedStatuses
        
        SwingUtilities.invokeLater {
            ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
        }
        
        PluginDownloadManager.downloadPlugin(gitLabPlugin, object : PluginDownloadManager.DownloadProgressCallback {
            override fun onProgress(pluginName: String, progress: Int) {
                val updatedStatuses = pluginStatuses.map {
                    if (it.name == pluginName) {
                        it.copy(downloadProgress = progress)
                    } else {
                        it
                    }
                }
                pluginStatuses = updatedStatuses
                
                SwingUtilities.invokeLater {
                    ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
                }
            }
            
            override fun onComplete(pluginName: String, success: Boolean, errorMessage: String?) {
                val updatedStatuses = pluginStatuses.map {
                    if (it.name == pluginName) {
                        it.copy(isDownloading = false, downloadProgress = if (success) 100 else 0)
                    } else {
                        it
                    }
                }
                pluginStatuses = updatedStatuses
                
                if (success) {
                    Helpers.showToast(mainPanel, "Plugin downloaded successfully!", JOptionPane.INFORMATION_MESSAGE)
                    // Reload plugins to make the newly downloaded plugin available
                    PluginRepository.reloadPlugins()
                } else {
                    Helpers.showToast(mainPanel, "Failed to download plugin: ${errorMessage ?: "Unknown error"}", JOptionPane.ERROR_MESSAGE)
                }
                
                SwingUtilities.invokeLater {
                    ReflectiveEditorView.addPlugins(ReflectiveEditorView.panel)
                }
            }
        })
    }

    fun getPluginsDirectory(): File = pluginsDirectory
    
    fun shouldReloadPlugins(): Boolean = reloadPlugins
    
    fun setReloadPlugins(reload: Boolean) {
        reloadPlugins = reload
    }
}
