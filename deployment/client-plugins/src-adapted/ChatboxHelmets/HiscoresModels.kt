package ChatboxHelmets

// Lifted from KondoHiscores view
data class HiscoresResponse(
    val info: PlayerInfo,
    val skills: List<Skill>
)

data class PlayerInfo(
    val exp_multiplier: String,
    val iron_mode: String
)

data class Skill(
    val id: String,
    val dynamic: String,
    val experience: String,
    val static: String
)