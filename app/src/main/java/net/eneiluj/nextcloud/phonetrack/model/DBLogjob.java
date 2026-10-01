package net.eneiluj.nextcloud.phonetrack.model;

import androidx.annotation.Nullable;

import net.eneiluj.nextcloud.phonetrack.util.LogjobAutomation;

import java.io.Serializable;

/**
 * DBLogjob represents a single logjob from the local SQLite database with all attributes.
 */
public class DBLogjob implements Item, Serializable {

    private long id;
    private String title;
    private String url;
    private String token;
    private String deviceName;
    private boolean post;
    private boolean json;
    private int minTime;
    private int minDistance;
    private int minAccuracy;
    private boolean keepGpsOnBetweenFixes;
    private boolean enabled;
    private int nbSync;
    private boolean useSignificantMotion = true;
    private boolean useSignificantMotionMixed;
    private int locationRequestTimeout;
    private String login;
    private String password;
    private LogjobAutomation automation;

    public DBLogjob(long id, String title, String url, String token, String deviceName,
                    int minTime, int minDistance, int minAccuracy, boolean keepGpsOnBetweenFixes,
                    boolean useSignificantMotion, boolean useSignificantMotionMixed, int timeout,
                    boolean post, boolean enabled, int nbSync, @Nullable String login,
                    @Nullable String password, boolean json) {
        this.id = id;
        this.title = title;
        this.url = url;
        this.token = token;
        this.deviceName = deviceName;
        this.post = post;
        this.json = json;
        this.minAccuracy = minAccuracy;
        this.minDistance = minDistance;
        this.minTime = minTime;
        this.enabled = enabled;
        this.keepGpsOnBetweenFixes = keepGpsOnBetweenFixes;
        this.useSignificantMotion = useSignificantMotion;
        this.useSignificantMotionMixed = useSignificantMotionMixed;
        this.locationRequestTimeout = timeout;
        this.nbSync = nbSync;
        this.login = login;
        this.password = password;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    @Nullable
    public LogjobAutomation getAutomation() {
        return automation;
    }

    public void setAutomation(@Nullable LogjobAutomation automation) {
        this.automation = automation;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean getJson() {
        return json;
    }

    public void setJson(boolean json) {
        this.json = json;
    }

    public void setNbSync(int nbSync) {
        this.nbSync = nbSync;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public void setPost(boolean post) {
        this.post = post;
    }

    public boolean keepGpsOnBetweenFixes() {
        return keepGpsOnBetweenFixes;
    }

    public void setKeepGpsOnBetweenFixes(boolean keepGpsOnBetweenFixes) {
        this.keepGpsOnBetweenFixes = keepGpsOnBetweenFixes;
    }

    public void setUseSignificantMotion(boolean useSignificantMotion) {
        this.useSignificantMotion = useSignificantMotion;
    }

    public void setLocationRequestTimeout(int timeout) {
        this.locationRequestTimeout = timeout;
    }

    public boolean setAttrFromLoggingUrl(String loggingUrl) {
        boolean worked = false;
        String[] spl = loggingUrl.split("/apps/phonetrack/");
        if (spl.length == 2) {
            String nextURL = spl[0];
            if (nextURL.contains("index.php")) {
                nextURL = nextURL.replace("index.php", "");
            }

            String right = spl[1];
            String[] spl2 = right.split("/");
            if (spl2.length > 2) {
                String token;
                String[] splEnd;
                // example .../apps/phonetrack/logGet/token/devname?lat=0.1...
                if (spl2.length == 3) {
                    token = spl2[1];
                    splEnd = spl2[2].split("\\?");
                }
                // example .../apps/phonetrack/log/osmand/token/devname?lat=0.1...
                else {
                    token = spl2[2];
                    splEnd = spl2[3].split("\\?");
                }
                String devname = splEnd[0];
                this.title = "From PhoneTrack logging URL";
                this.deviceName = devname;
                this.token = token;
                this.url = nextURL;
                worked = true;
            }
        }
        return worked;
    }

    public String getToken() {
        return token;
    }

    public int getNbSync() {
        return nbSync;
    }

    public String getUrl() {
        return url;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean getPost() {
        return post;
    }

    public int getMinTime() {
        return minTime;
    }
    public int getMinDistance() {
        return minDistance;
    }
    public int getMinAccuracy() {
        return minAccuracy;
    }

    public Boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public boolean useSignificantMotion() {
        return useSignificantMotion;
    }

    public boolean useSignificantMotionMixed() {
        return useSignificantMotionMixed;
    }

    public void setSignificantMotionMixed(boolean useSignificantMotionMixed) {
        this.useSignificantMotionMixed = useSignificantMotionMixed;
    }


    public int getLocationRequestTimeout() {
        return locationRequestTimeout;
    }

    public boolean isPhonetrack() {
        return !getToken().isEmpty() && !getDeviceName().isEmpty() && !getUrl().isEmpty();
    }

    @Override
    public boolean isSection() {
        return false;
    }

    @Override
    public String toString() {
        return "#DBLogjob" + getId() + "/" + this.title + ", " + this.enabled + ", " +
                this.url + ", " + this.token + ", " +
                this.deviceName;
    }

    public String toPrivateString() {
        return "[Logjob " + getId() + "]\n"
                + "enabled: " + this.enabled + "\n"
                + "post: " + this.post + "\n"
                + "json: " + this.json + "\n"
                + "minAccuracy: " + this.minAccuracy + "\n"
                + "minDistance: " + this.minDistance + "\n"
                + "minTime: " + this.minTime + "\n"
                + "keepGpsOnBetweenFixes: " + this.keepGpsOnBetweenFixes + "\n"
                + "useSignificantMotion: " + this.useSignificantMotion + "\n"
                + "useSignificantMotionMixed: " + this.useSignificantMotionMixed + "\n"
                + "locationRequestTimeout: " + this.locationRequestTimeout + "\n";

    }
}
