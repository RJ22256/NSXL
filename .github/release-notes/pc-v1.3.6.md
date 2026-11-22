## NSXL for Windows 1.3.6

**Controllers**
- PS5 and PS4 controllers connected over **Bluetooth** now open the quick menu (PS button, or Create + Options). Before, NSXL only recognised them over USB.
- Fixed NSXL randomly ignoring the controller. Windows sometimes leaves no window in front (after the quick menu closes, a game quits or a launch cover hides). NSXL now notices and takes focus back. If NSXL is behind the desktop, any button press brings it forward.
- The quick menu no longer closes the moment it opens when a game grabs focus back.
- Closing the quick menu hands focus back to the game, or to NSXL if no game is running.

**Reliability**
- The Windows helper can no longer be closed by "Close game". Errors inside it are written to the log instead of stopping it.
- The log (Settings › Controls › Quick menu check › Open log) now lists the controllers NSXL sees. It also says whether DS4Windows or HidHide is keeping a PlayStation controller to itself.
