package org.zephbyte.resourcepackprofiles.client.util

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

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
