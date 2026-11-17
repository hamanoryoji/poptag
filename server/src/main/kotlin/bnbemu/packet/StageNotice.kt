package bnbemu.packet

import bnbemu.netty.PKT

// CStage S23 -> virtual E4 -> 4FE33A: kind0 + cipher string.
// 4FA301 displays it for 10000ms in the current stage. S30 in the old
// protocol table is not this native message path.
fun StageNotice(message: String)=PKT {
    Encode1(0)
    EncodeCipherStr(message)
}
