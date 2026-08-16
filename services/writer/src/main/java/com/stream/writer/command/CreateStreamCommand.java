package com.stream.writer.command;

public record CreateStreamCommand(
        String name,
        String location
) {}
