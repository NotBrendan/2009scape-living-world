package ChatboxHelmets

import KondoKit.Exposed
import plugin.Plugin
import plugin.api.API
import rt4.Component
import rt4.JagString
import rt4.Player
import java.nio.charset.StandardCharsets
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class plugin : Plugin() {

    private val TARGET_COMPONENT_ID = 8978483
    private val TARGET_COMPONENT_INDEX = 51
    private val SETTINGS_KEY = "chat-helms"
    private val BUBBLE_ICON = "<img=3>"
    private val gson = Gson()

    val TYPE_ICONS: Map<Int, String> = hashMapOf(
        0 to "",
        1 to "<img=4>", // IM
        2 to "<img=5>", // HCIM
        3 to "<img=6>", // UIM
    )

    @Exposed(description = "Username to account type mappings (0=Normal, 1=IM, 2=HCIM, 3=UIM)")
    private var usernameMatches = HashMap<String, Int>()

    private var ACCOUNT_TYPE = 0
    private var component: Component? = null

    override fun Init() {
        // Load username matches from local storage
        val storedData = API.GetData(SETTINGS_KEY)
        usernameMatches = when (storedData) {
            is String -> {
                try {
                    gson.fromJson(storedData, HashMap::class.java) as HashMap<String, Int>
                } catch (e: Exception) {
                    println("Failed to deserialize username matches: ${e.message}")
                    HashMap()
                }
            }
            else -> HashMap()
        }
        getConfig()
    }

    override fun OnPluginsReloaded(): Boolean {
        cleanup()
        return false
    }

    override fun OnLogin() {
        Init()
    }

    override fun Draw(timeDelta: Long) {
        replace()
    }

    override fun ComponentDraw(componentIndex: Int, component: Component?, screenX: Int, screenY: Int) {
        if (component?.id == TARGET_COMPONENT_ID && componentIndex == TARGET_COMPONENT_INDEX) {
            this.component = component
        }
    }

    // Allow setting from the chatbox
    override fun ProcessCommand(commandStr: String?, args: Array<out String>?) {
        super.ProcessCommand(commandStr, args)
        when(commandStr) {
            "::chathelm" -> {
                if (args != null) {
                    if(args.isEmpty()) return
                    val type = args[0].toIntOrNull() ?: return
                    ACCOUNT_TYPE = type
                    usernameMatches[getCleanUserName().toLowerCase()] = ACCOUNT_TYPE
                    storeData()
                }
            }
        }
    }

    fun OnKondoValueUpdated() {
        storeData()
        Init()
    }

    private fun cleanup() {
        ACCOUNT_TYPE = 0
        replace()
        component = null
    }

    private fun replace() {
        if (component != null && component?.id == TARGET_COMPONENT_ID) {
            // Unmodified login name is used here to display it however the user types it
            // caps and spaces respected.
            val modifiedStr = replaceUsernameInBytes(component!!.text.chars, Player.usernameInput.toString())
            component?.text = JagString.parse(modifiedStr)
        }
    }

    private fun getCleanUserName(): String {
        return Player.usernameInput.toString().replace(" ", "_")
    }

    private fun getConfig() {
        val cleanUsername = getCleanUserName()

        //println(cleanUsername)

        // Abort if we are pre-login
        if (cleanUsername.isEmpty()) return

        // Check if we already have the account type for this user
        val lowercaseUsername = cleanUsername.toLowerCase()
        if (usernameMatches.containsKey(lowercaseUsername)) {
            ACCOUNT_TYPE = usernameMatches[lowercaseUsername]!!
            return
        }

        // No match found, check the hiscores (live server)
        fetchAccountTypeFromAPI(lowercaseUsername)
    }

    private fun fetchAccountTypeFromAPI(lowercaseUsername : String){
        val apiUrl = System.getProperty("livingworld.pricesUrl", "https://prices.example.com") + "/api/compat/playerSkills/${lowercaseUsername}"
        Thread {
            try {
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                // If a request take longer than 5 seconds timeout.
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.use { it.readText() }
                    reader.close()
                    updatePlayerData(response, lowercaseUsername)
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun updatePlayerData(jsonResponse: String, lowercaseUsername: String) {
        val hiscoresResponse = gson.fromJson(jsonResponse, HiscoresResponse::class.java)
        ACCOUNT_TYPE = hiscoresResponse.info.iron_mode.toInt()
        usernameMatches[lowercaseUsername] = ACCOUNT_TYPE
        storeData()
    }

    private fun replaceUsernameInBytes(bytes: ByteArray, username: String): String {
        // Convert bytes to string to work with them
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val colonIndex = text.indexOf(": ")
        if (colonIndex != -1) {
            val suffix = text.substring(colonIndex)
            // modify and add the rest (username is always before the first ':' )
            val newText = "${TYPE_ICONS.getOrDefault(ACCOUNT_TYPE, "")}$username$BUBBLE_ICON$suffix"
            return encodeToJagStr(newText)
        }
        return encodeToJagStr(text)
    }

    private fun storeData() {
        val jsonString = gson.toJson(usernameMatches)
        API.StoreData(SETTINGS_KEY, jsonString)
    }

    private fun encodeToJagStr(str: String): String {
        val result = StringBuilder()
        for (char in str) {
            val escaped = SPECIAL_ESCAPES[char]
            if (escaped != null) {
                result.append(escaped)
            } else {
                result.append(char)
            }
        }
        return result.toString()
    }
}