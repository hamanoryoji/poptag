package bnbemu.packet
import bnbemu.netty.PKT
/** EN CShopStageNew op202 -> 0x5D171B -> 0x66B4ED: result byte + raw OLE date. */
fun ShopPurchaseResult(success: Boolean) = PKT {
    Encode1(if (success) 0 else 1)
    if (success) EncodeBuffer(ByteArray(8)) // zero = offline permanent item
}
