package net.eneiluj.nextcloud.phonetrack.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.eneiluj.nextcloud.phonetrack.model.DBLogjob;
import net.eneiluj.nextcloud.phonetrack.util.LogjobAutomation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Automation conditions survive a save/load round trip through the database,
 * and old rows (NULL AUTOMATION) load as "no conditions".
 */
@RunWith(AndroidJUnit4.class)
public class LogjobAutomationStorageTest {
    private Context context;
    private PhoneTrackSQLiteOpenHelper db;

    @Before
    public void setUp() {
        PhoneTrackSQLiteOpenHelper.resetInstanceForTesting();
        context = ApplicationProvider.getApplicationContext();
        db = PhoneTrackSQLiteOpenHelper.getInstance(context);
    }

    @After
    public void tearDown() {
        PhoneTrackSQLiteOpenHelper.resetInstanceForTesting();
    }

    private DBLogjob newLogjob() {
        return new DBLogjob(0, "test", "https://example.com", "", "",
                60, 0, 0, false, false, false, 60, false, false, 0, null, null, false);
    }

    @Test
    public void automationRoundTrip() {
        DBLogjob logjob = newLogjob();
        logjob.setAutomation(new LogjobAutomation(
                new LogjobAutomation.TimeWindow(21 * 60, 6 * 60),
                Arrays.asList("HomeWiFi", "Office"),
                new LogjobAutomation.Fence(48.8584, 2.2945, 150)));
        long id = db.addLogjob(logjob);

        DBLogjob loaded = db.getLogjob(id);
        assertNotNull(loaded.getAutomation());
        assertTrue(loaded.getAutomation().isTimeWindowActive(23 * 60));
        assertTrue(loaded.getAutomation().isWifiActive("office"));
        assertTrue(loaded.getAutomation().isFenceActive(new double[]{48.8584, 2.2945}));
    }

    @Test
    public void logjobWithoutAutomationLoadsNull() {
        long id = db.addLogjob(newLogjob());
        assertNull(db.getLogjob(id).getAutomation());
    }

    @Test
    public void updateAutomationOnlyTouchesAutomation() {
        long id = db.addLogjob(newLogjob());
        db.updateLogjobAutomation(id, new LogjobAutomation(null,
                Arrays.asList("HomeWiFi"), null));
        DBLogjob loaded = db.getLogjob(id);
        assertTrue(loaded.getAutomation().isWifiActive("HomeWiFi"));
        assertEquals("test", loaded.getTitle());

        db.updateLogjobAutomation(id, null);
        assertNull(db.getLogjob(id).getAutomation());
    }
}
