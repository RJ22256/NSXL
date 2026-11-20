package com.nsxl.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can give NSXL notification access, which Android requires before it will
 * say whether another app (Spotify, say) is playing music. It does nothing with notifications.
 */
public class MediaListener extends NotificationListenerService {
}
