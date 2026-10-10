package com.ivisit.helper;

import com.ivisit.helper.sender.RfidSender;
import com.ivisit.helper.service.RfidService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.smartcardio.CardException;

@Component
public class RfidLoopRunner implements CommandLineRunner {

    private final RfidService rfidService;
    private final RfidSender rfidSender;

    // tracking consecutive scanner-level failures
    private int consecutiveScannerErrors = 0;

    // Flag to track if we've already notified about missing reader
    private boolean noReaderNotified = false;

    private enum ReaderState { OK, MISSING }

    private ReaderState readerState = ReaderState.OK;

    // rate-limit logs so they don't spam the console
    private long lastWaitingLogAt = 0L;
    private long lastMissingLogAt = 0L;

    private static final long WAITING_LOG_EVERY_MS = 15_000L;
    private static final long MISSING_LOG_EVERY_MS = 60_000L; // optional safety

    public RfidLoopRunner(RfidService rfidService, RfidSender rfidSender) {
        this.rfidService = rfidService;
        this.rfidSender = rfidSender;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("iVisit-helper RFID loop is running...");

        while (true) {
            try {
                long now = System.currentTimeMillis();

                if (readerState == ReaderState.OK && (now - lastWaitingLogAt) >= WAITING_LOG_EVERY_MS) {
                    System.out.println("Waiting for card...");
                    lastWaitingLogAt = now;
                }
                String uid = rfidService.readCardUID(10_000); // 10 seconds

                if (readerState == ReaderState.MISSING) {
                    readerState = ReaderState.OK;
                    noReaderNotified = false;
                    consecutiveScannerErrors = 0;
                    System.out.println("[RFID] Reader reconnected.");
                }

                if (uid != null) {
                    System.out.println("Card detected. UID = " + uid);
                    rfidSender.sendScan(uid);

                    // SUCCESS -> reset error counter and notification flag
                    consecutiveScannerErrors = 0;
                    noReaderNotified = false;
                } else {
                    // "No card" is not a scanner failure; also reset the counter
                    consecutiveScannerErrors = 0;
                    noReaderNotified = false;
                }
            } catch (Exception e) {
                String msg = e.getMessage();

                if (isScannerFatalError(e)) {
                    long now = System.currentTimeMillis();
                    consecutiveScannerErrors++;

                    // As soon as we suspect the reader is missing, switch state and stop "waiting" logs
                    if (readerState != ReaderState.MISSING) {
                        readerState = ReaderState.MISSING;
                        noReaderNotified = true;      // treat it as notified immediately
                        consecutiveScannerErrors = 0; // no longer need the 5-strike logic
                        // rfidService.resetScanner(); // already gets called in RfidService

                        System.out.println("[RFID] No RFID reader detected. OCR remains available. (Will auto-reconnect when plugged in)");
                        lastMissingLogAt = now;
                    } else {
                        // Optional: if you still want an occasional reminder (not required)
                        if ((now - lastMissingLogAt) >= MISSING_LOG_EVERY_MS) {
                            System.out.println("[RFID] Still no RFID reader detected...");
                            lastMissingLogAt = now;
                        }
                    }

                    Thread.sleep(10_000);
                    continue;
                }
            }

            // small delay to avoid tight loop if something goes wrong
            Thread.sleep(1000);
        }
    }

    /**
     * Decide if this looks like a scanner / driver level problem
     * (PC/SC, terminals missing, etc.), not just normal “no card” behavior.
     */
    private boolean isScannerFatalError(Exception e) {
        // Explicit smartcard exception
        if (e instanceof CardException) {
            return true;
        }

        String msg = safeMessage(e.getMessage()).toLowerCase();

        // Common cases:
        // - "No card terminals found" (your IllegalStateException)
        // - "list() failed" from PC/SC, caught and now "no rfid scanner detected"
        if (msg.contains("no card terminals found")) return true;
        if (msg.contains("list() failed")) return true;
        if (msg.contains("no rfid scanner detected")) return true;
        if (msg.contains("scard") && msg.contains("error")) return true;

        return false;
    }

    private String safeMessage(String msg) {
        return msg == null ? "(no message)" : msg;
    }
}
