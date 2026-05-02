package org.example.mq;

public final class MQConstants {

    public static final String TOPIC = "audio-transcription-topic";
    public static final String TAG_TRANSCRIBE = "TRANSCRIBE";
    public static final String TAG_REVIEW = "REVIEW";
    public static final String GROUP_TRANSCRIBE = "transcription-consumer-group";
    public static final String GROUP_REVIEW = "review-consumer-group";

    private MQConstants() {
    }
}
