package org.cardanofoundation.keriui.util;

import com.fasterxml.jackson.core.type.TypeReference;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.cesr.util.Utils;

import java.util.List;
import java.util.Objects;

public class IpexNotificationHelper {

    private static final int MAX_RETRIES = 5;
    private static final long POLL_INTERVAL_MS = 2000;

    /**
     * Poll until a notification with the given route arrives.
     * Blocks the calling thread until found or timeout.
     *
     * @param client the SignifyClient to poll
     * @param route  e.g. "/exn/ipex/offer" or "/exn/ipex/grant"
     * @return the first unread matching notification
     */
    public static Notification waitForNotification(SignifyClient client, String route) throws Exception {
        for (int i = 0; i < MAX_RETRIES; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<Notification> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});

            List<Notification> matching = notes.stream()
                    .filter(n -> Objects.equals(route, n.a.r) && !Boolean.TRUE.equals(n.r))
                    .toList();

            if (!matching.isEmpty()) {
                return matching.getFirst();
            }

            System.out.println("Waiting for notification: " + route + " (attempt " + (i + 1) + ")");
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for notification: " + route);
    }

    /**
     * Mark and delete a notification after processing it.
     */
    public static void markAndDelete(SignifyClient client, Notification note) throws Exception {
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
    }

    // Simple Notification model (adapt to your actual class if it already exists)
    public static class Notification {
        public String i;   // notification SAID
        public Boolean r;  // read flag
        public NotificationBody a;

        public static class NotificationBody {
            public String r;  // route e.g. "/exn/ipex/offer"
            public String d;  // SAID of the exchange message
        }
    }
}