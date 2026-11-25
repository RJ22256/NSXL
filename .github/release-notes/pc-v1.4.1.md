## NSXL for Windows 1.4.1

- Fixed the quick menu only opening once. After the first time, NSXL could wait forever for the menu's background picture to load in its hidden window. Then the menu wouldn't open again until NSXL restarted. Every step of opening the menu now has a time limit, and the menu window keeps running while hidden.
- Closing the menu can no longer get stuck partway either.
