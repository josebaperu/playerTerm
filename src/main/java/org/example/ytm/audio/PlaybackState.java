package org.example.ytm.audio;

public enum PlaybackState {
    IDLE("idle"),
    LOADING("loading"),
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
