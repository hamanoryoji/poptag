package bnbemu.handler.room

import bnbemu.netty.*
import bnbemu.server.Room
import io.netty.buffer.ByteBuf

data class MapScriptBombRequest(val power: Int,val sourceX: Int,val sourceY: Int,val state: Int,val cells: List<Pair<Int,Int>>)

// CMapActionBomb -> 550134 / C133: count E1, sourceY/sourceX E2, power E1,
// state E2, then count * (row E1, column E1). Confirmed by the real
// PangLand15 request 5B A5 DB A4 75 5B A5 6A 52 59 (10 bytes).
// Positions are selected by the
// client's original map script; the server must not invent a new pattern.
fun ByteBuf.decodeMapScriptBombRequest(): MapScriptBombRequest? {
    if(readableBytes()<10) return null
    val count=Decode1
    if(count !in 1..225 || readableBytes()!=7+count*2) return null
    val y=Decode2;val x=Decode2;val power=Decode1;val state=Decode2
    if(x !in 0..599 || y !in 0..599 || power !in 1..15 || state !in 0..4) return null
    val cells=List(count) { Decode1 to Decode1 }
    if(cells.any { (row,column)->row !in 0..14 || column !in 0..14 }) return null
    return MapScriptBombRequest(power,x,y,state,cells)
}

val MapScriptBombReq=PKTHandler { c ->
    val request=decodeMapScriptBombRequest() ?: return@PKTHandler
    Room.onMapScriptBombs(c,request)
}
