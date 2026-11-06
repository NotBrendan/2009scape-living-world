package SmartRoofs

import plugin.api.API
import plugin.api.MiniMenuAction
import plugin.api.MiniMenuEntry
import plugin.api.RoofVisibilityHandler
import rt4.Camera
import rt4.Cs1ScriptRunner
import rt4.LocType
import rt4.MathUtils
import rt4.Player
import rt4.PlayerList
import rt4.SceneGraph
import rt4.ScriptRunner
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object RoofHider {
    private const val TILE_FLAG_UNDER_ROOF = API.TILE_FLAG_UNDER_ROOF
    private const val SMART_ROOFS_ACTIVE_GRACE_MS = 750L

    private const val MAP_FLAG_ROOF_GROUP = 2

    private const val HOVER_GROUP_START = 3
    private const val HOVER_TARGET_LIMIT = 8
    private const val HOVER_HOLD_MS = 125L
    private const val HOVER_PRIMARY_HIT_DISTANCE = 64
    private const val HOVER_BUILDING_FOOTPRINT_PRIMARY_HIT_DISTANCE = 12
    private const val HOVER_UPPER_PLANE_TILE_SEARCH_RADIUS = 2
    private const val HOVER_BUILDING_FOOTPRINT_X_PADDING = 2
    private const val HOVER_BUILDING_FOOTPRINT_TOP_PADDING = 64
    private const val HOVER_BUILDING_FOOTPRINT_BOTTOM_PADDING = 4
    private const val HOVER_OUTSIDE_GROUND_HIT_MS = 50L

    private const val DESTINATION_TARGET_GROUP = HOVER_GROUP_START + HOVER_TARGET_LIMIT
    const val SELECTIVE_ROOF_GROUP_COUNT = DESTINATION_TARGET_GROUP + 1
    private const val DESTINATION_HOLD_MS = 20000L
    private const val DESTINATION_REACHED_DISTANCE = 1

    @JvmField
    val HANDLER: RoofVisibilityHandler = object : RoofVisibilityHandler {
        override fun getGroupLimit(): Int = SELECTIVE_ROOF_GROUP_COUNT

        override fun isActive(): Boolean = isSmartRoofsActive()

        override fun disableIfExpired() = disableSmartRoofsIfExpired()

        override fun ensureBuffers() = ensureSelectiveRoofBuffers()

        override fun applyRequests() {
            hideMapFlagRoof()
            hideHoverTargets()
            hideDestinationTarget()
        }

        override fun isPicking(): Boolean = isHoverPicking()

        override fun getPickScreenX(): Int = getHoverPickScreenX()

        override fun getPickScreenY(): Int = getHoverPickScreenY()

        override fun reportTile(sceneX: Int, sceneY: Int, plane: Int) {
            setHoverTile(sceneX, sceneY, plane)
        }

        override fun isLocPickable(key: Long): Boolean = isHoverLocPickable(key)

        override fun reportLoc(key: Long, plane: Int) {
            setHoverLoc(key, plane)
        }

        override fun beginGroup(group: Int) = beginBuildingFootprint(group)

        override fun addGroupTile(group: Int, plane: Int, sceneX: Int, sceneY: Int) {
            addBuildingFootprintTile(group, plane, sceneX, sceneY)
        }

        override fun setDestinationTarget(sceneX: Int, sceneY: Int) {
            this@RoofHider.setDestinationTarget(sceneX, sceneY)
        }

        override fun clearDestinationTarget() = this@RoofHider.clearDestinationTarget()

    }

    private var smartRoofsEnabled = false
    private var smartRoofsActiveUntil = 0L
    private var smartRoofsWasActive = false

    private var mapFlagRoofX = -1
    private var mapFlagRoofY = -1
    private var mapFlagRoofRadius = 0

    private var hoverPickActive = false
    private var hoverPickScreenX = 0
    private var hoverPickScreenY = 0
    private val hoverTargetX = IntArray(HOVER_TARGET_LIMIT) { -1 }
    private val hoverTargetY = IntArray(HOVER_TARGET_LIMIT) { -1 }
    private val hoverTargetPlane = IntArray(HOVER_TARGET_LIMIT) { -1 }
    private val hoverPrimaryHitX = IntArray(HOVER_TARGET_LIMIT) { Int.MIN_VALUE }
    private val hoverPrimaryHitY = IntArray(HOVER_TARGET_LIMIT) { Int.MIN_VALUE }
    private val hoverTargetUntil = LongArray(HOVER_TARGET_LIMIT)
    private val buildingFootprintMinX = IntArray(HOVER_TARGET_LIMIT) { Int.MAX_VALUE }
    private val buildingFootprintMaxX = IntArray(HOVER_TARGET_LIMIT) { Int.MIN_VALUE }
    private val buildingFootprintMinY = IntArray(HOVER_TARGET_LIMIT) { Int.MAX_VALUE }
    private val buildingFootprintMaxY = IntArray(HOVER_TARGET_LIMIT) { Int.MIN_VALUE }
    private val buildingFootprintTiles = Array(HOVER_TARGET_LIMIT) { Array(104) { BooleanArray(104) } }
    private var lastHoverTileX = -1
    private var lastHoverTileY = -1
    private var lastHoverTilePlane = -1
    private var lastHoverTileValid = false
    private var lastHoverTileAt = 0L
    private var lastHoverHitAt = 0L

    private var destinationTargetX = -1
    private var destinationTargetY = -1
    private var destinationTargetUntil = 0L

    fun isSmartRoofsActive(): Boolean {
        return smartRoofsEnabled && isSmartRoofsActive(System.currentTimeMillis()) && ScriptRunner.canUseSelectiveRoofHiding()
    }

    private fun isSmartRoofsActive(now: Long): Boolean {
        return now <= smartRoofsActiveUntil
    }

    private fun updateSmartRoofsPulse(now: Long) {
        smartRoofsActiveUntil = now + SMART_ROOFS_ACTIVE_GRACE_MS
        smartRoofsWasActive = true
    }

    private fun disableSmartRoofsIfExpired() {
        val now = System.currentTimeMillis()
        if (!smartRoofsWasActive || isSmartRoofsActive(now)) {
            return
        }
        clearSmartRoofsState()
        smartRoofsWasActive = false
        ScriptRunner.updateRoofRemovalMode()
    }

    private fun clearSmartRoofsState() {
        clearMapFlagRoof()
        clearHoverRequest()
        clearDestinationTarget()
        smartRoofsEnabled = false
    }

    fun setSmartRoofsEnabled(enabled: Boolean) {
        if (!enabled) {
            clearSmartRoofsState()
            return
        }
        smartRoofsEnabled = true
    }

    fun updateSmartRoofs() {
        if (!smartRoofsEnabled) {
            clearSmartRoofsState()
            return
        }
        val now = System.currentTimeMillis()
        val wasActive = isSmartRoofsActive(now)
        updateSmartRoofsPulse(now)
        if (!ScriptRunner.canUseSelectiveRoofHiding()) {
            clearSmartRoofsState()
            return
        }
        val rebuild = !wasActive ||
            ScriptRunner.getRoofRemovalMode() != 2 ||
            ScriptRunner.roofVisibility == null ||
            ScriptRunner.roofGroupMaxHeight.size < SELECTIVE_ROOF_GROUP_COUNT
        if (rebuild) {
            ScriptRunner.updateRoofRemovalMode()
        } else {
            ensureSelectiveRoofBuffers()
        }
    }

    private fun ensureSelectiveRoofBuffers() {
        if (ScriptRunner.roofVisibility == null) {
            ScriptRunner.fillRoofVisibility(((ScriptRunner.renderCycle - 4) and 0xFF).toByte())
        }
        if (ScriptRunner.roofGroupMaxHeight.size < SELECTIVE_ROOF_GROUP_COUNT) {
            ScriptRunner.allocateRoofVisibilityGroupArrays(SELECTIVE_ROOF_GROUP_COUNT)
        }
    }

    fun setMapFlagRoof(sceneX: Int, sceneY: Int, radius: Int) {
        if (!isSmartRoofsActive()) {
            clearMapFlagRoof()
            return
        }
        if (!isInSceneBounds(sceneX, sceneY)) {
            clearMapFlagRoof()
            return
        }
        mapFlagRoofX = sceneX
        mapFlagRoofY = sceneY
        mapFlagRoofRadius = radius.coerceIn(0, 8)
    }

    fun clearMapFlagRoof() {
        mapFlagRoofX = -1
        mapFlagRoofY = -1
        mapFlagRoofRadius = 0
    }

    private fun hideMapFlagRoof() {
        hideRoofFromSeedTile(mapFlagRoofX, mapFlagRoofY, mapFlagRoofRadius, MAP_FLAG_ROOF_GROUP, Player.plane)
    }

    private fun hideRoofFromSeedTile(sceneX: Int, sceneY: Int, radius: Int, group: Int, plane: Int): Boolean {
        if (sceneX == -1 || !isRoofHidePlane(plane) || ScriptRunner.roofGroupMaxHeight.size <= group) {
            return false
        }
        for (currentRadius in 0..radius) {
            for (dx in -currentRadius..currentRadius) {
                for (dy in -currentRadius..currentRadius) {
                    if (abs(dx) != currentRadius && abs(dy) != currentRadius) {
                        continue
                    }
                    if (tryHideRoofFromTile(sceneX + dx, sceneY + dy, group, plane)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun tryHideRoofFromTile(sceneX: Int, sceneY: Int, group: Int, plane: Int): Boolean {
        if (!isHideableRoofTile(sceneX, sceneY, plane)) {
            return false
        }
        val masks = ScriptRunner.roofVisibility ?: return false
        val currentRoofMask = (ScriptRunner.renderCycle and 0xFF).toByte()
        if (masks[plane][sceneX][sceneY] == currentRoofMask) {
            return true
        }
        return ScriptRunner.hideRoofAt(sceneX, sceneY, group, plane)
    }

    fun updateHoverPick(entries: Array<out MiniMenuEntry>?) {
        if (!isSmartRoofsActive()) {
            clearHoverRequest()
            return
        }
        hoverPickActive = false
        if (entries == null) {
            clearExpiredHoverTargets(System.currentTimeMillis())
            return
        }
        for (entry in entries) {
            if (entry.action == MiniMenuAction.WALK_HERE && entry.subjectIndex == 0L) {
                hoverPickActive = true
                hoverPickScreenX = entry.intArg1
                hoverPickScreenY = entry.intArg2
                return
            }
        }
        clearExpiredHoverTargets(System.currentTimeMillis())
    }

    private fun isHoverPicking(): Boolean {
        return isSmartRoofsActive() && hoverPickActive
    }

    private fun getHoverPickScreenX(): Int = hoverPickScreenX

    private fun getHoverPickScreenY(): Int = hoverPickScreenY

    private fun setHoverTile(sceneX: Int, sceneY: Int, plane: Int) {
        if (!isSmartRoofsActive() || !hoverPickActive || !isInSceneBounds(sceneX, sceneY)) {
            return
        }
        val valid = updateHoverTarget(sceneX, sceneY, Player.plane, plane)
        rememberHoverTileHit(sceneX, sceneY, plane, valid)
        if (valid) {
            markHoverHit()
            return
        }
        clearExpiredHoverTargets(System.currentTimeMillis())
    }

    private fun isHoverLocPickable(key: Long): Boolean {
        if (!isSmartRoofsActive() || !hoverPickActive || key == 0L) {
            return false
        }
        val entityType = (key.toInt() shr 29) and 0x3
        val locShape = ((key shr 14) and 0x3F).toInt()
        return entityType == 2 && isBuildingLocShape(locShape)
    }

    private fun setHoverLoc(key: Long, plane: Int) {
        if (!isHoverLocPickable(key)) {
            return
        }
        val sceneX = (key and 0x7F).toInt()
        val sceneY = ((key shr 7) and 0x7F).toInt()
        if (!isInSceneBounds(sceneX, sceneY)) {
            return
        }
        if (updateHoverTarget(sceneX, sceneY, Player.plane, plane)) {
            markHoverHit()
        }
    }

    private fun updateHoverTarget(sceneX: Int, sceneY: Int, plane: Int, hitPlane: Int): Boolean {
        var targetX = sceneX
        var targetY = sceneY
        val now = System.currentTimeMillis()
        if (!isHideableRoofTile(targetX, targetY, plane)) {
            val packed = findNearbyHideableRoofTile(sceneX, sceneY, plane, hitPlane)
            if (packed == -1) {
                return false
            }
            targetX = packed shr 7
            targetY = packed and 0x7F
        }
        if (!isHideableRoofTile(targetX, targetY, plane)) {
            return false
        }

        val duplicateSlot = findHoverTargetByBuildingFootprint(targetX, targetY, plane)
        if (duplicateSlot != -1) {
            updateHoverPrimaryHit(duplicateSlot)
            extendHoverTarget(duplicateSlot, now)
            return true
        }

        var oldestSlot = 0
        var oldestUntil = Long.MAX_VALUE
        for (i in 0 until HOVER_TARGET_LIMIT) {
            if (hoverTargetX[i] == targetX && hoverTargetY[i] == targetY && hoverTargetPlane[i] == plane) {
                updateHoverPrimaryHit(i)
                extendHoverTarget(i, now)
                return true
            }
            if (hoverTargetX[i] == -1 || now > hoverTargetUntil[i]) {
                hoverTargetX[i] = targetX
                hoverTargetY[i] = targetY
                hoverTargetPlane[i] = plane
                clearBuildingFootprint(i)
                updateHoverPrimaryHit(i)
                extendHoverTarget(i, now)
                return true
            }
            if (hoverTargetUntil[i] < oldestUntil) {
                oldestUntil = hoverTargetUntil[i]
                oldestSlot = i
            }
        }
        hoverTargetX[oldestSlot] = targetX
        hoverTargetY[oldestSlot] = targetY
        hoverTargetPlane[oldestSlot] = plane
        clearBuildingFootprint(oldestSlot)
        updateHoverPrimaryHit(oldestSlot)
        extendHoverTarget(oldestSlot, now)
        return true
    }

    private fun findNearbyHideableRoofTile(sceneX: Int, sceneY: Int, plane: Int, hitPlane: Int): Int {
        if (hitPlane <= plane) {
            return -1
        }
        for (radius in 1..HOVER_UPPER_PLANE_TILE_SEARCH_RADIUS) {
            for (dx in -radius..radius) {
                for (dy in -radius..radius) {
                    if (abs(dx) != radius && abs(dy) != radius) {
                        continue
                    }
                    val targetX = sceneX + dx
                    val targetY = sceneY + dy
                    if (isHideableRoofTile(targetX, targetY, plane)) {
                        return (targetX shl 7) or targetY
                    }
                }
            }
        }
        return -1
    }

    private fun rememberHoverTileHit(sceneX: Int, sceneY: Int, plane: Int, valid: Boolean) {
        lastHoverTileX = sceneX
        lastHoverTileY = sceneY
        lastHoverTilePlane = plane
        lastHoverTileValid = valid
        lastHoverTileAt = System.currentTimeMillis()
    }

    private fun markHoverHit() {
        lastHoverHitAt = System.currentTimeMillis()
    }

    private fun clearHoverRequest() {
        hoverPickActive = false
        clearHoverTargets()
    }

    private fun findHoverTargetByBuildingFootprint(sceneX: Int, sceneY: Int, plane: Int): Int {
        for (i in 0 until HOVER_TARGET_LIMIT) {
            if (hoverTargetX[i] == -1 || hoverTargetPlane[i] != plane) {
                continue
            }
            if (isBuildingFootprintTile(i, sceneX, sceneY)) {
                return i
            }
        }
        return -1
    }

    private fun extendHoverTarget(index: Int, now: Long) {
        hoverTargetUntil[index] = now + HOVER_HOLD_MS
    }

    private fun getHoverGroup(index: Int): Int = HOVER_GROUP_START + index

    private fun getHoverIndex(group: Int): Int {
        val index = group - HOVER_GROUP_START
        return if (index >= 0 && index < HOVER_TARGET_LIMIT) index else -1
    }

    private fun clearExpiredHoverTargets(now: Long) {
        for (i in 0 until HOVER_TARGET_LIMIT) {
            if (hoverTargetX[i] != -1 && now > hoverTargetUntil[i]) {
                if (isPickInsideBuildingFootprint(i)) {
                    extendHoverTarget(i, now)
                    continue
                }
                clearHoverTarget(i)
            }
        }
    }

    private fun clearHoverTargets() {
        for (i in 0 until HOVER_TARGET_LIMIT) {
            clearHoverTarget(i)
        }
        lastHoverTileX = -1
        lastHoverTileY = -1
        lastHoverTilePlane = -1
        lastHoverTileValid = false
        lastHoverTileAt = 0L
        lastHoverHitAt = 0L
    }

    private fun clearHoverTarget(index: Int) {
        hoverTargetX[index] = -1
        hoverTargetY[index] = -1
        hoverTargetPlane[index] = -1
        hoverPrimaryHitX[index] = Int.MIN_VALUE
        hoverPrimaryHitY[index] = Int.MIN_VALUE
        hoverTargetUntil[index] = 0L
        clearBuildingFootprint(index)
    }

    private fun hideHoverTargets() {
        val now = System.currentTimeMillis()
        val keepAlive = Cs1ScriptRunner.isMenuOpen
        for (i in 0 until HOVER_TARGET_LIMIT) {
            if (hoverTargetX[i] == -1) {
                continue
            }
            val hasBuildingArea = hasBuildingFootprint(i)
            val buildingFootprintKeepAlive = isPickInsideBuildingFootprint(i)
            val primaryHitKeepAlive = isPickNearPrimaryHit(i, if (hasBuildingArea) HOVER_BUILDING_FOOTPRINT_PRIMARY_HIT_DISTANCE else HOVER_PRIMARY_HIT_DISTANCE)
            if (hasRecentOutsideGroundHit(i, now, buildingFootprintKeepAlive || hasBuildingArea && primaryHitKeepAlive)) {
                clearHoverTarget(i)
                continue
            }
            if (keepAlive || primaryHitKeepAlive || buildingFootprintKeepAlive) {
                extendHoverTarget(i, now)
            } else if (now > hoverTargetUntil[i]) {
                clearHoverTarget(i)
                continue
            }
            val group = getHoverGroup(i)
            hideRoofFromSeedTile(hoverTargetX[i], hoverTargetY[i], 0, group, hoverTargetPlane[i])
        }
    }

    private fun updateHoverPrimaryHit(index: Int) {
        hoverPrimaryHitX[index] = hoverPickScreenX
        hoverPrimaryHitY[index] = hoverPickScreenY
    }

    private fun isPickNearPrimaryHit(index: Int, distance: Int): Boolean {
        if (!hoverPickActive || hoverPrimaryHitX[index] == Int.MIN_VALUE) {
            return false
        }
        val dx = hoverPickScreenX - hoverPrimaryHitX[index]
        val dy = hoverPickScreenY - hoverPrimaryHitY[index]
        return dx * dx + dy * dy <= distance * distance
    }

    private fun isPickInsideBuildingFootprint(index: Int): Boolean {
        if (!hoverPickActive || !hasBuildingFootprint(index)) {
            return false
        }
        for (x in 0 until 104) {
            for (y in 0 until 104) {
                if (buildingFootprintTiles[index][x][y] && isPickInsideBuildingFootprintTile(index, x, y)) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasBuildingFootprint(index: Int): Boolean {
        return buildingFootprintMinX[index] != Int.MAX_VALUE
    }

    private fun clearBuildingFootprint(index: Int) {
        buildingFootprintMinX[index] = Int.MAX_VALUE
        buildingFootprintMaxX[index] = Int.MIN_VALUE
        buildingFootprintMinY[index] = Int.MAX_VALUE
        buildingFootprintMaxY[index] = Int.MIN_VALUE
        for (x in 0 until 104) {
            buildingFootprintTiles[index][x].fill(false)
        }
    }

    private fun beginBuildingFootprint(group: Int) {
        val index = getHoverIndex(group)
        if (index != -1) {
            clearBuildingFootprint(index)
        }
    }

    private fun addBuildingFootprintTile(group: Int, plane: Int, sceneX: Int, sceneY: Int) {
        val index = getHoverIndex(group)
        if (index == -1 || plane + 1 >= SceneGraph.tileHeights.size) {
            return
        }
        buildingFootprintTiles[index][sceneX][sceneY] = true
        addBuildingFootprintTileBounds(index, plane, sceneX, sceneY)
    }

    private fun addBuildingFootprintTileBounds(index: Int, plane: Int, sceneX: Int, sceneY: Int) {
        addBuildingFootprintBoundPoint(index, projectBuildingFootprintPoint(sceneX shl 7, sceneY shl 7, SceneGraph.tileHeights[plane + 1][sceneX][sceneY]))
        addBuildingFootprintBoundPoint(index, projectBuildingFootprintPoint((sceneX + 1) shl 7, sceneY shl 7, SceneGraph.tileHeights[plane + 1][sceneX + 1][sceneY]))
        addBuildingFootprintBoundPoint(index, projectBuildingFootprintPoint((sceneX + 1) shl 7, (sceneY + 1) shl 7, SceneGraph.tileHeights[plane + 1][sceneX + 1][sceneY + 1]))
        addBuildingFootprintBoundPoint(index, projectBuildingFootprintPoint(sceneX shl 7, (sceneY + 1) shl 7, SceneGraph.tileHeights[plane + 1][sceneX][sceneY + 1]))
    }

    private fun isPickInsideBuildingFootprintTile(index: Int, sceneX: Int, sceneY: Int): Boolean {
        val plane = hoverTargetPlane[index]
        if (!isRoofHidePlane(plane) || plane + 1 >= SceneGraph.tileHeights.size) {
            return false
        }
        val point0 = projectBuildingFootprintPoint(sceneX shl 7, sceneY shl 7, SceneGraph.tileHeights[plane + 1][sceneX][sceneY])
        val point1 = projectBuildingFootprintPoint((sceneX + 1) shl 7, sceneY shl 7, SceneGraph.tileHeights[plane + 1][sceneX + 1][sceneY])
        val point2 = projectBuildingFootprintPoint((sceneX + 1) shl 7, (sceneY + 1) shl 7, SceneGraph.tileHeights[plane + 1][sceneX + 1][sceneY + 1])
        val point3 = projectBuildingFootprintPoint(sceneX shl 7, (sceneY + 1) shl 7, SceneGraph.tileHeights[plane + 1][sceneX][sceneY + 1])
        if (point0 == Long.MIN_VALUE || point1 == Long.MIN_VALUE || point2 == Long.MIN_VALUE || point3 == Long.MIN_VALUE) {
            return false
        }
        val minX = min(min((point0 shr 32).toInt(), (point1 shr 32).toInt()), min((point2 shr 32).toInt(), (point3 shr 32).toInt()))
        val maxX = max(max((point0 shr 32).toInt(), (point1 shr 32).toInt()), max((point2 shr 32).toInt(), (point3 shr 32).toInt()))
        val minY = min(min(point0.toInt(), point1.toInt()), min(point2.toInt(), point3.toInt()))
        val maxY = max(max(point0.toInt(), point1.toInt()), max(point2.toInt(), point3.toInt()))
        return hoverPickScreenX >= minX - HOVER_BUILDING_FOOTPRINT_X_PADDING &&
            hoverPickScreenX <= maxX + HOVER_BUILDING_FOOTPRINT_X_PADDING &&
            hoverPickScreenY >= minY - HOVER_BUILDING_FOOTPRINT_TOP_PADDING &&
            hoverPickScreenY <= maxY + HOVER_BUILDING_FOOTPRINT_BOTTOM_PADDING
    }

    private fun hasRecentOutsideGroundHit(index: Int, now: Long, pickInsideBuildingFootprint: Boolean): Boolean {
        if (pickInsideBuildingFootprint || lastHoverTileAt == 0L || now - lastHoverTileAt > HOVER_OUTSIDE_GROUND_HIT_MS) {
            return false
        }
        if (lastHoverTileValid || lastHoverTileAt <= lastHoverHitAt || now - lastHoverHitAt <= HOVER_OUTSIDE_GROUND_HIT_MS) {
            return false
        }
        if (lastHoverTilePlane != hoverTargetPlane[index]) {
            return false
        }
        return !isBuildingFootprintTile(index, lastHoverTileX, lastHoverTileY)
    }

    private fun isBuildingFootprintTile(index: Int, sceneX: Int, sceneY: Int): Boolean {
        return isInSceneBounds(sceneX, sceneY) && buildingFootprintTiles[index][sceneX][sceneY]
    }

    private fun addBuildingFootprintBoundPoint(index: Int, packedPoint: Long) {
        if (packedPoint == Long.MIN_VALUE) {
            return
        }
        val screenX = (packedPoint shr 32).toInt()
        val screenY = packedPoint.toInt()
        if (screenX < buildingFootprintMinX[index]) {
            buildingFootprintMinX[index] = screenX
        }
        if (screenX > buildingFootprintMaxX[index]) {
            buildingFootprintMaxX[index] = screenX
        }
        if (screenY < buildingFootprintMinY[index]) {
            buildingFootprintMinY[index] = screenY
        }
        if (screenY > buildingFootprintMaxY[index]) {
            buildingFootprintMaxY[index] = screenY
        }
    }

    private fun projectBuildingFootprintPoint(xFine: Int, yFine: Int, zFine: Int): Long {
        val localX = xFine - Camera.renderX
        val localY = yFine - Camera.renderY
        val localZ = zFine - Camera.renderZ
        val sinPitch = MathUtils.sin[Camera.cameraPitch]
        val cosPitch = MathUtils.cos[Camera.cameraPitch]
        val sinYaw = MathUtils.sin[Camera.cameraYaw]
        val cosYaw = MathUtils.cos[Camera.cameraYaw]
        val rotatedX = localX * cosYaw + localY * sinYaw shr 16
        val rotatedY = localY * cosYaw - localX * sinYaw shr 16
        val screenZDepth = rotatedY * cosPitch + localZ * sinPitch shr 16
        if (screenZDepth < 50) {
            return Long.MIN_VALUE
        }
        val rotatedZ = localZ * cosPitch - rotatedY * sinPitch shr 16
        val screenX = (rotatedX shl 9) / screenZDepth
        val screenZ = (rotatedZ shl 9) / screenZDepth
        return (screenX.toLong() shl 32) or (screenZ.toLong() and 0xFFFFFFFFL)
    }

    fun setDestinationTarget(sceneX: Int, sceneY: Int) {
        if (!isSmartRoofsActive()) {
            clearDestinationTarget()
            return
        }
        if (!isInSceneBounds(sceneX, sceneY)) {
            clearDestinationTarget()
            return
        }
        destinationTargetX = sceneX
        destinationTargetY = sceneY
        destinationTargetUntil = System.currentTimeMillis() + DESTINATION_HOLD_MS
    }

    fun clearDestinationTarget() {
        destinationTargetX = -1
        destinationTargetY = -1
        destinationTargetUntil = 0L
    }

    private fun hasDestinationTarget(): Boolean {
        if (destinationTargetX == -1) {
            return false
        }
        if (System.currentTimeMillis() > destinationTargetUntil) {
            clearDestinationTarget()
            return false
        }
        val self = PlayerList.self
        if (self != null &&
            abs(self.movementQueueX[0] - destinationTargetX) <= DESTINATION_REACHED_DISTANCE &&
            abs(self.movementQueueY[0] - destinationTargetY) <= DESTINATION_REACHED_DISTANCE
        ) {
            clearDestinationTarget()
            return false
        }
        return true
    }

    private fun hideDestinationTarget() {
        if (hasDestinationTarget()) {
            hideRoofFromSeedTile(destinationTargetX, destinationTargetY, 0, DESTINATION_TARGET_GROUP, Player.plane)
        }
    }

    private fun isInSceneBounds(sceneX: Int, sceneY: Int): Boolean {
        return sceneX in 0 until 104 && sceneY in 0 until 104
    }

    private fun isHideableRoofTile(sceneX: Int, sceneY: Int, plane: Int): Boolean {
        if (ScriptRunner.roofVisibility == null) {
            return false
        }
        return isRoofHidePlane(plane) &&
            isInSceneBounds(sceneX, sceneY) &&
            (SceneGraph.renderFlags[plane][sceneX][sceneY].toInt() and TILE_FLAG_UNDER_ROOF) != 0
    }

    private fun isRoofHidePlane(plane: Int): Boolean {
        return plane in 0 until 3
    }

    private fun isBuildingLocShape(shape: Int): Boolean {
        return shape == LocType.WALL_STRAIGHT ||
            shape == LocType.WALL_DIAGONALCORNER ||
            shape == LocType.WALL_L ||
            shape == LocType.WALL_SQUARECORNER ||
            shape == LocType.WALL_DIAGONAL ||
            shape == LocType.WALLDECOR_STRAIGHT_XOFFSET ||
            shape == LocType.WALLDECOR_STRAIGHT_YOFFSET ||
            shape == LocType.WALLDECOR_DIAGONAL_XOFFSET ||
            shape == LocType.WALLDECOR_DIAGONAL_YOFFSET ||
            shape == LocType.WALLDECOR_DIAGONAL_BOTH
    }

}
