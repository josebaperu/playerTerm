package org.example.player.audio;

/** What the decoder learned about the file it is playing. */
public final class FormatInfo {
    public volatile String codec = "";
    public volatile String bitrate = "";
    public volatile String sampleRate = "";
    public volatile double duration;
    public volatile String error = "";

    public void clear() {
        codec = bitrate = sampleRate = error = "";
        duration = 0;
    }
}
