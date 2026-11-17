package bnbemu.server

/** Opt-in only in the separate client/resource candidate; stable games stay unchanged. */
fun kunPrototypeEnabled(): Boolean = java.lang.Boolean.getBoolean("bnbemu.kunPrototype") &&
    java.io.File("../client/kun-prototype.json").isFile

fun localNpcCharacter(slot: Int, original: Int, prototype: Boolean = kunPrototypeEnabled()): Int =
    if (prototype && slot == 4) 25 else original
