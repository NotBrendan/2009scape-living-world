package KondoKit.ui

interface OnUpdateCallback {
    fun onUpdate()
}

interface OnDrawCallback {
    fun onDraw(timeDelta: Long)
}

interface OnXPUpdateCallback {
    fun onXPUpdate(skillId: Int, xp: Int)
}

interface OnKillingBlowNPCCallback {
    fun onKillingBlowNPC(npcID: Int, x: Int, z: Int)
}

interface OnPostClientTickCallback {
    fun onPostClientTick()
}
