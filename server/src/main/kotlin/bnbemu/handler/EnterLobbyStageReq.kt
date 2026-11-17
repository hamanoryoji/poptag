package bnbemu.handler

import bnbemu.netty.PKTHandler

import bnbemu.packet.EnterLobbyStage

// CChannelPool::OnEnterLobbyStage
val EnterLobbyStageReq = PKTHandler { c ->
    bnbemu.server.Room.cancelPendingReturn()
    c send EnterLobbyStage()
}
