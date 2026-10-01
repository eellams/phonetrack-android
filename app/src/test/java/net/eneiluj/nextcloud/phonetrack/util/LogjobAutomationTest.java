package net.eneiluj.nextcloud.phonetrack.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LogjobAutomationTest {

    private static LogjobAutomation.TimeWindow window(int start, int end) {
        return new LogjobAutomation.TimeWindow(start, end);
    }

    @Test
    public void emptyAutomationHasNoCondition() {
        LogjobAutomation automation = new LogjobAutomation(null, null, null);
        assertFalse(automation.hasAnyCondition());
    }

    @Test
    public void timeWindowActiveDuringDay() {
        LogjobAutomation automation = new LogjobAutomation(window(9 * 60, 17 * 60), null, null);
        assertTrue(automation.isTimeWindowActive(9 * 60));
        assertTrue(automation.isTimeWindowActive(12 * 60));
        assertFalse(automation.isTimeWindowActive(17 * 60));
        assertFalse(automation.isTimeWindowActive(8 * 60));
    }

    @Test
    public void timeWindowWrapsPastMidnight() {
        LogjobAutomation automation = new LogjobAutomation(window(21 * 60, 6 * 60), null, null);
        assertTrue(automation.isTimeWindowActive(23 * 60));
        assertTrue(automation.isTimeWindowActive(2 * 60));
        assertFalse(automation.isTimeWindowActive(12 * 60));
        assertFalse(automation.isTimeWindowActive(6 * 60));
    }

    @Test
    public void nullTimeWindowNeverActive() {
        LogjobAutomation automation = new LogjobAutomation(null, null, null);
        assertFalse(automation.isTimeWindowActive(0));
    }

    @Test
    public void wifiMatchesCaseInsensitivelyAndTrimsQuotes() {
        LogjobAutomation automation = new LogjobAutomation(null,
                Arrays.asList("HomeWiFi", "Office"), null);
        assertTrue(automation.isWifiActive("\"HomeWiFi\""));
        assertTrue(automation.isWifiActive("homewifi"));
        assertTrue(automation.isWifiActive(" Office "));
        assertFalse(automation.isWifiActive("Cafe"));
        assertFalse(automation.isWifiActive(null));
        assertFalse(automation.isWifiActive("<unknown ssid>"));
    }

    @Test
    public void emptyWifiListNeverMatches() {
        LogjobAutomation automation = new LogjobAutomation(null,
                Collections.emptyList(), null);
        assertFalse(automation.isWifiActive("HomeWiFi"));
    }

    @Test
    public void fenceContainsPoint() {
        LogjobAutomation automation = new LogjobAutomation(null, null,
                new LogjobAutomation.Fence(48.8584, 2.2945, 200));
        assertTrue(automation.isFenceActive(new double[]{48.8585, 2.2946}));
        assertFalse(automation.isFenceActive(new double[]{48.9, 2.35}));
        assertFalse(automation.isFenceActive(null));
    }

    @Test
    public void nullFenceNeverActive() {
        LogjobAutomation automation = new LogjobAutomation(null, null, null);
        assertFalse(automation.isFenceActive(new double[]{48.8584, 2.2945}));
    }

    @Test
    public void jsonRoundTrip() {
        LogjobAutomation automation = new LogjobAutomation(
                window(22 * 60, 7 * 60),
                Arrays.asList("HomeWiFi", "Office"),
                new LogjobAutomation.Fence(48.8584, 2.2945, 150));
        LogjobAutomation parsed = LogjobAutomation.fromJson(automation.toJson());
        assertTrue(parsed.isTimeWindowActive(23 * 60));
        assertTrue(parsed.isWifiActive("homewifi"));
        assertTrue(parsed.isFenceActive(new double[]{48.8584, 2.2945}));
    }

    @Test
    public void emptyOrNullAutomationParsesToNull() {
        assertNull(LogjobAutomation.fromJson(null));
        assertNull(LogjobAutomation.fromJson(""));
        assertNull(LogjobAutomation.fromJson("   "));
        // no condition set: treat as absent
        assertNull(LogjobAutomation.fromJson(new LogjobAutomation(null, null, null).toJson()));
        // garbage must not crash
        assertNull(LogjobAutomation.fromJson("not json at all"));
    }

    @Test
    public void lowPowerRoundTripsThroughJson() {
        LogjobAutomation automation = new LogjobAutomation(null, null, null,
                new LogjobAutomation.LowPower(300, 50));
        LogjobAutomation parsed = LogjobAutomation.fromJson(automation.toJson());
        org.junit.Assert.assertNotNull(parsed.lowPower);
        org.junit.Assert.assertEquals(300, parsed.lowPower.minTime);
        org.junit.Assert.assertEquals(50, parsed.lowPower.minDistance);
    }

    @Test
    public void lowPowerWithDefaultsOnlyParsesToNull() {
        LogjobAutomation automation = new LogjobAutomation(null, null, null,
                new LogjobAutomation.LowPower(-1, -1));
        org.junit.Assert.assertNull(LogjobAutomation.fromJson(automation.toJson()));
    }

    @Test
    public void lowPowerKeepsConditionsCompany() {
        // a job with only a time window plus low power overrides survives the
        // "nothing set" check on load
        LogjobAutomation automation = new LogjobAutomation(window(21 * 60, 6 * 60), null, null,
                new LogjobAutomation.LowPower(600, -1));
        LogjobAutomation parsed = LogjobAutomation.fromJson(automation.toJson());
        org.junit.Assert.assertNotNull(parsed);
        org.junit.Assert.assertEquals(600, parsed.lowPower.minTime);
        org.junit.Assert.assertEquals(-1, parsed.lowPower.minDistance);
    }

    @Test
    public void hhMmParsingAndFormatting() {
        assertEquals(1267, LogjobAutomation.parseHhMm("21:07"));
        assertEquals(0, LogjobAutomation.parseHhMm("00:00"));
        assertEquals(-1, LogjobAutomation.parseHhMm("24:00"));
        assertEquals(-1, LogjobAutomation.parseHhMm("12:60"));
        assertEquals(-1, LogjobAutomation.parseHhMm("noon"));
        assertEquals(-1, LogjobAutomation.parseHhMm(null));
        assertEquals("21:07", LogjobAutomation.formatHhMm(1267));
        assertEquals("00:00", LogjobAutomation.formatHhMm(0));
    }

    @Test
    public void enginePausesOnAnyActiveCondition() {
        class FixedState implements AutomationEngine.DeviceState {
            int minutes;
            String ssid;
            double[] latLon;

            FixedState(int minutes, String ssid, double[] latLon) {
                this.minutes = minutes;
                this.ssid = ssid;
                this.latLon = latLon;
            }

            public int nowMinutes() { return minutes; }

            public String currentWifiSsid() { return ssid; }

            public double[] lastKnownLatLon() { return latLon; }
        }
        LogjobAutomation automation = new LogjobAutomation(
                window(9 * 60, 17 * 60),
                Collections.singletonList("HomeWiFi"),
                new LogjobAutomation.Fence(48.8584, 2.2945, 200));

        // nothing active
        assertFalse(new AutomationEngine(new FixedState(20 * 60, "Cafe",
                new double[]{48.9, 2.35})).isPaused(automation));
        // time window active
        assertTrue(new AutomationEngine(new FixedState(12 * 60, "Cafe",
                new double[]{48.9, 2.35})).isPaused(automation));
        // wifi active
        assertTrue(new AutomationEngine(new FixedState(20 * 60, "HomeWiFi",
                new double[]{48.9, 2.35})).isPaused(automation));
        // fence active
        assertTrue(new AutomationEngine(new FixedState(20 * 60, "Cafe",
                new double[]{48.8585, 2.2946})).isPaused(automation));
        // no conditions at all: never paused
        assertFalse(new AutomationEngine(new FixedState(12 * 60, "HomeWiFi",
                new double[]{48.8585, 2.2946})).isPaused(null));
        assertFalse(new AutomationEngine(new FixedState(12 * 60, "HomeWiFi",
                new double[]{48.8585, 2.2946})).isPaused(new LogjobAutomation(null, null, null)));
    }
}
