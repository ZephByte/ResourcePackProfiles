package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.net.URI
//? if >=26.3 {
import com.mojang.blaze3d.Blaze3D
//?} else {
/*import net.minecraft.util.Util
*///?}

/** Screen-handling calls that were renamed/moved between Minecraft versions. */

//? if >=26.2 {
fun Minecraft.showScreen(screen: Screen) = setScreenAndShow(screen)

fun Minecraft.setGuiScreen(screen: Screen?) = gui.setScreen(screen)

fun Minecraft.activeScreen(): Screen? = gui.screen()
//?} else {
/*fun Minecraft.showScreen(screen: Screen) = setScreen(screen)

fun Minecraft.setGuiScreen(screen: Screen?) = setScreen(screen)

fun Minecraft.activeScreen(): Screen? = this.screen
*///?}

/** Opens [uri] in the system browser. */
//? if >=26.3 {
fun openInBrowser(uri: URI) = Blaze3D.openUri(uri)
//?} else {
/*fun openInBrowser(uri: URI) = Util.getPlatform().openUri(uri)
*///?}
