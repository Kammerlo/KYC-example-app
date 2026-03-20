package org.cardanofoundation.keriui.util;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.signify.app.Notifying;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.cesr.util.Utils;

import java.util.List;
import java.util.Objects;

/**
 * Utility for polling the KERI notification queue during IPEX credential exchange.
 * Blocking; intended to be called from a request thread.
 */
@Slf4j
public class IpexNotificationHelper {

    private static final int MAX_RETRIES = 20;
    private static final long POLL_INTERVAL_MS = 2000;

    /**
     * Poll until a notification with the given route arrives.
     * Blocks the calling thread until a match is found or the retry limit is exceeded.
     *
     * @param client the SignifyClient to poll
     * @param route  the IPEX route to wait for (e.g. "/exn/ipex/offer" or "/exn/ipex/grant")
     * @return the first unread matching notification
     * @throws RuntimeException if no notification arrives within the retry window
     */
    public static Notification waitForNotification(SignifyClient client, String route) throws Exception {
        for (int i = 0; i < MAX_RETRIES; i++) {
            Notifying.Notifications.NotificationListResponse response = client.notifications().list();
            List<Notification> notes = Utils.fromJson(response.notes(), new TypeReference<>() {});
            log.debug("Polled notifications ({} total), waiting for route={}", notes.size(), route);

            List<Notification> matching = notes.stream()
                    .filter(n -> Objects.equals(route, n.a.r) && !Boolean.TRUE.equals(n.r))
                    .toList();

            if (!matching.isEmpty()) {
                log.debug("Received notification for route={}", route);
                return matching.getFirst();
            }

            log.info("Waiting for notification: {} (attempt {}/{})", route, i + 1, MAX_RETRIES);
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for notification: " + route);
    }

    /**
     * Mark a notification as read and then delete it.
     */
    public static void markAndDelete(SignifyClient client, Notification note) throws Exception {
        client.notifications().mark(note.i);
        client.notifications().delete(note.i);
    }

    // ── Notification model ────────────────────────────────────────────────────

    /** Lightweight representation of a KERI notification (deserialized from agent JSON). */
    public static class Notification {
        public String i;          // notification SAID
        public Boolean r;         // true = already read
        public NotificationBody a;

        public static class NotificationBody {
            public String r;  // route, e.g. "/exn/ipex/offer"
            public String d;  // SAID of the associated exchange message
        }
    }
}
