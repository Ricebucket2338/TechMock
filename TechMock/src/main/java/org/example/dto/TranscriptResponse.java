package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranscriptResponse {
    private String transcript;
    private String speakerSegments;
}
