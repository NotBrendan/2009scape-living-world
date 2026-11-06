package RememberMyLogin

import plugin.Plugin
import plugin.annotations.PluginMeta
import plugin.api.API
import rt4.Component
import rt4.JagString
import rt4.Player
import rt4.client

class plugin : Plugin() {
    var hasRan = false
    var credentials = HashMap<String, HashMap<String, String>>()
    var server = ""
    var username = ""
    var password = ""

    override fun Init() {
        server = client.hostname + ":" + client.port
        credentials = API.GetData("login-credentials") as? HashMap<String, HashMap<String, String>> ?: HashMap()
        username = credentials.get(server)?.get("username") ?: ""
        password = credentials.get(server)?.get("password") ?: ""
    }

    override fun ComponentDraw(componentIndex: Int, component: Component?, screenX: Int, screenY: Int) {
        if (hasRan || API.IsLoggedIn()) return
        if (component!!.text == JagString.of("Please Log In")) {
            API.SetVarcStr(32, username)
            API.SetVarcStr(33, password)
            hasRan = true
        }
    }

    override fun OnLogin() {
        username = String(Player.usernameInput.chars)
        password = String(Player.password.chars)

        credentials[server] = mapOf("username" to username, "password" to password) as HashMap<String, String>

        API.StoreData("login-credentials", credentials)
    }

    override fun OnLogout() {
        hasRan = false
    }
}