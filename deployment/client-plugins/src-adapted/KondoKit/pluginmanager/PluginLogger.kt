package KondoKit.pluginmanager

object PluginLogger {
    fun debug(tag: String, message: String) {
        if (GitLabConfig.DEBUG) {
            println("[$tag] $message")
        }
    }
}
