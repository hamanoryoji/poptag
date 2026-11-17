package bnbemu.server

class Bomb(
    val id: Int,
    val ownerSlotId: Int,
    val pos: Int,
    val bombAttr: Int,
    var isSpecial: Boolean,
    var isMoving: Boolean = false,
    var state: Int = -1,
    var gridX: Int = 0,
    var gridY: Int = 0,
    var expireAt: Long = System.currentTimeMillis() + 2500
) {
    var landingFuseMs: Long = 2000L
    fun updateState(now: Long): Boolean {
        if (now < expireAt) return false

        if (isMoving) {
            // 停止移動，延長時間
            isMoving = false
            expireAt = now + landingFuseMs
            state = 1
        } else {
            // 爆炸
            state = 0
        }
        return true
    }

    fun shouldRemove() = state == 0
}
