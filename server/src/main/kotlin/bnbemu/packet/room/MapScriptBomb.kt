package bnbemu.packet.room

import bnbemu.netty.PKT

// S173 is a thrown ITEM, not a cannon bubble. Native 5329D1/533C0E
// registers CGOBombSprinkler's bomb bag at owner8. S139 applies source
// pixels through 578AC1 -> 4597B2 (native sprinkler-flight mode), and
// its final E2 selects Bomb/Bomb model state3=Sprinkler00/Pang_Bomb00.
fun MapScriptBomb(power: Int,id: Int,sourceX: Int,sourceY: Int,row: Int,column: Int,state: Int=3): PKT {
    require(id in 600..999)
    require(state in 0..4)
    return SetBomberEvent(slot=8,gridX=row,gridY=column,bombToken=-9,bombId=id,
        flag1=power,sourceX=sourceX,sourceY=sourceY,initialState=state)
}
