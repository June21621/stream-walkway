package com.stream.writer.command;

public record CreateTrailCommand(
        Long streamId,
        String cameraNumber,
        String location,
        String direction,
        String status
) {}
