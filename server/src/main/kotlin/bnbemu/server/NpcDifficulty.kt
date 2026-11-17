package bnbemu.server

// CSessionContext::GetSlotAILevelBadge (CA.exe 686B57), also used by the room.
// Difficulty is an AI preset index; these display levels must never replace it.
fun npcDifficultyBadgeLevel(difficulty: Int): Int = when(difficulty) {
    0 -> 3
    1 -> 10
    2 -> 52
    3 -> 109
    4 -> 163
    else -> 0
}
