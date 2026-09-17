package org.example.player.audio;

public enum PlaybackState {
    IDLE("idle"),
    PLAYING("playing"),
    PAUSED("paused"),
    STOPPED("stopped"),
    ERROR("error");

    private final String label;

    PlaybackState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
