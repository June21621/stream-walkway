package com.stream.writer.command;

public record CreateCaptureCommand(
        Integer trailId,
        Integer streamId,
        String imagePath,
        String roadStatus,
        Double confidence
) {}
