## NSXL for Windows 1.3.8

**Quick menu**
- Fixed the quick menu being invisible. You could hear it and move around it, but Windows wasn't drawing NSXL's see-through window. It's now a normal window that always draws, over games too. Behind it is a softened picture of your game, taken as the menu opens.
- Pressing the menu buttons no longer pulls NSXL Home in front of the quick menu.

**Xbox mode**
- iCUE now starts through Windows the same way as from the Start menu. That covers apps that need admin rights. NSXL then checks it's really running, tries again without the tray-only start if it isn't, and writes the result to the log.

**New: switch to Xbox mode on command**
- Power › **Switch to Xbox mode** (and in the quick menu's Power card) jumps into Windows' Xbox mode straight away, without signing out or restarting. Press it again to go back to the desktop. It uses Windows' own Win + F11 shortcut, so Xbox mode needs to be turned on in Windows settings.

**Playing**
- NSXL now remembers the game you're playing when you switch back to it (keyboard, Alt+Tab or the quick menu). It only counts the game as finished once it has actually closed. So **Close game**, the Power menu and the second screen's Now playing all keep working, and play time counts the whole session.
